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

Every computer that sends DenDen notifications has its own permission. Do not copy another computer's private settings directory.

### Let another computer send notifications

On a computer that already sends notifications, paste this into your AI assistant:

```text
Please follow this DenDen installation guide to load the setup assistant, then export my general notification settings for another computer:

https://raw.githubusercontent.com/hakendog/DenDen/26a9298d199d4c65b547288d36166a628d80b628/docs/agent-install.md
```

Then follow these steps:

1. Choose where to save the encrypted settings package.
2. Enter a backup password in the private computer prompt. The AI assistant does not see it.
3. Move the encrypted settings package to the new computer. Do not copy the private settings directory or sender key with it.
4. Open an AI assistant on the new computer and paste the entire block below:

   ```text
   Please follow this DenDen installation guide to load the setup assistant. Then import my general notification settings, create separate sender permission for this computer, and install the daily DenDen notification feature:

   https://raw.githubusercontent.com/hakendog/DenDen/26a9298d199d4c65b547288d36166a628d80b628/docs/agent-install.md
   ```

5. Choose the encrypted settings package and enter its password yourself.
6. When the browser opens, sign in with the Google account that manages DenDen.
7. Choose a daily notification policy and confirm the installation location.
8. Review the summary for the new computer and daily notification feature. Reply `I approve` when it is correct.
9. The setup assistant creates separate sender permission and a key for this computer, installs the daily notification feature, removes the Google administrative access used for setup, and sends a test notification.

Even when the setup assistant reports that it sent the test, confirm that the notification actually appears on your phone. Existing computers and phones do not need to pair again.

Remote sending currently supports Windows, macOS, and Linux computers. Bixby and Tasker on another Android phone can create messages only on that same phone. They cannot replace a computer as a remote sender to other phones.

### Disable a computer

You can disable a computer that is lost, retired, or no longer used. This removes only that computer's permission and does not affect other computers or phones.

If the entire pairing may have leaked, not just one computer's permission, use "Replace all pairing information" instead.
