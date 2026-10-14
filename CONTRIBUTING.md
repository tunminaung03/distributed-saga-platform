# Contributing

Thank you for your interest in contributing to Distributed Saga Platform!

Distributed Saga Platform is an educational and practical reference project demonstrating distributed transactions using the Saga pattern, transactional outbox, event-driven microservices, Kubernetes networking, and observability.

Contributions that improve correctness, reliability, security, maintainability, documentation, and architectural clarity are welcome.

## Before Contributing

Please read the following:

- [README.md](README.md) for the project overview and architecture.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.
- [LICENSE](LICENSE) for licensing terms.

For substantial changes, open an issue first to discuss the proposed design and avoid unnecessary rework.

## What You Can Contribute

Examples include:

- Bug fixes and reliability improvements.
- Saga transaction and compensation handling.
- Transactional outbox and event-processing improvements.
- Idempotency and duplicate-message handling.
- Kafka configuration and error-handling improvements.
- Spring Boot and Java modernization.
- Kubernetes deployment and Cilium network-policy improvements.
- Observability improvements involving OpenTelemetry, Prometheus, Tempo, Loki, or Grafana.
- Automated tests, architecture diagrams, and documentation.

## Development Guidelines

- Follow the existing project structure and coding conventions.
- Keep changes focused and avoid unrelated refactoring in the same pull request.
- Prefer clear, maintainable solutions over unnecessary abstractions.
- Preserve existing architectural boundaries unless a change is explicitly intended to revise them.
- Handle failures explicitly; do not silently discard errors that could cause inconsistent state.
- Consider retries, duplicate events, idempotency, and compensation when modifying saga processing.
- Avoid logging passwords, tokens, credentials, or other sensitive information.
- Update documentation and configuration examples when behavior changes.

## Development Environment

The project uses Java and Spring Boot, with Kafka for asynchronous messaging and Kubernetes/Cilium for container networking and network-policy demonstrations.

Refer to the README for the currently supported versions, prerequisites, configuration, and startup instructions.

Depending on the change, you may need:

- A compatible JDK and build tool.
- Access to the required application infrastructure, such as Kafka and the database.
- A Kubernetes environment for deployment or Cilium-specific changes.
- An observability stack for changes involving telemetry or dashboards.

Do not commit local credentials, environment-specific secrets, generated artifacts, or personal IDE configuration.

## Making Changes

1. Fork the repository or create a branch in your authorized working copy.
2. Create a focused branch for your change.
3. Implement the change using the existing project conventions.
4. Add or update tests where appropriate.
5. Run the relevant tests and build checks.
6. Update documentation or diagrams when necessary.
7. Review the final diff for accidental changes and sensitive information.
8. Submit a pull request describing the change and its impact.

Suggested branch names:

- `feature/<short-description>`
- `fix/<short-description>`
- `docs/<short-description>`
- `refactor/<short-description>`

## Testing and Validation

Before submitting a pull request:

- Run the relevant automated tests.
- Verify that affected modules compile and build successfully.
- Test changes to event handling, idempotency, and compensation where practical.
- Validate Kubernetes manifests and Cilium policies when those resources change.
- Document any tests that could not be executed because infrastructure was unavailable.

Include the commands executed and their outcomes in the pull request when useful. Do not claim a test passed if it was not run.

## Pull Request Guidelines

A pull request should include:

- A clear summary of the change.
- The motivation or problem being addressed.
- The principal implementation or design decisions.
- Tests performed and their results.
- Relevant issue references, if applicable.
- Documentation or diagram updates, where necessary.

Keep pull requests focused and reasonably sized. Be prepared to discuss trade-offs and revise the implementation in response to constructive review feedback.

## Security Issues

Please do not disclose suspected vulnerabilities in public issues or pull requests. Follow the reporting instructions in [SECURITY.md](SECURITY.md).

## Licensing

By submitting a contribution, you agree that your contribution is provided under the project's applicable license, Apache License 2.0, subject to its terms.

## Questions

For project questions or proposed improvements, open a GitHub issue when appropriate. Please provide enough technical context to make the discussion actionable.

Thank you for helping improve Distributed Saga Platform!
