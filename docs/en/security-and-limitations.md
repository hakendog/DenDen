# Security and limitations

DenDen keeps data on your own devices and in your own account whenever possible. Remote notifications still pass through Google, and Android settings can affect how they appear.

## DenDen does not create remote activity records

You do not register a DenDen account, and the maintainers do not operate a central DenDen server. The DenDen app contains no usage analytics or remote crash reporting. The maintainers do not receive your activity, message history, list of paired phones, or usage frequency.

Received messages, read states, archived content, Trash, pairing information, and personal settings stay on the phone. Android cloud backup and device transfer are disabled for DenDen, so the app does not send this data to Android backup services.

After decryption, message content is stored as readable text in DenDen's app-private Room database. DenDen does not add database-level encryption such as SQLCipher; protection at rest relies on the Android app sandbox, the device's screen lock, and Android device encryption. A compromised or unlocked device can expose that content.

DenDen settings and sender keys are not uploaded to the maintainers. All sender computers share one least-privilege key, transferred inside an unencrypted package initially readable only by the current user.

DenDen has no central synchronization service. Each phone keeps its own received content and read states. They do not sync to other phones.

## Remote notifications still pass through Google

Google FCM handles phone registration and notification delivery. Google may process metadata such as delivery time, data size, project information, and the private FCM topic identifier according to its policies.

DenDen encrypts the notification title and message text on the computer before sending them through your Firebase project. Google processes the ciphertext and delivery metadata, but it does not receive directly readable notification content.

## Protect pairing information

A DenDen pairing code contains secrets needed to join your private message channel. Do not capture, forward, paste into a conversation, or upload it to another service.

The shared sender permission is limited to sending DenDen notifications. The transfer package grants full sender authority; do not move it through chat, email, cloud sync, or another untrusted channel, and do not copy the raw key or settings directory. If a package or computer is lost, rotate the shared sender key. If pairing information may also have leaked, replace the entire pairing.

## Updates and data removal

- Install DenDen updates over the existing app. Do not uninstall first.
- Clearing app data or uninstalling removes the inbox history, settings, and pairing information on that phone.
- You need to pair again after reinstalling. Existing Tasker actions must also be opened and saved again.
- Deleting a message on one phone does not delete it from another phone.

## Notifications can be delayed or lost

Google notification services do not guarantee immediate, ordered, or successful delivery. A network outage, force-stopping DenDen, notification permission, Do Not Disturb, battery optimization, or manufacturer background limits can delay a notification, leave it only in the inbox, or prevent it from arriving.

DenDen has no history or replay server. If immediate delivery is unavailable, FCM may temporarily retain an encrypted ordinary message for up to five minutes and a ring or stop command for up to one minute. Expired messages are discarded; later recovery and guaranteed delivery remain unavailable.

If you force-stop DenDen in Android settings, you usually need to open the app yourself once before it can receive notifications again.

DenDen marks notification content as private for Android's lock-screen controls. A ringing alarm can still intentionally open its full-screen title and message over the lock screen. Tasker stores the fields configured for its DenDen action, and sender commands may be visible in terminal or agent logs. Do not put passwords, access tokens, private keys, pairing codes, or other highly sensitive content in a DenDen title or message.

## Bixby and Tasker

- Whether DenDen appears in Samsung Modes and Routines depends on the phone model and One UI version.
- Bixby and Tasker are still affected by Android notification permission, Do Not Disturb, battery optimization, and background limits.
- Bixby uses the three fixed actions provided by DenDen. Use Tasker when you need a custom title, message, or duration.

## When not to use DenDen

DenDen is not a medical, life-safety, emergency response, or guaranteed-delivery system. Use a professional system with the required guarantees for any workflow that needs confirmed delivery, regulatory auditing, or a single source of emergency alarms.

Report security issues according to [SECURITY.md](../../SECURITY.md). For details about how a sender computer reads settings and keys, see [How DenDen reads settings and sender keys](settings-and-sender-key.md). For commands and parameters, see the [CLI reference](cli.md).
