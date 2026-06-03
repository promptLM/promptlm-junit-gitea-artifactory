# Releasing

Releases are driven by [release-please](https://github.com/googleapis/release-please)
and the org-wide reusable workflow
[`release-java-central`](https://github.com/promptLM/.github/blob/main/.github/workflows/release-java-central.yml).
The repo's own dispatcher is [`.github/workflows/release-please.yml`](.github/workflows/release-please.yml).

## Normal flow

1. **Land work on `main` using [Conventional Commits](https://www.conventionalcommits.org/).**
   - `feat:` → "Added" section, minor-version bump
   - `fix:` → "Fixed" section, patch-version bump (pre-1.0 it's a minor bump)
   - `build:` / `deps:` → "Dependencies"
   - `ci:` → "CI"
   - `docs:` → "Documentation"
   - `chore:` / `style:` → hidden from the changelog
   - Mapping lives in [`release-please-config.json`](release-please-config.json).

2. **release-please opens (or updates) a rolling PR** titled
   `chore(main): release X.Y.Z` on every push to `main`. The PR bumps
   `pom.xml` + `.release-please-manifest.json` and rewrites the top of
   `CHANGELOG.md` from the conventional commits accumulated since the
   last release. **Never hand-edit `CHANGELOG.md`** — the next release-please
   pass will overwrite it.

3. **Merging the release PR** is the release. release-please-action then:
   - tags `vX.Y.Z` at the merge commit,
   - creates the corresponding GitHub Release with the same body it wrote
     into `CHANGELOG.md`,
   - chains to the `publish` job in `release-please.yml`, which calls the
     org reusable `release-java-central.yml`.

4. **Publish chain** (org reusable):
   `validate-inputs → verify → smoke-sign → deploy → promote`.
   The deploy job signs with GPG and uploads to the Sonatype Central Portal.
   The `promote` job is gated by the `maven-central-publish` GitHub
   environment (required reviewers).

   This repo's `pom.xml` currently configures the publishing plugin with
   `<autoPublish>true</autoPublish><waitUntil>published</waitUntil>`, so
   the deploy step already releases to Maven Central before the promote
   gate. The promote job is effectively decorative for this repo — flip
   the pom to `<autoPublish>false</autoPublish><waitUntil>validated</waitUntil>`
   if you want the human gate to be real.

## Required secrets

Inherited from the `promptLM` organisation (visibility: all repos):

| Secret | Use |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal user-token name |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal user-token secret |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored private key, set via `gh secret set … < key.asc` to preserve newlines |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for the above |

The corresponding **public** key must be reachable by Central — upload it
once with `gpg --keyserver hkps://keys.openpgp.org --send-keys <FPR>`.
Central also accepts `keyserver.ubuntu.com` and `pgp.mit.edu`. Without
this, every deploy is rejected with `Invalid signature … Could not find
a public key by the key fingerprint`.

## Recovery: publish failed after the tag was created

Tag is immutable, so re-tagging is not the answer. Use the manual
republish dispatcher:

```
gh workflow run republish.yml -f version=vX.Y.Z
```

It calls the same org reusable workflow against the existing tag, so all
five jobs re-run with the same semantics as the normal flow. `dry-run=true`
builds + signs without deploying — useful for testing credentials and the
signing key.

Before re-running, drop any failed deployment for the same version from
the Central Portal UI (`Publishing Deployments → ⋮ → Drop`); otherwise
the next attempt may collide.

## Verifying a release

```
curl -sI https://repo.maven.apache.org/maven2/dev/promptlm/test/junit-gitea-artifactory/X.Y.Z/junit-gitea-artifactory-X.Y.Z.pom
# Expect: HTTP/2 200
```

The same path with `.pom.asc`, `.jar`, `.jar.asc`, `-sources.jar`,
`-javadoc.jar` should all return 200.

## What changed from the 1.2.x flow

- No more milestones, `release` label, or Prepare Release / Tag Release
  workflows — all removed in [the cleanup PR][cleanup-pr]. Release notes
  come from Conventional Commits, not from PR labels or milestone titles
  (see [ADR-0003](https://github.com/promptLM/promptlm-release/blob/main/adrs/0003-changelog-and-release-notes.md)).
- No more `*-SNAPSHOT` publication — `snapshot.yml` is gone. release-please
  keeps `pom.xml` at a real release version between cuts; consumers that
  need mid-cycle builds can resolve the latest tag from GitHub or build
  locally.

[cleanup-pr]: https://github.com/promptLM/promptlm-junit-gitea-artifactory/pulls?q=is%3Apr+post-release-please-cleanup
