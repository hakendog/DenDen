# How DenDen reads settings and sender keys

When sending a notification, the DenDen CLI reads only the project's message channel settings, the current user's DenDen settings, and the sender key for this computer. It does not scan project source code, documents, browser data, or other files in the user directory.

Most users never need to open or edit these files. Use the DenDen setup assistant to install DenDen, move settings, or replace a computer.

## Read order

### 1. Project settings

The CLI searches upward from the current working directory for `.denden.json`. This file stores message channel names, the default channel, and notification rules that can be shared with a project. It must not contain pairing secrets, Google keys, or other credentials.

When sending a message, the CLI chooses a message channel in this order:

1. The `--channel-id` command option.
2. The `DENDEN_CHANNEL_ID` environment variable.
3. `defaultChannelId` in `.denden.json`.

If it cannot find project settings, the requested channel does not exist, or the file contains a forbidden field, the CLI stops instead of guessing.

### 2. User settings

The CLI then reads the current user's DenDen settings. The default path is:

```text
<user directory>/.config/denden/config.json
```

When `XDG_CONFIG_HOME` is set, the path changes to `denden/config.json` under that directory. `DENDEN_CONFIG_PATH` can specify another complete path for one run.

This file contains:

- Public identifiers for the Google project and Firebase app.
- The private message channel, pairing identifiers, and notification encryption key.
- Global and message-channel notification preferences.
- The directory containing this computer's sender key.

This is a private settings file. Do not put it in Git, a synchronized folder, a conversation, or a bug report. The CLI checks its fields and format. It refuses to send when the file is incomplete or contains an unknown field.

### 3. Sender key

The user settings contain only the location of the sender key directory. The key has a fixed filename:

```text
<sender key directory>/service-account.json
```

DenDen first confirms that only the current computer user can read the directory and file. It then checks that the key belongs to the Google project named in the user settings. It stops when the project does not match, the file format is invalid, or file permissions are too broad.

Windows limits file access to the current user. macOS and Linux check permissions on the private directory and file. All sender computers share one least-privilege key, but it may move only inside DenDen's sender transfer package. Do not copy the raw key or private directory.

## What happens when DenDen sends a notification

1. The CLI reads and validates the project and user settings.
2. It reads this computer's sender key from the protected directory.
3. Google Cloud CLI uses that key to obtain a short-lived access token limited to Firebase Messaging.
4. DenDen encrypts the notification title, message, and tags on the computer.
5. The CLI uses the short-lived token to send the ciphertext to your Firebase project.

The long-lived private key stays on the computer. A sending request uses a short-lived access token, not your Google password or the human administrative sign-in from initial setup. Daily notifications cannot create a project, change permissions, pair a phone again, or read a phone's FCM device token.

Google accepting the request only means that FCM accepted the message. It does not prove that the phone received it. DenDen does not maintain a remote device list or query inbox history from a server.

## Let another computer send notifications

Remote sending currently supports Windows, macOS, and Linux. A new computer imports the shared sender identity through the setup assistant without another Google sign-in.

First export an unencrypted general sender transfer package from a configured computer, then move it to the new computer through a user-controlled local medium. It contains Google project identifiers, the private message channel, the notification encryption key, and the shared Google sender private key. It does not contain the DenDen appearance key. Possession grants full sender authority.

After the import, the setup assistant writes the shared private key to the new computer's protected directory, confirms that it can send only FCM messages, and sends a test notification for you to confirm on the phone. No Google administrative sign-in is required.

See [Add or disable a computer](device-management.md#add-or-disable-a-computer) for complete steps.

Bixby and Tasker on Android create DenDen messages only on the same phone. Another Android phone cannot currently act as a remote sender.

## Disable or replace a computer

For a computer you still control, remove its private DenDen settings and daily skill. A single computer cannot be revoked remotely. If a computer is lost or untrusted, rotate the shared Google sender key and re-import it on every retained computer. Because the computer also stores pairing information and the notification encryption key, replace the entire pairing and pair every retained phone again.

See the [CLI reference](cli.md) for commands and setting formats. See [Add and manage devices](device-management.md) for normal operations.
