import { createHash, randomUUID } from "node:crypto";
import { cp, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { readJson, writePrivateJson } from "./config.mjs";
import { validateDirectFcmSenderConfig } from "./fcm-client.mjs";
import { validatePolicy } from "./policy.mjs";
import { userInstallRoot } from "./source.mjs";

const SUPPORTED = new Set(["codex", "claude", "gemini", "unsupported"]);
const MARKER_START = "<!-- denden:integration:start -->";
const MARKER_END = "<!-- denden:integration:end -->";

export function buildDailySkillInstallPlan({
  sourceRoot,
  choice,
  destination,
  agentType = "codex",
  sourceDigest = null,
  configPath = null,
  notificationPolicy = null,
  env = process.env,
}) {
  if (!new Set(["global", "specified", "skip"]).has(choice)) throw new Error("日常技能安裝選擇無效");
  const policy = choice === "skip" ? null : notificationPolicy;
  validatePolicy(policy, "日常通知");
  const source = join(resolve(sourceRoot), "skills", "denden");
  const persistentCli = join(userInstallRoot(env), "bin", "denden.mjs");
  let target = null;
  if (choice === "global") {
    if (agentType === "codex") target = resolve(env.CODEX_HOME || join(homedir(), ".codex"), "skills", "denden");
    else if (agentType === "claude") target = resolve(env.CLAUDE_HOME || join(homedir(), ".claude"), "skills", "denden");
    else if (agentType === "gemini") target = resolve(env.GEMINI_HOME || join(homedir(), ".gemini"), "skills", "denden");
    else throw new Error("無法判定此 Agent 的全域技能位置；請改用指定位置");
  }
  if (choice === "specified") {
    if (typeof destination !== "string" || !destination.trim()) throw new Error("指定技能位置無效");
    target = resolve(destination);
  }
  const value = {
    schemaVersion: 1,
    choice,
    agentType,
    source,
    destination: target,
    persistentCli,
    sourceDigest,
    configPath: choice === "skip" || !configPath ? null : resolve(configPath),
    notificationPolicy: policy,
    files: choice === "skip" ? [] : ["SKILL.md", "agents/openai.yaml"],
    behavior: choice === "skip"
      ? "略過日常技能安裝"
      : `安裝低權限 denden 日常回報技能${policy ? "並套用摘要中的通知政策" : ""}；不包含 setup、Google 登入、IAM、配對或品牌權限`,
  };
  return { ...value, digest: digest(value) };
}

export async function installDailySkill(plan, options = {}) {
  const expected = digest(Object.fromEntries(Object.entries(plan).filter(([key]) => key !== "digest")));
  if (plan.digest !== expected || options.approvedDigest !== plan.digest) throw new Error("日常技能安裝摘要尚未取得明確同意，或摘要已改變");
  if (plan.choice === "skip") return { installed: false, skipped: true, destination: null };
  if (!(await exists(plan.source))) throw new Error(`DenDen 日常技能來源不存在: ${plan.source}`);
  if (plan.sourceDigest && await hashTree(plan.source) !== plan.sourceDigest) throw new Error("日常技能來源內容已改變；請重新產生初次設定摘要");
  let nextConfig = null;
  if (plan.notificationPolicy) {
    if (!plan.configPath) throw new Error("日常通知政策缺少受保護的使用者設定路徑");
    const current = validateDirectFcmSenderConfig(await readJson(plan.configPath));
    nextConfig = validateDirectFcmSenderConfig({ ...current, policy: plan.notificationPolicy });
  }
  const changed = await copyDirectoryOnce(plan.source, plan.destination, options);
  if (!changed && !(await sameTree(plan.source, plan.destination))) throw new Error(`目的地已有不同版本，拒絕覆寫: ${plan.destination}`);
  if (nextConfig) await (options.writeNotificationPolicy || writePrivateJson)(plan.configPath, nextConfig);
  return { installed: true, reused: !changed, destination: plan.destination, persistentCli: plan.persistentCli };
}

export async function detectRepositoryAgent(repositoryRoot, options = {}) {
  if (options.agentType) {
    if (!SUPPORTED.has(options.agentType)) throw new Error(`不支援的 Agent 類型: ${options.agentType}`);
    return { agentType: options.agentType, detected: false };
  }
  const root = resolve(repositoryRoot);
  const candidates = [];
  if (await exists(join(root, ".claude"))) candidates.push("claude");
  if (await exists(join(root, ".gemini"))) candidates.push("gemini");
  if (await exists(join(root, ".codex")) || await exists(join(root, "AGENTS.md"))) candidates.push("codex");
  const unique = [...new Set(candidates)];
  if (unique.length > 1) throw new Error(`偵測到多種 Agent 設定（${unique.join("、")}）；請讓使用者明確選擇`);
  return { agentType: unique[0] || "unsupported", detected: unique.length === 1 };
}

export async function buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot, agentType, env = process.env }) {
  const repository = resolve(repositoryRoot);
  const source = resolve(sourceRoot);
  const installRoot = userInstallRoot(env);
  const persistentCli = join(installRoot, "bin", "denden.mjs");
  const hookCommand = `${quoteShellPath(join(installRoot, "bin", process.platform === "win32" ? "denden.cmd" : "denden.mjs"))} integration hook`;
  const selected = await detectRepositoryAgent(repository, { agentType });
  const files = [];
  let behavior;
  if (selected.agentType === "codex") {
    const destination = resolve(env.CODEX_HOME || join(homedir(), ".codex"), "skills", "denden");
    files.push({ source: join(source, "skills", "denden"), destination, operation: "copy-directory" });
    behavior = "安裝使用者層級低權限 denden 技能；由技能將標準工作結果交給共用 CLI。";
  } else if (selected.agentType === "claude") {
    files.push(
      { source: join(source, "integrations", "claude-code", "settings.example.json"), destination: join(repository, ".claude", "settings.json"), operation: "merge-json", hookCommand },
      { source: join(source, "integrations", "claude-code", "CLAUDE.snippet.md"), destination: join(repository, "CLAUDE.md"), operation: "append-marked-block" },
    );
    behavior = "Claude Code 完成回覆時執行低權限 Stop hook；只處理 Agent 明確留下的標準事件標記。";
  } else if (selected.agentType === "gemini") {
    files.push(
      { source: join(source, "integrations", "gemini-cli", "settings.example.json"), destination: join(repository, ".gemini", "settings.json"), operation: "merge-json", hookCommand },
      { source: join(source, "integrations", "gemini-cli", "GEMINI.snippet.md"), destination: join(repository, "GEMINI.md"), operation: "append-marked-block" },
    );
    behavior = "Gemini CLI 完成回覆時執行低權限 AfterAgent hook；只處理 Agent 明確留下的標準事件標記。";
  } else {
    behavior = "未辨識支援的 Agent；只建立 .denden.json，並提供手動執行 denden report 的說明。";
  }
  for (const file of files) await inspectIntegrationFile(file);
  const base = {
    schemaVersion: 1,
    agentType: selected.agentType,
    repositoryRoot: repository,
    sourceRoot: source,
    persistentCli,
    files,
    behavior,
    credentialFiles: [],
    manualOnly: selected.agentType === "unsupported",
  };
  return { ...base, digest: digest(base) };
}

