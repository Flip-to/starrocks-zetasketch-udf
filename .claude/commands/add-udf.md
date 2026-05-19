---
description: Recipe for adding a new HLL++ UDF that mirrors a BigQuery function.
---

Use this when the user asks to add a new HLL++ UDF (new INIT input type, new
merge variant, new extract semantic, etc).

Steps:

1. **Confirm the BigQuery semantic.** Ask which `HLL_COUNT.*` call this
   mirrors. Wrong semantics silently produce wrong cardinalities.
2. **Pick the right ZetaSketch builder/method.** `buildForStrings()`,
   `buildForLongs()`, `buildForBytes()`, or `HyperLogLogPlusPlus.forProto()`
   for type-erased merge UDAFs.
3. **Write the class** in `src/main/java/to/flip/udf/`. Model on the closest
   existing class:
   - Scalar UDF → `HllppExtract.java`
   - Init UDAF (single type) → `HllppInitString.java` / `HllppInitLong.java`
   - Type-erased UDAF → `HllppMerge.java` / `HllppMergePartial.java`
4. **Honor the UDAF contract** — see `.claude/rules/udaf-contract.md`.
   `serializeLength` must match write size; no `ByteBuffer.remaining()` in
   `merge`; null update is a no-op.
5. **Add a test** in `src/test/java/to/flip/udf/HllppRoundTripTest.java`.
   Use `assertWithinRelativeError(expected, est, 0.02)` to assert
   cardinality within ±2%.
6. **Update README** — function table, CREATE FUNCTION snippet.
7. **Update CLAUDE.md** if the new UDF changes the architecture summary.
8. **Run** `./mvnw test` to verify before committing.

Commit with a `feat:` conventional commit so release-please picks it up:
e.g. `feat: add hllpp_init_numeric for HLL_COUNT.INIT(NUMERIC)`.
