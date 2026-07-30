# Notifications, images, and backups

After first-time pairing, ask your AI assistant to install daily notifications, change the DenDen image, export or import settings, or update the phone app. The setup assistant shows a summary before making changes.

## Install daily notifications

If you skipped daily notifications during initial setup, you can install them later. Choose the AI assistant's usual skill location, another location, or only check the current state.

Copy the entire block below and paste it into that AI assistant:

```text
Please follow this DenDen installation guide to load the setup assistant, then install the daily DenDen notification feature for this AI assistant:

https://raw.githubusercontent.com/hakendog/DenDen/c25d89bbe1ca72c59d1acc6470b7afe04e81bb24/docs/agent-install.md
```

Choose which task results should notify you during installation. Daily notifications can only send notifications. They cannot manage the Google project, pair a phone again, or change the DenDen image.

If the selected location already contains different content, the setup assistant explains the difference instead of overwriting it.

## Change the DenDen image

You can switch to the built-in image, generate a new image, generate one from your instructions, or import a transparent PNG at any time. Changing the image does not require phone pairing again.

The setup assistant shows a preview first. After you approve it, the image is sent to paired phones. Each phone accepts the update separately, so you can finish an incomplete update later.

Before using an external image service, the setup assistant explains what it needs to upload. You can refuse and use the built-in image or a local image instead.

## Export or import settings

The setup assistant handles two kinds of portable data:

1. An unencrypted general sender transfer package that gives another trusted computer full sender authority.
2. A password-encrypted DenDen image-management backup for updating or restoring the image.

The general sender transfer package does not use a password. It is initially readable only by the current user, but moving or copying it may change those permissions. Do not use chat, email, cloud sync, or another untrusted channel. The DenDen image-management backup still requires a password of at least 12 characters entered in the computer prompt; the AI assistant does not see it.

The general sender transfer package contains the shared least-privilege Google sender private key, private notification channel, and notification encryption key, so a new computer does not require another Google sign-in after import. Possession grants full sender authority. It does not contain the DenDen image-management key or notification history from the phone.

Before importing, the setup assistant explains which content will be added or replaced. It writes the settings only after you approve, then separately asks whether to delete the transfer-package copy.

## Update DenDen

Download the new APK from the [GitHub Releases page](https://github.com/hakendog/DenDen/releases), then install it over the existing Android app. Existing messages, settings, and pairing information remain.

Do not uninstall the old version or clear DenDen app data first. If Android rejects the update, stop and confirm that the APK came from the DenDen GitHub Releases page, then see [Troubleshooting](troubleshooting.md).

## Security and use reminders

- Do not share or upload a DenDen pairing code, settings backup, private settings directory, or sender permission.
- Enter passwords, Google verification codes, and other sign-in information only in your own browser or private computer prompt.
- Any operation that changes a project, pairing, image, or computer permission should show a clear summary first.
- Confirm setup, repairs, and new devices with a test notification.
- DenDen does not save notifications for an offline phone. Messages sent while the phone is offline will not arrive after it reconnects.
