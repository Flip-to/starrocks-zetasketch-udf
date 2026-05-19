"""Dump BigQuery HLL++ sketches as test fixtures for the Java UDFs.

Pulls a small sample of `metrics.total_users_hll` rows from
`REDACTED_PROJECT.REDACTED_DATASET.REDACTED_TABLE`, plus BQ's ground-truth
cardinalities, into two CSV files that the JUnit test
`BqCompatibilityTest` reads.

Why system Python + truststore: local bq/gcloud SSL is broken on this
Windows box because the corp CA is in the Windows cert store, not the
bundled certifi bundle. truststore.inject_into_ssl() routes everything
through the Windows store.

Usage:
    pip install google-cloud-bigquery truststore  # in system python
    python scripts/dump_bq_fixtures.py
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

PROJECT = "REDACTED_PROJECT"
DATASET = "spacetime"
TABLE = "REDACTED_TABLE"
DAYS_BACK = 30
EXTRACT_SAMPLE = 50
MERGE_SAMPLE = 25

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES_DIR = REPO_ROOT / "test-fixtures" / "bq_sketches"
EXTRACT_CSV = FIXTURES_DIR / "search_extract_fixtures.csv"
MERGE_CSV = FIXTURES_DIR / "search_merge_fixtures.csv"


def _b64(blob: bytes | None) -> str | None:
    if blob is None:
        return None
    return base64.b64encode(blob).decode("ascii")


def dump_extract_fixtures(client: bigquery.Client) -> int:
    """Per-row HLL_COUNT.EXTRACT — exact deterministic match expected."""
    sql = f"""
    SELECT
      HLL_COUNT.EXTRACT(metrics.total_users_hll) AS expected_count,
      metrics.total_users_hll                     AS sketch_bytes
    FROM `{PROJECT}.{DATASET}.{TABLE}`
    WHERE search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL {DAYS_BACK} DAY)
      AND metrics.total_users_hll IS NOT NULL
    LIMIT {EXTRACT_SAMPLE}
    """
    print(f"[extract] querying {EXTRACT_SAMPLE} rows from {DATASET}.{TABLE}")
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
    """N sketches + the BQ HLL_COUNT.MERGE total. Java HllppMerge must match exactly."""
    sql = f"""
    WITH sample AS (
      SELECT metrics.total_users_hll AS sketch
      FROM `{PROJECT}.{DATASET}.{TABLE}`
      WHERE search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL {DAYS_BACK} DAY)
        AND metrics.total_users_hll IS NOT NULL
      LIMIT {MERGE_SAMPLE}
    )
    SELECT
      ARRAY_AGG(sketch)        AS sketches,
      HLL_COUNT.MERGE(sketch)  AS expected_merged_count
    FROM sample
    """
    print(f"[merge] querying {MERGE_SAMPLE} sketches from {DATASET}.{TABLE}")
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
    client = bigquery.Client(project=os.environ.get("GCP_PROJECT", PROJECT))
    dump_extract_fixtures(client)
    dump_merge_fixtures(client)
    print("Done. Run `./mvnw test` to exercise BqCompatibilityTest.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
