package org.miosys.transport.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Splits the raw path and strictly percent-decodes each segment. '+' stays literal. */
public final class PathCodec {

    private PathCodec() {}

    public static List<String> segments(String rawPath) {
        String[] parts = rawPath.split("/", -1);
        int start = 0;
        int end = parts.length;
        while (start < end && parts[start].isEmpty()) {
            start++;
        }
        while (end > start && parts[end - 1].isEmpty()) {
            end--;
        }
        List<String> out = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            out.add(decode(parts[i]));
        }
        return out;
    }

    static String decode(String segment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        byte[] ascii = segment.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        while (i < ascii.length) {
            byte b = ascii[i];
            if (b == '%') {
                if (i + 2 >= ascii.length) {
                    throw new ApiException.BadRequest("invalid path encoding");
                }
                int hi = Character.digit(ascii[i + 1], 16);
                int lo = Character.digit(ascii[i + 2], 16);
                if (hi < 0 || lo < 0) {
                    throw new ApiException.BadRequest("invalid path encoding");
                }
                bytes.write((hi << 4) | lo);
                i += 3;
            } else {
                bytes.write(b);
                i++;
            }
        }
        CharsetDecoder decoder =
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new ApiException.BadRequest("invalid path encoding");
        }
    }
}
