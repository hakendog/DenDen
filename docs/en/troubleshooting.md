# Troubleshooting

If you have an installation, pairing, or notification problem, copy the entire block below and paste it into an AI assistant that can operate your computer:

```text
Please follow this DenDen installation guide to load the setup assistant, then check or repair DenDen. This is what I see: <describe the problem>

https://raw.githubusercontent.com/hakendog/DenDen/a823029c9f9ff609352696302a08c9b6d329cacb/docs/agent-install.md
```

The setup assistant reads the current state first. It will not immediately rebuild the project, clear phone data, or overwrite settings.

## Setup does not start

- Let the setup assistant check Git, Node.js, and Google Cloud tools. If something is missing, read the purpose and proposed change before deciding whether to install it.
- If Google asks you to accept Firebase terms, follow the setup assistant to the Firebase page. Accept only the required terms and do not enable Analytics or other products.
- If the selected Google project has billing enabled, has an unknown purpose, or contains unknown resources, use a new project dedicated to DenDen.
- Do not paste Google credentials, private keys, setting files, or a DenDen pairing code to get around an error.

## The DenDen pairing code does not scan

- Make sure the pairing code is still within the display period shown by the setup assistant.
- Scan the image displayed locally by the setup assistant. Do not use a screenshot compressed by a messaging service.
- Make sure the phone has a network connection and Google Play services. It does not need to use the same Wi-Fi network as the computer.
- If the pairing code may have leaked, do not keep using it. Ask the setup assistant to replace the entire pairing.

## The phone is paired but receives no notification

Check these in order:

1. Open DenDen and confirm that its settings page says it is subscribed.
2. Make sure the phone can access the network and Android has not force-stopped DenDen.
3. Check DenDen notification permission and each notification category.
4. Check Do Not Disturb, battery optimization, and manufacturer background limits.
5. Open the DenDen inbox and see whether the message was saved without a system notification.
6. Ask the setup assistant to send another standard test notification.

If you force-stopped DenDen, open the app yourself once before testing again.

## No alarm appears

- Make sure DenDen has notification and full-screen notification permission.
- Check Do Not Disturb, volume, battery optimization, and background limits.
- Make sure you explicitly allowed alarms for this situation. Otherwise DenDen uses a less disruptive notification method.

Stopping an alarm only silences the phone. It does not stop the AI assistant's work.

## Bixby cannot find DenDen

Update DenDen and the Samsung system, then reopen Modes and Routines. Some phones or One UI versions do not support third-party app actions. You can use Tasker instead.

## A Tasker action stopped working

If you recently cleared DenDen data or reinstalled the app, open and save the existing action again in Tasker. Test a standard notification before checking alarms.

## Android rejects an update

Do not uninstall the old version or clear app data. Stop the update, confirm that the APK came from the official DenDen GitHub Releases page, then ask the publisher to verify that the signatures match.

## The DenDen appearance did not update

Bring DenDen to the foreground and check for an appearance waiting for approval. A new image is applied only after you preview and accept it. If the transfer stopped, ask the setup assistant to resume the same image.

Report security issues according to [SECURITY.md](../../SECURITY.md).
