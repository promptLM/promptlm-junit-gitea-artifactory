# Changelog

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
