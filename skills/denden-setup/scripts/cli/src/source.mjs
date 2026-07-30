import { createHash, randomUUID } from "node:crypto";
import { chmod, cp, lstat, mkdir, mkdtemp, readFile, readdir, rename, rm, stat, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { runExternal } from "./process.mjs";

const INSTALL_GUIDE_PATH = "docs/agent-install.md";
const OFFICIAL_OWNER = "hakendog";
const OFFICIAL_REPOSITORY = "DenDen";
export const RUNTIME_PROTOCOL = "direct-fcm-v2";
export const INSTALL_ALLOWLIST = [
  "cli",
  "integrations",
  "skills/denden",
  "skills/denden-setup",
  "docs/agent-install.md",
  "package.json",
  "package-lock.json",
];

export function parseInstallGuideUrl(value) {
  let url;
  try {
    url = new URL(String(value || ""));
  } catch {
    throw new Error("安裝指引 URL 無效");
  }
  if (url.protocol !== "https:" || url.hostname !== "raw.githubusercontent.com") {
    throw new Error("安裝指引必須使用 GitHub Raw HTTPS URL");
  }
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts.length !== 5 || parts.slice(3).join("/") !== INSTALL_GUIDE_PATH) {
    throw new Error(`安裝指引 URL 必須指向 ${INSTALL_GUIDE_PATH}`);
  }
  const [owner, repository, commitSha] = parts;
  if (owner.toLowerCase() !== OFFICIAL_OWNER || repository.toLowerCase() !== OFFICIAL_REPOSITORY.toLowerCase()) {
    throw new Error("安裝指引必須來自官方 hakendog/DenDen repository");
  }
  if (!/^[0-9a-f]{40}$/.test(commitSha)) throw new Error("安裝指引必須固定到 40 碼完整 commit SHA");
  return {
    owner,
    repository,
    commitSha,
    remoteUrl: `https://github.com/${owner}/${repository}.git`,
    guideUrl: url.href,
  };
}

