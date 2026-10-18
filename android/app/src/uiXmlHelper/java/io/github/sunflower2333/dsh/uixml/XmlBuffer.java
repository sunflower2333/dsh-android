package io.github.sunflower2333.dsh.uixml;

import java.nio.charset.StandardCharsets;

/** UTF-8 bounded XML 1.0 attribute encoding; contains no Android access. */
final class XmlBuffer {
    static final class Limit extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        Limit(String code) { this.code = code; }
    }

    private final int maxBytes;
    private final int maxFieldChars;
    private final StringBuilder value = new StringBuilder();
    private int bytes;

    XmlBuffer(int maxBytes, int maxFieldChars) {
        this.maxBytes = maxBytes;
        this.maxFieldChars = maxFieldChars;
    }

    XmlBuffer raw(String text) throws Limit {
        int added = text.getBytes(StandardCharsets.UTF_8).length;
        if (added > maxBytes - bytes) throw new Limit("xml_byte_limit");
        value.append(text);
        bytes += added;
        return this;
    }

    XmlBuffer attribute(String name, CharSequence text) throws Limit {
        return attribute(name, text, maxFieldChars);
    }

    XmlBuffer attribute(String name, CharSequence text, int fieldLimit) throws Limit {
        int length = text == null ? 0 : text.length();
        if (length > fieldLimit) throw new Limit("xml_field_limit");
        StringBuilder escaped = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\'': escaped.append("&apos;"); break;
                case '\t': escaped.append("&#9;"); break;
                case '\n': escaped.append("&#10;"); break;
                case '\r': escaped.append("&#13;"); break;
                default:
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                            escaped.append(c).append(text.charAt(++i));
                        } else {
                            escaped.append('\ufffd');
                        }
                    } else if (Character.isLowSurrogate(c) || c < 0x20 || c == 0xfffe || c == 0xffff) {
                        escaped.append('\ufffd');
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return raw(" " + name + "=\"").raw(escaped.toString()).raw("\"");
    }

    byte[] toUtf8() { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
