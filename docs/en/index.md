# DenDen documentation

English · [繁體中文](../zh-TW/index.md)

DenDen is an Android notification system that lets computers, AI assistants, and phone automations notify you when something important happens. You can choose a quiet notification, standard notification, or alarm, and delivered messages remain in the DenDen inbox.

These docs cover first-time setup, Bixby and Tasker, everyday device management, troubleshooting, safety limits, and technical integration. Start here, then open the detailed guide for the task you want to complete.

## How to start

Choose the path that matches how you want to use DenDen:

### Use phone automation only

Install DenDen and choose “Use local automation only” the first time you open it. You can then create quiet messages, standard notifications, and alarms with Samsung Bixby Routines or Tasker. No computer, Firebase project, DenDen pairing code, or network connection is required.

[Set up Bixby and Tasker](local-automation.md)

### Receive notifications from a computer or AI assistant

Install DenDen on Android and allow notifications, then give the official setup guide to an AI assistant that can operate your computer. After you approve the change summary, scan the DenDen pairing code and confirm that both the test notification and inbox entry appear.

[See the installation and first-time pairing steps](setup.md)

## Daily use

- [Use the inbox, notifications, and alarms](usage.md)
- [Add a sending device (computer)](device-management.md#add-or-disable-a-computer)
- [Add a receiving device (phone)](device-management.md#add-a-phone)
- [Change the DenDen image](preferences-and-backup.md#change-the-denden-image)
- [Change notification methods](usage.md#notification-methods)
- [Export or import settings](preferences-and-backup.md#export-or-import-settings)
- [Update DenDen](preferences-and-backup.md#update-denden)

## If something goes wrong

- See [Troubleshooting](troubleshooting.md) when a notification does not appear, pairing fails, or an update is rejected.
- Read [Security and limitations](security-and-limitations.md) for offline behavior, delivery limits, local history, and appropriate use.

## Technical documentation

You only need these pages when building an integration or inspecting computer-side settings:

- [How DenDen reads settings and sender keys](settings-and-sender-key.md)
- [CLI reference](cli.md)
