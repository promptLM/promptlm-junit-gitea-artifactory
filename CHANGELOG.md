# Changelog

## [1.3.0](https://github.com/promptLM/promptlm-junit-gitea-artifactory/compare/v1.2.0...v1.3.0) (2026-06-02)


### Added

* **docker:** add DockerAvailableExtension auto-applied via @WithGitea/@WithArtifactory ([#59](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/59)) ([c8b0a70](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/c8b0a7090b0694e4b24568f0d23558a912136425))
* **polling:** add generic PollingHelper for out-of-process waits ([#60](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/60)) ([4c07a70](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/4c07a701386616e8c5abedec35d8d4a19bf64035))


### Fixed

* align published Jackson dependencies ([#41](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/41)) ([bce59ce](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/bce59ce6b0f15fdeec7a3740e2b5196b0dad4389))
* **ci:** correct release-please-action SHA pin (v4.1.3 -&gt; v4.1.5) ([#78](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/78)) ([140b1ce](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/140b1ce8a91dcae848a9bf3faa0c0fbf972d07ce))
* **ci:** set skip-snapshot so release-please opens a release PR not a snapshot PR ([#81](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/81)) ([c6c796e](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/c6c796e2f1338683da38ebf0ee1e50a0b5bd46b8))
* make release reruns recover after Central publish ([#38](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/38)) ([66af0f6](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/66af0f609944af6477ced9a3a9256d62d98551c4))


### Dependencies

* **deps-dev:** bump bytebuddy.version from 1.18.7 to 1.18.8 ([#48](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/48)) ([394ac9d](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/394ac9dff9e7007108cbd4f3f076d0a7267704c9))
* **deps-dev:** bump bytebuddy.version from 1.18.8 to 1.18.9 ([#71](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/71)) ([233b661](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/233b6619ce34f2e0ee2ed039671db1bc3ce3ea56))
* **deps-dev:** bump org.apache.maven.plugins:maven-surefire-plugin ([#64](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/64)) ([d52441a](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/d52441aa2ff36b3a20cc7f36ccf128008e83a0b4))
* **deps:** bump actions/checkout from 4.1.7 to 6.0.3 ([#68](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/68)) ([0f3f367](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/0f3f3674751cac3d4ef53c5303cdb985b187afcf))
* **deps:** bump actions/setup-java from 4.8.0 to 5.2.0 ([#73](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/73)) ([8919780](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/8919780a0c1eb1f845a0651050d1d493bf89fe18))
* **deps:** bump com.fasterxml.jackson.core:jackson-core ([#45](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/45)) ([2b2331b](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/2b2331bf3b0798ffcfeb758253b5ae5b773f452e))
* **deps:** bump com.fasterxml.jackson.core:jackson-core ([#53](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/53)) ([df94106](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/df941061797d979a4efe5188f1feb67f518244b2))
* **deps:** bump com.fasterxml.jackson.core:jackson-core ([#65](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/65)) ([03a4a87](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/03a4a872dcf41df8c45006fa8d39a120478ff9f9))
* **deps:** bump com.fasterxml.jackson.core:jackson-databind ([#46](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/46)) ([3f6047e](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/3f6047e7a710378a4804414648767b6702528237))
* **deps:** bump com.fasterxml.jackson.core:jackson-databind ([#52](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/52)) ([1c306e1](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/1c306e17ac3f176dc71a8577bbca2b367880daaa))
* **deps:** bump com.github.luben:zstd-jni from 1.5.7-7 to 1.5.7-9 ([#57](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/57)) ([cc51e15](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/cc51e151b7f6dcccf1418c5b74b8310a3052246f))
* **deps:** bump jackson-annotations to 2.22 and jackson-databind to 2.22.0 ([#74](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/74)) ([82dc166](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/82dc166f4fbd23817cbfcf8f9e8c7586903e7b4f))
* **deps:** bump junit-jupiter.version from 6.0.3 to 6.1.0 ([#56](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/56)) ([1a7b1f4](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/1a7b1f411681eeecbc27b04c7f894b7e6e35b8c4))
* **deps:** bump org.slf4j:slf4j-api from 2.0.17 to 2.0.18 ([#55](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/55)) ([4df9c3e](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/4df9c3e60ab8acf4e2bf24d0cd66b709389e6a27))
* **deps:** bump org.testcontainers:testcontainers from 2.0.3 to 2.0.5 ([#49](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/49)) ([88b2898](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/88b289821b477cedb6295b03b2e2699da7b90e72))
* **deps:** bump peter-evans/create-pull-request from 6.1.0 to 8.1.1 ([#70](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/70)) ([7a2bfb9](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/7a2bfb9c7f6eef7ed54ddfb422bc291ccaaf74b9))
* **deps:** bump softprops/action-gh-release from 2.5.0 to 3.0.0 ([#72](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/72)) ([14bcb77](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/14bcb77bc2fcab5f7bb54d1339ba5746e4734cd7))
* **deps:** bump spring-io/github-changelog-generator ([#69](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/69)) ([a6aa636](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/a6aa636a7d6c5a36559a2fa867a4b360f69abb63))


### CI

* expand dependabot, add license-eye config + oss-checks caller ([#62](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/62)) ([590ab77](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/590ab772c63a7f90f48ad125c3bbf9d0331471dc))
* opt in to org action-pin audit + Dependabot auto-merge ([#76](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/76)) ([63e0d5f](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/63e0d5fcd53ec08af4c963991d1cccf2d9030e19))
* **release:** adopt release-please + Conventional Commit guard ([#77](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/77)) ([a9b61fb](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/a9b61fbe59ff3f0c6a60e4439441ba018773fb64))


### Tests

* **docker:** add DockerAvailableExtensionTest; document skip in READ… ([#75](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/75)) ([1331f8d](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/1331f8d2d4b002708334ba766ef336684f60a6f0))
* **docker:** add DockerAvailableExtensionTest; document skip in README + CHANGELOG ([#63](https://github.com/promptLM/promptlm-junit-gitea-artifactory/issues/63)) ([a5760f2](https://github.com/promptLM/promptlm-junit-gitea-artifactory/commit/a5760f269a38b1b16caf02cee1114d1b2c729764))

## Changelog

All notable changes to this project will be documented in this file. The format
loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added

- **Fail-fast Docker availability precondition for `@WithGitea` and
  `@WithArtifactory`** (#58, baseline shipped in #59). A new
  `DockerAvailableExtension` is auto-wired into both annotations via
  `@ExtendWith` and pings the Docker daemon with a 5-second hard cap before any
  container is started. If Docker is unreachable, the test class is skipped
  (not failed) via JUnit's `Assumptions` with an actionable remediation message
  pointing at the typical macOS broken-symlink fix
  (`sudo ln -sf "$HOME/.docker/run/docker.sock" /var/run/docker.sock`).
  Consumers of `@WithGitea` / `@WithArtifactory` inherit this behaviour
  automatically — no extra `@ExtendWith` registration required.
- `DockerAvailableExtensionTest` covering the skip-with-remediation path on a
  connection-refused ping, the timeout-cap branch, and the no-op behaviour on a
  healthy host.

### Changed

- `DockerAvailableExtension` gains a package-private constructor that accepts a
  `Runnable` ping action and a timeout (in seconds). This is a unit-test seam;
  the public no-arg constructor (the one JUnit uses via `@ExtendWith`) is
  unchanged, so downstream behaviour and wiring are preserved.

### Notes

- README "Setup" section now describes the skip behaviour and the remediation
  hint so developers running into the macOS broken-`/var/run/docker.sock`
  symlink case find the fix without reading source.
