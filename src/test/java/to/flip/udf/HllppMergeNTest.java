package to.flip.udf;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HllppMergeNTest {

    // Build a base64 sketch containing 'count' distinct string values starting at 'offset'.
    private String buildSketch(int offset, int count) {
        HllppInitString init = new HllppInitString();
        HllppInitString.State s = init.create();
        for (int i = offset; i < offset + count; i++) {
            init.update(s, "u-" + i);
        }
        return init.finalize(s);
    }

    @Test
    void singlePosition_matchesHllppMerge() {
        String sketch = buildSketch(0, 10_000);

        HllppMerge single = new HllppMerge();
        HllppMerge.State sState = single.create();
        single.update(sState, sketch);
        long expected = single.finalize(sState);

        HllppMergeN multi = new HllppMergeN();
        HllppMergeN.State mState = multi.create();
        multi.update(mState, List.of(sketch));
        List<Long> result = multi.finalize(mState);

        assertEquals(1, result.size());
        assertEquals(expected, result.get(0));
    }

    @Test
    void multiplePositions_eachMatchesIndividualMerge() {
        String sketchA = buildSketch(0, 5_000);
        String sketchB = buildSketch(2_500, 5_000);
        String sketchC = buildSketch(8_000, 3_000);

        // Expected: merge each column independently across two rows
        long[] expected = new long[3];
        String[][] rows = {{sketchA, sketchB, sketchC}, {sketchA, sketchB, sketchC}};
        for (int col = 0; col < 3; col++) {
            HllppMerge m = new HllppMerge();
            HllppMerge.State st = m.create();
            for (String[] row : rows) m.update(st, row[col]);
            expected[col] = m.finalize(st);
        }

        HllppMergeN multi = new HllppMergeN();
        HllppMergeN.State mState = multi.create();
        for (String[] row : rows) {
            multi.update(mState, List.of(row[0], row[1], row[2]));
        }
        List<Long> result = multi.finalize(mState);

        assertEquals(3, result.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(expected[i], result.get(i), "position " + i + " mismatch");
        }
    }

    @Test
    void nullInput_isNoOp() {
        HllppMergeN multi = new HllppMergeN();
        HllppMergeN.State state = multi.create();
        multi.update(state, null);
        // state.count still 0 — finalize returns empty list
        List<Long> result = multi.finalize(state);
        assertTrue(result.isEmpty());
    }

    @Test
    void nullElementsInArray_returnZeroForPosition() {
        String sketch = buildSketch(0, 1_000);

        HllppMergeN multi = new HllppMergeN();
        HllppMergeN.State state = multi.create();
        multi.update(state, List.of(sketch));

        // Pass a list with a null element — Arrays.asList(null) is ambiguous (varargs NPE).
        List<String> nullRow = new java.util.ArrayList<>();
        nullRow.add(null);
        multi.update(state, nullRow); // null sketch — should be skipped, not throw

        List<Long> result = multi.finalize(state);
        assertEquals(1, result.size());
        assertTrue(result.get(0) > 0, "expected non-zero cardinality");
    }

    @Test
    void serializeRoundTrip_preservesAllSketches() {
        String sketchA = buildSketch(0, 2_000);
        String sketchB = buildSketch(1_000, 2_000); // 2000 distinct values

        HllppMergeN multi = new HllppMergeN();
        HllppMergeN.State src = multi.create();
        multi.update(src, List.of(sketchA, sketchB)); // one row: pos0=sketchA, pos1=sketchB

        int len = src.serializeLength();
        ByteBuffer buf = ByteBuffer.allocate(len);
        multi.serialize(src, buf);
        buf.flip();

        HllppMergeN.State dst = multi.create();
        multi.merge(dst, buf);

        List<Long> result = multi.finalize(dst);
        assertEquals(2, result.size());
        assertWithinRelativeError(2_000L, result.get(0), 0.02); // pos0: sketchA = 2000 distinct
        assertWithinRelativeError(2_000L, result.get(1), 0.02); // pos1: sketchB = 2000 distinct
    }

    @Test
    void mergeAcrossWorkers_combinesCorrectly() {
        // Simulates two pipeline workers each aggregating their partition, then state merged.
        // col 0: both workers see overlapping range → union ~3000
        // col 1: workers see disjoint ranges → union ~6000
        String colA_worker1 = buildSketch(0, 3_000);       // u-0..u-2999
        String colA_worker2 = buildSketch(0, 3_000);       // same range, different rows
        String colB_worker1 = buildSketch(0, 3_000);       // u-0..u-2999
        String colB_worker2 = buildSketch(3_000, 3_000);   // u-3000..u-5999 (disjoint)

        HllppMergeN multi = new HllppMergeN();

        HllppMergeN.State worker1 = multi.create();
        multi.update(worker1, List.of(colA_worker1, colB_worker1));

        HllppMergeN.State worker2 = multi.create();
        multi.update(worker2, List.of(colA_worker2, colB_worker2));

        // Combine worker states (simulates SR pipeline merge phase)
        int len1 = worker1.serializeLength();
        ByteBuffer buf1 = ByteBuffer.allocate(len1);
        multi.serialize(worker1, buf1);
        buf1.flip();

        HllppMergeN.State combined = multi.create();
        multi.merge(combined, buf1);

        int len2 = worker2.serializeLength();
        ByteBuffer buf2 = ByteBuffer.allocate(len2);
        multi.serialize(worker2, buf2);
        buf2.flip();
        multi.merge(combined, buf2);

        List<Long> result = multi.finalize(combined);
        assertEquals(2, result.size());
        assertWithinRelativeError(3_000L, result.get(0), 0.02); // overlapping → ~3000
        assertWithinRelativeError(6_000L, result.get(1), 0.02); // disjoint → ~6000
    }

    private static void assertWithinRelativeError(long expected, long actual, double tolerance) {
        double err = Math.abs(actual - expected) / (double) expected;
        assertTrue(err <= tolerance,
                "expected ~" + expected + " ± " + (tolerance * 100) + "%, got " + actual);
    }
}
