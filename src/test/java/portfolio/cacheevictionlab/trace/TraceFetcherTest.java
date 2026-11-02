package portfolio.cacheevictionlab.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Downloads from a loopback HTTP server: success, stalls, oversized bodies, wrong bytes, HTTP errors. */
class TraceFetcherTest {
    static final byte[] DATA = "0,k,1,1,1,get,0\n1,j,1,1,1,set,0\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private HttpServer server;
    private ExecutorService pool;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        pool.shutdownNow();
    }

    /** Serves {@code handler} at a path and returns its URL. */
    private URI serve(String path, Handler handler) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** The pin of {@link #DATA}, or of a different size or digest. */
    private static TracePin pin(long bytes, String sha) {
        return new TracePin("t", URI.create(TracePinTest.URL), bytes, sha, "CC-BY-4.0");
    }

    /** Files left in the data folder. */
    private List<String> files() throws IOException {
        try (var listing = Files.list(dir)) {
            return listing.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void downloadsThePinnedBytesOnceAndReusesThemAfterwards() throws Exception {
        URI url = serve("/ok", ex -> {
            ex.sendResponseHeaders(200, DATA.length);
            ex.getResponseBody().write(DATA);
        });
        TracePin pin = pin(DATA.length, TracePinTest.sha256(DATA));
        try (TraceFetcher fetcher = new TraceFetcher()) {
            assertTrue(fetcher.fetch(pin, url, dir).downloaded());
            assertFalse(fetcher.fetch(pin, url, dir).downloaded(), "a second fetch re-hashes, never downloads");
        }
        assertEquals(1, requests.get());
        assertArrayEquals(DATA, Files.readAllBytes(dir.resolve("t.csv")));
        assertEquals(List.of("t.csv"), files(), "no .part file is left");
    }

    @Test
    @Timeout(30)
    void aBodyThatStallsIsAbandonedAtTheDeadline() throws Exception {
        URI url = serve("/stall", ex -> {
            ex.sendResponseHeaders(200, DATA.length);
            OutputStream out = ex.getResponseBody();
            out.write(DATA, 0, 5);
            out.flush();
            release.await(60, TimeUnit.SECONDS);
        });
        try (TraceFetcher fetcher = new TraceFetcher(Duration.ofSeconds(1))) {
            IOException e = assertThrows(
                    IOException.class, () -> fetcher.fetch(pin(DATA.length, TracePinTest.sha256(DATA)), url, dir));
            assertTrue(e.getMessage().contains("did not finish within PT1S"), e.getMessage());
        }
        assertEquals(List.of(), files(), "the partial download is deleted");
    }

    @Test
    void aBodyLargerThanThePinIsCutOffAndDeleted() throws Exception {
        URI url = serve("/big", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(DATA);
        });
        try (TraceFetcher fetcher = new TraceFetcher()) {
            IOException e = assertThrows(
                    IOException.class, () -> fetcher.fetch(pin(DATA.length - 1, TracePinTest.sha256(DATA)), url, dir));
            assertTrue(e.getMessage().contains("larger than the pinned"), e.getMessage());
        }
        assertEquals(List.of(), files());
    }

    @Test
    void bytesThatDoNotMatchThePinnedDigestAreNeverRenamedIntoPlace() throws Exception {
        URI url = serve("/wrong", ex -> {
            ex.sendResponseHeaders(200, DATA.length);
            ex.getResponseBody().write(DATA);
        });
        try (TraceFetcher fetcher = new TraceFetcher()) {
            IOException e =
                    assertThrows(IOException.class, () -> fetcher.fetch(pin(DATA.length, TracePinTest.SHA), url, dir));
            assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        }
        assertEquals(List.of(), files());
    }

    @Test
    void httpErrorsAndRedirectsFailWithoutAFile() throws Exception {
        URI missing = serve("/missing", ex -> ex.sendResponseHeaders(404, -1));
        URI moved = serve("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
        });
        TracePin pin = pin(DATA.length, TracePinTest.sha256(DATA));
        try (TraceFetcher fetcher = new TraceFetcher()) {
            IOException notFound = assertThrows(IOException.class, () -> fetcher.fetch(pin, missing, dir));
            assertTrue(notFound.getMessage().contains("HTTP 404"), notFound.getMessage());
            IOException redirect = assertThrows(IOException.class, () -> fetcher.fetch(pin, moved, dir));
            assertTrue(redirect.getMessage().contains("HTTP 302"), redirect.getMessage());
        }
        assertEquals(List.of(), files());
    }
}
