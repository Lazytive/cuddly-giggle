package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Byte access to a remote cloud-optimised GeoTIFF using HTTP range requests,
 * so only the header and the internal tiles actually needed are downloaded
 * (about 2 MB each instead of ~40 MB per 1x1 degree file). Every piece is
 * cached on disk, so each is fetched once.
 */
final class RemoteBytes {
    static final int HEAD = 65536;

    private final URI url;
    private final Path dir;
    private final HttpClient http;
    private final byte[] head;

    /** @throws NoSuchFileException if the file does not exist on the server */
    RemoteBytes(URI url, Path dir, HttpClient http) throws IOException {
        this.url = url;
        this.dir = dir;
        this.http = http;
        Path headFile = dir.resolve("head.bin");
        if (Files.exists(headFile)) {
            head = Files.readAllBytes(headFile);
        } else {
            head = fetch(0, HEAD, true);
            Files.createDirectories(dir);
            write(headFile, head);
        }
    }

    ByteBuffer read(long off, int len) throws IOException {
        if (off + len <= head.length) return ByteBuffer.wrap(head, (int) off, len).slice();
        Path piece = dir.resolve(off + "-" + len + ".bin");
        byte[] data;
        if (Files.exists(piece)) {
            data = Files.readAllBytes(piece);
        } else {
            data = fetch(off, len, false);
            if (data.length != len) throw new IOException("short read from " + url + " at " + off);
            write(piece, data);
        }
        return ByteBuffer.wrap(data);
    }

    private byte[] fetch(long off, int len, boolean allowShort) throws IOException {
        long t0 = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(2))
            .header("Range", "bytes=" + off + "-" + (off + len - 1)).GET().build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        int code = resp.statusCode();
        if (code == 404 || code == 403) throw new NoSuchFileException(url.toString());
        byte[] body = resp.body();
        if (code == 200) { // server ignored the range: take the slice we asked for
            int end = (int) Math.min(body.length, off + len);
            byte[] cut = new byte[Math.max(0, end - (int) off)];
            System.arraycopy(body, (int) off, cut, 0, cut.length);
            body = cut;
        } else if (code != 206) {
            throw new IOException("HTTP " + code + " for " + url);
        }
        if (!allowShort && body.length != len) throw new IOException("short read from " + url);
        AutoDem.BYTES_FETCHED.addAndGet(body.length);
        AutoDem.log(String.format("fetched %.1f MB of %s in %.1f s", body.length / 1e6,
            url.getPath().substring(url.getPath().lastIndexOf('/') + 1), (System.nanoTime() - t0) / 1e9));
        return body;
    }

    private static void write(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
