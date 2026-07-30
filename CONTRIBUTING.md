# Contributing to DenDen

Thanks for helping improve DenDen.

## Before you start

- Use GitHub Issues for reproducible bugs and focused feature proposals.
- Use [private vulnerability reporting](https://github.com/hakendog/DenDen/security/advisories/new) for security issues. Do not disclose secrets or an unpatched vulnerability in a public issue.
- Keep changes focused on DenDen as an Android notification system.

## Development checks

DenDen requires Java 17, Android SDK 35, Node.js 22, and npm.

```powershell
./gradlew.bat test
./gradlew.bat assembleDebug
./gradlew.bat lint
npm test
```

Run Android instrumentation tests on an emulator or device you are authorized to use:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

Do not uninstall the app or clear its data to bypass `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; stop and check the signing certificate.

## Pull requests

- Explain the user-visible problem and the smallest change that solves it.
- Add or update one runnable regression test for behavior changes.
- Update English and Traditional Chinese user documentation when behavior changes.
- Keep AI-facing source documents in English, including skills, agent installation instructions, bundled references, integration snippets, and agent metadata. Localize user-facing interaction at runtime instead of maintaining translated machine instructions.
- Keep `stop` limited to stopping a phone alarm; it must never stop the source task.
- Never infer permission to ring. Only explicit user authority may enable an alarm.
- Never commit Google credentials, ADC, private FCM topics, pairing keys, QR contents, FCM tokens, user data, or release-signing material.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