export async function installRepositoryIntegration(plan, options = {}) {
  if (options.approvedDigest !== plan.digest) throw new Error("日常 DenDen 整合摘要尚未取得明確同意，或摘要已改變");
  for (const file of plan.files) await inspectIntegrationFile(file);
  const changedFiles = [];
  for (const file of plan.files) {
    const changed = file.operation === "copy-directory"
      ? await copyDirectoryOnce(file.source, file.destination)
      : file.operation === "merge-json"
        ? await mergeHookSettings(file)
        : await appendMarkedSnippet(file.source, file.destination);
    if (changed) changedFiles.push(file.destination);
  }
  return {
    installed: !plan.manualOnly,
    agentType: plan.agentType,
    changedFiles,
    manualInstructions: plan.manualOnly ? [`在重要工作完成時，明確呼叫 node ${quoteShellPath(plan.persistentCli)} report --event <standard-event>。`] : [],
  };
}

async function inspectIntegrationFile(file) {
  if (!(await exists(file.source))) throw new Error(`DenDen 整合來源不存在: ${file.source}`);
  if (!(await exists(file.destination))) return;
  if (file.operation === "copy-directory") {
    if (await sameTree(file.source, file.destination)) return;
    throw new Error(`目的地已有不同版本，拒絕覆寫: ${file.destination}`);
  }
  if (file.operation === "merge-json") {
    const existing = await readJson(file.destination);
    const desired = await desiredHookSettings(file);
    assertNoHookConflict(existing, desired, file.destination);
    return;
  }
  const current = await readFile(file.destination, "utf8");
  const block = await markedBlock(file.source);
  if (!current.includes(MARKER_START)) return;
  if (!current.includes(block)) throw new Error(`目的地已有不同 DenDen 指引區塊，拒絕覆寫: ${file.destination}`);
}

