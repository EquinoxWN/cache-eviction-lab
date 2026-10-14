package portfolio.cacheevictionlab.trace;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One public trace pinned by URL, exact size and SHA-256, so a replay always reads the same bytes.
 *
 * @param name short file-safe name; the trace is stored as {@code <name>.csv}
 * @param url HTTPS address on an allowed host, pinned to a commit
 * @param bytes exact size of the file
 * @param sha256 lower-case hex SHA-256 of the file
 * @param licence licence of the trace data
 */
public record TracePin(String name, URI url, long bytes, String sha256, String licence) {
    /** Hosts a pin may download from. */
    public static final Set<String> ALLOWED_HOSTS = Set.of("raw.githubusercontent.com");
    /** Largest trace a pin may name, 256 MiB, so one download cannot fill the disk. */
    public static final long MAX_BYTES = 256L << 20;

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Validates every field. */
    public TracePin {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("trace name must match " + NAME + ", got '" + name + "'");
        }
        if (!"https".equals(url.getScheme())
                || url.getHost() == null
                || !ALLOWED_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))
                || url.getPort() != -1
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getFragment() != null) {
            throw new IllegalArgumentException(
                    "trace url must be plain https on " + ALLOWED_HOSTS + ", got '" + url + "'");
        }
        if (bytes < 1 || bytes > MAX_BYTES) {
            throw new IllegalArgumentException("trace size must be between 1 and " + MAX_BYTES + " bytes");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex digits");
        }
        if (licence.isBlank()) {
            throw new IllegalArgumentException("licence must not be blank");
        }
    }

    /** File name of the downloaded trace. */
    public String fileName() {
        return name + ".csv";
    }

    /**
     * Reads a pins file: tab-separated {@code name url bytes sha256 licence}, a header line starting
     * with {@code name}, and {@code #} comment lines.
     */
    public static List<TracePin> readAll(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() > 1_000) {
            throw new IllegalArgumentException(file + ": more than 1,000 lines");
        }
        List<TracePin> pins = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("name\t")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 5) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": expected 5 tab-separated fields");
            }
            TracePin pin;
            try {
                pin = new TracePin(f[0], new URI(f[1]), Long.parseLong(f[2]), f[3], f[4]);
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
            if (!names.add(pin.name())) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": duplicate trace name " + pin.name());
            }
            pins.add(pin);
        }
        if (pins.isEmpty()) {
            throw new IllegalArgumentException(file + ": no traces pinned");
        }
        return pins;
    }
}
