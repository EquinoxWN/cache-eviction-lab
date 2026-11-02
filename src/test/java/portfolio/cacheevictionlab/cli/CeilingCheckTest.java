package portfolio.cacheevictionlab.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import portfolio.cacheevictionlab.sim.HitRateRow;
import portfolio.cacheevictionlab.sim.ReplayMode;
import portfolio.cacheevictionlab.sim.Replayer;
import portfolio.cacheevictionlab.trace.Op;

class CeilingCheckTest {
    /** A result with the never-evicting row first, as the replayer builds it. */
    private static Replayer.Result result(HitRateRow... cells) {
        Replayer.Survey survey = new Replayer.Survey(10, 0, 0, new long[Op.values().length], 5, 10, 6);
        List<HitRateRow> rows = new java.util.ArrayList<>();
        rows.add(new HitRateRow("t", "lookaside", "infinite", "-", "-", 10, 6));
        rows.addAll(List.of(cells));
        return new Replayer.Result("t", ReplayMode.LOOKASIDE, survey, rows);
    }

    @Test
    void cellsAtOrBelowTheCeilingWithTheSameReadsPass() {
        assertEquals(
                List.of(),
                Main.ceilingBreaks(result(
                        new HitRateRow("t", "lookaside", "lru", "1%", "1", 10, 6),
                        new HitRateRow("t", "lookaside", "arc", "1%", "1", 10, 0))));
    }

    @Test
    void aCellAboveTheCeilingOrWithDifferentReadsIsReported() {
        List<String> broken = Main.ceilingBreaks(result(
                new HitRateRow("t", "lookaside", "lru", "1%", "1", 10, 7),
                new HitRateRow("t", "lookaside", "arc", "1%", "1", 9, 5)));
        assertEquals(2, broken.size(), broken.toString());
        assertTrue(broken.get(1).contains("5/9 vs never-evicting 6/10"), broken.get(1));
    }
}
