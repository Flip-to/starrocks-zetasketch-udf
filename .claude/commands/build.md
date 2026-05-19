---
description: Run mvnw test + package and report results.
---

Run the full build for this repo. Steps:

1. `./mvnw -B -ntp test` — JUnit suite, must report all green.
2. `./mvnw -B -ntp package -DskipTests` — build the shaded jar (skip retest).
3. Report the final jar path under `target/` and its size.

On Windows, if you hit `PKIX path building failed`, retry with
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT ./mvnw ...`.

Do not commit anything. Just verify the build is green.
