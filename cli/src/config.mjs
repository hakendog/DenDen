import { chmod, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname, join, resolve } from "node:path";
import { homedir } from "node:os";
import { spawn } from "node:child_process";

export function userConfigPath(env = process.env) {
  return resolve(env.DENDEN_CONFIG_PATH || join(env.XDG_CONFIG_HOME || join(homedir(), ".config"), "denden", "config.json"));
}

export async function readJson(path, { required = true } = {}) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (!required && error?.code === "ENOENT") return null;
    throw new Error(`無法讀取設定 ${path}: ${error.message}`);
  }
}

export async function writePrivateJson(path, value) {
  return writePrivateFile(path, `${JSON.stringify(value, null, 2)}\n`);
}

export async function writePrivateFile(path, value) {
  await ensurePrivateDirectory(dirname(path));
  return writeHardenedFile(path, value);
}

export async function writeProtectedExportFile(path, value) {
  const parent = await stat(dirname(path));
  if (!parent.isDirectory()) throw new Error(`匯出位置的父路徑不是目錄: ${dirname(path)}`);
  return writeHardenedFile(path, value);
}

async function writeHardenedFile(path, value) {
  const temporary = `${path}.tmp-${randomUUID()}`;
  try {
    await writeFile(temporary, value, { mode: 0o600, flag: "wx" });
    await hardenPrivateFile(temporary);
    await assertPrivateFile(temporary);
    await rename(temporary, path);
    await hardenPrivateFile(path);
    await assertPrivateFile(path);
  } catch (error) {
    await rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
}

export async function ensurePrivateDirectory(path) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  if (process.platform === "win32") {
    await applyWindowsPrivateAcl(path, true);
  } else {
    await removeMacAcl(path);
    await chmod(path, 0o700);
  }
  return assertPrivateDirectory(path);
}

export async function assertPrivateDirectory(path) {
  const info = await stat(path);
  if (!info.isDirectory()) throw new Error(`私密設定位置不是目錄: ${path}`);
  if (process.platform === "win32") {
    await verifyWindowsPrivateAcl(path, true);
  } else if ((info.mode & 0o077) !== 0) {
    throw new Error(`私密設定目錄權限過寬: ${path}`);
  } else {
    await assertNoMacAcl(path);
  }
  return true;
}

export async function assertPrivateFile(path) {
  const info = await stat(path);
  if (!info.isFile()) throw new Error(`私密設定不是一般檔案: ${path}`);
  if (process.platform === "win32") {
    await verifyWindowsPrivateAcl(path, false);
  } else if ((info.mode & 0o077) !== 0) {
    throw new Error(`私密設定權限過寬: ${path}`);
  } else {
    await assertNoMacAcl(path);
  }
  return true;
}

async function hardenPrivateFile(path) {
  if (process.platform !== "win32") {
    await removeMacAcl(path);
    return chmod(path, 0o600);
  }
  await applyWindowsPrivateAcl(path, false);
}

async function removeMacAcl(path) {
  if (process.platform === "darwin") await runNative("/bin/chmod", ["-N", path]);
}

async function assertNoMacAcl(path) {
  if (process.platform !== "darwin") return;
  const { stdout } = await runNative("/bin/ls", ["-lde", path]);
  if (stdout.trimStart().split(/\s+/, 1)[0].endsWith("+") || /\n\s*\d+:/.test(stdout)) {
    throw new Error(`私密設定仍含延伸 ACL: ${path}`);
  }
}

async function applyWindowsPrivateAcl(path, isDirectory) {
  await runWindowsAclScript(path, isDirectory, "set");
}

async function verifyWindowsPrivateAcl(path, isDirectory) {
  await runWindowsAclScript(path, isDirectory, "verify");
}

async function runWindowsAclScript(path, isDirectory, mode) {
  const script = `
$ErrorActionPreference = 'Stop'
$target = [Environment]::GetEnvironmentVariable('DENDEN_ACL_TARGET')
$kind = [Environment]::GetEnvironmentVariable('DENDEN_ACL_KIND')
$mode = [Environment]::GetEnvironmentVariable('DENDEN_ACL_MODE')
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$isDirectory = $kind -eq 'directory'
if ($mode -eq 'set') {
  if ($isDirectory) {
    $acl = [System.Security.AccessControl.DirectorySecurity]::new()
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
  } else {
    $acl = [System.Security.AccessControl.FileSecurity]::new()
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::None
  }
  $acl.SetAccessRuleProtection($true, $false)
  $acl.SetOwner($identity)
  $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $identity,
    [System.Security.AccessControl.FileSystemRights]::FullControl,
    $inheritance,
    [System.Security.AccessControl.PropagationFlags]::None,
    [System.Security.AccessControl.AccessControlType]::Allow
  )
  $acl.AddAccessRule($rule) | Out-Null
  if ($isDirectory) {
    [System.IO.Directory]::SetAccessControl($target, $acl)
  } else {
    [System.IO.File]::SetAccessControl($target, $acl)
  }
}
$actual = if ($isDirectory) {
  [System.IO.Directory]::GetAccessControl($target)
} else {
  [System.IO.File]::GetAccessControl($target)
}
if (-not $actual.AreAccessRulesProtected) { throw 'ACL 仍允許繼承' }
$rules = @($actual.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
if ($rules.Count -ne 1) { throw 'ACL 不只包含目前使用者' }
$only = $rules[0]
if ($only.IdentityReference.Value -ne $identity.Value -or
    $only.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow -or
    ($only.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::FullControl) -ne [System.Security.AccessControl.FileSystemRights]::FullControl) {
  throw 'ACL 未限定目前使用者完整控制'
}
`;
  const env = {
    ...process.env,
    DENDEN_ACL_TARGET: path,
    DENDEN_ACL_KIND: isDirectory ? "directory" : "file",
    DENDEN_ACL_MODE: mode,
  };
  await runNative("powershell.exe", [
    "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
    Buffer.from(script, "utf16le").toString("base64"),
  ], { env });
}

