# Daily use

After initial setup, an AI assistant can report task results according to your notification preferences. You do not normally need to open the DenDen setup assistant or enter commands yourself.

## What the AI assistant can report

- A task completed.
- A task failed.
- Only part of a task was completed.
- The task is blocked and cannot continue yet.
- The assistant needs your reply or decision.
- A notification you explicitly asked it to send.

DenDen only sends and stores results. It does not make decisions for the AI assistant or stop work on your computer.

## Notification methods

- Off: do not send this kind of result to the phone.
- Quiet notification: show a silent notification and save it in the DenDen inbox.
- Standard notification: alert you according to Android notification settings.
- Alarm: show the alarm screen and keep alerting you.

Alarms are off by default. An AI assistant can use an alarm only when you explicitly ask for one or when a protected rule you created earlier applies.

Stopping an alarm only silences the phone. It does not stop the AI assistant, terminal, or remote work.

<table>
  <tr>
    <td align="center"><strong>Notifications and inbox history</strong><br><img src="../assets/screenshots/en/03-inbox.png" alt="DenDen inbox showing notifications from different tasks" width="320"></td>
    <td align="center"><strong>Alarm for something that needs attention</strong><br><img src="../assets/screenshots/en/05-alarm.png" alt="DenDen alarm asking the user to confirm an action" width="320"></td>
  </tr>
</table>

## Use the inbox

DenDen organizes messages by message channel. You can:

- Search titles and message text.
- Filter by tag.
- See unread messages.
- Archive messages that do not need immediate attention.
- Move messages to Trash or restore them before permanent deletion.

All history stays on the current phone. Read, archive, and delete states do not sync to other phones.

## Bixby and Tasker

Bixby and Tasker can create DenDen messages directly. They do not require an AI assistant or remote pairing. See [Bixby and Tasker](local-automation.md).

## Add a sending device (computer)

To send DenDen notifications from another computer, ask the AI assistant on a working computer to export encrypted settings, then move that package to the new computer. The setup assistant creates separate sender permission for the new computer instead of sharing the old computer's private files.

Confirm that your phone receives a test notification from the new computer. See [Add or disable a computer](device-management.md#add-or-disable-a-computer) for the complete procedure.

## Add a receiving device (phone)

Install DenDen on the new Android phone and allow notifications, then ask your AI assistant to add a phone. Scan the short-lived DenDen pairing code on the new phone and confirm the test notification. Existing phones remain paired and do not need to be set up again.

See [Add a phone](device-management.md#add-a-phone) for the complete procedure.

## Change the DenDen image

Tell your AI assistant that you want to change the DenDen image. You can use the built-in image, generate a new one, or import a transparent PNG. The setup assistant shows a preview and sends the image to paired phones only after you approve it. Changing the image does not require pairing again.

See [Change the DenDen image](preferences-and-backup.md#change-the-denden-image) for the complete procedure.

## Other everyday settings

Tell your AI assistant when you want to change notification preferences, back up settings, update DenDen, or disable an old computer. The setup assistant checks the current state and waits for you to approve a change summary. See [Add and manage devices](device-management.md) and [Notifications, images, and backups](preferences-and-backup.md) for detailed steps.

Read the [CLI reference](cli.md) only when you need to build your own command-line integration.
