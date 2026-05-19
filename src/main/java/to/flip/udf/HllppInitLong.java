package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;

/**
 * UDAF: hllpp_init_long(value BIGINT) -> STRING (base64 sketch)
 *
 * Equivalent to BigQuery HLL_COUNT.INIT(INT64). Sketches built by this UDAF
 * are interchangeable with sketches built by BQ's INT64 path, but NOT with
 * STRING or BYTES sketches — merging across underlying types throws.
 *
 * Defaults to normal precision 15 to match BigQuery.
 */
public class HllppInitLong {

    private static final int DEFAULT_NORMAL_PRECISION = 15;

    public static class State {
        HyperLogLogPlusPlus<Long> sketch =
                new HyperLogLogPlusPlus.Builder()
                        .normalPrecision(DEFAULT_NORMAL_PRECISION)
                        .buildForLongs();
        byte[] cachedSerialized;

        public int serializeLength() {
            cachedSerialized = sketch.serializeToByteArray();
            return 4 + cachedSerialized.length;
        }
    }

    public State create() {
        return new State();
    }

    public void destroy(State state) {
        state.sketch = null;
        state.cachedSerialized = null;
    }

    public void update(State state, Long value) {
        if (value == null) return;
        state.sketch.add(value.longValue());
    }

    public void serialize(State state, ByteBuffer buff) {
        byte[] bytes = state.cachedSerialized;
        if (bytes == null) bytes = state.sketch.serializeToByteArray();
        buff.putInt(bytes.length);
        buff.put(bytes);
        state.cachedSerialized = null;
    }

    public void merge(State state, ByteBuffer buffer) {
        int len = buffer.getInt();
        if (len == 0) return;
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        state.sketch.merge(bytes);
    }

    public String finalize(State state) {
        return SketchCodec.encode(state.sketch.serializeToByteArray());
    }
}
