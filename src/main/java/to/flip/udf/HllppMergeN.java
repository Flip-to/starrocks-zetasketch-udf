package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * UDAF: hllpp_merge_n(ARRAY<STRING>) -> ARRAY<BIGINT>
 *
 * Single-pass equivalent of calling hllpp_merge() N times. Takes one ARRAY of
 * base64-encoded HLL++ sketches per row and returns one ARRAY of cardinality
 * estimates — one per input position.
 *
 * Why this exists: StarRocks runs one UDAF state machine per aggregate function
 * reference in the query. A query with 17 hllpp_merge() columns creates 17
 * independent state machines, each processing all rows. This UDAF processes all
 * N positions in a single pass — 17x fewer update() dispatches and better JVM
 * JIT locality on the hot ZetaSketch merge path.
 *
 * SQL usage:
 *   SELECT
 *     result[1] AS total_users,
 *     result[2] AS is_new_users,
 *     ...
 *   FROM (
 *     SELECT hllpp_merge_n(ARRAY[
 *       to_base64(metrics.total_hll),
 *       to_base64(metrics.is_new_user_hll),
 *       ...
 *     ]) AS result
 *     FROM spacetime.mv_traffic_metrics_root
 *     WHERE entity_uuid = ? AND granular_date BETWEEN ? AND ?
 *   ) t
 *
 * Array size is determined by the first non-null input row. All subsequent rows
 * must have the same array length; mismatches silently process up to min(lengths).
 *
 * Wire format for intermediate state (serialize/merge):
 *   [int32 count]
 *   for each i in [0..count):
 *     [int32 byte_len]   -- 0 means null/empty sketch for position i
 *     [bytes if len > 0]
 */
public class HllppMergeN {

    @SuppressWarnings("rawtypes")
    public static class State {
        int count = 0;
        HyperLogLogPlusPlus[] sketches = null;
        byte[][] cachedSerialized = null;

        public int serializeLength() {
            int total = 4; // count int32
            if (count == 0 || sketches == null) return total;
            cachedSerialized = new byte[count][];
            for (int i = 0; i < count; i++) {
                total += 4; // len prefix int32
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
            String b64 = input.get(i);
            byte[] bytes = SketchCodec.decode(b64);
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
        // If incoming state has more positions than our state, skip them.
        for (int i = limit; i < n; i++) {
            int len = buffer.getInt();
            if (len > 0) buffer.position(buffer.position() + len);
        }
    }

    public List<Long> finalize(State state) {
        List<Long> result = new ArrayList<>(state.count);
        for (int i = 0; i < state.count; i++) {
            result.add(state.sketches[i] == null ? 0L : state.sketches[i].result());
        }
        return result;
    }
}
