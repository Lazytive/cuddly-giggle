package io.github.lazytive.alosearth.core;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Reference TIFF LZW encoder for tests (with early change, clears at 4094). */
final class LzwEncoder {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long acc;
    private int nacc;

    private void write(int code, int bits) {
        acc = (acc << bits) | code;
        nacc += bits;
        while (nacc >= 8) {
            out.write((int) (acc >> (nacc - 8)) & 0xff);
            nacc -= 8;
        }
    }

    static byte[] encode(byte[] data) {
        LzwEncoder e = new LzwEncoder();
        Map<Long, Integer> table = new HashMap<>();
        int next = 258, bits = 9;
        e.write(256, bits);
        int w = -1;
        for (byte b : data) {
            int c = b & 0xff;
            if (w == -1) {
                w = c;
                continue;
            }
            long key = ((long) w << 8) | c;
            Integer code = table.get(key);
            if (code != null) {
                w = code;
                continue;
            }
            e.write(w, bits);
            table.put(key, next++);
            if (next == 512) bits = 10;
            else if (next == 1024) bits = 11;
            else if (next == 2048) bits = 12;
            if (next == 4094) {
                e.write(256, bits);
                table.clear();
                next = 258;
                bits = 9;
            }
            w = c;
        }
        if (w != -1) {
            e.write(w, bits);
            next++;
            if (next == 512) bits = 10;
            else if (next == 1024) bits = 11;
            else if (next == 2048) bits = 12;
        }
        e.write(257, bits);
        if (e.nacc > 0) e.out.write((int) (e.acc << (8 - e.nacc)) & 0xff);
        return e.out.toByteArray();
    }
}
