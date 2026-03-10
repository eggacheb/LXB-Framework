package com.lxb.server.cortex.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/writer for on-device LXB core (no external deps).
 *
 * Supports:
 * - objects, arrays
 * - strings with common escapes
 * - numbers (int/float)
 * - true/false/null
 *
 * This is not a full RFC implementation, but is sufficient for locator payloads and trace/events.
 */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("json is null");
        Parser p = new Parser(s);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("trailing json at pos=" + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected json object");
        return (Map<String, Object>) v;
    }

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            sb.append('\"').append(escape((String) v)).append('\"');
            return;
        }
        if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
            return;
        }
        if (v instanceof Number) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('\"').append(escape(e.getKey())).append("\":");
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> a = (List<Object>) v;
            sb.append('[');
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, a.get(i));
            }
            sb.append(']');
            return;
        }
        // Fallback for unknown types.
        sb.append('\"').append(escape(String.valueOf(v))).append('\"');
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static final class Parser {
        final String s;
        int i = 0;

        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }

        void skipWs() {
            while (!eof()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (eof()) throw err("unexpected eof");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '\"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw err("unexpected char '" + c + "'");
        }

        Map<String, Object> parseObject() {
            expect('{');
            skipWs();
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek('}')) { i++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            return m;
        }

        List<Object> parseArray() {
            expect('[');
            skipWs();
            List<Object> a = new ArrayList<>();
            if (peek(']')) { i++; return a; }
            while (true) {
                Object v = parseValue();
                a.add(v);
                skipWs();
                if (peek(']')) { i++; break; }
                expect(',');
            }
            return a;
        }

        String parseString() {
            expect('\"');
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = s.charAt(i++);
                if (c == '\"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '\"': sb.append('\"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw err("bad escape char '" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("bad boolean");
        }

        Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("bad null");
        }

        Number parseNumber() {
            int start = i;
            if (peek('-')) i++;
            while (!eof()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else break;
            }
            boolean isFloat = false;
            if (!eof() && s.charAt(i) == '.') {
                isFloat = true;
                i++;
                while (!eof()) {
                    char c = s.charAt(i);
                    if (c >= '0' && c <= '9') i++;
                    else break;
                }
            }
            if (!eof()) {
                char c = s.charAt(i);
                if (c == 'e' || c == 'E') {
                    isFloat = true;
                    i++;
                    if (!eof() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    while (!eof()) {
                        char d = s.charAt(i);
                        if (d >= '0' && d <= '9') i++;
                        else break;
                    }
                }
            }
            String num = s.substring(start, i);
            try {
                if (isFloat) return Double.parseDouble(num);
                long v = Long.parseLong(num);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
                return v;
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }

        boolean peek(char c) {
            return !eof() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) throw err("expected '" + c + "'");
            i++;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("json parse error at pos=" + i + ": " + msg);
        }
    }
}

