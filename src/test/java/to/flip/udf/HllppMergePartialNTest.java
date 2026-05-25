package to.flip.udf;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HllppMergePartialNTest {

    private String buildSketch(int offset, int count) {
        HllppInitString init = new HllppInitString();
        HllppInitString.State s = init.create();
        for (int i = offset; i < offset + count; i++) init.update(s, "u-" + i);
        return init.finalize(s);
    }

    @Test
    void outputIsExtractable_matchesHllppMergePartial() {
        String sketch = buildSketch(0, 5_000);

        // Single-column reference result
        HllppMergePartial single = new HllppMergePartial();
        HllppMergePartial.State sState = single.create();
        single.update(sState, sketch);
        String expectedPartial = single.finalize(sState);
        long expectedCount = new HllppExtract().evaluate(expectedPartial);

        // Array version
        HllppMergePartialN multi = new HllppMergePartialN();
        HllppMergePartialN.State mState = multi.create();
        multi.update(mState, List.of(sketch));
        List<String> partials = multi.finalize(mState);

        assertEquals(1, partials.size());
        assertNotNull(partials.get(0));
        long actualCount = new HllppExtract().evaluate(partials.get(0));
        assertEquals(expectedCount, actualCount);
    }

    @Test
    void multiplePositions_eachExtractMatchesIndividualMergePartial() {
        String sketchA = buildSketch(0, 4_000);
        String sketchB = buildSketch(2_000, 4_000); // overlap: union = 6000

        // Feed both sketches as separate rows into position 0 and position 1
        HllppMergePartialN multi = new HllppMergePartialN();
        HllppMergePartialN.State state = multi.create();
        multi.update(state, List.of(sketchA, sketchB));
        multi.update(state, List.of(sketchB, sketchA));

        List<String> partials = multi.finalize(state);
        assertEquals(2, partials.size());

        // Each position merged {sketchA, sketchB} → same union cardinality
        long card0 = new HllppExtract().evaluate(partials.get(0));
        long card1 = new HllppExtract().evaluate(partials.get(1));
        assertWithinRelativeError(6_000L, card0, 0.02);
        assertWithinRelativeError(6_000L, card1, 0.02);
    }

    @Test
    void partialOutputFeedableIntoMergeN() {
        // Two-phase aggregation: merge_partial_n on partitions, merge_n on combined partials.
        String s1 = buildSketch(0, 3_000);
        String s2 = buildSketch(3_000, 3_000); // disjoint → union 6000

        HllppMergePartialN partialUdaf = new HllppMergePartialN();

        HllppMergePartialN.State p1 = partialUdaf.create();
        partialUdaf.update(p1, List.of(s1));
        List<String> partial1 = partialUdaf.finalize(p1);

        HllppMergePartialN.State p2 = partialUdaf.create();
        partialUdaf.update(p2, List.of(s2));
        List<String> partial2 = partialUdaf.finalize(p2);

        // Final merge of the two partial sketches
        HllppMergeN finalUdaf = new HllppMergeN();
        HllppMergeN.State finalState = finalUdaf.create();
        finalUdaf.update(finalState, partial1);
        finalUdaf.update(finalState, partial2);
        List<Long> result = finalUdaf.finalize(finalState);

        assertEquals(1, result.size());
        assertWithinRelativeError(6_000L, result.get(0), 0.02);
    }

    @Test
    void nullElement_returnedAsNullInOutput() {
        String sketch = buildSketch(0, 1_000);

        HllppMergePartialN multi = new HllppMergePartialN();
        HllppMergePartialN.State state = multi.create();
        multi.update(state, List.of(sketch));

        List<String> nullRow = new ArrayList<>();
        nullRow.add(null);
        multi.update(state, nullRow); // null sketch — skipped

        List<String> partials = multi.finalize(state);
        assertEquals(1, partials.size());
        assertNotNull(partials.get(0)); // still has data from first update
        assertWithinRelativeError(1_000L, new HllppExtract().evaluate(partials.get(0)), 0.02);
    }

    @Test
    void serializeRoundTrip_preservesSketches() {
        String sketchA = buildSketch(0, 2_000);
        String sketchB = buildSketch(0, 2_000);

        HllppMergePartialN multi = new HllppMergePartialN();
        HllppMergePartialN.State src = multi.create();
        multi.update(src, List.of(sketchA, sketchB));

        int len = src.serializeLength();
        ByteBuffer buf = ByteBuffer.allocate(len);
        multi.serialize(src, buf);
        buf.flip();

        HllppMergePartialN.State dst = multi.create();
        multi.merge(dst, buf);

        List<String> partials = multi.finalize(dst);
        assertEquals(2, partials.size());
        assertWithinRelativeError(2_000L, new HllppExtract().evaluate(partials.get(0)), 0.02);
        assertWithinRelativeError(2_000L, new HllppExtract().evaluate(partials.get(1)), 0.02);
    }

    private static void assertWithinRelativeError(long expected, long actual, double tolerance) {
        double err = Math.abs(actual - expected) / (double) expected;
        assertTrue(err <= tolerance,
                "expected ~" + expected + " ± " + (tolerance * 100) + "%, got " + actual);
    }
}
