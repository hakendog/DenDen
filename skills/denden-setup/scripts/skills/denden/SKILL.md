---
name: denden
description: Report completed, failed, partial, blocked, or reply-needed agent work to DenDen with the bundled low-privilege CLI. Use for manual notifications and, only with explicit authority, starting or stopping a phone alarm. Do not use for setup, pairing, Firebase management, or appearance changes.
---

# DenDen daily reporting

- `<denden>` always means `node <this skill directory>/scripts/denden.mjs`. Do not use a PATH command, persistent launcher, npm package, or custom sender.
- After substantive work completes, partially completes, fails, becomes blocked, or needs a reply, send a report before the final response and wait for its result. Do not report simple answers or status checks.
- Before sending, run `<denden> capabilities`. Continue only when it returns `{"schemaVersion":1,"runtimeProtocol":"direct-fcm-v2","requiresAutomationToken":false}`. Otherwise stop and report that the skill is incomplete; never look for a legacy credential.
- Map the result to `completed`, `failed`, `partial`, `blocked`, `needs-reply`, or `manual`, then run `<denden> report --event <event> --title <short title> --message <safe summary>`. Lock screens and logs may show both fields; exclude credentials, tokens, keys, pairing codes, private paths, and sensitive content. Add `--duration <seconds> --duration-reliable` only for a reliable measurement.
- By default, `completed` is quiet; `failed`, `partial`, `blocked`, and `needs-reply` use a standard notification; alarms are off.
- An alarm requires an explicit user request or an exact protected user rule. Repository policy may choose only `off`, `quiet`, or `notify`; it can never authorize `ring`.
- `<denden> stop --event-id <id>` stops the phone alarm only. It does not stop the source task.
- Do not run setup, Firebase/IAM, pairing, export, or appearance operations. Use `denden-setup` instead. Never print or pass credentials, tokens, private topics, keys, or QR codes.
- Resolve the Channel in this order: `--channel-id`, `DENDEN_CHANNEL_ID`, then `.denden.json.defaultChannelId`. Stop on invalid configuration. The only user configuration is `~/.config/denden/config.json`; never read the legacy `~/.config/agent-skills/denden.json`.
- FCM acceptance is not proof that the phone received a message. A DenDen send failure does not change the original task result.
- Match the user's language in the final report. Summarize technical CLI output instead of exposing untranslated internal diagnostics.
