# Releasing

This document describes how to cut a release using GitHub Actions and the Spring IO changelog generator.

## Checklist

1. Create a milestone named after the target version (e.g. `1.1.7`).
2. Assign PRs/issues to the milestone and apply labels for sections.
3. Run the **Prepare Release** workflow with `release_version` (e.g. `1.1.7`).
   - This validates the version is greater than the current one,
     refuses already-tagged or already-published versions, updates `pom.xml`,
     and opens a PR labeled `release`.
   - If auto-merge is enabled, the PR merges when checks pass.
4. When the release PR merges, the **Tag Release** workflow tags `v<version>`.
5. Tag creation triggers the **Release** workflow (or you can run it manually), which will:
   - build and test
   - deploy to Maven Central (requires `-Prelease`, GPG, and Central Portal credentials)
   - open a PR that bumps `main` to the next patch `-SNAPSHOT` version and enables auto-merge
   - generate release notes from the milestone
   - create the GitHub Release
   - skip the deploy step on reruns if the version is already on Maven Central, so the workflow can recover post-release steps after a partial success

## Snapshots

Snapshots are published to GitHub Packages after each successful **CI** run on `main`
(or via manual run of the **Publish Snapshot** workflow). Snapshot deployments always
use the current `-SNAPSHOT` version, so consumers resolve the latest snapshot without
changing coordinates.
