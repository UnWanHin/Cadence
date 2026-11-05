package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON encode/decode so the LAN room protocol needs no third-party
 * library. Shared by the Android app and the desktop test harness, which is
 * why it lives in plain Java rather than Kotlin/Android APIs.
 */
public final class RoomJson {

    private RoomJson() {
    }

    // ---- encode ----------------------------------------------------------

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append('0');
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(d);
        } else if (value instanceof Number) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    // ---- decode ----------------------------------------------------------

    public static Object read(String text) {
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.readValue();
        parser.skipSpace();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object value = read(text);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        void skipSpace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        Object readValue() {
            if (index >= text.length()) return null;
            char c = text.charAt(index);
            switch (c) {
                case '{': return readMap();
                case '[': return readList();
                case '"': return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default: return readNumber();
            }
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) throw new IllegalArgumentException("bad literal at " + index);
            index += literal.length();
        }

        private Map<String, Object> readMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // {
            skipSpace();
            if (index < text.length() && text.charAt(index) == '}') { index++; return map; }
            while (index < text.length()) {
                skipSpace();
                String key = readString();
                skipSpace();
                index++; // :
                skipSpace();
                map.put(key, readValue());
                skipSpace();
                if (index >= text.length()) break;
                char c = text.charAt(index++);
                if (c == '}') break;
            }
            return map;
        }

        private List<Object> readList() {
            List<Object> list = new ArrayList<>();
            index++; // [
            skipSpace();
            if (index < text.length() && text.charAt(index) == ']') { index++; return list; }
            while (index < text.length()) {
                skipSpace();
                list.add(readValue());
                skipSpace();
                if (index >= text.length()) break;
                char c = text.charAt(index++);
                if (c == ']') break;
            }
            return list;
        }

        private String readString() {
            skipSpace();
            if (index >= text.length() || text.charAt(index) != '"') return "";
            index++;
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') break;
                if (c != '\\') { out.append(c); continue; }
                if (index >= text.length()) break;
                char esc = text.charAt(index++);
                switch (esc) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (index + 4 <= text.length()) {
                            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            index += 4;
                        }
                        break;
                    default: out.append(esc);
                }
            }
            return out.toString();
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length() && "+-.eE0123456789".indexOf(text.charAt(index)) >= 0) index++;
            if (start == index) { index++; return null; }
            String token = text.substring(start, index);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }
}