export async function checkVerifiedSourceUpdate({ readmeUrl, currentCommitSha, fetchImpl = fetch }) {
  let url;
  try {
    url = new URL(String(readmeUrl || ""));
  } catch {
    throw new Error("官方 README URL 無效");
  }
  const path = url.pathname.split("/").filter(Boolean);
  if (url.protocol !== "https:" || url.hostname !== "raw.githubusercontent.com" || path.length < 4 || path.at(-1) !== "README.md") {
    throw new Error("版本檢查只接受官方 GitHub Raw README URL");
  }
  try {
    const response = await fetchImpl(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const readme = await response.text();
    const match = readme.match(/https:\/\/raw\.githubusercontent\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/[0-9a-f]{40}\/docs\/agent-install\.md/);
    if (!match) throw new Error("README 未包含固定完整 SHA 的官方安裝指引");
    const source = parseInstallGuideUrl(match[0]);
    if (source.owner.toLowerCase() !== path[0].toLowerCase() || source.repository.toLowerCase() !== path[1].toLowerCase()) {
      throw new Error("README 指向不同 repository 的安裝來源");
    }
    return {
      checked: true,
      existingVersionUsable: true,
      currentCommitSha: currentCommitSha || null,
      verifiedCommitSha: source.commitSha,
      updateAvailable: Boolean(currentCommitSha) && currentCommitSha.toLowerCase() !== source.commitSha,
      source,
    };
  } catch (error) {
    return {
      checked: false,
      existingVersionUsable: true,
      currentCommitSha: currentCommitSha || null,
      updateAvailable: null,
      reason: `暫時無法檢查已驗證版本：${error.message}`,
    };
  }
}

export function normalizeGitRemote(value) {
  const remote = String(value || "").trim().replace(/\\/g, "/").replace(/\.git\/?$/, "");
  const ssh = remote.match(/^git@github\.com:([^/]+)\/(.+)$/i);
  if (ssh) return `github.com/${ssh[1]}/${ssh[2]}`.toLowerCase();
  try {
    const url = new URL(remote);
    if (url.hostname.toLowerCase() !== "github.com") return remote.toLowerCase();
    return `github.com${url.pathname}`.replace(/\/$/, "").toLowerCase();
  } catch {
    return remote.toLowerCase();
  }
}

export async function inspectSourceCheckout(sourceRoot, expected, runner = runExternal) {
  const cwd = resolve(sourceRoot);
  const remoteUrl = (await runner("git", ["remote", "get-url", "origin"], { cwd })).stdout.trim();
  const commitSha = (await runner("git", ["rev-parse", "HEAD"], { cwd })).stdout.trim().toLowerCase();
  const worktree = (await runner("git", ["status", "--porcelain=v1", "--untracked-files=all"], { cwd })).stdout;
  const errors = [];
  if (normalizeGitRemote(remoteUrl) !== normalizeGitRemote(expected.remoteUrl)) errors.push("origin 不是核准的官方 repository");
  if (commitSha !== expected.commitSha.toLowerCase()) errors.push("目前 HEAD 與安裝指引固定的 commit SHA 不符");
  if (worktree.trim()) errors.push("來源 worktree 不乾淨");
  return { sourceRoot: cwd, remoteUrl, commitSha, clean: !worktree.trim(), verified: errors.length === 0, errors };
}

export async function acquireVerifiedSource(guideUrl, options = {}) {
  const source = parseInstallGuideUrl(guideUrl);
  const runner = options.runner || runExternal;
  const parent = options.tempParent ? resolve(options.tempParent) : tmpdir();
  await mkdir(parent, { recursive: true });
  const tempRoot = await (options.mkdtempImpl || mkdtemp)(join(parent, "denden-source-"));
  const checkout = join(tempRoot, "checkout");
  try {
    await runner("git", ["clone", "--no-checkout", "--filter=blob:none", source.remoteUrl, checkout], { cwd: tempRoot });
    await runner("git", ["checkout", "--detach", source.commitSha], { cwd: checkout });
    const inspection = await inspectSourceCheckout(checkout, source, runner);
    if (!inspection.verified) throw new Error(`DenDen 來源驗證失敗：${inspection.errors.join("；")}`);
    return { ...source, tempRoot, checkout, inspection };
  } catch (error) {
    await (options.rmImpl || rm)(tempRoot, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
}

export async function cleanupAcquiredSource(acquired, options = {}) {
  const target = resolve(acquired?.tempRoot || "");
  if (!basename(target).startsWith("denden-source-") || dirname(resolve(acquired.checkout)) !== target) {
    throw new Error("拒絕清理非 DenDen 暫存來源目錄");
  }
  await (options.rmImpl || rm)(target, { recursive: true, force: true });
}

export function userInstallRoot(env = process.env) {
  return resolve(env.DENDEN_INSTALL_ROOT || join(env.XDG_DATA_HOME || join(homedir(), ".local", "share"), "denden"));
}

export function buildUserInstallPlan(inspection, env = process.env) {
  if (!inspection?.verified || !/^[0-9a-f]{40}$/.test(inspection.commitSha)) throw new Error("只能安裝已驗證的 DenDen 來源");
  const root = userInstallRoot(env);
  return {
    sourceRoot: resolve(inspection.sourceRoot),
    sourceRemote: inspection.remoteUrl,
    commitSha: inspection.commitSha,
    versionDirectory: join(root, "versions", inspection.commitSha),
    currentPointer: join(root, "current.json"),
    files: [...INSTALL_ALLOWLIST],
  };
}

export async function installUserTools(plan, options = {}) {
  const root = dirname(dirname(plan.versionDirectory));
  const versionsRoot = join(root, "versions");
  const existing = await readMetadata(plan.versionDirectory);
  if (existing) {
    if (existing.commitSha !== plan.commitSha || normalizeGitRemote(existing.sourceRemote) !== normalizeGitRemote(plan.sourceRemote)) {
      throw new Error("目標版本目錄已存在衝突內容，拒絕覆寫");
    }
    await validateInstalledVersion(plan.versionDirectory);
    if (options.activate !== false) await activateUserTools(plan, options);
    return { installed: false, reused: true, versionDirectory: plan.versionDirectory, currentPointer: plan.currentPointer, launcher: join(root, "bin", "denden.mjs") };
  }

  await mkdir(versionsRoot, { recursive: true });
  const candidate = join(versionsRoot, `.${plan.commitSha}.candidate-${randomUUID()}`);
  try {
    await mkdir(candidate, { recursive: false });
    for (const relative of plan.files) {
      const from = join(plan.sourceRoot, relative);
      const to = join(candidate, relative);
      if (!(await exists(from))) throw new Error(`來源缺少安裝檔案：${relative}`);
      await mkdir(dirname(to), { recursive: true });
      await (options.cpImpl || cp)(from, to, { recursive: true, errorOnExist: true, force: false });
    }
    const metadata = {
      schemaVersion: 3,
      commitSha: plan.commitSha,
      sourceRemote: plan.sourceRemote,
      runtimeProtocol: RUNTIME_PROTOCOL,
      contentManifest: await buildContentManifest(candidate),
    };
    await writeFile(join(candidate, "source.json"), `${JSON.stringify(metadata, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    const runner = options.runner || runExternal;
    await runner("npm", ["ci", "--omit=dev", "--ignore-scripts"], { cwd: candidate });
    await validateInstalledVersion(candidate);
    await rename(candidate, plan.versionDirectory);
    if (options.activate !== false) await activateUserTools(plan, options);
    return { installed: true, reused: false, versionDirectory: plan.versionDirectory, currentPointer: plan.currentPointer, launcher: join(root, "bin", "denden.mjs") };
  } catch (error) {
    await rm(candidate, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
}

export async function activateUserTools(plan, options = {}) {
  const root = dirname(dirname(plan.versionDirectory));
  await validateInstalledVersion(plan.versionDirectory);
  await writeLaunchers(root);
  await switchCurrent(plan, options);
  return { activated: true, commitSha: plan.commitSha, currentPointer: plan.currentPointer };
}

export async function rollbackUserTools(env = process.env, options = {}) {
  const root = userInstallRoot(env);
  const currentPointer = join(root, "current.json");
  const previousPointer = join(root, "previous.json");
  const current = await readPointer(currentPointer, root);
  const previous = await readPointer(previousPointer, root);
  if (!current) throw new Error("DenDen 尚未啟用任何持久版本");
  if (!previous) throw new Error("沒有可回復的上一個 DenDen 版本");
  await validateInstalledVersion(previous.versionDirectory);
  await writeLaunchers(root);
  await switchCurrent({
    currentPointer,
    commitSha: previous.commitSha,
    versionDirectory: previous.versionDirectory,
  }, options);
  return {
    rolledBack: true,
    fromCommitSha: current.commitSha,
    commitSha: previous.commitSha,
    versionDirectory: previous.versionDirectory,
    launcher: join(root, "bin", "denden.mjs"),
  };
}

async function switchCurrent(plan, options) {
  const root = dirname(plan.currentPointer);
  await mkdir(root, { recursive: true });
  const value = { schemaVersion: 1, commitSha: plan.commitSha, versionDirectory: plan.versionDirectory };
  await (options.validateImpl || validateInstalledVersion)(plan.versionDirectory);
  const current = await readPointer(plan.currentPointer, root);
  if (current?.commitSha === value.commitSha && current.versionDirectory === value.versionDirectory) return;
  if (current) await writePointerAtomic(join(root, "previous.json"), current);
  await writePointerAtomic(plan.currentPointer, value);
}

async function validateInstalledVersion(root) {
  const metadata = await readMetadata(root);
  if (!metadata || metadata.schemaVersion !== 3 || metadata.runtimeProtocol !== RUNTIME_PROTOCOL ||
      !/^[0-9a-f]{40}$/.test(metadata.commitSha) ||
      !metadata.contentManifest || typeof metadata.contentManifest !== "object" || Array.isArray(metadata.contentManifest)) {
    throw new Error("安裝版本 metadata 或執行協定無效");
  }
  const actualManifest = await buildContentManifest(root);
  if (JSON.stringify(actualManifest) !== JSON.stringify(metadata.contentManifest)) {
    throw new Error("安裝版本內容雜湊不符，拒絕啟用或回復");
  }
  for (const relative of ["cli/bin/denden.mjs", "skills/denden-setup/SKILL.md", "skills/denden/SKILL.md", "node_modules/qrcode/package.json", "package.json"]) {
    if (!(await exists(join(root, relative)))) throw new Error(`安裝版本缺少必要檔案：${relative}`);
  }
}

async function writeLaunchers(root) {
  const bin = join(root, "bin");
  await mkdir(bin, { recursive: true });
  const launcher = `#!/usr/bin/env node\nimport { readFile } from "node:fs/promises";\nimport { basename, dirname, join, resolve } from "node:path";\nimport { fileURLToPath, pathToFileURL } from "node:url";\nconst root = dirname(dirname(fileURLToPath(import.meta.url)));\nconst current = JSON.parse(await readFile(join(root, "current.json"), "utf8"));\nconst versionsRoot = resolve(root, "versions");\nconst versionDirectory = resolve(String(current.versionDirectory || ""));\nconst sameParent = process.platform === "win32" ? dirname(versionDirectory).toLowerCase() === versionsRoot.toLowerCase() : dirname(versionDirectory) === versionsRoot;\nif (current.schemaVersion !== 1 || !/^[0-9a-f]{40}$/.test(current.commitSha || "") || !sameParent || basename(versionDirectory).toLowerCase() !== current.commitSha.toLowerCase()) throw new Error("DenDen 版本指標無效");\nconst metadata = JSON.parse(await readFile(join(versionDirectory, "source.json"), "utf8"));\nif (metadata.schemaVersion !== 3 || metadata.runtimeProtocol !== "${RUNTIME_PROTOCOL}" || metadata.commitSha !== current.commitSha) throw new Error("DenDen 持久版本驗證或執行協定失敗");\nprocess.argv[1] = join(versionDirectory, "cli", "bin", "denden.mjs");\nawait import(pathToFileURL(process.argv[1]).href);\n`;
  const launcherPath = join(bin, "denden.mjs");
  await writeFile(launcherPath, launcher, { encoding: "utf8", mode: 0o700 });
  await chmod(launcherPath, 0o700).catch(() => {});
  await writeFile(join(bin, "denden.cmd"), "@echo off\r\nnode \"%~dp0denden.mjs\" %*\r\n", "utf8");
}

async function readMetadata(versionDirectory) {
  try {
    return JSON.parse(await readFile(join(versionDirectory, "source.json"), "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw new Error(`無法驗證既有 DenDen 安裝：${error.message}`);
  }
}

async function readPointer(path, root) {
  let value;
  try {
    value = JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw new Error(`DenDen 版本指標無法讀取：${error.message}`);
  }
  const versionDirectory = resolve(String(value?.versionDirectory || ""));
  const versionsRoot = resolve(root, "versions");
  if (value?.schemaVersion !== 1 || !/^[0-9a-f]{40}$/.test(value?.commitSha || "") ||
      !samePath(dirname(versionDirectory), versionsRoot) ||
      basename(versionDirectory).toLowerCase() !== value.commitSha.toLowerCase()) {
    throw new Error("DenDen 版本指標格式或路徑無效");
  }
  return { schemaVersion: 1, commitSha: value.commitSha, versionDirectory };
}

async function buildContentManifest(root) {
  const entries = [];
  for (const relative of INSTALL_ALLOWLIST) await collectContentFiles(root, relative, entries);
  entries.sort(([left], [right]) => left.localeCompare(right));
  return Object.fromEntries(entries);
}

async function collectContentFiles(root, relative, entries) {
  const absolute = join(root, relative);
  const info = await lstat(absolute);
  if (info.isSymbolicLink()) throw new Error(`安裝內容不得包含符號連結：${relative}`);
  if (info.isDirectory()) {
    const children = await readdir(absolute);
    children.sort();
    for (const child of children) await collectContentFiles(root, join(relative, child), entries);
    return;
  }
  if (!info.isFile()) throw new Error(`安裝內容不是一般檔案：${relative}`);
  const key = relative.replace(/\\/g, "/");
  entries.push([key, createHash("sha256").update(await readFile(absolute)).digest("hex")]);
}

function samePath(left, right) {
  return process.platform === "win32"
    ? left.toLowerCase() === right.toLowerCase()
    : left === right;
}

async function writePointerAtomic(path, value) {
  const temporary = `${path}.tmp-${randomUUID()}`;
  try {
    await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
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
