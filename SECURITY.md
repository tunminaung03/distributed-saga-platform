# Security Policy

## Overview

Distributed Saga Platform is an educational and reference implementation of a cloud-native, event-driven microservices architecture.

Security reports are welcome. Responsible disclosure helps identify weaknesses and improve the project's security practices.

## Supported Versions

Security fixes and vulnerability assessments are considered for the latest maintained version on the default branch.

Older commits, experimental configurations, and previously published examples may not receive security updates.

| Version | Security Support |
|---|---|
| Latest default branch | Best-effort review |
| Older revisions | Not guaranteed |

## Reporting a Vulnerability

**Please do not disclose suspected security vulnerabilities through public GitHub issues, discussions, or pull requests.**

Use one of the following private reporting channels:

1. **GitHub Private Vulnerability Reporting:** If enabled, use the repository's Security tab to submit a private vulnerability report.
2. **Alternative contact:** [Insert a dedicated security reporting email or other private reporting channel].

Please include as much of the following information as you can safely provide:

- A description of the suspected vulnerability.
- The affected component, module, configuration, or version.
- The potential security impact.
- Reproduction steps or a minimal proof of concept, where appropriate.
- Any suggested mitigation or remediation.

Please avoid including real credentials, production secrets, personal information, or sensitive customer data in your report.

## Review and Disclosure

Reports will be reviewed on a best-effort basis.

The maintainer may request additional technical details to assess the issue, determine its impact, and identify an appropriate remediation.

Please allow reasonable time for investigation and remediation before publicly disclosing a suspected vulnerability.

This project does not guarantee a particular response or remediation timeframe.

## Security Areas of Interest

Examples of security issues relevant to this project include:

- Authentication, authorization, and token-handling weaknesses.
- Exposure of secrets, credentials, or sensitive application data.
- Insecure inter-service communication or trust boundaries.
- Improper validation of event payloads or external inputs.
- Transactional outbox or saga-processing flaws with security implications.
- Unsafe retry, duplicate-message, or compensation behavior that enables abuse.
- Kubernetes configuration errors or overly permissive Cilium network policies.
- Vulnerabilities in dependencies or container configurations that affect this project's security.

## Scope and Limitations

This project is a demonstration and reference implementation. Its configuration and deployment examples should not be assumed to be production-ready without an independent security review and environment-specific hardening.

The presence of Cilium network policies, observability tooling, or application-level security controls does not by itself guarantee that a deployment is secure.

## Acknowledgment

Thank you for reporting security issues responsibly and helping improve Distributed Saga Platform.
