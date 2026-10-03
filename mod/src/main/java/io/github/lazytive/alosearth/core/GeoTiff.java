package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipFile;

/**
 * Minimal GeoTIFF reader for single-band lon/lat rasters: strips or tiles,
 * no/Deflate/LZW compression, horizontal and floating-point predictors,
 * 8/16/32-bit integer and 32-bit float samples, classic TIFF and BigTIFF.
 * Pixels are decoded one segment (strip or tile) at a time through a shared
 * {@link SegmentCache}, so huge global grids never have to fit in memory.
 */
public final class GeoTiff implements AutoCloseable {
    /**
     * Where the bytes come from: a file, an entry of a zip file (read into
     * memory), or a remote cloud-optimised GeoTIFF read with range requests
     * and cached under {@code path}.
     */
    public record Source(Path path, String zipEntry, java.net.URI url, java.net.http.HttpClient http) {
        public Source(Path path, String zipEntry) {
            this(path, zipEntry, null, null);
        }

        @Override
        public String toString() {
            return url != null ? url.toString() : zipEntry == null ? path.toString() : path + "!" + zipEntry;
        }
    }

    public final Source source;
    public final int width, height;
    public final int segWidth, segHeight, segsAcross;
    /** Longitude/latitude of the centre of pixel (0, 0), and pixel size in degrees (both > 0). */
    public final double x0, y0, dx, dy;
    public final double nodata;
    private final int bits, sampleFormat, compression, predictor;
    private final long[] offsets, byteCounts;
    private final ByteOrder order;
    private ByteBuffer memory; // zip entries are held in memory; files are opened per read
    private RemoteBytes remote;

    public GeoTiff(Source source) throws IOException {
        this.source = source;
        if (source.url() != null) {
            remote = new RemoteBytes(source.url(), source.path(), source.http());
        } else if (source.zipEntry() != null) {
            try (ZipFile z = new ZipFile(source.path().toFile())) {
                var entry = z.getEntry(source.zipEntry());
                if (entry == null) throw new IOException("missing zip entry " + source);
                try (InputStream in = z.getInputStream(entry)) {
                    memory = ByteBuffer.wrap(in.readAllBytes());
                }
            }
        }
        ByteBuffer head = read(0, 16);
        byte b0 = head.get(0);
        order = b0 == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        head.order(order);
        int magic = head.getShort(2) & 0xffff;
        boolean big = magic == 43;
        if (magic != 42 && !big) throw new IOException("not a TIFF: " + source);
        long ifd = big ? head.getLong(8) : head.getInt(4) & 0xffffffffL;

        ByteBuffer cnt = read(ifd, big ? 8 : 2).order(order);
        long n = big ? cnt.getLong(0) : cnt.getShort(0) & 0xffff;
        int entrySize = big ? 20 : 12;
        ByteBuffer entries = read(ifd + (big ? 8 : 2), (int) (n * entrySize)).order(order);

        int w = 0, h = 0, bps = 8, sf = 1, comp = 1, pred = 1, rps = -1, tw = 0, th = 0, spp = 1;
        long[] offs = null, counts = null;
        double[] scale = null, tie = null, transform = null;
        int rasterType = 1;
        double nd = Double.NaN;
        for (int i = 0; i < n; i++) {
            int base = i * entrySize;
            int tag = entries.getShort(base) & 0xffff;
            int type = entries.getShort(base + 2) & 0xffff;
            long count = big ? entries.getLong(base + 4) : entries.getInt(base + 4) & 0xffffffffL;
            int valueOff = base + (big ? 12 : 8);
            switch (tag) {
                case 256 -> w = (int) values(entries, valueOff, type, count, big)[0];
                case 257 -> h = (int) values(entries, valueOff, type, count, big)[0];
                case 258 -> bps = (int) values(entries, valueOff, type, count, big)[0];
                case 259 -> comp = (int) values(entries, valueOff, type, count, big)[0];
                case 277 -> spp = (int) values(entries, valueOff, type, count, big)[0];
                case 278 -> rps = (int) values(entries, valueOff, type, count, big)[0];
                case 317 -> pred = (int) values(entries, valueOff, type, count, big)[0];
                case 322 -> tw = (int) values(entries, valueOff, type, count, big)[0];
                case 323 -> th = (int) values(entries, valueOff, type, count, big)[0];
                case 339 -> sf = (int) values(entries, valueOff, type, count, big)[0];
                case 273, 324 -> offs = toLongs(values(entries, valueOff, type, count, big));
                case 279, 325 -> counts = toLongs(values(entries, valueOff, type, count, big));
                case 33550 -> scale = values(entries, valueOff, type, count, big);
                case 33922 -> tie = values(entries, valueOff, type, count, big);
                case 34264 -> transform = values(entries, valueOff, type, count, big);
                case 34735 -> {
                    double[] k = values(entries, valueOff, type, count, big);
                    for (int j = 4; j + 3 < k.length; j += 4) {
                        if ((int) k[j] == 1025 && (int) k[j + 1] == 0) rasterType = (int) k[j + 3];
                    }
                }
                case 42113 -> {
                    String s = ascii(entries, valueOff, count, big).trim();
                    try {
                        nd = Double.parseDouble(s);
                    } catch (NumberFormatException ignored) {
                        // leave as NaN
                    }
                }
                default -> { }
            }
        }
        if (spp != 1) throw new IOException("only single-band rasters are supported: " + source);
        if (offs == null || counts == null) throw new IOException("no image data: " + source);
        width = w;
        height = h;
        bits = bps;
        sampleFormat = sf;
        compression = comp;
        predictor = pred;
        offsets = offs;
        byteCounts = counts;
        if (tw > 0) {
            segWidth = tw;
            segHeight = th;
        } else {
            segWidth = w;
            segHeight = rps <= 0 || rps > h ? h : rps;
        }
        segsAcross = (width + segWidth - 1) / segWidth;
        nodata = nd;

        double sx, sy, X, Y;
        if (scale != null && tie != null) {
            sx = scale[0];
            sy = scale[1];
            X = tie[3] - tie[0] * sx;
            Y = tie[4] + tie[1] * sy;
        } else if (transform != null) {
            sx = transform[0];
            sy = -transform[5];
            X = transform[3];
            Y = transform[7];
        } else {
            throw new IOException("no georeferencing in " + source);
        }
        if (rasterType == 2) { // PixelIsPoint
            x0 = X;
            y0 = Y;
        } else {
            x0 = X + sx / 2;
            y0 = Y - sy / 2;
        }
        dx = sx;
        dy = sy;
    }

