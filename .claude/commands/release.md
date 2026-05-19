---
description: Walk through cutting a release for this repo.
---

Cut a release. The pipeline is automated via release-please; your job is
mainly to confirm intent and merge.

Steps:

1. **Check the open release-please PR.**

   ```bash
   gh pr list --repo flip-to/starrocks-zetasketch-udf --state open
   ```

   The PR will be titled `chore(main): release X.Y.Z`. If there is no such
   PR, there are no version-bumping commits since the last release — nothing
   to release.

2. **Dispatch CI on the release-please branch.** Release-please opens the PR
   with `GITHUB_TOKEN`, which does NOT trigger downstream workflows. Without
   a manual dispatch, the PR will sit without any CI signal:

   ```bash
   gh workflow run ci.yml --repo flip-to/starrocks-zetasketch-udf \
     --ref release-please--branches--main--components--starrocks-zetasketch-udf
   ```

   Wait for that run to be green before merging. (If `RELEASE_PLEASE_TOKEN`
   has been configured later as a PAT or GH App, this step is unnecessary.)

3. **Squash-merge the release PR.**

   ```bash
   gh pr merge <PR#> --repo flip-to/starrocks-zetasketch-udf --squash --delete-branch
   ```

4. **The same workflow run will:**
   - create the tag `vX.Y.Z`
   - create the GitHub Release with the changelog body
   - run the `publish-jar` job that builds and attaches the shaded jar +
     plain jar + sha256 sums

5. **Verify the release.**

   ```bash
   gh release view vX.Y.Z --repo flip-to/starrocks-zetasketch-udf
   ```

   The asset list must contain 4 files:
   - `starrocks-zetasketch-udf-X.Y.Z-jar-with-dependencies.jar`
   - `starrocks-zetasketch-udf-X.Y.Z-jar-with-dependencies.jar.sha256`
   - `starrocks-zetasketch-udf-X.Y.Z.jar`
   - `starrocks-zetasketch-udf-X.Y.Z.jar.sha256`

6. **If asset upload failed** (e.g. flaky network), re-run the publish job
   via the `release.yml` workflow_dispatch:

   ```bash
   gh workflow run release.yml --repo flip-to/starrocks-zetasketch-udf \
     -f version=X.Y.Z
   ```
