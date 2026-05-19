# starrocks-zetasketch-udf

BigQuery-compatible HLL++ functions for **StarRocks**, implemented as Java
UDFs that wrap [google/zetasketch](https://github.com/google/zetasketch). Lets
StarRocks read and re-aggregate the same `HLL_COUNT.*` sketches that the
BigQuery `spacetime` dbt pipeline already produces — no recomputing distinct
counts from raw Snowplow events.

## Stack

- **Language**: Java 17 (StarRocks JDK requirement)
- **Build**: Maven 3.9.9 via Maven Wrapper (`./mvnw`)
- **Library**: `com.google.zetasketch:zetasketch:0.1.0` (only version published
  to Maven Central; 0.2.0 from GitHub is Bazel-only and not on Central)
- **Test**: JUnit 5
- **Target**: StarRocks v3.x Java UDF runtime

## Common commands

From repo root:

```bash
./mvnw test                    # Run unit tests
./mvnw package                 # Build shaded jar (also runs tests)
./mvnw package -DskipTests     # Build shaded jar without tests
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false  # Bump pom
```

### Windows + corporate TLS

If `./mvnw` fails handshake (`PKIX path building failed`) on a dev box behind
an MITM proxy whose CA is not in JDK cacerts:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
```

Or create local `.mvn/jvm.config` with that flag — file is `.gitignored` so
it won't leak onto Linux CI runners (where `WINDOWS-ROOT` is not a valid
truststore type and breaks the JVM).

## Architecture

```
src/main/java/to/flip/udf/
├── SketchCodec.java          # base64 ↔ byte[] (wire format on SQL boundary)
├── HllppInitString.java      # UDAF: STRING → STRING (base64 sketch)
├── HllppInitLong.java        # UDAF: BIGINT → STRING
├── HllppInitBytes.java       # UDAF: base64 STRING → STRING
├── HllppMerge.java           # UDAF: STRING → BIGINT (cardinality)
├── HllppMergePartial.java    # UDAF: STRING → STRING (combined sketch)
└── HllppExtract.java         # scalar: STRING → BIGINT
```

| UDF | BigQuery equivalent | Notes |
|---|---|---|
| `hllpp_init_string` | `HLL_COUNT.INIT(STRING)` | `buildForStrings()`, precision 15 |
| `hllpp_init_long` | `HLL_COUNT.INIT(INT64)` | `buildForLongs()`, precision 15 |
| `hllpp_init_bytes` | `HLL_COUNT.INIT(BYTES)` | `buildForBytes()`, precision 15 |
| `hllpp_merge` | `HLL_COUNT.MERGE` | Raw HyperLogLogPlusPlus, reified by `forProto(bytes)` |
| `hllpp_merge_partial` | `HLL_COUNT.MERGE_PARTIAL` | Same engine as `hllpp_merge`, different finalize |
| `hllpp_extract` | `HLL_COUNT.EXTRACT` | Scalar — single sketch in, count out |

Sketches built by `init_string` / `init_long` / `init_bytes` are NOT
inter-mergeable — same constraint as BigQuery; merging across underlying
types throws inside ZetaSketch.

## Wire format

- **SQL boundary**: `STRING` (base64-encoded sketch bytes). StarRocks Java UDF
  type table (v3.x docs) does not list `VARBINARY → byte[]`, so base64 STRING
  is the portable choice. Storage can still be `VARBINARY` in the table; cast
  with `TO_BASE64()` at query time.
- **State serialization** (UDAF intermediate buffer): `[int32 length][bytes
  proto sketch]`. The length prefix is required because StarRocks bans
  `ByteBuffer.remaining()` and `ByteBuffer.clear()` inside `merge()`.

## Critical gotchas

### StarRocks UDAF contract

- `serializeLength()` **must** equal the exact byte count `serialize()` writes
  — checked at runtime. Sketch size varies, so `State` caches the serialized
  bytes between the two calls.
- `merge(State, ByteBuffer)` must not call `remaining()` or `clear()` on the
  buffer. Length-prefix every variable-size payload.
- Empty state encodes as `[int32 0]`. Merge code must early-return on `len ==
  0` before allocating the empty byte array.

### ZetaSketch 0.1.0 quirks

- Protobuf is shaded to `com.google.zetasketch.shaded.com.google.protobuf`.
  Importing vanilla `com.google.protobuf.ByteString` fails to compile.
- `HyperLogLogPlusPlus.merge(HyperLogLogPlusPlus<T>)` requires matching type
  parameter — wildcards don't compile. Use `merge(byte[])` instead; it
  round-trips through the proto wire format and works with raw types.
- `HllppMerge` / `HllppMergePartial` hold a **raw** `HyperLogLogPlusPlus`
  because the underlying type isn't known until the first sketch arrives.
  First update calls `forProto(bytes)` to reify it; subsequent updates use
  `merge(byte[])`.

### Cross-type sketches throw

- Mixing types in `update()` (e.g. feeding a string-typed sketch into a
  long-typed `init_*` state) throws at runtime. The `crossTypeMerge_throws`
  test asserts this behavior. Mirrors BigQuery.

### Performance

- StarRocks Java UDFs run in the BE JVM and are slower than the native HLL
  type. Use these for cross-engine sketch reuse — *not* as a hot per-row
  scalar path.

## Release pipeline

1. Push commits to `main` with **Conventional Commits** (`feat:`, `fix:`,
   `feat!:`).
2. `release-please.yml` opens a release PR that bumps `version.txt`,
   `CHANGELOG.md`, and `.release-please-manifest.json`.
3. Merging the release PR creates a tag `vX.Y.Z` and a GitHub Release.
4. Inside the **same workflow run** (gated on `release_created=true`), the
   `publish-jar` job:
   - checks out the tag
   - runs `versions:set` to write the version into `pom.xml`
   - tests + builds the shaded jar
   - attaches the shaded jar, plain jar, and `.sha256` sums to the release.

Bump rules (pre-1.0, see `release-please-config.json`):
- `feat:` → minor bump
- `fix:` → no bump (kept quiet while API stabilizes)
- `feat!:` / `BREAKING CHANGE:` → major bump
- everything else → no bump

### Known limitation

PRs and tags opened by `GITHUB_TOKEN` (release-please-action's default) do
**not** trigger downstream workflows. CI on release-please PRs has to be
dispatched manually (`gh workflow run ci.yml --ref <branch>`). The permanent
fix is a GitHub App or PAT stored as `RELEASE_PLEASE_TOKEN`; see README.

## Deploy to StarRocks

After a release, the shaded jar URL is:

```
https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/vX.Y.Z/starrocks-zetasketch-udf-X.Y.Z-jar-with-dependencies.jar
```

That URL must be reachable from every FE and BE in the StarRocks cluster (FE
caches the jar checksum, BEs download and execute it). Then run the
`CREATE [GLOBAL] [AGGREGATE] FUNCTION` statements in `README.md`.

## When you edit this repo

- Add new UDF → drop a class in `src/main/java/to/flip/udf/`, add an entry to
  the table above + the README, write a JUnit test in
  `HllppRoundTripTest.java` (model on existing tests with the `±2% relative
  error` assertion helper).
- Add new BQ HLL semantics → mirror BigQuery's `HLL_COUNT.*` docs exactly.
  Wrong semantics will silently produce different cardinalities than BQ.
- Touch the UDAF state contract → re-read the "Critical gotchas" section.
  Almost every bug here comes from breaking `serializeLength()`, calling
  `ByteBuffer.remaining()`, or generic-type mismatches with ZetaSketch.
- Touch CI/release → remember the GITHUB_TOKEN-doesn't-trigger trap. If
  release PRs stop getting CI runs or releases stop getting jar assets,
  that's the root cause.
