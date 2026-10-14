package portfolio.cacheevictionlab.sim;

/**
 * One measured cell: a policy at one cache size on one trace.
 *
 * @param trace trace name
 * @param mode replay mode label
 * @param policy policy label, or {@code infinite} for the never-evicting upper bound
 * @param sizePercent cache size as a percentage of the trace's distinct keys, or {@code -} for infinite
 * @param capacity cache size in entries, or {@code -} for infinite
 * @param reads requests counted towards the hit rate
 * @param hits reads that hit
 */
public record HitRateRow(
        String trace, String mode, String policy, String sizePercent, String capacity, long reads, long hits) {
    /** Validates the counts. */
    public HitRateRow {
        if (reads < 0 || hits < 0 || hits > reads) {
            throw new IllegalArgumentException("need 0 <= hits <= reads, got " + hits + " of " + reads);
        }
    }

    /** Hits divided by reads, or 0 when there were no reads. */
    public double hitRate() {
        return reads == 0 ? 0.0 : (double) hits / reads;
    }

    /** Identity of the cell, without the measured values. */
    public String cell() {
        return trace + "/" + mode + "/" + policy + "/" + sizePercent;
    }
}
