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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    private final Context context;
    private final String profileId;
    private final String keyAlias;
    private final byte[] associatedData;
    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        this.context = context.getApplicationContext();
        profileId = currentProfileId(context);
        keyAlias = "dev.agentos.settings.v1." + profileId;
        associatedData = ("Agent OS settings v1 " + profileId).getBytes(StandardCharsets.UTF_8);
        preferences = this.context.getSharedPreferences("agent_settings_secure_" + profileId.replace(':', '_'), Context.MODE_PRIVATE);
    }

    public SavedSettings load() throws IOException {
        synchronized (STORAGE_LOCK) {
        String encoded = preferences.getString(STORAGE_KEY, null);
        if (encoded == null) {
            SavedSettings empty = new SavedSettings();
            empty.profileId = profileId;
            return empty;
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
            SavedSettings settings = new SavedSettings();
            settings.profileId = json.getString("profileId");
            if (!profileId.equals(settings.profileId)) throw new IOException("配置所属的用户资料已变化，请重新配置。");
            settings.endpoint = json.getString("endpoint");
            settings.apiKey = json.getString("apiKey");
            settings.model = json.getString("model");
            settings.confirmEachAction = json.getBoolean("confirmEachAction");
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
            return settings;
        } catch (GeneralSecurityException | JSONException | IllegalArgumentException error) {
            // Do not expose provider errors, ciphertext, or secrets in UI/log output.
            throw new IOException("无法解密已保存的配置。请重新填写并保存；原配置尚未修改。");
        }
        }
    }

    public void save(SavedSettings settings) throws IOException {
        synchronized (STORAGE_LOCK) {
        validate(settings);
        if (!profileId.equals(settings.profileId)) throw new IOException("配置所属的用户资料已变化，请重新配置。");
        // A damaged/newer configuration must first be explicitly reset by the user.
        // Reading before replacement also rejects a lost Keystore key without plaintext fallback.
        if (preferences.contains(STORAGE_KEY)) load();
        try {
            JSONObject json = new JSONObject();
            json.put("profileId", settings.profileId);
            json.put("endpoint", settings.endpoint);
            json.put("apiKey", settings.apiKey);
            json.put("model", settings.model);
            json.put("confirmEachAction", settings.confirmEachAction);
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
            if (!preferences.edit().putString(STORAGE_KEY, encoded).commit()) {
                throw new IOException("配置保存失败，请稍后重试。");
            }
        } catch (GeneralSecurityException | JSONException error) {
            throw new IOException("无法安全保存模型配置。请解锁设备后重试。");
        }
        }
    }

    private static void validate(SavedSettings settings) throws IOException {
        if (settings == null || settings.endpoint == null || settings.apiKey == null || settings.model == null
                || settings.allowedPackages == null || settings.grants == null || settings.profileId == null) throw new IOException("模型配置不完整。");
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
        synchronized (STORAGE_LOCK) {
        if (!preferences.edit().remove(STORAGE_KEY).commit()) throw new IOException("无法清除配置，请稍后重试。");
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias);
        } catch (GeneralSecurityException error) {
            throw new IOException("配置已清除，但密钥清理未完成，请重启后重试。");
        }
        }
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
        public boolean confirmEachAction = true;
        public Set<String> allowedPackages = new LinkedHashSet<>();
        public Map<String, AppGrant> grants = new LinkedHashMap<>();
    }
}
