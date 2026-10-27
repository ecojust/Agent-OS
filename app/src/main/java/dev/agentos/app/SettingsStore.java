package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Process;
import android.os.UserManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.ModelSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only configuration. Task text, observations, and screenshots are never persisted here. */
public final class SettingsStore {
    private static final Object KEY_LOCK = new Object();
    private static final Object STORAGE_LOCK = new Object();
    private static final int MAX_ENVELOPE_LENGTH = 200_000;
    private static final String STORAGE_KEY = "encrypted_settings";
    private static final String REVISION_KEY = "configuration_revision";
    private static final Map<String, ProfileState> PROFILES = new ConcurrentHashMap<>();
    private final Context context;
    private final String profileId;
    private final String keyAlias;
    private final byte[] associatedData;
    private final SharedPreferences preferences;
    private final ProfileState state;
    private final Committer committer;

    public SettingsStore(Context context) {
        this(context, "", SharedPreferences.Editor::commit);
    }

    /** Isolated storage/Keystore namespace and one narrow failure seam for package tests. */
    SettingsStore(Context context, String isolatedNamespace, Committer committer) {
        this.context = context.getApplicationContext();
        if (isolatedNamespace == null || !isolatedNamespace.isEmpty()
                && !isolatedNamespace.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid storage namespace");
        this.committer = Objects.requireNonNull(committer);
        profileId = currentProfileId(context);
        String suffix = isolatedNamespace.isEmpty() ? "" : "." + isolatedNamespace;
        keyAlias = "dev.agentos.settings.v1." + profileId + suffix;
        associatedData = ("Agent OS settings v1 " + profileId + suffix).getBytes(StandardCharsets.UTF_8);
        String name = "agent_settings_secure_" + profileId.replace(':', '_')
                + (isolatedNamespace.isEmpty() ? "" : "__" + isolatedNamespace);
        preferences = this.context.getSharedPreferences(name, Context.MODE_PRIVATE);
        state = PROFILES.computeIfAbsent(name, ignored -> new ProfileState());
    }

    public SavedSettings load() throws IOException {
        return loadWithLease().settings;
    }

    /** Worker-only decryption. A lease is issued only for a fully validated, nonempty configuration. */
    public LoadedSettings loadWithLease() throws IOException {
        List<Registration> invalidated = Collections.emptyList();
        LoadedSettings loaded = null;
        IOException failure = null;
        synchronized (STORAGE_LOCK) {
            requireReadable();
            try {
                ParsedSettings parsed = readStored();
                synchronized (state.lock) {
                    Published current = state.published;
                    if (!Objects.equals(current.envelope, parsed.raw.envelope)
                            || !Objects.equals(current.revision, parsed.raw.revision)
                            || parsed.raw.envelope != null && current.lease == null) {
                        invalidated = detachWatchersLocked();
                        long generation = current.generation + 1;
                        ConfigurationLease lease = parsed.raw.envelope == null ? null
                                : new ConfigurationLease(state, profileId, generation);
                        state.published = new Published(generation, lease, false, false,
                                parsed.raw.envelope, parsed.raw.revision);
                    }
                    loaded = new LoadedSettings(parsed.settings, state.published.lease);
                }
            } catch (IOException error) {
                synchronized (state.lock) {
                    invalidated = detachWatchersLocked();
                    Published current = state.published;
                    state.published = new Published(current.generation + 1, null, false, false, null, null);
                }
                failure = error;
            }
        }
        notifyInvalidated(invalidated);
        if (failure != null) throw failure;
        if (loaded.lease != null && !isCurrent(loaded.lease)) throw new IOException("配置在读取期间已改变，请重新读取。");
        return loaded;
    }

    private ParsedSettings readStored() throws IOException {
        RawSettings raw = readRaw();
        if (raw.revision != null && (!(raw.revision instanceof String) || !validRevision((String) raw.revision))) {
            throw new IOException("配置版本标识无效；原配置尚未修改，请明确重置后重试。");
        }
        if (raw.envelope != null && !(raw.envelope instanceof String)) throw new IOException("已保存配置的格式无效；原配置尚未修改。");
        String encoded = (String) raw.envelope;
        if (encoded == null) {
            SavedSettings empty = new SavedSettings();
            empty.profileId = profileId;
            return new ParsedSettings(empty, raw);
        }
        if (encoded.length() > MAX_ENVELOPE_LENGTH) throw new IOException("已保存配置的大小异常。请重新保存配置。");
        try {
            JSONObject envelope = new JSONObject(encoded);
            if (envelope.getInt("version") != 1) throw new IOException("此版本无法读取已保存的模型配置。");
            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP);
            if (iv.length != 12 || ciphertext.length < 16) throw new IOException("已保存配置不完整。");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(associatedData);
            JSONObject json = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            // The plaintext sidecar is never independently trusted. A reset tombstone
            // cannot authorize an old ciphertext, and only double absence is legacy v1.
            Object revision = json.has("configurationRevision") ? json.get("configurationRevision") : null;
            if (revision != null || raw.revision != null) {
                if (!(revision instanceof String) || !validRevision((String) revision)
                        || !revision.equals(raw.revision)) throw new IOException("配置版本与加密内容不一致；原配置尚未修改，请明确重置后重试。");
            }
            SavedSettings settings = new SavedSettings();
            settings.profileId = json.getString("profileId");
            if (!profileId.equals(settings.profileId)) throw new IOException("配置所属的用户资料已变化，请重新配置。");
            settings.endpoint = json.getString("endpoint");
            settings.apiKey = json.getString("apiKey");
            settings.model = json.getString("model");
            settings.maxOutputTokens = readMaxOutputTokens(json);
            settings.confirmEachAction = json.getBoolean("confirmEachAction");
            settings.executionMode = readExecutionMode(json);
            settings.taskBudget = readTaskBudget(json);
            JSONArray grants = json.getJSONArray("grants");
            for (int i = 0; i < grants.length(); i++) {
                JSONObject grant = grants.getJSONObject(i);
                Set<String> signatures = new LinkedHashSet<>();
                JSONArray hashes = grant.getJSONArray("signers");
                for (int j = 0; j < hashes.length(); j++) signatures.add(hashes.getString(j));
                AppGrant identity = new AppGrant(grant.getString("packageName"), grant.getString("profileId"),
                        grant.getLong("firstInstallTime"), grant.getInt("uid"), signatures);
                settings.allowedPackages.add(identity.packageName);
                settings.grants.put(identity.packageName, identity);
            }
            validate(settings);
            return new ParsedSettings(settings, raw);
        } catch (GeneralSecurityException | JSONException | IllegalArgumentException error) {
            // Do not expose provider errors, ciphertext, or secrets in UI/log output.
            throw new IOException("无法解密已保存的配置。请重新填写并保存；原配置尚未修改。");
        }
    }

