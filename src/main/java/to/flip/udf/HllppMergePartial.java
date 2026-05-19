package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;

/**
 * UDAF: hllpp_merge_partial(base64_sketch STRING) -> STRING (base64)
 *
 * Equivalent to BigQuery HLL_COUNT.MERGE_PARTIAL — merges serialized HLL++
 * sketches and returns a single combined sketch (still base64-encoded).
 *
 * Same wire format and raw-type handling as HllppMerge. Only finalize()
 * differs: it emits the combined sketch instead of the cardinality.
 */
public class HllppMergePartial {

    @SuppressWarnings("rawtypes")
    public static class State {
        HyperLogLogPlusPlus sketch;
        byte[] cachedSerialized;

        public int serializeLength() {
            cachedSerialized = (sketch == null) ? new byte[0] : sketch.serializeToByteArray();
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

    public void update(State state, String base64Sketch) {
        byte[] bytes = SketchCodec.decode(base64Sketch);
        if (bytes == null) return;
        if (state.sketch == null) {
            state.sketch = HyperLogLogPlusPlus.forProto(bytes);
        } else {
            state.sketch.merge(bytes);
        }
    }

    public void serialize(State state, ByteBuffer buff) {
        byte[] bytes = state.cachedSerialized;
        if (bytes == null) {
            bytes = (state.sketch == null) ? new byte[0] : state.sketch.serializeToByteArray();
        }
        buff.putInt(bytes.length);
        if (bytes.length > 0) buff.put(bytes);
        state.cachedSerialized = null;
    }

    public void merge(State state, ByteBuffer buffer) {
        int len = buffer.getInt();
        if (len == 0) return;
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        if (state.sketch == null) {
            state.sketch = HyperLogLogPlusPlus.forProto(bytes);
        } else {
            state.sketch.merge(bytes);
        }
    }

    public String finalize(State state) {
        if (state.sketch == null) return null;
        return SketchCodec.encode(state.sketch.serializeToByteArray());
    }
}
