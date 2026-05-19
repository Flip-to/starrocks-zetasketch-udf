# StarRocks Java UDAF contract

Applies to files in: `src/main/java/to/flip/udf/Hllpp*.java`

Every UDAF class must implement the following methods, in the exact shape
StarRocks reflects on. Breaking any rule below will compile but fail at
runtime with non-obvious errors.

## Required methods

| Method | Notes |
|---|---|
| `State create()` | New empty state per group key. |
| `void destroy(State s)` | Release references; called once per state. |
| `void update(State s, ...)` | Per-row update. May receive `null` — must no-op, not throw. |
| `void serialize(State s, ByteBuffer buf)` | Write intermediate state. **Bytes written must equal `serializeLength(s)`.** |
| `void merge(State s, ByteBuffer buf)` | Combine serialized peer into `s`. **Must NOT call `buf.remaining()` or `buf.clear()`.** |
| `TYPE finalize(State s)` | Final result. May return `null`. |

State class also needs:

| Method | Notes |
|---|---|
| `int serializeLength()` | Instance method on `State`. Returns the exact byte count `serialize()` will write. Re-called per state. Cache the serialized bytes if size is dynamic. |

## Length-prefix every variable-size payload

The ban on `ByteBuffer.remaining()` in `merge()` means you cannot know the
incoming payload's size from the buffer alone. Always write a length prefix:

```java
public void serialize(State s, ByteBuffer buf) {
    byte[] bytes = s.cachedSerialized;
    if (bytes == null) bytes = s.sketch.serializeToByteArray();
    buf.putInt(bytes.length);
    if (bytes.length > 0) buf.put(bytes);
    s.cachedSerialized = null;
}

public void merge(State s, ByteBuffer buf) {
    int len = buf.getInt();
    if (len == 0) return;                  // empty peer — early return
    byte[] bytes = new byte[len];
    buf.get(bytes);
    // ... reconstruct
}
```

`serializeLength()` returns `4 + bytes.length`.

## Null inputs must be no-ops

`update(State, ...)` is called for every row including `NULL` ones (StarRocks
does not strip nulls before the UDAF). Guard the body:

```java
public void update(State s, String value) {
    if (value == null) return;
    s.sketch.add(value);
}
```

Same for base64 STRING wrappers: decode returns `null` on `null` input;
`update` returns immediately.

## Cross-type sketches throw

UDAFs that operate on **arbitrary** sketches (`HllppMerge`,
`HllppMergePartial`) hold a raw `HyperLogLogPlusPlus` because the underlying
type isn't known until the first sketch arrives. The first `update`/`merge`
call reifies it via `HyperLogLogPlusPlus.forProto(bytes)`. Later calls go
through `merge(byte[])`, which ZetaSketch validates internally — mixing
underlying types throws there.

Do NOT try to "fix" this by changing the state to typed. The runtime can't
know the type up front.

## Wire format reminder

Public boundary (SQL ↔ Java) is base64 STRING — see `SketchCodec`.
Internal boundary (`serialize`/`merge` ByteBuffer) is `[int32 length][bytes
proto sketch]`. Do not confuse the two: `serialize` must not write base64,
and `update` must not consume raw bytes.