    private static ActionConfirmationPolicy.Mode readExecutionMode(JSONObject json) throws IOException, JSONException {
        // Older builds ignored confirmEachAction=false. Never reinterpret that value
        // as permission for a newly introduced unconfirmed navigation path.
        if (!json.has("executionMode")) return ActionConfirmationPolicy.Mode.CONFIRM_ALL;
        Object raw = json.get("executionMode");
        if (raw instanceof String) {
            for (ActionConfirmationPolicy.Mode mode : ActionConfirmationPolicy.Mode.values()) {
                if (mode.name().equals(raw)) return mode;
            }
        }
        throw new IOException("已保存的执行模式无法识别；原配置尚未修改，请明确重置后重试。");
    }

    private static int readMaxOutputTokens(JSONObject json) throws IOException, JSONException {
        // Only absence is a legacy default. Do not coerce or silently repair a
        // present value, and check before narrowing a JSON long to an int.
        if (!json.has("maxOutputTokens")) return ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS;
        Object raw = json.get("maxOutputTokens");
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalidMaxOutputTokens();
        long value = ((Number) raw).longValue();
        if (value < ModelSettings.MIN_MAX_OUTPUT_TOKENS || value > ModelSettings.MAX_MAX_OUTPUT_TOKENS)
            throw invalidMaxOutputTokens();
        return (int) value;
    }

