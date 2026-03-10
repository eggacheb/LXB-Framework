package com.lxb.server.cortex;

public final class Util {
    private Util() {}

    public static String normalizeResourceId(String rid) {
        if (rid == null) return "";
        String s = rid.trim();
        if (s.isEmpty()) return "";
        // Examples:
        // - "tv.danmaku.bili:id/expand_search" -> "expand_search"
        // - "id/expand_search" -> "expand_search"
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            s = s.substring(slash + 1);
        }
        // Some dumps include "@id/xxx"
        if (s.startsWith("@id/")) {
            s = s.substring(4);
        }
        return s.trim();
    }

    public static String normalizeClass(String cls) {
        if (cls == null) return "";
        String s = cls.trim();
        if (s.isEmpty()) return "";
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        return s.trim();
    }

    public static String normalizeText(String s) {
        if (s == null) return "";
        // Trim + normalize line endings; avoid aggressive collapsing.
        String t = s.replace("\r\n", "\n").replace('\r', '\n').trim();
        return t;
    }
}

