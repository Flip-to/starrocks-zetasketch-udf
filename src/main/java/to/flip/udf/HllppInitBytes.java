package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;
import com.google.zetasketch.shaded.com.google.protobuf.ByteString;

import java.nio.ByteBuffer;

/**
 * UDAF: hllpp_init_bytes(base64_value STRING) -> STRING (base64 sketch)
 *
 * Equivalent to BigQuery HLL_COUNT.INIT(BYTES). Input bytes are passed as
 * base64 STRING because the StarRocks Java UDF type table does not list a
 * VARBINARY → byte[] mapping.
 *
 * Sketches built here are interchangeable with sketches built by BQ's BYTES
 * path, but NOT with STRING or INT64 sketches — merging across underlying
 * types throws.
 *
 * The ByteString import targets the shaded copy that zetasketch 0.1.0 ships
 * (its public surface uses the shaded type, not vanilla protobuf).
 */
public class HllppInitBytes {

    private static final int DEFAULT_NORMAL_PRECISION = 15;

    public static class State {
        HyperLogLogPlusPlus<ByteString> sketch =
                new HyperLogLogPlusPlus.Builder()
                        .normalPrecision(DEFAULT_NORMAL_PRECISION)
                        .buildForBytes();
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

    public void update(State state, String base64Value) {
        byte[] bytes = SketchCodec.decode(base64Value);
        if (bytes == null) return;
        state.sketch.add(ByteString.copyFrom(bytes));
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
