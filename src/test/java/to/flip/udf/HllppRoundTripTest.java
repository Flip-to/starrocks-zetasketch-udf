package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HllppRoundTripTest {

    @Test
    void initEmits_extract_matchesCardinality() {
        HllppInitString init = new HllppInitString();
        HllppInitString.State s = init.create();
        for (int i = 0; i < 10_000; i++) {
            init.update(s, "u-" + i);
        }
        String sketch = init.finalize(s);
        assertNotNull(sketch);

        long est = new HllppExtract().evaluate(sketch);
        assertWithinRelativeError(10_000L, est, 0.02);
    }

    @Test
    void merge_acrossPartitions_matchesUnion() {
        HllppInitString init = new HllppInitString();

        HllppInitString.State a = init.create();
        for (int i = 0; i < 5_000; i++) init.update(a, "u-" + i);
        String sketchA = init.finalize(a);

        HllppInitString.State b = init.create();
        for (int i = 2_500; i < 7_500; i++) init.update(b, "u-" + i);
        String sketchB = init.finalize(b);

        HllppMerge merge = new HllppMerge();
        HllppMerge.State m = merge.create();
        merge.update(m, sketchA);
        merge.update(m, sketchB);
        long est = merge.finalize(m);

        assertWithinRelativeError(7_500L, est, 0.02);
    }

    @Test
    void serializeState_roundTrips_throughByteBuffer() {
        HllppMerge merge = new HllppMerge();
        HllppMerge.State source = merge.create();

        HyperLogLogPlusPlus<String> sketch =
                new HyperLogLogPlusPlus.Builder().normalPrecision(15).buildForStrings();
        for (int i = 0; i < 1_000; i++) sketch.add("u-" + i);
        merge.update(source, SketchCodec.encode(sketch.serializeToByteArray()));

        int len = source.serializeLength();
        ByteBuffer buf = ByteBuffer.allocate(len);
        merge.serialize(source, buf);
        buf.flip();

        HllppMerge.State target = merge.create();
        merge.merge(target, buf);

        long est = merge.finalize(target);
        assertWithinRelativeError(1_000L, est, 0.02);
    }

    @Test
    void mergePartial_outputs_extractableSketch() {
        HllppInitString init = new HllppInitString();

        HllppInitString.State a = init.create();
        for (int i = 0; i < 3_000; i++) init.update(a, UUID.randomUUID().toString());
        String sketchA = init.finalize(a);

        HllppMergePartial mp = new HllppMergePartial();
        HllppMergePartial.State m = mp.create();
        mp.update(m, sketchA);
        String combined = mp.finalize(m);
        assertNotNull(combined);

        long est = new HllppExtract().evaluate(combined);
        assertWithinRelativeError(3_000L, est, 0.02);
    }

    @Test
    void initLong_extract_matchesCardinality() {
        HllppInitLong init = new HllppInitLong();
        HllppInitLong.State s = init.create();
        for (long i = 0; i < 10_000; i++) init.update(s, i);
        long est = new HllppExtract().evaluate(init.finalize(s));
        assertWithinRelativeError(10_000L, est, 0.02);
    }

    @Test
    void initBytes_extract_matchesCardinality() {
        HllppInitBytes init = new HllppInitBytes();
        HllppInitBytes.State s = init.create();
        for (int i = 0; i < 10_000; i++) {
            byte[] b = java.nio.ByteBuffer.allocate(4).putInt(i).array();
            init.update(s, SketchCodec.encode(b));
        }
        long est = new HllppExtract().evaluate(init.finalize(s));
        assertWithinRelativeError(10_000L, est, 0.02);
    }

    @Test
    void crossTypeMerge_throws() {
        HllppInitString stringInit = new HllppInitString();
        HllppInitString.State stringState = stringInit.create();
        stringInit.update(stringState, "u-1");
        String stringSketch = stringInit.finalize(stringState);

        HllppInitLong longInit = new HllppInitLong();
        HllppInitLong.State longState = longInit.create();
        longInit.update(longState, 1L);
        String longSketch = longInit.finalize(longState);

        HllppMerge merge = new HllppMerge();
        HllppMerge.State m = merge.create();
        merge.update(m, stringSketch);
        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> merge.update(m, longSketch),
                "merging across underlying types must throw");
    }

    @Test
    void nullInputs_areNoOps() {
        HllppInitString init = new HllppInitString();
        HllppInitString.State s = init.create();
        init.update(s, null);
        long est = new HllppExtract().evaluate(init.finalize(s));
        assertEquals(0L, est);
    }

    private static void assertWithinRelativeError(long expected, long actual, double tolerance) {
        double err = Math.abs(actual - expected) / (double) expected;
        assertTrue(err <= tolerance,
                "expected ~" + expected + " ± " + (tolerance * 100) + "%, got " + actual);
    }
}
