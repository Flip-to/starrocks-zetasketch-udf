package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;

/**
 * UDAF: hllpp_merge(base64_sketch STRING) -> BIGINT
 *
 * Equivalent to BigQuery HLL_COUNT.MERGE — merges serialized HLL++ sketches
 * across rows and returns the final cardinality estimate.
 *
 * Wire format for intermediate state (serialize/merge):
 *   [int32 length][bytes serialized HLL++ proto]
 *   length == 0 means "empty state, skip".
 *
 * Notes
 *   - ByteBuffer.remaining() and clear() are forbidden by the StarRocks UDAF
 *     contract during merge(), so the length prefix is required.
 *   - serializeLength() must equal the exact number of bytes serialize() writes.
 *   - The sketch is held as a raw type because the underlying type (String /
 *     Long / ByteString) is not known until the first input sketch arrives.
 *     ZetaSketch's HyperLogLogPlusPlus.forProto(bytes) reconstructs the
 *     properly-typed sketch at runtime; subsequent merges use the byte[]
 *     overload of merge() which round-trips through the proto wire format.
 */
public class HllppMerge {

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

    public Long finalize(State state) {
        return state.sketch == null ? 0L : state.sketch.result();
    }
}
