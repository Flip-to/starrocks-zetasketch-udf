# Security Policy

## Reporting a vulnerability

If you discover a security issue in this repository, please **do not** open a
public GitHub issue.

Submit the report through the contact form at <https://www.example.com/about/>.
Use the subject line `[SECURITY] starrocks-zetasketch-udf: <short summary>`
and include reproduction steps, affected version, and impact.

You will receive an acknowledgement within 3 business days. A fix or
mitigation plan is targeted within 14 days for high-severity issues.

## Supported versions

This library is pre-1.0 and only the latest published release on GitHub
Releases is supported with security fixes.

| Version | Supported |
| ------- | --------- |
| Latest tagged release | yes |
| Older tagged releases | no   |

## Scope

In-scope vulnerabilities:

- Code execution paths triggered by malformed sketches or untrusted input
  reaching the UDF JNI boundary.
- Build-pipeline issues that could allow tampering with published jars
  (e.g. workflow injection, dependency confusion).
- Secret leakage through CI logs, release notes, or fixture files.

Out of scope:

- Bugs in `google/zetasketch` itself (report upstream).
- Bugs in StarRocks' Java UDF runtime (report to StarRocks).
- Cardinality estimation error within the expected HLL++ tolerance.
- Issues in user-deployed clusters that depend on operator configuration
  (network exposure, IAM, etc).

## What we do

- All releases are built from a tagged commit on `main` via GitHub Actions.
  The shaded jar is attached to each release with a `.sha256` checksum.
- Dependabot watches for vulnerable dependencies weekly and opens grouped
  upgrade PRs.
- Test fixtures are generated from **synthetic data** inside BigQuery
  itself (`GENERATE_ARRAY`). No real table is queried; no real users are
  referenced.
- ZetaSketch hashes inputs before storing, so even leaked sketches would
  reveal only cardinality estimates — not identity.
