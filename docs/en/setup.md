# DenDen setup assistant

The DenDen setup assistant handles installation, phone pairing, notification preferences, images, setting backups, and computer management. Tell your AI assistant what you want to do. It checks the current state and explains the next steps first. Important changes are shown in a summary and do not run until you approve them.

You can tell the AI assistant to cancel at any time. Changes you have not approved will not run.

## Install DenDen on your phone

DenDen supports Android 8.0 and later:

1. Open the [GitHub Releases page](https://github.com/hakendog/DenDen/releases) and download the latest DenDen APK.
2. Complete the installation in Android.
3. Open DenDen and allow notifications.
4. Choose remote notification pairing, or use Bixby and Tasker without pairing.

<p align="center">
  <img src="../assets/screenshots/en/01-welcome.png" alt="DenDen first-time setup choices" width="320">
</p>

If you only use Bixby or Tasker on the phone, setup is complete. You do not need a Google account or computer. See [Bixby and Tasker](local-automation.md).

## Install and pair DenDen

Remote notifications from a computer or AI assistant require Google Play services on the phone. The computer can run Windows, macOS, or Linux. Before you begin, have a Google account and an AI assistant that can operate your computer.

### 1. Ask the AI assistant to begin

Copy the entire block below and paste it into the AI assistant:

```text
Please follow this DenDen installation guide and help me install and set up DenDen:

docs/agent-install.md
```

The AI assistant reads the installation guide, loads the DenDen setup assistant, and starts checking the computer.

This check does not change your Google account, create a project, or write settings. If a required tool is missing, the AI assistant explains its purpose and what it would install before asking whether to continue.

### 2. Sign in to Google

After the check, your browser opens the Google sign-in and authorization page. This sign-in is used only for DenDen setup. It does not reuse an account already connected to the AI assistant.

Choose the Google account that will manage the DenDen project and sign in through the browser. Enter passwords and verification codes yourself. The AI assistant does not need them.

### 3. Choose a project for DenDen

DenDen needs a dedicated Google project to send notifications. You can:

1. Create a new project dedicated to DenDen. This is the recommended choice.
2. Use a dedicated existing project that you select. The setup assistant checks whether it is suitable for DenDen.

The setup assistant does not choose or change another existing project on its own. Standard setup uses the free plan, does not connect a payment method, and does not enable paid services.

If Google requires you to accept Firebase terms first, the AI assistant takes you to the correct page. Complete only the items required by DenDen. You do not need to enable Analytics or other AI features.

### 4. Choose daily notifications

The setup assistant asks whether to install the daily DenDen notification feature for the current AI assistant. Once installed, the assistant can notify your phone when work completes, fails, becomes blocked, or needs your reply. You can choose another installation location or skip this step and install it later.

If you install it, choose one notification policy:

1. Send a quiet notification whenever work completes, and a standard notification for other results.
2. Notify you of completion only when work takes longer than one minute, and use a standard notification for other results.
3. Notify you only when work fails, is blocked, needs your reply, or when you request a notification.
4. Choose a notification method for each result.

Alarms are off by default. The setup assistant enables them only for situations you explicitly choose.

### 5. Choose a DenDen image

During installation, you can:

- Use the built-in DenDen.
- Generate a new DenDen image.
- Generate an image from your instructions.
- Import a transparent PNG image.
- Use the default image and change it later.

If an external image service needs to receive any material, the setup assistant tells you what will be uploaded and waits for your approval.

### 6. Review the setup summary

Before making changes, the AI assistant shows a complete summary that includes:

- The Google project it will create or use.
- The settings it will add to this computer.
- Whether it will install daily notifications and which notification policy it will use.
- The selected DenDen image.
- The phone pairing and notification test that remain.

Read the summary. If everything is correct, reply:

```text
I approve
```

If anything is unclear or needs to change, ask the AI assistant to explain or update it before you approve.

### 7. Complete computer setup

After approval, the AI assistant creates or configures the project dedicated to DenDen and gives this computer its own notification permission. It then removes the temporary Google administrative access used for setup. Daily notifications never receive that administrative access.

If a step cannot be completed, the AI assistant stops and explains the problem. It does not report an incomplete setup as successful.

### 8. Pair the phone

When computer setup is complete, the conversation shows a short-lived DenDen pairing code. Do not capture, forward, or upload it.

Complete these steps on your phone:

1. Open the installed DenDen app.
2. Follow the screen to allow notifications if needed.
3. Choose to scan the DenDen pairing code.
4. Check the project details shown by the phone, then confirm pairing.

The phone does not need to use the same network as the computer. After pairing succeeds, the pairing code image is deleted from the computer.

### 9. Confirm the test notification

The setup assistant sends a standard test notification. Confirm that the notification appears on your phone and that its content is correct in DenDen.

Installation and pairing are complete only after the phone receives this notification. If it does not arrive, the setup assistant identifies the step that failed and continues checking from the current state.

You can also create an encrypted backup for future setting recovery or DenDen image management. This backup does not contain received notification history. DenDen never adds message content to the backup.

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

## Change the DenDen image

You can switch to the built-in image, generate a new image, generate one from your instructions, or import a transparent PNG at any time. Changing the image does not require phone pairing again.

The setup assistant shows a preview first. After you approve it, the image is sent to paired phones. Each phone accepts the update separately, so you can finish an incomplete update later.

Before using an external image service, the setup assistant explains what it needs to upload. You can refuse and use the built-in image or a local image instead.

## Export or import settings

The setup assistant handles two kinds of encrypted backup:

1. General notification settings for restoring basic DenDen settings.
2. DenDen image management settings for updating or restoring the image.

When exporting, enter a backup password directly in the computer prompt. Enter it there again when importing. The AI assistant does not see the password.

A general settings backup does not include the notification permission unique to each computer. A new computer still needs its own permission after importing the backup. The backup also excludes notification content received by the phone.

Before importing, the setup assistant explains which content will be added or replaced. It writes the settings only after you approve.

## Add or disable a computer

Every computer that sends DenDen notifications has its own permission. Do not copy another computer's private settings directory.

### Let another computer send notifications

On a computer that already sends notifications, paste this into your AI assistant:

```text
Please follow this DenDen installation guide to load the setup assistant, then export my general notification settings for another computer:

docs/agent-install.md
```

Then follow these steps:

1. Choose where to save the encrypted settings package.
2. Enter a backup password in the private computer prompt. The AI assistant does not see it.
3. Move the encrypted settings package to the new computer. Do not copy the private settings directory or sender key with it.
4. Open an AI assistant on the new computer and paste the entire block below:

   ```text
   Please follow this DenDen installation guide to load the setup assistant. Then import my general notification settings, create separate sender permission for this computer, and install the daily DenDen notification feature:

   docs/agent-install.md
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

## Install daily notifications

If you skipped daily notifications during initial setup, you can install them later. Choose the AI assistant's usual skill location, another location, or only check the current state.

Copy the entire block below and paste it into that AI assistant:

```text
Please follow this DenDen installation guide to load the setup assistant, then install the daily DenDen notification feature for this AI assistant:

docs/agent-install.md
```

Choose which task results should notify you during installation. Daily notifications can only send notifications. They cannot manage the Google project, pair a phone again, or change the DenDen image.

If the selected location already contains different content, the setup assistant explains the difference instead of overwriting it.

## Update DenDen

Download the new APK from the [GitHub Releases page](https://github.com/hakendog/DenDen/releases), then install it over the existing Android app. Existing messages, settings, and pairing information remain.

Do not uninstall the old version or clear DenDen app data first. If Android rejects the update, stop and confirm that the APK came from the DenDen GitHub Releases page, then see [Troubleshooting](troubleshooting.md).

## Security and use reminders

- Do not share or upload a DenDen pairing code, settings backup, private settings directory, or sender permission.
- Enter passwords, Google verification codes, and other sign-in information only in your own browser or private computer prompt.
- Any operation that changes a project, pairing, image, or computer permission should show a clear summary first.
- Confirm setup, repairs, and new devices with a test notification.
- DenDen does not save notifications for an offline phone. Messages sent while the phone is offline will not arrive after it reconnects.
