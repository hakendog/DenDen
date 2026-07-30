# Security and limitations

DenDen keeps data on your own devices and in your own account whenever possible. Remote notifications still pass through Google, and Android settings can affect how they appear.

## DenDen does not create remote activity records

You do not register a DenDen account, and the maintainers do not operate a central DenDen server. The DenDen app contains no usage analytics or remote crash reporting. The maintainers do not receive your activity, message history, list of paired phones, or usage frequency.

Received messages, read states, archived content, Trash, pairing information, and personal settings stay on the phone. Android cloud backup and device transfer are disabled for DenDen, so the app does not send this data to Android backup services.

DenDen settings and sender keys on a computer stay on that computer and are not uploaded to the maintainers. Each computer has its own sender key, and settings backups do not contain that key.

DenDen has no central synchronization service. Each phone keeps its own received content and read states. They do not sync to other phones.

## Remote notifications still pass through Google

Google FCM handles phone registration and notification delivery. Google may process metadata such as delivery time, data size, project information, and the private FCM topic identifier according to its policies.

DenDen encrypts the notification title and message text on the computer before sending them through your Firebase project. Google processes the ciphertext and delivery metadata, but it does not receive directly readable notification content.

## Protect pairing information

A DenDen pairing code contains secrets needed to join your private message channel. Do not capture, forward, paste into a conversation, or upload it to another service.

Each sender computer has its own permission, limited to sending DenDen notifications. Do not copy settings from an old computer when adding another one. If a computer is lost or retired, or a DenDen pairing code may have leaked, follow [Add and manage devices](device-management.md) to disable that computer or replace the entire pairing.

## Updates and data removal

- Install DenDen updates over the existing app. Do not uninstall first.
- Clearing app data or uninstalling removes the inbox history, settings, and pairing information on that phone.
- You need to pair again after reinstalling. Existing Tasker actions must also be opened and saved again.
- Deleting a message on one phone does not delete it from another phone.

## Notifications can be delayed or lost

Google notification services do not guarantee immediate, ordered, or successful delivery. A network outage, force-stopping DenDen, notification permission, Do Not Disturb, battery optimization, or manufacturer background limits can delay a notification, leave it only in the inbox, or prevent it from arriving.

DenDen does not keep messages on a server while waiting for a phone. A phone that is offline cannot receive remote messages, and messages sent during that period do not arrive after it reconnects.

If you force-stop DenDen in Android settings, you usually need to open the app yourself once before it can receive notifications again.

## Bixby and Tasker

- Whether DenDen appears in Samsung Modes and Routines depends on the phone model and One UI version.
- Bixby and Tasker are still affected by Android notification permission, Do Not Disturb, battery optimization, and background limits.
- Bixby uses the three fixed actions provided by DenDen. Use Tasker when you need a custom title, message, or duration.

## When not to use DenDen

DenDen is not a medical, life-safety, emergency response, or guaranteed-delivery system. Use a professional system with the required guarantees for any workflow that needs confirmed delivery, regulatory auditing, or a single source of emergency alarms.

Report security issues according to [SECURITY.md](../../SECURITY.md). For details about how a sender computer reads settings and keys, see [How DenDen reads settings and sender keys](settings-and-sender-key.md). For commands and parameters, see the [CLI reference](cli.md).