async function runNative(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
      env: options.env || process.env,
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? resolvePromise({ stdout, stderr }) : reject(new Error(`${command} 失敗（exit ${code}）: ${stderr.trim()}`)));
  });
}

export async function writeRepoJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

export function createRepoConfig(channelId = randomUUID(), channelName) {
  const id = channelId.trim();
  const name = String(channelName || "").trim();
  if (!id || !name) throw new Error("Channel ID 與名稱不得空白");
  return { defaultChannelId: id, channels: { [id]: { channelId: id, channelName: name } } };
}

export function addChannel(value, channelId, channelName) {
  const config = validateRepoConfig(value);
  const id = String(channelId || "").trim();
  const name = String(channelName || "").trim();
  if (!id || !name) throw new Error("Channel ID 與名稱不得空白");
  if (config.channels[id]) throw new Error(`Channel 已存在: ${id}`);
  return { ...config, channels: { ...config.channels, [id]: { channelId: id, channelName: name } } };
}

export function setDefaultChannel(value, channelId) {
  const config = validateRepoConfig(value);
  if (!config.channels[channelId]) throw new Error(`找不到 Channel: ${channelId}`);
  return { ...config, defaultChannelId: channelId };
}

export function removeChannel(value, channelId) {
  const config = validateRepoConfig(value);
  if (!config.channels[channelId]) throw new Error(`找不到 Channel: ${channelId}`);
  if (config.defaultChannelId === channelId) throw new Error("不得刪除 defaultChannelId 指向的 Channel；請先切換預設 Channel");
  const channels = { ...config.channels };
  delete channels[channelId];
  return validateRepoConfig({ ...config, channels });
}

export async function findRepoConfig(start = process.cwd()) {
  let current = resolve(start);
  while (true) {
    const path = join(current, ".denden.json");
    const value = await readJson(path, { required: false });
    if (value) return { path, value };
    const parent = dirname(current);
    if (parent === current) throw new Error("找不到 .denden.json");
    current = parent;
  }
}

export function validateRepoConfig(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(".denden.json 必須是物件");
  const unknownRoot = Object.keys(value).filter((key) => !new Set(["defaultChannelId", "channels"]).has(key));
  if (unknownRoot.length) throw new Error(`.denden.json 包含未知或禁止欄位: ${unknownRoot.join(", ")}`);
  const channels = value.channels;
  if (!channels || typeof channels !== "object" || Array.isArray(channels)) throw new Error("channels 設定無效");
  const ids = Object.keys(channels);
  if (ids.length === 0) throw new Error("至少需要一個 Channel");
  for (const id of ids) {
    const channel = channels[id];
    const unknownChannel = channel && typeof channel === "object"
      ? Object.keys(channel).filter((key) => !new Set(["channelId", "channelName", "policy"]).has(key))
      : [];
    if (unknownChannel.length) throw new Error(`Channel ${id} 包含未知或禁止欄位: ${unknownChannel.join(", ")}`);
    if (!channel || channel.channelId !== id || typeof channel.channelName !== "string" || !channel.channelName.trim()) {
      throw new Error(`Channel ${id} 設定無效`);
    }
    rejectCredentialFields(channel.policy, `Channel ${id} policy`);
    rejectRepositoryRing(channel.policy, `Channel ${id} policy`);
  }
  if (typeof value.defaultChannelId !== "string" || !channels[value.defaultChannelId]) {
    throw new Error("defaultChannelId 缺少或未指向有效 Channel");
  }
  return value;
}

function rejectRepositoryRing(policy, label) {
  if (policy?.events && Object.values(policy.events).includes("ring")) {
    throw new Error(`${label} 不得設定 ring；ring 規則只能放在受保護的使用者設定`);
  }
}

function rejectCredentialFields(value, label) {
  if (value == null) return;
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} 必須是物件`);
  for (const [key, item] of Object.entries(value)) {
    if (/token|authorization|credential|secret/i.test(key)) throw new Error(`${label} 不得包含 credential 欄位: ${key}`);
    if (item && typeof item === "object") rejectCredentialFields(item, label);
  }
}

export function selectChannel(value, explicitChannelId, env = process.env) {
  const config = validateRepoConfig(value);
  const channelId = explicitChannelId || env.DENDEN_CHANNEL_ID || config.defaultChannelId;
  const channel = config.channels[channelId];
  if (!channel) throw new Error(`找不到 Channel: ${channelId}`);
  return channel;
}

export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [
    key,
    /token|authorization|credential|secret/i.test(key) ? "[REDACTED]" : redact(item),
  ]));
}
