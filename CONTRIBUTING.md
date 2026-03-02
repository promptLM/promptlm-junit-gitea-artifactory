# Contributing

Thanks for your interest in contributing. This project provides JUnit 5 test utilities for Gitea and Artifactory via Testcontainers.

## Development setup

Requirements:
- Java 17+
- Docker (required for Testcontainers)

Build:
- ./mvnw -q -DskipTests install

Run tests:
- ./mvnw test

## Pull requests

- Keep changes focused and small.
- Include tests for behavior changes where practical.
- Update docs when behavior or usage changes.
- Use clear commit messages (imperative voice).

## Code style

- Follow existing style and naming patterns.
- Prefer small, well-named helpers over large methods.

## Reporting issues

Please use GitHub Issues and include:
- What you expected
- What happened
- Steps to reproduce
- Environment details (OS, Java version, Docker version)
