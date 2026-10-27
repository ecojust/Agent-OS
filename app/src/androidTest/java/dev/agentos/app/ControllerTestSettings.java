package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.ActionConfirmationPolicy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Isolates synthetic controller configuration while preserving any existing Keystore key. */
final class ControllerTestSettings implements AutoCloseable {
    private final SharedPreferences preferences;
    private final Map<String, ?> original;
    final ModelSettings model;
    final ActionConfirmationPolicy.Mode executionMode;
    final SettingsStore.ConfigurationLease lease;

    ControllerTestSettings(Context context, LocalControllerModelServer server) throws IOException {
        this(context, server, ActionConfirmationPolicy.Mode.CONFIRM_ALL);
    }

    ControllerTestSettings(Context context, LocalControllerModelServer server,
                           ActionConfirmationPolicy.Mode executionMode) throws IOException {
        this(context, new ModelSettings(server.endpoint(), LocalControllerModelServer.SYNTHETIC_KEY,
                LocalControllerModelServer.MODEL), executionMode);
    }

    /** Real saved configuration for a fixture that serves both synthetic image and task requests. */
    ControllerTestSettings(Context context, ModelSettings model,
                           ActionConfirmationPolicy.Mode executionMode) throws IOException {
        this.executionMode = executionMode;
        String profile = SettingsStore.currentProfileId(context);
        preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
        original = preferences.getAll();
        if (!preferences.edit().clear().commit()) throw new IOException("Could not isolate test configuration");
        this.model = model;
        try {
            SettingsStore store = new SettingsStore(context);
            SettingsStore.SavedSettings saved = store.load();
            saved.endpoint = model.endpoint;
            saved.apiKey = model.apiKey;
            saved.model = model.model;
            saved.maxOutputTokens = model.maxOutputTokens;
            saved.confirmEachAction = true;
            saved.executionMode = executionMode;
            saved.allowedPackages.add(DeviceHarness.FIXTURE);
            saved.grants.put(DeviceHarness.FIXTURE, SettingsStore.captureGrant(context, DeviceHarness.FIXTURE));
            lease = store.save(saved);
        } catch (Throwable failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set<?>) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                editor.putStringSet(entry.getKey(), values);
            }
        }
        if (!editor.commit()) throw new IOException("Could not restore pre-test configuration");
    }
}
