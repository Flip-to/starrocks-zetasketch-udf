# starrocks-zetasketch-udf

BigQuery-compatible HLL++ functions for StarRocks, implemented as Java UDFs
backed by [google/zetasketch](https://github.com/google/zetasketch).

Lets you store BigQuery `HLL_COUNT.INIT` sketches in StarRocks and re-aggregate
them with the same cardinality semantics — i.e. read BigQuery sketches
directly from StarRocks without re-computing distinct counts from raw events.

## Functions

| StarRocks UDF              | BigQuery equivalent      | Signature                            |
| -------------------------- | ------------------------ | ------------------------------------ |
| `hllpp_init_string`        | `HLL_COUNT.INIT(STRING)` | `(STRING) -> STRING` (UDAF)          |
| `hllpp_init_long`          | `HLL_COUNT.INIT(INT64)`  | `(BIGINT) -> STRING` (UDAF)          |
| `hllpp_init_bytes`         | `HLL_COUNT.INIT(BYTES)`  | `(STRING base64) -> STRING` (UDAF)   |
| `hllpp_merge_partial`      | `HLL_COUNT.MERGE_PARTIAL`| `(STRING) -> STRING` (UDAF)          |
| `hllpp_merge`              | `HLL_COUNT.MERGE`        | `(STRING) -> BIGINT` (UDAF)          |
| `hllpp_extract`            | `HLL_COUNT.EXTRACT`      | `(STRING) -> BIGINT` (scalar)        |

Sketches built by `init_string` / `init_long` / `init_bytes` are NOT
inter-mergeable — same constraint as BigQuery. Merging across underlying types
throws at update time.

Sketches are exchanged as base64-encoded strings because the StarRocks Java UDF
type table (v3.x docs) does not list `VARBINARY`. Storage may still be
`VARBINARY` — wrap reads in `TO_BASE64()` until native `byte[]` support is
verified on the target cluster.

Precision defaults to **normal=15** (BigQuery default). Sparse promotion to
dense happens automatically inside ZetaSketch on merge.

## Build

Requires JDK 17. Maven is bootstrapped by the Maven Wrapper (`./mvnw`).

```bash
./mvnw package
```

### Windows + corporate TLS interception

If `./mvnw` fails at HTTPS handshake with `PKIX path building failed` (common
on dev boxes behind corporate MITM proxies whose CA is not in the JDK
cacerts), point the JVM at the Windows certificate store:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
./mvnw package
```

Or persist it locally only — create `.mvn/jvm.config` containing
`-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`. The file is gitignored so it
won't break Linux CI runners (where the flag is invalid).

Output: `target/starrocks-zetasketch-udf-1.0.0-SNAPSHOT-jar-with-dependencies.jar`.

The shaded jar relocates `com.google.protobuf` → `to.flip.shaded.protobuf` and
`com.google.common` → `to.flip.shaded.guava` so the BE JVM's own protobuf and
Guava versions are not affected.

## Test

```bash
mvn test
```

Tests cover: init → extract round-trip, cross-partition merge, ByteBuffer
serialize/merge contract, `merge_partial` output extractability, and null
input handling. Cardinality assertions use ±2% relative error against
known-size inputs.

## Release process

Releases are automated via [release-please](https://github.com/googleapis/release-please)
driven by [Conventional Commits](https://www.conventionalcommits.org).

Flow:
1. Push commits to `main` with conventional prefixes (`feat:`, `fix:`, `feat!:`, etc.)
2. `release-please.yml` opens (or updates) a release PR that bumps `pom.xml`,
   regenerates `CHANGELOG.md`, and updates `.release-please-manifest.json`
3. Merging the release PR creates a Git tag (`vX.Y.Z`) and a GitHub Release
4. The tag triggers `release.yml` which verifies the pom version, runs tests,
   builds the shaded jar, and attaches the jar + `.sha256` checksums to the
   release

Commit conventions used:
- `feat:` → minor bump (or patch while < 1.0.0, per config)
- `fix:` → patch bump
- `feat!:` / `BREAKING CHANGE:` → major bump
- `chore:`, `docs:`, `refactor:`, `test:`, `ci:` → no bump

Pre-1.0.0: `feat` bumps the minor, `fix` is skipped from version bumps to keep
the surface small while the API stabilizes (see `release-please-config.json`).

Manual release: `workflow_dispatch` the `release` workflow with a `version`
input matching an existing tag (used for re-running the build, not for
creating a new version).

CI on every push/PR runs tests and uploads the shaded jar as a 14-day build
artifact (no Release created).

## Deploy to StarRocks

### 1. Make the jar reachable from every FE and BE

Pick one:

- **Public GitHub Release** (simplest, requires internet from the cluster):
  use the URL of the shaded jar from a GitHub release, e.g.
  `https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar`
- **Self-hosted HTTP**: drop the shaded jar on any HTTP server the cluster
  can reach (`python3 -m http.server 8000` on a jump host works).

### 2. Enable Java UDFs

In `fe/conf/fe.conf` (and restart FEs):

```
enable_udf = true
```

### 3. Register the functions

Set the jar URL once and substitute it into each `CREATE FUNCTION`. The
example below uses the public v0.1.1 release; replace as needed. StarRocks
does NOT support SQL variables in `PROPERTIES`, so the URL must be inlined.

```sql
-- Scalar UDF: cardinality from a single sketch.
CREATE GLOBAL FUNCTION hllpp_extract(STRING)
RETURNS BIGINT
PROPERTIES (
    "symbol" = "to.flip.udf.HllppExtract",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

-- INIT family — pick the input type that matches your column.
CREATE GLOBAL AGGREGATE FUNCTION hllpp_init_string(STRING)
RETURNS STRING
PROPERTIES (
    "symbol" = "to.flip.udf.HllppInitString",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

CREATE GLOBAL AGGREGATE FUNCTION hllpp_init_long(BIGINT)
RETURNS STRING
PROPERTIES (
    "symbol" = "to.flip.udf.HllppInitLong",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

CREATE GLOBAL AGGREGATE FUNCTION hllpp_init_bytes(STRING)
RETURNS STRING
PROPERTIES (
    "symbol" = "to.flip.udf.HllppInitBytes",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

-- MERGE family — operates on pre-built sketches (your own or from BigQuery).
CREATE GLOBAL AGGREGATE FUNCTION hllpp_merge(STRING)
RETURNS BIGINT
PROPERTIES (
    "symbol" = "to.flip.udf.HllppMerge",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

CREATE GLOBAL AGGREGATE FUNCTION hllpp_merge_partial(STRING)
RETURNS STRING
PROPERTIES (
    "symbol" = "to.flip.udf.HllppMergePartial",
    "type"   = "StarrocksJar",
    "file"   = "https://github.com/Flip-to/starrocks-zetasketch-udf/releases/download/v0.1.1/starrocks-zetasketch-udf-0.1.1-jar-with-dependencies.jar"
);

SHOW GLOBAL FUNCTIONS;
```

### 4. (Optional) Pull BigQuery sketches into StarRocks for testing

Two paths to feed real BQ sketches at a local StarRocks cluster:

- **StarRocks BigQuery External Catalog** (StarRocks v3.2+) — query BQ
  tables in place. Wrap your HLL `BYTES` column with `TO_BASE64()` so it
  lands in StarRocks as a `STRING` for the UDFs to consume.
- **Pull + Stream Load** — run a small Python script on the host that
  reads sketches from BQ (as `BYTES`), base64-encodes them, and POSTs to
  `http://<be-host>:8040/api/<db>/<table>/_stream_load`. Then query in
  StarRocks like any other table.

The repo's `scripts/dump_bq_fixtures.py` is the pattern to copy — it
already builds the SQL that gets `HLL_COUNT.EXTRACT` ground-truth and the
base64 sketch in the same row, which makes a `diff` column trivial:

```sql
SELECT
    expected_count                 AS bq_count,
    hllpp_extract(base64_sketch)   AS sr_count,
    expected_count
      - hllpp_extract(base64_sketch) AS diff   -- must be 0
FROM <your_sketch_table>;
```

## Cross-engine validation

Recommended one-time check before trusting these UDFs in production:

```sql
-- BigQuery
SELECT TO_BASE64(HLL_COUNT.INIT(user_id)) AS sketch
FROM `your-project.your_dataset.your_events_table`
WHERE event_date >= '2026-05-01';

-- Save the sketch string, load into StarRocks as VARCHAR
SELECT hllpp_extract('<base64 from above>');  -- should match HLL_COUNT.EXTRACT in BQ
```

Place sketches in `test-fixtures/bq_sketches/` to add deterministic regression
tests.

## End-to-end validation against BigQuery via GCS Parquet

Validated path (StarRocks 4.1, May 2026): export BQ sketches to GCS as Parquet,
read with `FILES()`, run `hllpp_extract` against the BQ ground-truth column in
the same row. 100/100 rows on a real sketch table returned `diff = 0`.

### 1. Export BQ sketches + ground truth to GCS

Run from BigQuery. `TO_BASE64` is required — Parquet `BYTES` round-trip into
StarRocks Java UDFs as base64 strings (see note at top of README).

```sql
EXPORT DATA OPTIONS(
  uri='gs://<your-bucket>/sr_test/sketches_*.parquet',
  format='PARQUET',
  overwrite=true
) AS
SELECT
  some_row_key,
  HLL_COUNT.EXTRACT(my_hll_bytes_col) AS bq_count,
  TO_BASE64(my_hll_bytes_col)          AS base64_sketch
FROM `<your-project>.<dataset>.<table>`
WHERE <partition filter>
LIMIT 100;
```

The service account used needs `storage.buckets.get` and
`storage.objects.{list,get,create}` on the bucket.

### 2. Configure GCS auth on StarRocks (4.1-latest workaround)

StarRocks 4.1 `FILES()` accepts inline `gcp.gcs.service_account_*` properties
per docs, but at the time of writing the inline path hits an NPE inside
`HadoopCredentialsConfiguration.getCredentialsInternal` — the auth mode
defaults to `SERVICE_ACCOUNT_JSON_KEYFILE` but the keyfile path is never set.

Workaround: configure the GCS Hadoop connector globally via `core-site.xml`
on **both FE and BE** (`fe/conf/core-site.xml`, `be/conf/core-site.xml`),
then drop the inline credentials from the `FILES()` call.

```xml
<configuration>
  <property>
    <name>fs.gs.impl</name>
    <value>com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem</value>
  </property>
  <property>
    <name>fs.AbstractFileSystem.gs.impl</name>
    <value>com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS</value>
  </property>
  <property>
    <name>fs.gs.auth.type</name>
    <value>SERVICE_ACCOUNT_JSON_KEYFILE</value>
  </property>
  <property>
    <name>fs.gs.auth.service.account.json.keyfile</name>
    <value>/path/inside/container/sa.json</value>
  </property>
</configuration>
```

Mount the service account JSON at the path referenced above and restart the
cluster. The path must be readable by both FE and BE processes.

### 3. Read + validate

```sql
SELECT
  COUNT(*)                                                                AS total,
  SUM(CASE WHEN bq_count  = hllpp_extract(base64_sketch) THEN 1 ELSE 0 END) AS matched,
  SUM(CASE WHEN bq_count != hllpp_extract(base64_sketch) THEN 1 ELSE 0 END) AS mismatched,
  MAX(ABS(bq_count - hllpp_extract(base64_sketch)))                       AS max_abs_diff
FROM FILES(
  'path'   = 'gs://<your-bucket>/sr_test/sketches_*.parquet',
  'format' = 'parquet'
);
```

`mismatched = 0` and `max_abs_diff = 0` confirms bit-exact compatibility on
the sample. Pass the base64 string directly to `hllpp_extract` — do **not**
call `from_base64()` first; the UDF decodes internally and raw bytes will
throw `IllegalArgumentException: Illegal base64 character`.

### Notes

- A JDBC catalog against BigQuery via the Simba driver does not work. The
  StarRocks FE has a hardcoded `driver_class` substring allowlist
  (`mysql|postgresql|mariadb|clickhouse|oracle|sqlserver`) and additionally
  overrides `getDriverName()` to force a bundled vendor driver matching the
  allowlist token — so even a shim class named e.g.
  `com.simba.googlebigquery.jdbc.mysqlDriver` is bypassed at load time.
  Stick to the GCS-Parquet path above (or a BigLake/Iceberg catalog).

## Gotchas

- `ByteBuffer.remaining()` and `clear()` are forbidden in `merge()` per
  StarRocks docs → length-prefix the wire state.
- `serializeLength()` must equal the exact byte count `serialize()` writes.
  Sketch size varies, so `State` caches the serialized bytes between the two
  calls.
- ZetaSketch is typed (`String` / `Long` / `ByteString`). Mixing types in one
  `HyperLogLogPlusPlus` throws — keep one UDF per input type, then merge after
  serialization where everything is `ByteString`.
- StarRocks Java UDFs run in the BE JVM and are slower than native HLL. Use
  for cross-engine sketch reuse, not as a hot scalar path.
