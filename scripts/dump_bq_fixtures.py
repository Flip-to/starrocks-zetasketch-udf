"""Generate BigQuery HLL++ test fixtures from synthetic data.

Produces two CSV files that the JUnit test `BqCompatibilityTest` reads.
All sketches are built from synthetic UUIDs generated inside BigQuery itself
(`GENERATE_ARRAY`) — no real table is queried, no real users are referenced.

What this validates: the bytes BigQuery's HLL_COUNT.INIT emits, when handed
to the Java UDFs in this repo, produce identical cardinalities to BigQuery's
HLL_COUNT.EXTRACT / HLL_COUNT.MERGE on the same bytes. ZetaSketch is
deterministic per-precision, so the match must be exact.

You only need a BigQuery project that can run a tiny synthetic query
(a few MB scanned, well under the free tier). Set GCP_PROJECT to your
project ID, or pass it as the first CLI arg.

Why system Python + truststore: when the Windows certificate store has a
corporate root CA that the bundled certifi bundle does not,
`truststore.inject_into_ssl()` routes TLS through the system store and
unblocks google-cloud-bigquery.

Usage:
    pip install -r scripts/requirements.txt
    GCP_PROJECT=my-billing-project python scripts/dump_bq_fixtures.py
"""

from __future__ import annotations

import base64
import csv
import os
import sys
from pathlib import Path


def _inject_truststore() -> None:
    try:
        import truststore  # type: ignore
    except ImportError:
        return
    truststore.inject_into_ssl()


_inject_truststore()

from google.cloud import bigquery  # noqa: E402

EXTRACT_GROUPS = 50            # rows in the per-sketch extract test
MERGE_GROUPS = 25              # sketches the merge test will combine
USERS_PER_GROUP = 1_000        # disjoint synthetic users per group

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES_DIR = REPO_ROOT / "test-fixtures" / "bq_sketches"
EXTRACT_CSV = FIXTURES_DIR / "extract_fixtures.csv"
MERGE_CSV = FIXTURES_DIR / "merge_fixtures.csv"


def _b64(blob: bytes | None) -> str | None:
    if blob is None:
        return None
    return base64.b64encode(blob).decode("ascii")


def dump_extract_fixtures(client: bigquery.Client) -> int:
    """50 sketches, each over a disjoint synthetic user range.

    Each row: (expected cardinality from BigQuery HLL_COUNT.EXTRACT,
    base64 of the sketch bytes). The Java HllppExtract must return the
    same cardinality on the same bytes.
    """
    sql = f"""
    WITH gen AS (
      SELECT g AS group_id,
             GENERATE_ARRAY(g * {USERS_PER_GROUP},
                            g * {USERS_PER_GROUP} + {USERS_PER_GROUP} - 1) AS uids
      FROM UNNEST(GENERATE_ARRAY(1, {EXTRACT_GROUPS})) AS g
    ),
    sketches AS (
      SELECT
        group_id,
        HLL_COUNT.INIT(CAST(uid AS STRING)) AS sketch
      FROM gen, UNNEST(uids) AS uid
      GROUP BY group_id
    )
    SELECT
      HLL_COUNT.EXTRACT(sketch) AS expected_count,
      sketch                    AS sketch_bytes
    FROM sketches
    ORDER BY group_id
    """
    print(f"[extract] building {EXTRACT_GROUPS} synthetic sketches in BigQuery")
    rows = list(client.query(sql).result())
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    with EXTRACT_CSV.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["expected_count", "base64_sketch"])
        for r in rows:
            w.writerow([r["expected_count"], _b64(r["sketch_bytes"])])
    print(f"[extract] wrote {len(rows)} rows -> {EXTRACT_CSV.relative_to(REPO_ROOT)}")
    return len(rows)


def dump_merge_fixtures(client: bigquery.Client) -> int:
    """25 sketches over disjoint user ranges + BQ's HLL_COUNT.MERGE total.

    Disjoint inputs make the merged cardinality deterministic at
    `MERGE_GROUPS * USERS_PER_GROUP`, modulo HLL++ noise — which is
    identical between BigQuery and the Java UDF. Java HllppMerge must
    match BQ exactly.
    """
    sql = f"""
    WITH gen AS (
      SELECT g AS group_id,
             GENERATE_ARRAY(g * {USERS_PER_GROUP},
                            g * {USERS_PER_GROUP} + {USERS_PER_GROUP} - 1) AS uids
      FROM UNNEST(GENERATE_ARRAY(1, {MERGE_GROUPS})) AS g
    ),
    sketches AS (
      SELECT
        group_id,
        HLL_COUNT.INIT(CAST(uid AS STRING)) AS sketch
      FROM gen, UNNEST(uids) AS uid
      GROUP BY group_id
    )
    SELECT
      ARRAY_AGG(sketch)        AS sketches,
      HLL_COUNT.MERGE(sketch)  AS expected_merged_count
    FROM sketches
    """
    print(f"[merge] building {MERGE_GROUPS} synthetic sketches + BQ merge")
    row = next(iter(client.query(sql).result()))
    expected = row["expected_merged_count"]
    sketches = row["sketches"] or []
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    with MERGE_CSV.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["expected_merged_count", expected])
        for s in sketches:
            w.writerow(["sketch", _b64(s)])
    print(f"[merge] wrote {len(sketches)} sketches + expected={expected} -> {MERGE_CSV.relative_to(REPO_ROOT)}")
    return len(sketches)


def main() -> int:
    project = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GCP_PROJECT")
    if not project:
        print(
            "error: pass a BigQuery project ID either as the first CLI arg "
            "or via GCP_PROJECT.",
            file=sys.stderr,
        )
        return 2
    client = bigquery.Client(project=project)
    dump_extract_fixtures(client)
    dump_merge_fixtures(client)
    print("Done. Run `./mvnw test` to exercise BqCompatibilityTest.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
