# Security Policy

## Reporting a vulnerability

Use GitHub private vulnerability reporting from the repository Security tab. Do not open a public issue for an undisclosed vulnerability.

Include the affected version, minimal reproduction steps, impact, and any safe diagnostic output. Never include real QR codes, DDC payloads, private topics, application keys, Google tokens, service-account JSON, passwords, signing keys, FCM registration tokens, or personal data.

If private vulnerability reporting is temporarily unavailable, do not disclose sensitive details publicly. Retry after the repository security feature is restored.

## Scope

Security reports may cover the Android app, CLI, fixed-source installer, Direct FCM v2 protocol, configuration permissions, credential redaction, and release artifacts.

DenDen has no maintainer-operated backend. Users own their Firebase projects. Application content is encrypted with AES-256-GCM, but Google still processes FCM routing metadata. FCM is not guaranteed to be timely or reliable, and DenDen is not suitable for medical, life-safety, emergency, or guaranteed-delivery use.

Supported security fixes are provided for the latest published release.
