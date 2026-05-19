package to.flip.udf;

import java.util.Base64;

/**
 * Wire format between StarRocks SQL layer and UDFs.
 *
 * StarRocks Java UDF docs (v3.x) do not list VARBINARY in the SQL→Java type
 * table, so sketches are exchanged as STRING and base64 encoded. Storage can
 * still be VARBINARY in the table — cast to STRING with TO_BASE64 at query
 * time, or read directly once VARBINARY→byte[] is confirmed on the cluster.
 */
final class SketchCodec {
    private SketchCodec() {}

    static byte[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        return Base64.getDecoder().decode(s);
    }

    static String encode(byte[] b) {
        if (b == null) return null;
        return Base64.getEncoder().encodeToString(b);
    }
}
