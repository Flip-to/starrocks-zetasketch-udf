package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;

/**
 * UDAF: hllpp_init_string(value STRING) -> STRING (base64 sketch)
 *
 * Equivalent to BigQuery HLL_COUNT.INIT(STRING) — builds a new HLL++ sketch
 * over STRING inputs and emits it as a serialized base64 blob.
 *
 * Defaults to normal precision 15 (matches BigQuery default). To expose a
 * tunable precision argument, add a second overload UDF with an INT param.
 *
 * STRING-typed only for now; INT64 / BYTES variants can mirror this class
 * with a different HyperLogLogPlusPlus.Builder.buildFor{Longs,Bytes}() call.
 */
public class HllppInitString {

    private static final int DEFAULT_NORMAL_PRECISION = 15;

    public static class State {
        HyperLogLogPlusPlus<String> sketch =
                new HyperLogLogPlusPlus.Builder()
                        .normalPrecision(DEFAULT_NORMAL_PRECISION)
                        .buildForStrings();
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

    public void update(State state, String value) {
        if (value == null) return;
        state.sketch.add(value);
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
