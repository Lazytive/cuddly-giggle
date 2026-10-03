package io.github.lazytive.alosearth.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The Koppen-Geiger climate map shipped inside the mod (Rubel et al. 2017,
 * resampled to 5 arc-minutes; see scripts/make_climate.py), used when no
 * climate GeoTIFF is installed. Classes use the Beck et al. numbering; 0 is
 * ocean.
 */
public final class BuiltinClimate {
    private static volatile BuiltinClimate instance;
    private final int width, height;
    /** Per row: run start columns and classes (the map is stored run-length encoded, ~1.5 MB). */
    private final short[][] starts;
    private final byte[][] classes;

    private BuiltinClimate(int w, int h, short[][] starts, byte[][] classes) {
        width = w;
        height = h;
        this.starts = starts;
        this.classes = classes;
    }

    public int width() {
        return width;
    }

    /** The bundled map, or null if it cannot be read. */
    public static BuiltinClimate get() {
        BuiltinClimate b = instance;
        if (b == null) {
            synchronized (BuiltinClimate.class) {
                b = instance;
                if (b == null) {
                    b = load();
                    instance = b;
                }
            }
        }
        return b.width > 0 ? b : null;
    }

    private static BuiltinClimate load() {
        try (InputStream raw = BuiltinClimate.class.getResourceAsStream("/alosearth/koppen.rle.gz")) {
            if (raw == null) return new BuiltinClimate(0, 0, null, null);
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw, 1 << 16)));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("KRLE")) throw new IOException("bad map");
            int w = in.readInt(), h = in.readInt();
            short[][] st = new short[h][];
            byte[][] cl = new byte[h][];
            for (int y = 0; y < h; y++) {
                int n = in.readUnsignedShort();
                st[y] = new short[n];
                cl[y] = new byte[n];
                for (int i = 0; i < n; i++) {
                    st[y][i] = (short) in.readUnsignedShort();
                    cl[y][i] = in.readByte();
                }
            }
            return new BuiltinClimate(w, h, st, cl);
        } catch (IOException e) {
            return new BuiltinClimate(0, 0, null, null);
        }
    }

    /** Koppen class (Beck numbering) at a place; 0 for ocean. */
    public int at(double lon, double lat) {
        lon = Rasters.normLon(lon);
        int x = (int) Math.floor((lon + 180) / 360 * width);
        int y = (int) Math.floor((90 - lat) / 180 * height);
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        short[] st = starts[y];
        int lo = 0, hi = st.length - 1;
        while (lo < hi) { // last run starting at or before x
            int mid = (lo + hi + 1) >>> 1;
            if ((st[mid] & 0xffff) <= x) lo = mid;
            else hi = mid - 1;
        }
        return classes[y][lo] & 0xff;
    }
}
