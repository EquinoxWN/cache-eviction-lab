package portfolio.cacheevictionlab.trace;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads pinned traces and proves they are the pinned bytes.
 *
 * <p>A download goes to {@code <name>.part}, stops as soon as it passes the pinned size, and is
 * renamed to {@code <name>.csv} only after the size and SHA-256 match. Redirects are not followed.
 * A trace already on disk is re-hashed instead of downloaded again, and a file that does not match
 * its pin is reported, never overwritten.
 */
public final class TraceFetcher implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** Releases the HTTP client's threads. */
    @Override
    public void close() {
        client.close();
    }

    /** Result of making one trace available. */
    public record Fetched(Path path, boolean downloaded) {}

    /** Ensures {@code dir/<name>.csv} holds exactly the pinned bytes, downloading it if absent. */
    public Fetched fetch(TracePin pin, Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path target = dir.resolve(pin.fileName());
        if (Files.exists(target)) {
            verify(pin, target);
            return new Fetched(target, false);
        }
        Path part = dir.resolve(pin.name() + ".part");
        HttpRequest request = HttpRequest.newBuilder(pin.url())
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(pin.url() + " answered HTTP " + response.statusCode());
            }
            MessageDigest sha = sha256();
            long total = 0;
            try (OutputStream out = Files.newOutputStream(
                    part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = body.read(buf)) > 0) {
                    total += n;
                    if (total > pin.bytes()) {
                        throw new IOException(
                                pin.name() + ": download is larger than the pinned " + pin.bytes() + " bytes");
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            check(pin, total, HexFormat.of().formatHex(sha.digest()));
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        return new Fetched(target, true);
    }

    /** Throws unless the file has the pinned size and SHA-256. */
    public static void verify(TracePin pin, Path file) throws IOException {
        long size = Files.size(file);
        if (size != pin.bytes()) {
            check(pin, size, "");
        }
        MessageDigest sha = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        }
        check(pin, size, HexFormat.of().formatHex(sha.digest()));
    }

    /** Compares a size and digest with the pin. */
    private static void check(TracePin pin, long size, String sha256) throws IOException {
        if (size != pin.bytes()) {
            throw new IOException(
                    pin.name() + ": size " + size + " does not match the pinned " + pin.bytes() + " bytes");
        }
        if (!sha256.equals(pin.sha256())) {
            throw new IOException(pin.name() + ": SHA-256 " + sha256 + " does not match the pinned " + pin.sha256());
        }
    }

    /** A fresh SHA-256 digest. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }
}
