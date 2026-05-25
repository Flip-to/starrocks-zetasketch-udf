package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * UDAF: hllpp_merge_partial_n(ARRAY<STRING>) -> ARRAY<STRING>
 *
 * Single-pass equivalent of calling hllpp_merge_partial() N times. Same
 * motivation as hllpp_merge_n: avoids N independent UDAF state machines
 * each scanning all rows separately.
 *
 * Returns one base64-encoded combined sketch per input position — suitable
 * for two-phase aggregation where a downstream hllpp_merge_n() or
 * hllpp_extract() call finalizes the cardinality.
 *
 * Wire format: identical to HllppMergeN.
 *   [int32 count]
 *   for each i in [0..count):
 *     [int32 byte_len]   -- 0 = null/empty sketch for position i
 *     [bytes if len > 0]
 */
public class HllppMergePartialN {

    @SuppressWarnings("rawtypes")
    public static class State {
        int count = 0;
        HyperLogLogPlusPlus[] sketches = null;
        byte[][] cachedSerialized = null;

        public int serializeLength() {
            int total = 4;
            if (count == 0 || sketches == null) return total;
            cachedSerialized = new byte[count][];
            for (int i = 0; i < count; i++) {
                total += 4;
                if (sketches[i] != null) {
                    cachedSerialized[i] = sketches[i].serializeToByteArray();
                    total += cachedSerialized[i].length;
                }
            }
            return total;
        }
    }

    public State create() {
        return new State();
    }

    public void destroy(State state) {
        state.sketches = null;
        state.cachedSerialized = null;
        state.count = 0;
    }

    @SuppressWarnings("unchecked")
    public void update(State state, List<String> input) {
        if (input == null || input.isEmpty()) return;

        int n = input.size();
        if (state.sketches == null) {
            state.sketches = new HyperLogLogPlusPlus[n];
            state.count = n;
        }
        int limit = Math.min(n, state.count);
        for (int i = 0; i < limit; i++) {
            byte[] bytes = SketchCodec.decode(input.get(i));
            if (bytes == null) continue;
            if (state.sketches[i] == null) {
                state.sketches[i] = HyperLogLogPlusPlus.forProto(bytes);
            } else {
                state.sketches[i].merge(bytes);
            }
        }
    }

    public void serialize(State state, ByteBuffer buff) {
        buff.putInt(state.count);
        if (state.count == 0 || state.sketches == null) return;

        byte[][] cache = state.cachedSerialized;
        for (int i = 0; i < state.count; i++) {
            byte[] bytes = (cache != null) ? cache[i] : null;
            if (bytes == null && state.sketches[i] != null) {
                bytes = state.sketches[i].serializeToByteArray();
            }
            if (bytes == null || bytes.length == 0) {
                buff.putInt(0);
            } else {
                buff.putInt(bytes.length);
                buff.put(bytes);
            }
        }
        state.cachedSerialized = null;
    }

    @SuppressWarnings("unchecked")
    public void merge(State state, ByteBuffer buffer) {
        int n = buffer.getInt();
        if (n == 0) return;

        if (state.sketches == null) {
            state.sketches = new HyperLogLogPlusPlus[n];
            state.count = n;
        }
        int limit = Math.min(n, state.count);
        for (int i = 0; i < limit; i++) {
            int len = buffer.getInt();
            if (len == 0) continue;
            byte[] bytes = new byte[len];
            buffer.get(bytes);
            if (state.sketches[i] == null) {
                state.sketches[i] = HyperLogLogPlusPlus.forProto(bytes);
            } else {
                state.sketches[i].merge(bytes);
            }
        }
        for (int i = limit; i < n; i++) {
            int len = buffer.getInt();
            if (len > 0) buffer.position(buffer.position() + len);
        }
    }

    public List<String> finalize(State state) {
        List<String> result = new ArrayList<>(state.count);
        for (int i = 0; i < state.count; i++) {
            result.add(state.sketches[i] == null
                    ? null
                    : SketchCodec.encode(state.sketches[i].serializeToByteArray()));
        }
        return result;
    }
}
