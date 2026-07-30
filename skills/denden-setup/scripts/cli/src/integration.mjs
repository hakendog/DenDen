import { runDailyCommand } from "./commands.mjs";

const EVENT_PATTERN = /<!--\s*denden:event=(completed|failed|partial|blocked|needs-reply|manual)(?:;durationSeconds=(\d+(?:\.\d+)?);durationReliable=true)?\s*-->/i;

export async function runIntegrationHook({
  stdin = process.stdin,
  fetchImpl,
  accessToken,
  tokenProvider,
  now,
  nowMillis,
  messageId,
  nonce,
  env = process.env,
} = {}) {
  let input;
  try {
    input = JSON.parse(await readAll(stdin));
  } catch {
    return { notified: false, reason: "invalid-hook-input" };
  }
  const response = await hookResponse(input);
  const match = EVENT_PATTERN.exec(response || "");
  if (!match) return { notified: false, reason: "missing-standard-event" };
  const args = ["report", "--event", match[1].toLowerCase(), "--title", `Agent ${match[1].toLowerCase()}`];
  if (match[2] !== undefined) args.push("--duration", match[2], "--duration-reliable");
  const result = await runDailyCommand(args, {
    cwd: input.cwd || process.cwd(), fetchImpl, accessToken, tokenProvider, now, nowMillis, messageId, nonce, env,
  });
  return { notified: true, event: match[1].toLowerCase(), action: result.policy?.action || null };
}

async function hookResponse(input) {
  if (typeof input.prompt_response === "string") return input.prompt_response;
  if (typeof input.last_assistant_message === "string") return input.last_assistant_message;
  if (typeof input.transcript_path !== "string") return "";
  try {
    const { readFile } = await import("node:fs/promises");
    const lines = (await readFile(input.transcript_path, "utf8")).trim().split(/\r?\n/).reverse();
    for (const line of lines) {
      const record = JSON.parse(line);
      const content = record?.message?.content ?? record?.content;
      if (typeof content === "string") return content;
      if (Array.isArray(content)) {
        const text = content.filter((item) => item?.type === "text" && typeof item.text === "string").map((item) => item.text).join("\n");
        if (text) return text;
      }
    }
  } catch {
    return "";
  }
  return "";
}

async function readAll(stream) {
  let value = "";
  for await (const chunk of stream) value += chunk;
  return value;
}
