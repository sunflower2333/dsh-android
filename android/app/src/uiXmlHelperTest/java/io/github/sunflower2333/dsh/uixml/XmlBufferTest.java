package io.github.sunflower2333.dsh.uixml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;

/** Tests encoding/limits only. No fake Android nodes or capture result fixtures. */
public final class XmlBufferTest {
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void limit(String expected, XmlBuffer buffer, String value) throws Exception {
        try {
            buffer.attribute("a", value);
            throw new AssertionError("expected " + expected);
        } catch (XmlBuffer.Limit failure) {
            require(expected.equals(failure.code));
        }
    }

    public static void main(String[] args) throws Exception {
        String source = "<&>\"'\t\n\r\ud83d\ude80\u0000\ud800";
        XmlBuffer encoded = new XmlBuffer(4096, 512);
        encoded.raw("<node").attribute("text", source).raw("/>");
        String decoded = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(encoded.toUtf8()))
                .getDocumentElement().getAttribute("text");
        require(("<&>\"'\t\n\r\ud83d\ude80\ufffd\ufffd").equals(decoded));
        limit("xml_field_limit", new XmlBuffer(100, 2), "abc");
        // Two non-ASCII characters consume four UTF-8 bytes plus five attribute bytes.
        XmlBuffer exact = new XmlBuffer(9, 10);
        exact.attribute("a", "\u00e9\u00e9");
        require(exact.toUtf8().length == 9);
        limit("xml_byte_limit", new XmlBuffer(8, 10), "\u00e9\u00e9");
        XmlBuffer nullField = new XmlBuffer(5, 1);
        nullField.attribute("a", null);
        require(" a=\"\"".equals(new String(nullField.toUtf8(), StandardCharsets.UTF_8)));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 8192; i++) longText.append('x');
        XmlBuffer longField = new XmlBuffer(9000, 512);
        longField.attribute("text", longText, 8192);
        require(longField.toUtf8().length == 8200);
        longText.append('x');
        try {
            new XmlBuffer(9000, 512).attribute("text", longText, 8192);
            throw new AssertionError("expected text cap");
        } catch (XmlBuffer.Limit failure) {
            require("xml_field_limit".equals(failure.code));
        }
        System.out.println("XmlBufferTest: 7 encoding/limit checks passed");
    }
}
