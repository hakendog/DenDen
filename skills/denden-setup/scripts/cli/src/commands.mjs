import { resolve } from "node:path";
import { addChannel, createRepoConfig, findRepoConfig, readJson, redact, removeChannel, selectChannel, setDefaultChannel, userConfigPath, validateRepoConfig, writeRepoJson } from "./config.mjs";
import { sendDirectFcmMessage } from "./fcm-client.mjs";
import { inspectPolicy, PRESETS } from "./policy.mjs";

const EVENT_NAMES = new Set(["completed", "failed", "partial", "blocked", "needs-reply", "manual"]);

export function parseArgs(argv) {
  const positional = [];
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) {
      positional.push(arg);
      continue;
    }
    const key = arg.slice(2);
    if (["dry-run", "duration-reliable", "token-stdin", "json"].includes(key)) {
      options[key] = true;
      continue;
    }
    const value = argv[++i];
    if (value === undefined || value.startsWith("--")) throw new Error(`--${key} 缺少值`);
    if (key === "tag") options.tag = [...(options.tag || []), value];
    else options[key] = value;
  }
  return { positional, options };
}

export async function loadRuntime({ cwd = process.cwd(), env = process.env } = {}) {
  const repo = await findRepoConfig(cwd);
  validateRepoConfig(repo.value);
  const userPath = userConfigPath(env);
  const user = await readJson(userPath, { required: false }) || {};
  return { repo, user, userPath, env };
}

export async function runDailyCommand(argv, context = {}) {
  const { positional, options } = parseArgs(argv);
  const command = positional[0];
  const runtime = await loadRuntime(context);
  const channel = selectChannel(runtime.repo.value, options["channel-id"], runtime.env);
  if (options.device) throw new Error("直接 FCM 使用配對主題廣播，不支援指定單一裝置");
  const request = async (action, body) => {
    return sendDirectFcmMessage({
      action,
      payload: body,
      config: runtime.user,
      fetchImpl: context.fetchImpl,
      accessToken: context.accessToken,
      tokenProvider: context.tokenProvider,
      nowMillis: context.nowMillis,
      messageId: context.messageId,
      nonce: context.nonce,
    });
  };

  if (["ring", "notify"].includes(command)) {
    const tags = normalizeTags(options.tag);
    const payload = {
      channelId: channel.channelId,
      channelName: channel.channelName,
      ...(options.title ? { title: options.title } : {}),
      ...(options.message ? { message: options.message } : {}),
      ...(options["event-id"] ? { eventId: options["event-id"] } : {}),
      ...(options.duration ? { duration: numberOption(options.duration, "duration") } : {}),
      ...(command === "notify" && options.mode ? { notificationMode: options.mode } : {}),
      ...(tags.length ? { tags } : {}),
    };
    if (options["dry-run"]) return { dryRun: true, action: command, payload: redact(payload) };
    const action = command === "ring" ? "ring" : payload.notificationMode === "quiet" ? "quiet" : "notify";
    return request(action, payload);
  }

  if (command === "stop") {
    const eventId = options["event-id"] || positional[1];
    if (!eventId) throw new Error("stop 需要 --event-id");
    const payload = { targetEventId: eventId };
    if (options["dry-run"]) return { dryRun: true, action: "stop", payload };
    return request("stop", payload);
  }

  if (command === "devices") throw new Error("直接 FCM 不提供遠端裝置清單");

  if (command === "report" || (command === "policy" && positional[1] === "inspect")) {
    const event = options.event || positional[1 + (command === "policy" ? 1 : 0)];
    if (!EVENT_NAMES.has(event)) throw new Error("report/policy inspect 需要有效 --event");
    const durationSeconds = options.duration === undefined ? undefined : numberOption(options.duration, "duration");
    const result = inspectPolicy({
      event,
      durationSeconds,
      durationReliable: options["duration-reliable"] === true,
      explicitAction: options.action,
      channelPolicy: runtime.user.channelPolicies?.[channel.channelId],
      globalPolicy: runtime.user.policy,
      repositoryPolicy: channel.policy,
      now: context.now,
    });
    if (command === "policy" || options["dry-run"] || result.action === "off") {
      return { dryRun: command === "report" && options["dry-run"] === true, channelId: channel.channelId, ...result };
    }
    const payload = {
      channelId: channel.channelId,
      channelName: channel.channelName,
      title: options.title || eventTitle(event),
      ...(options.message ? { message: options.message } : {}),
      tags: ["agent", event],
    };
    if (result.action === "ring") return { policy: result, ...(await request("ring", payload)) };
    return {
      policy: result,
      ...(await request(result.action === "quiet" ? "quiet" : "notify", { ...payload, notificationMode: result.action === "quiet" ? "quiet" : "normal" })),
    };
  }

  throw new Error(`未知日常命令: ${command || "(空白)"}`);
}

export function normalizeTags(values = []) {
  if (!Array.isArray(values)) values = [values];
  const tags = [...new Set(values.map((tag) => tag.trim()))];
  if (tags.some((tag) => !tag)) throw new Error("--tag 不可為空白");
  if (tags.length > 20) throw new Error("每個事件最多 20 個標籤");
  if (tags.some((tag) => [...tag].length > 100)) throw new Error("每個標籤最多 100 字");
  return tags;
}

export function listPresets() {
  return PRESETS;
}

export async function runChannelCommand(argv, { cwd = process.cwd() } = {}) {
  const { positional, options } = parseArgs(argv);
  const command = positional[0];
  if (command === "init") {
    const path = resolve(cwd, ".denden.json");
    const existing = await readJson(path, { required: false });
    if (existing) throw new Error(".denden.json 已存在");
    const value = createRepoConfig(options["channel-id"], options.name || "main");
    await writeRepoJson(path, value);
    return { path, ...value };
  }
  const repo = await findRepoConfig(cwd);
  const config = validateRepoConfig(repo.value);
  if (command === "list") return { defaultChannelId: config.defaultChannelId, channels: Object.values(config.channels) };
  let next;
  if (command === "add") next = addChannel(config, options["channel-id"], options.name);
  else if (command === "use") next = setDefaultChannel(config, options["channel-id"]);
  else if (command === "remove") next = removeChannel(config, options["channel-id"]);
  else throw new Error(`未知 channel 命令: ${command || "(空白)"}`);
  await writeRepoJson(repo.path, next);
  return { path: repo.path, defaultChannelId: next.defaultChannelId, channels: Object.values(next.channels) };
}

function numberOption(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`${name} 必須是非負數字`);
  return parsed;
}

function eventTitle(event) {
  return ({ completed: "Agent 工作完成", failed: "Agent 工作失敗", partial: "Agent 部分完成", blocked: "Agent 工作受阻", "needs-reply": "Agent 需要回覆", manual: "Agent 通知" })[event];
}
