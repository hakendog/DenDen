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

Windows limits file access to the current user. macOS and Linux check permissions on the private directory and file. Each sender computer has its own key. Do not copy one from another computer.

## What happens when DenDen sends a notification

1. The CLI reads and validates the project and user settings.
2. It reads this computer's sender key from the protected directory.
3. Google Cloud CLI uses that key to obtain a short-lived access token limited to Firebase Messaging.
4. DenDen encrypts the notification title, message, and tags on the computer.
5. The CLI uses the short-lived token to send the ciphertext to your Firebase project.

The long-lived private key stays on the computer. A sending request uses a short-lived access token, not your Google password or the human administrative sign-in from initial setup. Daily notifications cannot create a project, change permissions, pair a phone again, or read a phone's FCM device token.

Google accepting the request only means that FCM accepted the message. It does not prove that the phone received it. DenDen does not maintain a remote device list or query inbox history from a server.

## Let another computer send notifications

Remote sending currently supports Windows, macOS, and Linux. A new computer cannot reuse another computer's `service-account.json`. The setup assistant creates a separate sender identity and key for it.

First export a password-protected general settings package from a configured computer, then import it on the new computer. This package contains Google project identifiers, the private message channel, and the notification encryption key. It does not contain a Google sender key from any computer.

After the import, the setup assistant asks you to sign in with the Google account that manages DenDen. Once you approve the summary, it creates a separate sender identity, saves the new key locally, confirms that it can send only FCM messages, and removes the administrative sign-in. The new computer sends a test notification, which you confirm on the phone.

See [Add or disable a computer](setup.md#add-or-disable-a-computer) for complete steps.

Bixby and Tasker on Android create DenDen messages only on the same phone. Another Android phone cannot currently act as a remote sender.

## Disable or replace a computer

If a computer is lost or retired, you can disable only its sender identity without affecting other computers or phones. If pairing information or the notification encryption key leaked, replace the entire pairing and pair every phone you keep again.

See the [CLI reference](cli.md) for commands and setting formats. See the [DenDen setup assistant](setup.md) for normal operations.
