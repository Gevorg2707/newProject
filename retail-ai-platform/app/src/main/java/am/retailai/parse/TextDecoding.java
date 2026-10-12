package am.retailai.parse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Text files are UTF-8 (with or without BOM) or UTF-16 with BOM. Anything else is refused: decoding it as UTF-8 would
 * silently turn letters into U+FFFD, and guessing a legacy code page (windows-1251, ARMSCII-8, cp866) can be wrong.
 */
final class TextDecoding {

    private TextDecoding() {
    }

    static String decode(byte[] b, String what) throws IOException {
        if (b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IOException(what + " is not UTF-8 (probably windows-1251 or another legacy encoding). Nothing was"
                + " imported. Export it again as UTF-8 (Excel: 'CSV UTF-8'), or convert it: iconv -f CP1251 -t UTF-8 file > file.utf8", e);
        }
    }
}