    private static IOException invalidMaxOutputTokens() {
        return new IOException("每次回复 Token 上限格式或范围无效；原配置尚未修改，请填写 128–8192 的整数。");
    }

    private static TaskBudget readTaskBudget(JSONObject json) throws IOException, JSONException {
        // Absence alone identifies a legacy document. Do not silently repair a
        // present but malformed budget or rewrite old ciphertext while reading.
        if (!json.has("taskBudget")) return TaskBudget.DEFAULT;
        Object raw = json.get("taskBudget");
        if (!(raw instanceof JSONObject)) throw invalidTaskBudget();
        JSONObject budget = (JSONObject) raw;
        if (budget.length() != 3 || !budget.has("maxModelRounds") || !budget.has("maxToolCalls")
                || !budget.has("maxActiveMillis")) throw invalidTaskBudget();
        long rounds = budgetInteger(budget, "maxModelRounds"), calls = budgetInteger(budget, "maxToolCalls");
        long millis = budgetInteger(budget, "maxActiveMillis");
        // Check long values before narrowing so overflow cannot create a smaller valid budget.
        if (rounds < TaskBudget.MIN_MODEL_ROUNDS || rounds > TaskBudget.MAX_MODEL_ROUNDS
                || calls < TaskBudget.MIN_TOOL_CALLS || calls > TaskBudget.MAX_TOOL_CALLS
                || millis < 1_000 || millis > TaskBudget.MAX_ACTIVE_MILLIS || millis % 1_000 != 0) {
            throw invalidTaskBudget();
        }
        return new TaskBudget((int) rounds, (int) calls, millis);
    }

    private static long budgetInteger(JSONObject budget, String key) throws IOException, JSONException {
        Object raw = budget.get(key);
        // JSONObject's numeric getters coerce strings and truncate doubles. Only
        // integer JSON values are configuration authority, including whole seconds.
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalidTaskBudget();
        return ((Number) raw).longValue();
    }

    private static IOException invalidTaskBudget() {
        return new IOException("任务预算格式或范围无效；原配置尚未修改，请检查配置后重试。");
    }

    public ConfigurationLease save(SavedSettings settings) throws IOException {
        Mutation mutation = beginMutation(false);
        try {
        synchronized (STORAGE_LOCK) {
        requireMutation(mutation);
        validate(settings);
        if (!profileId.equals(settings.profileId)) throw new IOException("配置所属的用户资料已变化，请重新配置。");
        // A damaged/newer configuration must first be explicitly reset by the user.
        // Reading before replacement also rejects a lost Keystore key without plaintext fallback.
        ParsedSettings previous = readStored();
        try {
            String revision = UUID.randomUUID().toString();
            JSONObject json = new JSONObject();
            json.put("configurationRevision", revision);
            json.put("profileId", settings.profileId);
            json.put("endpoint", settings.endpoint);
            json.put("apiKey", settings.apiKey);
            json.put("model", settings.model);
            json.put("maxOutputTokens", settings.maxOutputTokens);
            // Keep the legacy field restrictive for older readers. Only executionMode
            // records an explicit local choice; an old false value grants no authority.
            json.put("confirmEachAction", true);
            json.put("executionMode", settings.executionMode.name());
            JSONObject budget = new JSONObject();
            budget.put("maxModelRounds", settings.taskBudget.maxModelRounds);
            budget.put("maxToolCalls", settings.taskBudget.maxToolCalls);
            budget.put("maxActiveMillis", settings.taskBudget.maxActiveMillis);
            json.put("taskBudget", budget);
            JSONArray grants = new JSONArray();
            for (String packageName : settings.allowedPackages) {
                AppGrant identity = settings.grants.get(packageName);
                JSONObject grant = new JSONObject();
                grant.put("packageName", identity.packageName);
                grant.put("profileId", identity.profileId);
                grant.put("firstInstallTime", identity.firstInstallTime);
                grant.put("uid", identity.uid);
                grant.put("signers", new JSONArray(identity.signers));
                grants.put(grant);
            }
            json.put("grants", grants);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject();
            envelope.put("version", 1);
            envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            envelope.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            String encoded = envelope.toString();
            if (encoded.length() > MAX_ENVELOPE_LENGTH) throw new IOException("配置过大，未修改原配置。请减少允许操作的应用后重试。");
            commitOrFault(preferences.edit().putString(STORAGE_KEY, encoded).putString(REVISION_KEY, revision), previous.raw, mutation);
            synchronized (state.lock) {
                ConfigurationLease lease = new ConfigurationLease(state, profileId, mutation.generation);
                state.published = new Published(mutation.generation, lease, false, false, encoded, revision);
                return lease;
            }
        } catch (GeneralSecurityException | JSONException error) {
            throw new IOException("无法安全保存模型配置。请解锁设备后重试。");
        }
        }
        } finally {
            finishFailedMutation(mutation);
        }
    }

