package dev.agentos.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;

/** Connection credentials are deliberately excluded from toString and diagnostics. */
public final class ModelSettings {
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;
    public static final int MIN_MAX_OUTPUT_TOKENS = 128;
    public static final int MAX_MAX_OUTPUT_TOKENS = 8192;
    public final String endpoint;
    public final String apiKey;
    public final String model;
    public final int maxOutputTokens;

    public ModelSettings(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public ModelSettings(String endpoint, String apiKey, String model, int maxOutputTokens) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.maxOutputTokens = maxOutputTokens;
    }

    public void validate() {
        completionUrl();
        if (model.isEmpty() || model.length() > 200 || hasControl(model)) {
            throw new IllegalArgumentException("请填写有效的模型名称。");
        }
        if (apiKey.length() > 8192 || hasControl(apiKey)) {
            throw new IllegalArgumentException("密钥格式无效。");
        }
        if (maxOutputTokens < MIN_MAX_OUTPUT_TOKENS || maxOutputTokens > MAX_MAX_OUTPUT_TOKENS) throw new IllegalArgumentException("输出 token 上限应在 128 至 8192 之间。");
    }

    public URL completionUrl() {
        try {
            if (endpoint.length() > 2048) throw new IllegalArgumentException();
            URI uri = new URI(endpoint);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || hasControl(endpoint)) throw new IllegalArgumentException();
            if (!"https".equalsIgnoreCase(scheme)
                    && !("http".equalsIgnoreCase(scheme) && isLocalHost(host))) {
                throw new IllegalArgumentException();
            }
            if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String path = uri.getPath();
            if (path == null) path = "";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) path = "/v1";
            if (!path.endsWith("/chat/completions")) path += "/chat/completions";
            return new URI(scheme.toLowerCase(Locale.ROOT), null, host, uri.getPort(), path, null, null).toURL();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("模型地址必须是 HTTPS 地址，或本机／局域网的 HTTP 地址；请勿在地址中填写密钥、账号或查询参数。");
        }
    }

    private static boolean isLocalHost(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("::1") || host.startsWith("fc") && host.contains(":")
                || host.startsWith("fd") && host.contains(":") || host.startsWith("fe80:")
                || host.equals("0:0:0:0:0:0:0:1")) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("[0-9]{1,3}")) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return false;
            }
        } catch (NumberFormatException invalid) { return false; }
        return octets[0] == 127 || octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168
                || octets[0] == 169 && octets[1] == 254;
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }
}
