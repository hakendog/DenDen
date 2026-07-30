# Add and manage devices

After first-time pairing, ask your AI assistant to check settings, add a phone, add or disable a computer, or replace the entire pairing if its information may have leaked. The setup assistant checks the current state and shows a summary before making changes.

## Check or repair settings

Ask the AI assistant to check DenDen when notifications stop arriving, the computer environment changes, or you are unsure whether setup is complete.

The setup assistant first runs checks that do not change anything. It checks computer settings, the Google project, sender permission, and phone pairing. If a repair is needed, it explains the problem, proposed changes, and affected items, then waits for your approval.

A repair does not create a new project on its own or ask you to pair the phone again unless necessary. It sends another test notification afterward so you can confirm the result.

## Add a phone

Ask the AI assistant to add a phone when another Android phone should receive the same DenDen notifications.

The setup assistant creates another short-lived DenDen pairing code for the current pairing. The new phone can scan and confirm it without removing or reconfiguring existing phones.

Each phone must allow notifications and confirm pairing. The setup assistant deletes the pairing code image afterward and sends a test notification for you to confirm.

## Replace all pairing information

Replace all pairing information only when someone else may have obtained the DenDen pairing code or pairing secrets. This is different from adding a phone.

The setup assistant lists which phones and computers will be affected. After you approve, the old pairing information stops working and every phone you keep must scan the new DenDen pairing code.

Make sure those phones are available before you begin. The setup assistant sends a test notification after they are paired again.

## Add or disable a computer

All sender computers share one least-privilege Google sender identity. Transfer it only with DenDen's sender transfer package. Do not copy the raw key or private settings directory.

### Let another computer send notifications

On a computer that already sends notifications, paste this into your AI assistant:

```text
Please follow this DenDen installation guide to load the setup assistant, then export my general notification settings for another computer:

https://raw.githubusercontent.com/hakendog/DenDen/c2f7e66fb8c3c0daeeace2f9ce46027706f24976/docs/agent-install.md
```

Then follow these steps:

1. Choose where to save the transfer package. It is unencrypted and readable only by the current user.
2. Move it to the new computer through a user-controlled local medium. Do not use chat, email, cloud sync, or another untrusted channel.
3. The package contains the shared Google sender private key, private notification channel, and notification encryption key. Possession grants full sender authority.
4. Open an AI assistant on the new computer and paste the entire block below:

   ```text
   Please follow this DenDen installation guide to load the setup assistant. Then import my general notification settings, verify the shared sender permission, and install the daily DenDen notification feature:

   https://raw.githubusercontent.com/hakendog/DenDen/c2f7e66fb8c3c0daeeace2f9ce46027706f24976/docs/agent-install.md
   ```

5. Choose the transfer package. No password is required.
6. Review the import project, paths, fingerprints, replacement status, and effect, then approve only if they are correct.
7. The setup assistant writes the shared key to a protected directory and verifies its least privilege without asking for a Google sign-in.
8. Choose a daily notification policy, confirm the installation location, install the daily notification feature, and send a test notification.
9. After a successful import, decide whether to delete the transfer-package copies on both computers. Ordinary deletion may leave recoverable storage traces.

Even when the setup assistant reports that it sent the test, confirm that the notification actually appears on your phone. Existing computers and phones do not need to pair again.

Remote sending currently supports Windows, macOS, and Linux computers. Bixby and Tasker on another Android phone can create messages only on that same phone. They cannot replace a computer as a remote sender to other phones.

### Disable a computer

For a computer you still control, remove its private DenDen settings and daily skill. No Google sign-in is required.

A single computer cannot be revoked remotely. If a computer or transfer package is lost or untrusted, sign in with the Google account that manages DenDen on a retained computer, rotate the shared sender identity, and import the new package on every retained computer. Because a lost computer also stores pairing information, use "Replace all pairing information" as well.