    private static void validate(SavedSettings settings) throws IOException {
        if (settings == null || settings.endpoint == null || settings.apiKey == null || settings.model == null
                || settings.allowedPackages == null || settings.grants == null || settings.profileId == null
                || settings.executionMode == null) throw new IOException("模型配置不完整。");
        if (settings.maxOutputTokens < ModelSettings.MIN_MAX_OUTPUT_TOKENS
                || settings.maxOutputTokens > ModelSettings.MAX_MAX_OUTPUT_TOKENS) throw invalidMaxOutputTokens();
        if (settings.taskBudget == null || settings.taskBudget.maxActiveMillis < 1_000
                || settings.taskBudget.maxActiveMillis > TaskBudget.MAX_ACTIVE_MILLIS
                || settings.taskBudget.maxActiveMillis % 1_000 != 0) throw invalidTaskBudget();
        if (settings.endpoint.length() > 2048 || settings.apiKey.length() > 8192 || settings.model.length() > 256
                || settings.allowedPackages.size() > 500) throw new IOException("模型配置超出支持范围。");
        for (String packageName : settings.allowedPackages) {
            if (packageName == null || packageName.length() > 255
                    || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                throw new IOException("允许应用列表包含无效项目。");
            }
            AppGrant identity = settings.grants.get(packageName);
            if (identity == null || !packageName.equals(identity.packageName)
                    || !settings.profileId.equals(identity.profileId) || identity.signers.isEmpty() || identity.signers.size() > 32
                    || identity.firstInstallTime <= 0 || identity.uid < 0) throw new IOException("应用授权身份不完整，请重新选择应用。");
            for (String hash : identity.signers) {
                if (!hash.matches("[0-9a-f]{64}")) throw new IOException("应用签名身份不完整，请重新选择应用。");
            }
        }
    }

