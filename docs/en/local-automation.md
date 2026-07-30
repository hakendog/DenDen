# Bixby and Tasker

DenDen can receive messages directly from Bixby Routines and Tasker automations on your phone. This does not require Firebase, a DenDen pairing code, a computer tool, or a network connection.

When you first open DenDen, choose "Use local automation only" on the pairing screen. If you later want remote notifications from an AI assistant, open DenDen settings and choose "Pair now."

Messages created by Bixby and Tasker are saved in the DenDen inbox. You can still find them in DenDen even if Android does not display a notification.

## Samsung Bixby

1. Open Modes and Routines on a Samsung phone.
2. Create a routine and set its conditions.
3. Choose DenDen as an action.
4. Choose a quiet message, standard notification, or alarm.

Bixby actions use fixed text, so you do not need to enter a title or message. An alarm rings for 30 seconds by default.

If DenDen does not appear in Modes and Routines, update DenDen and your phone software first. Some Samsung phones or One UI versions may not support third-party app actions.

## Tasker

Tasker support is built into DenDen. You do not need another companion app.

1. Add an action to a Tasker task and choose "DenDen notification or alarm."
2. Choose a quiet message, standard notification, or alarm.
3. Enter a title, message, and alarm duration.
4. You can use Tasker variables such as `%title`, `%message`, and `%duration` in the text fields.
5. Save the action and run one test.

Titles can contain up to 200 characters and messages up to 1,000 characters. Alarm duration can be 0 to 300 seconds. A value of 0 uses the default duration of 30 seconds.

If you clear DenDen data or reinstall the app, open and save each existing Tasker action again so it can receive permission to run.

## If no notification appears

Open the DenDen inbox first and check whether the message exists. If the message was saved but Android did not display a notification, check notification permission, the matching notification category, Do Not Disturb, and battery optimization.

Alarms are also affected by full-screen notification permission and device background limits. See [Troubleshooting](troubleshooting.md) for more steps.