    /** {west, south, east, north} of the pixel areas. */
    public double[] bounds() {
        return new double[] {x0 - dx / 2, y0 - (height - 0.5) * dy, x0 + (width - 0.5) * dx, y0 + dy / 2};
    }

    @Override
    public synchronized void close() {
        memory = null;
    }

    // ------------------------------------------------------------ IFD parsing

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            default -> 8; // 5, 10, 12, 16, 17, 18
        };
    }

    private double[] values(ByteBuffer entries, int valueOff, int type, long count, boolean big) throws IOException {
        int size = typeSize(type);
        long total = size * count;
        ByteBuffer buf;
        int pos;
        if (total <= (big ? 8 : 4)) {
            buf = entries;
            pos = valueOff;
        } else {
            long off = big ? entries.getLong(valueOff) : entries.getInt(valueOff) & 0xffffffffL;
            buf = read(off, (int) total).order(order);
            pos = 0;
        }
        double[] out = new double[(int) count];
        for (int i = 0; i < count; i++) {
            int p = pos + i * size;
            out[i] = switch (type) {
                case 1, 7 -> buf.get(p) & 0xff;
                case 6 -> buf.get(p);
                case 3 -> buf.getShort(p) & 0xffff;
                case 8 -> buf.getShort(p);
                case 4 -> buf.getInt(p) & 0xffffffffL;
                case 9 -> buf.getInt(p);
                case 11 -> buf.getFloat(p);
                case 12 -> buf.getDouble(p);
                case 16, 17, 18 -> buf.getLong(p);
                case 5 -> (buf.getInt(p) & 0xffffffffL) / (double) (buf.getInt(p + 4) & 0xffffffffL);
                case 10 -> buf.getInt(p) / (double) buf.getInt(p + 4);
                default -> 0;
            };
        }
        return out;
    }

    private String ascii(ByteBuffer entries, int valueOff, long count, boolean big) throws IOException {
        byte[] b = new byte[(int) count];
        if (count <= (big ? 8 : 4)) {
            for (int i = 0; i < count; i++) b[i] = entries.get(valueOff + i);
        } else {
            long off = big ? entries.getLong(valueOff) : entries.getInt(valueOff) & 0xffffffffL;
            read(off, (int) count).get(b);
        }
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII).replace("\0", "");
    }

    private static long[] toLongs(double[] v) {
        long[] out = new long[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (long) v[i];
        return out;
    }

    private ByteBuffer read(long off, int len) throws IOException {
        if (remote != null) return remote.read(off, len).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer mem = memory;
        if (source.zipEntry() != null) {
            if (mem == null) throw new IOException("closed: " + source);
            ByteBuffer b = ByteBuffer.allocate(len);
            b.put(mem.duplicate().position((int) off).limit((int) off + len));
            return b.flip();
        }
        ByteBuffer b = ByteBuffer.allocate(len);
        try (FileChannel ch = FileChannel.open(source.path(), StandardOpenOption.READ)) {
            while (b.hasRemaining()) {
                int r = ch.read(b, off + b.position());
                if (r < 0) throw new IOException("unexpected end of " + source);
            }
        }
        return b.flip();
    }

    // ------------------------------------------------------------ segments

    /** A decoded strip or tile. */
    public interface Segment {
        double get(int row, int col);

        long bytes();
    }

    record ShortSeg(short[] v, int w, boolean unsigned) implements Segment {
        public double get(int r, int c) {
            short s = v[r * w + c];
            return unsigned ? (s & 0xffff) : s;
        }

        public long bytes() {
            return 2L * v.length;
        }
    }

    record ByteSeg(byte[] v, int w, boolean signed) implements Segment {
        public double get(int r, int c) {
            byte b = v[r * w + c];
            return signed ? b : (b & 0xff);
        }

        public long bytes() {
            return v.length;
        }
    }

    record IntSeg(int[] v, int w, boolean unsigned) implements Segment {
        public double get(int r, int c) {
            int i = v[r * w + c];
            return unsigned ? (i & 0xffffffffL) : i;
        }

        public long bytes() {
            return 4L * v.length;
        }
    }

    record FloatSeg(float[] v, int w) implements Segment {
        public double get(int r, int c) {
            return v[r * w + c];
        }

        public long bytes() {
            return 4L * v.length;
        }
    }

    public int segmentIndex(int row, int col) {
        return (row / segHeight) * segsAcross + col / segWidth;
    }

    /** Decode segment {@code idx}; rows/cols inside it are relative to its top-left pixel. */
    public Segment decode(int idx) throws IOException {
        int segRows = segHeight;
        if (segWidth == width) { // strips: the last one may be short
            int first = (idx / segsAcross) * segHeight;
            segRows = Math.min(segHeight, height - first);
        }
        int bytesPer = bits / 8;
        int raw = segWidth * segRows * bytesPer;
        ByteBuffer comp = read(offsets[idx], (int) byteCounts[idx]);
        byte[] data;
        switch (compression) {
            case 1 -> {
                data = new byte[raw];
                comp.get(data, 0, Math.min(raw, comp.remaining()));
            }
            case 8, 32946 -> data = inflate(comp, raw);
            case 5 -> data = lzw(comp, raw);
            default -> throw new IOException("unsupported TIFF compression " + compression + " in " + source);
        }
        if (predictor == 2) undoHorizontal(data, segWidth, segRows, bytesPer);
        else if (predictor == 3) data = undoFloatPredictor(data, segWidth, segRows, bytesPer);
        ByteBuffer bb = ByteBuffer.wrap(data).order(predictor == 3 ? ByteOrder.BIG_ENDIAN : order);
        int count = segWidth * segRows;
        if (sampleFormat == 3) {
            if (bits != 32) throw new IOException("only 32-bit floats are supported");
            float[] v = new float[count];
            bb.asFloatBuffer().get(v);
            return new FloatSeg(v, segWidth);
        }
        boolean signed = sampleFormat == 2;
        return switch (bits) {
            case 8 -> new ByteSeg(data, segWidth, signed);
            case 16 -> {
                short[] v = new short[count];
                bb.asShortBuffer().get(v);
                yield new ShortSeg(v, segWidth, !signed);
            }
            case 32 -> {
                int[] v = new int[count];
                bb.asIntBuffer().get(v);
                yield new IntSeg(v, segWidth, !signed);
            }
            default -> throw new IOException("unsupported sample size " + bits);
        };
    }

    private static byte[] inflate(ByteBuffer comp, int raw) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(comp);
            byte[] out = new byte[raw];
            int n = 0;
            while (n < raw && !inf.finished()) {
                int r = inf.inflate(out, n, raw - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("bad deflate data", e);
        } finally {
            inf.end();
        }
    }

    /** TIFF LZW (MSB-first codes, 9-12 bits, "early change"). */
    static byte[] lzw(ByteBuffer comp, int raw) {
        byte[] in = new byte[comp.remaining()];
        comp.get(in);
        byte[] out = new byte[raw];
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] first = new byte[4096];
        int[] length = new int[4096];
        for (int i = 0; i < 256; i++) {
            prefix[i] = -1;
            suffix[i] = (byte) i;
            first[i] = (byte) i;
            length[i] = 1;
        }
        int next = 258, nbits = 9, old = -1, op = 0;
        long bitPos = 0, totalBits = (long) in.length * 8;
        while (bitPos + nbits <= totalBits && op < raw) {
            int code = 0;
            for (int i = 0; i < nbits; i++, bitPos++) {
                code = (code << 1) | ((in[(int) (bitPos >> 3)] >> (7 - (bitPos & 7))) & 1);
            }
            if (code == 257) break;
            if (code == 256) {
                next = 258;
                nbits = 9;
                old = -1;
                continue;
            }
            if (old == -1) {
                out[op++] = (byte) code;
                old = code;
                continue;
            }
            int entry;
            if (code < next) {
                entry = code;
                if (next < 4096) add(prefix, suffix, first, length, next++, old, first[code]);
            } else {
                add(prefix, suffix, first, length, next++, old, first[old]);
                entry = next - 1;
            }
            int len = length[entry];
            for (int p = op + len - 1, c = entry; p >= op; p--, c = prefix[c]) {
                if (p < raw) out[p] = suffix[c];
            }
            op = Math.min(op + len, raw);
            old = code;
            if (next == 511) nbits = 10;
            else if (next == 1023) nbits = 11;
            else if (next == 2047) nbits = 12;
        }
        return out;
    }

    private static void add(int[] prefix, byte[] suffix, byte[] first, int[] length, int code, int old, byte b) {
        prefix[code] = old;
        suffix[code] = b;
        first[code] = first[old];
        length[code] = length[old] + 1;
    }

    private void undoHorizontal(byte[] d, int w, int rows, int bytesPer) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(order);
        for (int r = 0; r < rows; r++) {
            int base = r * w * bytesPer;
            for (int c = 1; c < w; c++) {
                int p = base + c * bytesPer, q = p - bytesPer;
                switch (bytesPer) {
                    case 1 -> d[p] = (byte) (d[p] + d[q]);
                    case 2 -> bb.putShort(p, (short) (bb.getShort(p) + bb.getShort(q)));
                    case 4 -> bb.putInt(p, bb.getInt(p) + bb.getInt(q));
                    default -> { }
                }
            }
        }
    }

    /** Floating point predictor: byte-wise differencing of byte-shuffled rows. Output is big-endian. */
    private static byte[] undoFloatPredictor(byte[] d, int w, int rows, int bytesPer) {
        byte[] out = new byte[d.length];
        int rowBytes = w * bytesPer;
        for (int r = 0; r < rows; r++) {
            int base = r * rowBytes;
            for (int i = 1; i < rowBytes; i++) d[base + i] = (byte) (d[base + i] + d[base + i - 1]);
            for (int c = 0; c < w; c++) {
                for (int k = 0; k < bytesPer; k++) out[base + c * bytesPer + k] = d[base + k * w + c];
            }
        }
        return out;
    }
}