    private SecretKey getKey(boolean create) throws GeneralSecurityException, IOException {
        synchronized (KEY_LOCK) {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(keyAlias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) throw new GeneralSecurityException("Configuration key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
        }
    }

    /** Explicit user reset only; never call this as an automatic recovery path. */
    public void reset() throws IOException {
        Mutation mutation = beginMutation(true);
        try {
        synchronized (STORAGE_LOCK) {
        requireMutation(mutation);
        RawSettings previous = readRaw();
        String revision = UUID.randomUUID().toString();
        commitOrFault(preferences.edit().remove(STORAGE_KEY).putString(REVISION_KEY, revision), previous, mutation);
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias);
        } catch (GeneralSecurityException | IOException error) {
            markFault(mutation);
            throw new IOException("配置已清除，但密钥清理未完成，请重启后重试。");
        }
        synchronized (state.lock) {
            // Persisted tombstone remains, but an empty configuration has no execution lease.
            state.published = new Published(mutation.generation, null, false, false, null, revision);
        }
        }
        } finally {
            finishFailedMutation(mutation);
        }
    }

    /** Pure in-process check: no preference access, locks, disk I/O or decryption. */
    public static boolean isCurrent(ConfigurationLease lease) {
        if (lease == null) return false;
        Published current = lease.owner.published;
        return !current.mutating && !current.fault && current.lease == lease
                && current.generation == lease.generation;
    }

    /** Atomically watches this exact lease. Never invokes callbacks while holding a store/state lock. */
    public Registration watch(ConfigurationLease lease, Runnable invalidated) {
        return watchState(state, lease, invalidated);
    }

    private static Registration watchState(ProfileState state, ConfigurationLease lease, Runnable invalidated) {
        Objects.requireNonNull(invalidated);
        Registration registration = new Registration(state, invalidated);
        boolean notifyNow;
        synchronized (state.lock) {
            notifyNow = lease == null || lease.owner != state || !isCurrent(lease);
            if (notifyNow) registration.closed = true;
            else state.watchers.add(registration);
        }
        if (notifyNow) registration.notifyInvalidation();
        return registration;
    }

    private Mutation beginMutation(boolean resetting) throws IOException {
        List<Registration> invalidated;
        Mutation mutation;
        synchronized (STORAGE_LOCK) {
            synchronized (state.lock) {
                Published current = state.published;
                if (current.mutating) throw new IOException("配置正在修改，请稍后重试。");
                if (current.fault && !resetting) throw new IOException("上次存储结果无法确认，请先明确重置本机配置。");
                mutation = new Mutation(current.generation + 1);
                invalidated = detachWatchersLocked();
                state.published = new Published(mutation.generation, null, true, current.fault, null, null);
            }
        }
        // Invalidation happens before any disk/Keystore mutation. Reentrant callbacks
        // can inspect the invalid lease, but a new load/save sees MUTATING and fails closed.
        notifyInvalidated(invalidated);
        return mutation;
    }

    private void requireReadable() throws IOException {
        Published current = state.published;
        if (current.fault) throw new IOException("上次存储结果无法确认，请先明确重置本机配置。");
        if (current.mutating) throw new IOException("配置正在修改，请稍后重试。");
    }

    private void requireMutation(Mutation mutation) throws IOException {
        Published current = state.published;
        if (!current.mutating || current.generation != mutation.generation) throw new IOException("配置修改已失效，请重新操作。");
    }

    private void finishFailedMutation(Mutation mutation) {
        synchronized (state.lock) {
            Published current = state.published;
            if (current.mutating && current.generation == mutation.generation) {
                state.published = new Published(current.generation, null, false, current.fault, null, null);
            }
        }
    }

    private void markFault(Mutation mutation) {
        synchronized (state.lock) {
            state.published = new Published(mutation.generation, null, false, true, null, null);
        }
    }

    private RawSettings readRaw() throws IOException {
        try {
            Map<String, ?> all = preferences.getAll();
            return new RawSettings(all.get(STORAGE_KEY), all.get(REVISION_KEY));
        } catch (RuntimeException error) {
            throw new IOException("无法读取配置存储；原配置尚未修改。");
        }
    }

    private void commitOrFault(SharedPreferences.Editor editor, RawSettings previous, Mutation mutation) throws IOException {
        boolean committed;
        try { committed = committer.commit(editor); }
        catch (RuntimeException uncertain) { committed = false; }
        if (committed) return;
        // commit() updates SharedPreferences memory before it reports the disk result.
        // Restore the prior envelope best-effort, but do not claim this proves disk rollback.
        markFault(mutation);
        try {
            SharedPreferences.Editor restore = preferences.edit();
            restoreValue(restore, STORAGE_KEY, previous.envelope);
            restoreValue(restore, REVISION_KEY, previous.revision);
            restore.commit();
        } catch (RuntimeException ignored) { }
        throw new IOException("配置写入结果无法确认，旧确认已失效。请先明确重置本机配置后重试。");
    }

    private static void restoreValue(SharedPreferences.Editor editor, String name, Object value) {
        if (value == null) editor.remove(name);
        else if (value instanceof String) editor.putString(name, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(name, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(name, (Integer) value);
        else if (value instanceof Long) editor.putLong(name, (Long) value);
        else if (value instanceof Float) editor.putFloat(name, (Float) value);
        else if (value instanceof Set<?>) {
            @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
            editor.putStringSet(name, strings);
        }
    }

    private List<Registration> detachWatchersLocked() {
        List<Registration> detached = new ArrayList<>(state.watchers);
        state.watchers.clear();
        for (Registration registration : detached) registration.closed = true;
        return detached;
    }

    private static void notifyInvalidated(List<Registration> invalidated) {
        for (Registration registration : invalidated) registration.notifyInvalidation();
    }

    private static boolean validRevision(String revision) {
        return revision.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    interface Committer { boolean commit(SharedPreferences.Editor editor); }

    public static final class ConfigurationLease {
        public final String profileId;
        /** Random local binding only: contains no endpoint, model, API key or credential hash. */
        public final String configurationToken = UUID.randomUUID().toString();
        private final ProfileState owner;
        private final long generation;
        private ConfigurationLease(ProfileState owner, String profileId, long generation) {
            this.owner = owner;
            this.profileId = profileId;
            this.generation = generation;
        }
        /** Lightweight registration for controllers that must not open storage on the UI thread. */
        public Registration watch(Runnable invalidated) { return watchState(owner, this, invalidated); }
    }

    public static final class LoadedSettings {
        public final SavedSettings settings;
        public final ConfigurationLease lease;
        private LoadedSettings(SavedSettings settings, ConfigurationLease lease) {
            this.settings = settings;
            this.lease = lease;
        }
    }

    public static final class Registration implements AutoCloseable {
        private final ProfileState owner;
        private final Runnable invalidated;
        private boolean closed;
        private Registration(ProfileState owner, Runnable invalidated) { this.owner = owner; this.invalidated = invalidated; }
        @Override public void close() {
            synchronized (owner.lock) {
                if (closed) return;
                closed = true;
                owner.watchers.remove(this);
            }
        }
        private void notifyInvalidation() {
            try { invalidated.run(); } catch (RuntimeException ignored) { }
        }
    }

    private static final class ProfileState {
        final Object lock = new Object();
        final List<Registration> watchers = new ArrayList<>();
        volatile Published published = new Published(0, null, false, false, null, null);
    }

    private static final class Published {
        final long generation;
        final ConfigurationLease lease;
        final boolean mutating, fault;
        final Object envelope, revision;
        Published(long generation, ConfigurationLease lease, boolean mutating, boolean fault, Object envelope, Object revision) {
            this.generation = generation; this.lease = lease; this.mutating = mutating; this.fault = fault;
            this.envelope = envelope; this.revision = revision;
        }
    }

    private static final class Mutation {
        final long generation;
        Mutation(long generation) { this.generation = generation; }
    }

    private static final class RawSettings {
        final Object envelope, revision;
        RawSettings(Object envelope, Object revision) { this.envelope = envelope; this.revision = revision; }
    }

    private static final class ParsedSettings {
        final SavedSettings settings;
        final RawSettings raw;
        ParsedSettings(SavedSettings settings, RawSettings raw) { this.settings = settings; this.raw = raw; }
    }

    /** Call on a worker thread. Choosing applications is an explicit reauthorization. */
    public Map<String, AppGrant> grantPackages(Set<String> packages) throws IOException {
        requirePersonalProfile(context);
        Map<String, AppGrant> result = new LinkedHashMap<>();
        for (String packageName : packages) result.put(packageName, captureGrant(context, packageName));
        return result;
    }

    /** Never renews an old authorization merely because the same package name exists. */
    public Set<String> validateAllowedPackages(SavedSettings settings) throws IOException {
        requirePersonalProfile(context);
        validate(settings);
        if (!profileId.equals(settings.profileId)) throw new IOException("当前用户资料与授权不一致。");
        Set<String> valid = new LinkedHashSet<>();
        for (String packageName : settings.allowedPackages) {
            if (isGrantValid(context, settings.grants.get(packageName))) valid.add(packageName);
        }
        return valid;
    }

    public static String currentProfileId(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null) throw new IllegalStateException("无法确认当前用户资料。");
        long serial = users.getSerialNumberForUser(Process.myUserHandle());
        if (serial < 0) throw new IllegalStateException("无法确认当前用户资料。");
        return "user:" + serial;
    }

    public static void requirePersonalProfile(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.isManagedProfile() || !users.isSystemUser()) {
            throw new IllegalStateException("首版仅支持设备所有者的个人资料，不支持工作资料、访客或其他用户。");
        }
        if (!users.isUserUnlocked()) throw new IllegalStateException("请先解锁当前用户资料。");
    }

    public static AppGrant captureGrant(Context context, String packageName) throws IOException {
        requirePersonalProfile(context);
        if (packageName == null || packageName.equals(context.getPackageName())) throw new IOException("不能授权操作 Agent OS 自身。");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.applicationInfo == null || info.signingInfo == null || info.firstInstallTime <= 0) {
                throw new IOException("无法核对应用的安装身份，请重新安装该应用后再选择。");
            }
            Set<String> signers = signatureHashes(info.signingInfo.getApkContentsSigners());
            if (signers.isEmpty()) throw new IOException("无法核对应用签名。");
            return new AppGrant(packageName, currentProfileId(context), info.firstInstallTime, info.applicationInfo.uid, signers);
        } catch (PackageManager.NameNotFoundException | GeneralSecurityException error) {
            throw new IOException("应用已卸载或无法读取安装身份，请重新选择应用。");
        }
    }

    public static boolean isGrantValid(Context context, AppGrant grant) {
        if (grant == null) return false;
        try {
            requirePersonalProfile(context);
            if (!grant.profileId.equals(currentProfileId(context))) return false;
            PackageInfo info = context.getPackageManager().getPackageInfo(grant.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.applicationInfo == null || info.signingInfo == null || grant.firstInstallTime != info.firstInstallTime
                    || grant.uid != info.applicationInfo.uid) return false;
            SigningInfo signing = info.signingInfo;
            Set<String> current = signatureHashes(signing.getApkContentsSigners());
            if (signing.hasMultipleSigners() || grant.signers.size() != 1) return current.equals(grant.signers);
            // The platform verifies the signing rotation lineage supplied here.
            Set<String> lineage = signatureHashes(signing.getSigningCertificateHistory());
            return lineage.containsAll(grant.signers);
        } catch (RuntimeException | PackageManager.NameNotFoundException | GeneralSecurityException error) {
            return false;
        }
    }

    private static Set<String> signatureHashes(Signature[] signatures) throws GeneralSecurityException {
        Set<String> result = new LinkedHashSet<>();
        if (signatures == null) return result;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            byte[] bytes = digest.digest(signature.toByteArray());
            StringBuilder value = new StringBuilder(64);
            for (byte item : bytes) value.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
            result.add(value.toString());
        }
        return result;
    }

    public static final class AppGrant {
        public final String packageName;
        public final String profileId;
        public final long firstInstallTime;
        public final int uid;
        public final Set<String> signers;

        public AppGrant(String packageName, String profileId, long firstInstallTime, int uid, Set<String> signers) {
            this.packageName = packageName;
            this.profileId = profileId;
            this.firstInstallTime = firstInstallTime;
            this.uid = uid;
            this.signers = Collections.unmodifiableSet(new LinkedHashSet<>(signers));
        }
    }

    public static final class SavedSettings {
        public String profileId = "";
        public String endpoint = "";
        public String apiKey = "";
        public String model = "";
        /** Requested response limit, not cumulative usage or a billing guarantee. */
        public int maxOutputTokens = ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS;
        /** Legacy storage compatibility only; never use this flag to authorize execution. */
        public boolean confirmEachAction = true;
        public ActionConfirmationPolicy.Mode executionMode = ActionConfirmationPolicy.Mode.CONFIRM_ALL;
        public TaskBudget taskBudget = TaskBudget.DEFAULT;
        public Set<String> allowedPackages = new LinkedHashSet<>();
        public Map<String, AppGrant> grants = new LinkedHashMap<>();
    }
}
