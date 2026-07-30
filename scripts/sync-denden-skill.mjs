import { cp, lstat, mkdir, readFile, readdir, rm, rename } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { hashTree } from "../cli/src/integration-install.mjs";

const repoRoot = resolve(import.meta.dirname, "..");
export const DAILY_RUNTIME_FILES = [
  "commands.mjs",
  "config.mjs",
  "direct-fcm-protocol.mjs",
  "fcm-client.mjs",
  "policy.mjs",
  "process.mjs",
];
export const SETUP_RUNTIME_FILES = [
  "cli/bin/denden.mjs",
  "cli/src/commands.mjs",
  "cli/src/config.mjs",
  "cli/src/direct-branding.mjs",
  "cli/src/direct-fcm-protocol.mjs",
  "cli/src/direct-image.mjs",
  "cli/src/direct-setup.mjs",
  "cli/src/fcm-client.mjs",
  "cli/src/integration-install.mjs",
  "cli/src/integration.mjs",
  "cli/src/policy.mjs",
  "cli/src/portable-config.mjs",
  "cli/src/process.mjs",
  "cli/src/setup-operations.mjs",
  "cli/src/setup-plan.mjs",
  "cli/src/source.mjs",
  "package.json",
  "package-lock.json",
];

export async function syncDendenSkillRuntime({ sourceRoot = repoRoot, write = false } = {}) {
  const source = resolve(sourceRoot, "cli", "src");
  const target = resolve(sourceRoot, "skills", "denden", "scripts", "runtime");
  const current = await isPhysicalDirectory(target) && await sameRuntime(source, target);
  if (current) return "current";
  if (!write) return "drift";

  const temporary = join(dirname(target), `.runtime-sync-${randomUUID()}`);
  try {
    await mkdir(temporary, { recursive: false });
    for (const file of DAILY_RUNTIME_FILES) {
      await cp(join(source, file), join(temporary, file), { dereference: true, errorOnExist: true, force: false });
    }
    if (!await sameRuntime(source, temporary)) throw new Error("DenDen skill CLI 暫存內容驗證失敗");
    await rm(target, { recursive: true, force: true });
    await rename(temporary, target);
  } catch (error) {
    await rm(temporary, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
  return "synced";
}

export async function syncDendenSetupSkillRuntime({ sourceRoot = repoRoot, write = false } = {}) {
  const target = resolve(sourceRoot, "skills", "denden-setup", "scripts");
  const current = await isPhysicalDirectory(target) && await sameSetupRuntime(sourceRoot, target);
  if (current) return "current";
  if (!write) return "drift";

  const temporary = join(dirname(target), `.setup-runtime-sync-${randomUUID()}`);
  try {
    await mkdir(temporary, { recursive: false });
    for (const file of SETUP_RUNTIME_FILES) {
      const destination = join(temporary, file);
      await mkdir(dirname(destination), { recursive: true });
      await cp(join(sourceRoot, file), destination, { dereference: true, errorOnExist: true, force: false });
    }
    await mkdir(join(temporary, "skills"), { recursive: true });
    await cp(resolve(sourceRoot, "skills", "denden"), join(temporary, "skills", "denden"), { recursive: true, dereference: true, errorOnExist: true, force: false });
    if (!await sameSetupRuntime(sourceRoot, temporary)) throw new Error("DenDen 設定技能執行檔暫存內容驗證失敗");
    await rm(target, { recursive: true, force: true });
    await rename(temporary, target);
  } catch (error) {
    await rm(temporary, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
  return "synced";
}

export async function syncDendenSkillMirror({
  sourceRoot = repoRoot,
  agentSkillsRoot = resolve(sourceRoot, "..", "agent-skills"),
  write = false,
} = {}) {
  const source = resolve(sourceRoot, "skills", "denden");
  const target = resolve(agentSkillsRoot, "skills", "denden");
  if (source === target) throw new Error("DenDen skill 來源與鏡像不可相同");
  await requirePhysicalDirectory(source, "DenDen skill 來源");
  await requireGitRepository(agentSkillsRoot);

  const current = await isPhysicalDirectory(target) && await hashTree(source) === await hashTree(target);
  if (current) return "current";
  if (!write) return "drift";

  await mkdir(dirname(target), { recursive: true });
  const temporary = join(dirname(target), `.denden-sync-${randomUUID()}`);
  try {
    await cp(source, temporary, { recursive: true, dereference: true, errorOnExist: true, force: false });
    if (await hashTree(source) !== await hashTree(temporary)) throw new Error("DenDen skill 暫存鏡像驗證失敗");
    await rm(target, { recursive: true, force: true });
    await rename(temporary, target);
  } catch (error) {
    await rm(temporary, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
  return "synced";
}

async function requireGitRepository(root) {
  if (!await exists(join(root, ".git"))) throw new Error(`agent-skills 不是 Git checkout: ${root}`);
}

async function sameRuntime(source, target) {
  const entries = await readdir(target, { withFileTypes: true });
  if (entries.some((entry) => !entry.isFile()) || entries.map((entry) => entry.name).sort().join("\n") !== DAILY_RUNTIME_FILES.join("\n")) return false;
  for (const file of DAILY_RUNTIME_FILES) {
    if (!await exists(join(source, file)) || !Buffer.from(await readFile(join(source, file))).equals(await readFile(join(target, file)))) return false;
  }
  return true;
}

async function sameSetupRuntime(sourceRoot, target) {
  const dailySource = resolve(sourceRoot, "skills", "denden");
  if (!await isPhysicalDirectory(join(target, "skills", "denden")) || await hashTree(dailySource) !== await hashTree(join(target, "skills", "denden"))) return false;
  const expected = [...SETUP_RUNTIME_FILES, ...(await listFiles(dailySource)).map((file) => `skills/denden/${file}`)].sort();
  if ((await listFiles(target)).sort().join("\n") !== expected.join("\n")) return false;
  for (const file of SETUP_RUNTIME_FILES) {
    if (!Buffer.from(await readFile(join(sourceRoot, file))).equals(await readFile(join(target, file)))) return false;
  }
  return true;
}

async function listFiles(root, prefix = "") {
  const files = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) files.push(...await listFiles(join(root, entry.name), relative));
    else if (entry.isFile()) files.push(relative);
    else throw new Error(`技能內容不得包含符號連結或特殊檔案: ${join(root, entry.name)}`);
  }
  return files;
}

async function requirePhysicalDirectory(path, label) {
  if (!await isPhysicalDirectory(path)) throw new Error(`${label}必須是實體目錄: ${path}`);
}

async function isPhysicalDirectory(path) {
  try {
    const entry = await lstat(path);
    return entry.isDirectory() && !entry.isSymbolicLink();
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

async function exists(path) {
  try {
    await lstat(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [mode = "--check", targetRoot] = process.argv.slice(2);
    if (!["--check", "--write"].includes(mode) || process.argv.length > 4) {
      throw new Error("用法: node scripts/sync-denden-skill.mjs [--check|--write] [agent-skills-root]");
    }
    const runtime = await syncDendenSkillRuntime({ write: mode === "--write" });
    const setupRuntime = await syncDendenSetupSkillRuntime({ write: mode === "--write" });
    const defaultAgentSkillsRoot = resolve(repoRoot, "..", "agent-skills");
    const mirror = targetRoot || await exists(join(defaultAgentSkillsRoot, ".git"))
      ? await syncDendenSkillMirror({
          agentSkillsRoot: targetRoot ? resolve(targetRoot) : defaultAgentSkillsRoot,
          write: mode === "--write",
        })
      : "unavailable";
    if ([runtime, setupRuntime, mirror].includes("drift")) {
      console.error("DenDen 技能執行檔或鏡像不同步；執行 npm run sync:denden-skill");
      process.exitCode = 1;
    } else {
      console.log([runtime, setupRuntime, mirror].includes("synced") ? "DenDen 技能執行檔與鏡像已同步" : "DenDen 技能執行檔與鏡像一致");
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
