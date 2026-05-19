package to.flip.udf;

import com.google.zetasketch.HyperLogLogPlusPlus;

/**
 * Scalar UDF: hllpp_extract(base64_sketch STRING) -> BIGINT
 *
 * Equivalent to BigQuery HLL_COUNT.EXTRACT — returns the cardinality estimate
 * of a single serialized HLL++ sketch.
 */
public class HllppExtract {

    public Long evaluate(String base64Sketch) {
        byte[] bytes = SketchCodec.decode(base64Sketch);
        if (bytes == null) return 0L;
        HyperLogLogPlusPlus<?> sketch = HyperLogLogPlusPlus.forProto(bytes);
        return sketch.result();
    }
}
