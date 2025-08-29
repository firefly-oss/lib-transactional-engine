package com.firefly.transactionalengine.engine;

/**
 * Small helper for building compact JSON-like log strings and safe summaries.
 * Extracted from SagaEngine to keep it focused on orchestration logic.
 */
public final class SagaLogUtil {
    private SagaLogUtil() {}

    public static String summarize(Object obj, int max) {
        if (obj == null) return "null";
        String s = String.valueOf(obj);
        return safeString(s, max);
    }

    public static String safeString(String s, int max) {
        if (s == null) return "null";
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static String json(String... kv) {
        if (kv == null || kv.length == 0) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            String k = i < kv.length ? kv[i] : null;
            String v = (i + 1) < kv.length ? kv[i + 1] : null;
            sb.append('"').append(esc(k)).append('"').append(':').append('"').append(esc(v)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