async function mergeHookSettings(file) {
  const desired = await desiredHookSettings(file);
  const existing = await readJson(file.destination, { required: false }) || {};
  assertNoHookConflict(existing, desired, file.destination);
  const event = Object.keys(desired.hooks)[0];
  const desiredGroup = desired.hooks[event][0];
  const groups = existing.hooks?.[event] || [];
  const desiredCommand = hookCommands(desiredGroup)[0];
  if (groups.some((group) => hookCommands(group).includes(desiredCommand))) return false;
  let migrated = false;
  const nextGroups = groups.map((group) => {
    if (!hookCommands(group).includes("denden integration hook")) return group;
    migrated = true;
    return { ...group, hooks: group.hooks.map((hook) => hook?.command === "denden integration hook" ? { ...hook, command: desiredCommand } : hook) };
  });
  if (!migrated) nextGroups.push(desiredGroup);
  const value = {
    ...existing,
    hooks: {
      ...(existing.hooks || {}),
      [event]: nextGroups,
    },
  };
  await writeTextAtomic(file.destination, `${JSON.stringify(value, null, 2)}\n`);
  return true;
}

function assertNoHookConflict(existing, desired, destination) {
  const event = Object.keys(desired.hooks)[0];
  const groups = existing?.hooks?.[event];
  if (groups !== undefined && !Array.isArray(groups)) throw new Error(`既有 hook 格式無效，拒絕修改: ${destination}`);
  const dendenCommands = (groups || []).flatMap(hookCommands).filter((command) => /denden/i.test(command));
  const allowed = new Set(["denden integration hook", ...desired.hooks[event].flatMap(hookCommands)]);
  if (dendenCommands.some((command) => !allowed.has(command))) {
    throw new Error(`既有 DenDen hook 命令不同，拒絕覆寫: ${destination}`);
  }
}

async function desiredHookSettings(file) {
  const desired = await readJson(file.source);
  if (!file.hookCommand) return desired;
  const event = Object.keys(desired.hooks)[0];
  desired.hooks[event][0].hooks[0].command = file.hookCommand;
  return desired;
}

function quoteShellPath(value) {
  if (process.platform === "win32") {
    if (value.includes('"')) throw new Error("DenDen 啟動器路徑含無法安全引用的字元");
    return `"${value}"`;
  }
  return `'${value.replaceAll("'", "'\"'\"'")}'`;
}

function hookCommands(group) {
  return Array.isArray(group?.hooks) ? group.hooks.map((hook) => hook?.command).filter((value) => typeof value === "string") : [];
}

async function appendMarkedSnippet(source, destination) {
  const block = await markedBlock(source);
  const existing = await readFile(destination, "utf8").catch((error) => {
    if (error?.code === "ENOENT") return "";
    throw error;
  });
  if (existing.includes(block)) return false;
  if (existing.includes(MARKER_START) || existing.includes(MARKER_END)) throw new Error(`目的地已有不完整或不同 DenDen 指引區塊: ${destination}`);
  const prefix = existing && !existing.endsWith("\n") ? "\n\n" : existing ? "\n" : "";
  await writeTextAtomic(destination, `${existing}${prefix}${block}\n`);
  return true;
}

async function markedBlock(source) {
  const snippet = (await readFile(source, "utf8")).trim();
  return `${MARKER_START}\n${snippet}\n${MARKER_END}`;
}

async function copyDirectoryOnce(source, destination, options = {}) {
  if (await exists(destination)) return false;
  await mkdir(dirname(destination), { recursive: true });
  const temporary = join(dirname(destination), `.${basename(destination)}.tmp-${randomUUID()}`);
  try {
    await (options.copyDirectory || cp)(source, temporary, { recursive: true, force: false, errorOnExist: true, dereference: true });
    await (options.rename || rename)(temporary, destination);
    return true;
  } catch (error) {
    await rm(temporary, { recursive: true, force: true }).catch(() => {});
    if (await exists(destination)) return false;
    throw error;
  }
}

async function sameTree(left, right) {
  return (await hashTree(left)) === (await hashTree(right));
}

export async function hashTree(root) {
  const { readdir } = await import("node:fs/promises");
  const entries = await readdir(root, { recursive: true, withFileTypes: true });
  const files = entries.filter((entry) => entry.isFile()).map((entry) => join(entry.parentPath || entry.path, entry.name)).sort();
  const hash = createHash("sha256");
  for (const path of files) {
    hash.update(path.slice(resolve(root).length).replaceAll("\\", "/"));
    hash.update(await readFile(path));
  }
  return hash.digest("hex");
}

async function writeTextAtomic(path, value) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${randomUUID()}`;
  try {
    await writeFile(temporary, value, { encoding: "utf8", flag: "wx" });
    await rename(temporary, path);
  } catch (error) {
    await rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

function digest(value) {
  return createHash("sha256").update(stable(value)).digest("hex");
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  return JSON.stringify(value);
}
