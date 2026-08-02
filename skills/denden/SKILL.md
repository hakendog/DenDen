---
name: denden
description: Automatically report substantive agent work to DenDen with its bundled low-privilege CLI before the final response when completed, partial, failed, blocked, or awaiting user reply. Skip simple answers/status checks. Manual use too. Alarms need explicit authority. Not for setup/pairing/Firebase/appearance.
---

# DenDen daily reporting

- `<denden>` means `node <this skill directory>/scripts/denden.mjs`; never use PATH, launchers, npm, or a custom sender.
- After substantive work completes, is partial, fails, blocks, or needs a reply, report before the final response and wait for its result. Do not report simple answers or status checks.
- Run `<denden> capabilities` first. Continue only for `{"schemaVersion":1,"runtimeProtocol":"direct-fcm-v2","requiresAutomationToken":false}`; otherwise stop and never seek a legacy credential.
- Run `<denden> report --event <completed|failed|partial|blocked|needs-reply|manual> --title <short title> --message <safe summary>`. Both may appear on lock screens/logs; exclude credentials, tokens, keys, pairing codes, private paths, and sensitive content. Add `--duration <seconds> --duration-reliable` only for reliable timing.
- `completed` is quiet; `failed`, `partial`, `blocked`, and `needs-reply` notify; alarms are off.
- Alarms require an explicit user request or exact protected user rule. Repository policy may choose only `off`, `quiet`, or `notify`; it can never authorize `ring`.
- `<denden> stop --event-id <id>` stops only the phone alarm, not the source task.
- Do not run setup, Firebase/IAM, pairing, export, or appearance operations; use `denden-setup`. Never expose secrets.
- Select the workspace containing the task's primary deliverable or inspected files. Run every `<denden>` command from its root, never the skill directory or an arbitrary process cwd. Search upward from that workspace for `.denden.json`. If none exists, run `<denden> channel init` once at the workspace root, then retry the same report. Existing invalid config still stops; never overwrite it.
- Resolve Channel by `--channel-id`, `DENDEN_CHANNEL_ID`, then `.denden.json.defaultChannelId`. User config exists only at `~/.config/denden/config.json`; never use `~/.config/agent-skills/denden.json`.
- After a report succeeds, say “DenDen <quiet|standard|ringing> notification sent” in the user's language for `quiet|notify|ring`, respectively; never say “FCM accepted” or imply receipt. Report failure; keep task status.
