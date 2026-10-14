package portfolio.cacheevictionlab.trace;

/** What a trace request does to the cache, grouped by how the replay treats it. */
public enum Op {
    /** {@code get}, {@code gets}: a lookup. */
    READ,
    /** {@code set}: stores the key whether or not it is cached. */
    SET,
    /** {@code add}: stores the key only if it is not cached. */
    ADD,
    /** {@code replace}, {@code cas}, {@code append}, {@code prepend}, {@code incr}, {@code decr}: change a cached key only. */
    UPDATE,
    /** {@code delete}: removes the key. */
    DELETE;

    /** Maps a Twemcache command name (ASCII bytes) to its group, or null if unknown. */
    public static Op fromCommand(byte[] buf, int offset, int length) {
        return switch (ascii(buf, offset, length)) {
            case "get", "gets" -> READ;
            case "set" -> SET;
            case "add" -> ADD;
            case "replace", "cas", "append", "prepend", "incr", "decr" -> UPDATE;
            case "delete" -> DELETE;
            default -> null;
        };
    }

    /** Decodes a short lower-case ASCII token, or "" if it cannot be a command. */
    private static String ascii(byte[] buf, int offset, int length) {
        if (length < 1 || length > 7) {
            return "";
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            byte b = buf[offset + i];
            if (b < 'a' || b > 'z') {
                return "";
            }
            chars[i] = (char) b;
        }
        return new String(chars);
    }
}
