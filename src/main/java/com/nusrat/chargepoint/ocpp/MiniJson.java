package com.nusrat.chargepoint.ocpp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recursive-descent JSON parser and writer.
 *
 * OCPP-J frames are nested JSON arrays/objects, e.g.
 *   [2, "19223201", "StatusNotification", {"connectorId": 1, "status": "Charging", "errorCode": "NoError"}]
 * so a regex can't read them reliably. This turns JSON text into plain Java
 * values: Map, List, String, Double/Long, Boolean or null.
 *
 * Written by hand because this project deliberately has zero external
 * dependencies (the build environment can't reach Maven Central). In a real
 * service this would be Jackson or kotlinx.serialization.
 */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) {
            throw p.error("Unexpected trailing content");
        }
        return v;
    }

    private Object value() {
        skipWs();
        if (i >= s.length()) throw error("Unexpected end of input");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nul();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            m.put(key, value());
            skipWs();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWs();
        if (peek() == ']') { i++; return list; }
        while (true) {
            list.add(value());
            skipWs();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("Expected ',' or ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("Bad escape: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        if (num.isEmpty()) throw error("Unexpected character");
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw error("Expected true/false");
    }

    private Object nul() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw error("Expected null");
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        if (i >= s.length()) throw error("Unexpected end of input");
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw error("Expected '" + c + "'");
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at position " + i);
    }

    // ---------- writer ----------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            sb.append('"');
            for (char c : str.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) sb.append(',');
                write(list.get(k), sb);
            }
            sb.append(']');
        } else {
            write(v.toString(), sb);
        }
    }
}
