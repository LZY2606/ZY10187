package com.windtunnel.zeroledger.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON：不引入额外依赖，保证本地离线可运行。
 * 支持 Map/List/String/Number/Boolean/null 的读写。
 * 序列化遇到 NaN/Infinity 直接抛异常，从根源上杜绝“无穷大系数”被输出。
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        return write(value, false);
    }

    public static String writePretty(Object value) {
        return write(value, true);
    }

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value, 0, pretty);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value, int indent, boolean pretty) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Boolean bool) {
            sb.append(bool.booleanValue());
        } else if (value instanceof Number num) {
            double d = num.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON 不支持 NaN/Infinity");
            }
            if (num instanceof Double || num instanceof Float) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    sb.append((long) d);
                } else {
                    sb.append(num);
                }
            } else {
                sb.append(num);
            }
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            if (pretty) {
                sb.append('\n');
            }
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (pretty) {
                    pad(sb, indent + 1);
                }
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                writeTo(sb, e.getValue(), indent + 1, pretty);
                if (++i < map.size()) {
                    sb.append(',');
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, indent);
            }
            sb.append('}');
        } else if (value instanceof int[] arr) {
            writeTo(sb, toList(arr), indent, pretty);
        } else if (value instanceof long[] arr) {
            writeTo(sb, toList(arr), indent, pretty);
        } else if (value instanceof double[] arr) {
            writeTo(sb, toList(arr), indent, pretty);
        } else if (value instanceof boolean[] arr) {
            writeTo(sb, toList(arr), indent, pretty);
        } else if (value instanceof Object[] arr) {
            writeTo(sb, java.util.Arrays.asList(arr), indent, pretty);
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            if (pretty) {
                sb.append('\n');
            }
            for (int i = 0; i < list.size(); i++) {
                if (pretty) {
                    pad(sb, indent + 1);
                }
                writeTo(sb, list.get(i), indent + 1, pretty);
                if (i < list.size() - 1) {
                    sb.append(',');
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, indent);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static java.util.List<Object> toList(int[] a) {
        java.util.List<Object> l = new java.util.ArrayList<>(a.length);
        for (int v : a) l.add(v);
        return l;
    }

    private static java.util.List<Object> toList(long[] a) {
        java.util.List<Object> l = new java.util.ArrayList<>(a.length);
        for (long v : a) l.add(v);
        return l;
    }

    private static java.util.List<Object> toList(double[] a) {
        java.util.List<Object> l = new java.util.ArrayList<>(a.length);
        for (double v : a) l.add(v);
        return l;
    }

    private static java.util.List<Object> toList(boolean[] a) {
        java.util.List<Object> l = new java.util.ArrayList<>(a.length);
        for (boolean v : a) l.add(v);
        return l;
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.text.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余字符 @" + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("意外结束");
            }
            return switch (text.charAt(pos)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                map.put(key, readValue());
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("需要 , 或 } @" + pos);
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("需要 , 或 ] @" + pos);
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("非法字面量 @" + pos);
        }

        Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("非法字面量 @" + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || token.equals("-")) {
                throw new IllegalArgumentException("非法数字 @" + start);
            }
            return isDouble ? Double.parseDouble(token) : Long.parseLong(token);
        }

        char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        char next() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("意外结束");
            }
            return text.charAt(pos++);
        }

        void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new IllegalArgumentException("需要 '" + c + "' 但得到 '" + actual + "' @" + pos);
            }
        }
    }
}
