package portfolio.cacheevictionlab.trace;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import portfolio.cacheevictionlab.sketch.Murmur3;

/**
 * Streams requests from a Twitter cache trace (Twemcache/Pelikan, OSDI 2020 release): one request
 * per line, {@code timestamp,key,key_size,value_size,client_id,operation,ttl}.
 *
 * <p>The reader holds at most {@link #MAX_LINE_BYTES} bytes of a line. The key is everything between
 * the first comma and the fifth comma from the end, so a comma inside a key cannot shift the other
 * fields. Each key is reduced to the first 64 bits of its MurmurHash3 x64 128 hash; for n distinct
 * keys the chance that any two collide is about {@code n^2 / 2^65} (below {@code 10^-7} for a million
 * keys). Lines that are too long, have a non-numeric numeric field, an empty key or an unknown
 * command are skipped and counted, never guessed.
 */
public final class TwitterTraceReader implements Closeable {
    /** Longest accepted line in bytes, without the terminator; the traces used here stay under 100. */
    public static final int MAX_LINE_BYTES = 4096;
    /** Longest accepted numeric field, in digits. */
    static final int MAX_DIGITS = 19;

    /** One parsed request; the reader refills the same instance on every call. */
    public static final class Request {
        private Op op;
        private long key;

        /** What the request does. */
        public Op op() {
            return op;
        }

        /** 64-bit hash of the request's key. */
        public long key() {
            return key;
        }
    }

    private final InputStream in;
    private final byte[] chunk = new byte[1 << 16];
    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final int[] commas = new int[6];
    private int pos;
    private int end;
    private long lines;
    private long malformed;
    private long oversized;

    /** Wraps a stream; the reader closes it. */
    public TwitterTraceReader(InputStream in) {
        this.in = in;
    }

    /** Reads the next valid request into {@code out}; false at the end of the trace. */
    public boolean next(Request out) throws IOException {
        while (true) {
            int length = readLine();
            if (length == END) {
                return false;
            }
            lines++;
            if (length == TOO_LONG) {
                oversized++;
            } else if (parse(length, out)) {
                return true;
            } else {
                malformed++;
            }
        }
    }

    /** Lines read so far, valid or not. */
    public long lines() {
        return lines;
    }

    /** Lines skipped because a field was missing or invalid. */
    public long malformed() {
        return malformed;
    }

    /** Lines skipped because they were longer than {@link #MAX_LINE_BYTES}. */
    public long oversized() {
        return oversized;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private static final int END = -1;
    private static final int TOO_LONG = -2;

    /** Copies the next line into {@link #line}; returns its length, {@link #END} or {@link #TOO_LONG}. */
    private int readLine() throws IOException {
        int length = 0;
        boolean any = false;
        boolean tooLong = false;
        while (true) {
            if (pos == end) {
                end = in.read(chunk, 0, chunk.length);
                pos = 0;
                if (end <= 0) {
                    end = 0;
                    if (!any) {
                        return END;
                    }
                    break;
                }
            }
            any = true;
            byte b = chunk[pos++];
            if (b == '\n') {
                break;
            }
            if (length < MAX_LINE_BYTES) {
                line[length++] = b;
            } else {
                tooLong = true;
            }
        }
        if (tooLong) {
            return TOO_LONG;
        }
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }
        return length;
    }

    /** Validates one line and fills the request; false if the line is malformed. */
    private boolean parse(int length, Request out) {
        int first = -1;
        for (int i = 0; i < length; i++) {
            if (line[i] == ',') {
                first = i;
                break;
            }
        }
        int found = 0;
        for (int i = length - 1; i > first && found < commas.length - 1; i--) {
            if (line[i] == ',') {
                commas[commas.length - 1 - found] = i;
                found++;
            }
        }
        if (first < 0 || found < commas.length - 1) {
            return false;
        }
        commas[0] = first;
        int keyStart = first + 1;
        int keyEnd = commas[1];
        if (keyEnd <= keyStart
                || !digits(0, first)
                || !digits(commas[1] + 1, commas[2])
                || !digits(commas[2] + 1, commas[3])
                || !digits(commas[3] + 1, commas[4])
                || !digits(commas[5] + 1, length)) {
            return false;
        }
        Op op = Op.fromCommand(line, commas[4] + 1, commas[5] - commas[4] - 1);
        if (op == null) {
            return false;
        }
        out.op = op;
        out.key = Murmur3.hash128(line, keyStart, keyEnd - keyStart, Murmur3.DEFAULT_SEED)
                .h1();
        return true;
    }

    /** True when {@code line[from, to)} is 1 to {@link #MAX_DIGITS} ASCII digits. */
    private boolean digits(int from, int to) {
        if (to <= from || to - from > MAX_DIGITS) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (line[i] < '0' || line[i] > '9') {
                return false;
            }
        }
        return true;
    }
}
