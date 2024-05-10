# DenDen CLI

DenDen has no web API or REST API. Use the CLI when a program or AI assistant needs to work with DenDen. This page contains technical details for commands, setting formats, and program integrations.

The CLI supports Windows, macOS, and Linux. It requires Git, Node.js 22, and Google Cloud CLI.

## Execution and output

The examples below use `denden`. A production integration should use the absolute launcher path reported by the installer.

On success, the CLI writes one JSON value to stdout. On failure, it writes a JSON error to stderr and returns a nonzero exit code. Callers must check both the exit code and JSON instead of matching text alone.

```sh
denden capabilities
```

A compatible version returns:

```json
{
  "schemaVersion": 1,
  "runtimeProtocol": "direct-fcm-v2",
  "requiresAutomationToken": false
}
```

`runtimeProtocol` is an internal CLI compatibility identifier. It is not a product version name for the DenDen pairing code.

The CLI must not output private keys, tokens, a complete DenDen pairing code, the private message channel, passwords, or unredacted settings.

## Daily commands

```text
denden notify [--channel-id ID] [--title TEXT] [--message TEXT]
  [--event-id ID] [--duration SECONDS] [--mode normal|quiet]
  [--tag TEXT ...] [--dry-run]

denden ring [--channel-id ID] [--title TEXT] [--message TEXT]
  [--event-id ID] [--duration SECONDS] [--tag TEXT ...] [--dry-run]

denden stop --event-id ID [--dry-run]

denden report --event EVENT [--duration SECONDS] [--duration-reliable]
  [--action off|quiet|notify|ring] [--title TEXT] [--message TEXT]
  [--channel-id ID] [--dry-run]

denden policy inspect --event EVENT [--duration SECONDS]
  [--duration-reliable] [--action off|quiet|notify|ring]
  [--channel-id ID]
```

`EVENT` accepts `completed`, `failed`, `partial`, `blocked`, `needs-reply`, and `manual`.

`--duration` must be a nonnegative number. `--tag` can be repeated. After normalization, a message can have up to 20 tags, each no longer than 100 characters.

`--dry-run` does not call FCM. It returns the planned action and redacted data. `stop` silences only the phone alarm for the specified event.

`--device` and `devices` are always rejected because DenDen has no remote device registry and does not send to phone device tokens.

## Message channels

```text
denden channel init --channel-id ID [--name NAME]
denden channel list
denden channel add --channel-id ID --name NAME
denden channel use --channel-id ID
denden channel remove --channel-id ID
```

`init` creates `.denden.json` in the current directory and refuses to overwrite an existing file. The CLI does not allow removing the current default message channel.

## Notification presets

```sh
denden presets
```

This returns the JSON definitions for `important`, `balanced`, and `all-completed`.

## Setup and management

```sh
denden setup --help
```

Public subcommands:

- Installation: `install`, `rollback`, `doctor`
- Initial setup: `management-auth`, `plan`, `direct`
- Sender identities: `sender-auth-plan`, `sender-auth`, `sender-verify`, `sender-revoke-plan`, `sender-revoke`
- Pairing: `status`, `qr`, `qr-remove`, `rotate-plan`, `rotate`
- Transfers and backups: `export sender|brand`, `import-plan sender`, `import sender|brand`
- Appearance: `brand apply|resume|reset`
- Daily skill: `skill-plan`, `skill-install`
- Administrative sign-in: `management-revoke-plan`, `management-revoke`

The general sender transfer package is unencrypted and contains the shared Google sender private key; possession grants full sender authority. Run `import-plan sender` before every import, then run `import sender` with its `--approved-digest <digest>`. Different existing settings are not overwritten by default; add `--replace-existing true` only after an approved shared-sender rotation. `brand` backups remain password-encrypted.

Commands that change Google resources, pairing, credentials, skills, or appearance require a summary from the matching planning command. Run them with the same `--approved-digest`. Do not generate a digest yourself.

A safe read-only example:

```sh
denden setup status
```

Give other setup commands to `denden-setup`. An AI assistant loads its complete rules from the [installation and setup entry point](../agent-install.md).

## Setting files

A project directory can use `.denden.json` to select a default message channel and notification policy for each channel. This file cannot contain secrets or authorize alarms.

```json
{
  "defaultChannelId": "docs",
  "channels": {
    "docs": {
      "channelId": "docs",
      "channelName": "Documentation",
      "policy": {
        "preset": "balanced"
      }
    }
  }
}
```

User settings that contain the private message channel, encryption keys, and sender credential location are stored in a protected directory. Do not manually copy, print, or commit this file.

See [How DenDen reads settings and sender keys](settings-and-sender-key.md) for the complete read order, sender-key checks, and sending flow.

Common environment variables:

| Variable | Purpose |
|---|---|
| `DENDEN_CONFIG_PATH` | Override the setting file for this process |
| `DENDEN_CHANNEL_ID` | Override the message channel for this process |
| `DENDEN_INSTALL_ROOT` | Override the persistent tool installation directory |
| `XDG_CONFIG_HOME` | Set the root configuration directory |
| `XDG_DATA_HOME` | Set the root data directory |

There is no valid `DENDEN_AUTOMATION_TOKEN` setting. A request for this token means that an obsolete tool is running.

## Notification policy format

```json
{
  "preset": "all-completed",
  "events": {
    "failed": "notify"
  },
  "minCompletedDurationSeconds": 60,
  "quietHours": {
    "start": "22:00",
    "end": "07:00",
    "timeZone": "Asia/Taipei",
    "mode": "downgrade"
  }
}
```

Actions can be `off`, `quiet`, `notify`, or `ring`. Only protected user settings can authorize `ring`.

## AI assistant integrations

```sh
denden integration hook
```

This command reads JSON from stdin and accepts only the standard event annotation in a response:

```html
<!-- denden:event=completed;durationSeconds=95;durationReliable=true -->
```

If it cannot find an exact annotation, the input is damaged, or the response cannot be read, the CLI returns `notified: false`. It does not guess an event or send a notification.

Codex uses the low-privilege `skills/denden/`. Examples for Claude Code and Gemini CLI are in `integrations/`. A tool without a native integration can call `denden report` directly.

## DenDen pairing code and message delivery

The raw DenDen pairing code begins with `DDC.`. It contains the private message channel and encryption keys, so treat the entire value as a secret. External tools should not create, parse, record, or upload it. Use `denden setup` to create and remove pairing codes.

The phone registers with FCM and subscribes to the private message channel itself. The CLI sends only to `message.topic`. It never receives, stores, or uses a phone's FCM device token.

Notification and appearance content uses AES-256-GCM encryption. FCM accepting a request only means Google received the sending request. It does not prove that the phone received the message or added it to the DenDen inbox.
