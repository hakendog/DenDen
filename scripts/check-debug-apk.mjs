import { execFileSync } from "node:child_process";
import { mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findForbiddenRepositoryContent, isForbiddenRepositoryPath } from "./check-repository.mjs";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const apk = join(root, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const internalMarkers = [
  /DenDen-internal/i,
  /D:\\workspace\\DenDen/i,
  /RFCW11VMBHB/i,
  /(?:^|[\\/])\.agents[\\/]/i,
  /"(?:private_key|client_email|project_id)"\s*:/i,
];

export function inspectApkEntryName(value) {
  const path = value.replaceAll("\\", "/");
  if (!path || path.startsWith("/") || path.split("/").includes("..")) return "不安全的 APK 路徑";
  if (isForbiddenRepositoryPath(path)) return "禁止的秘密或簽章檔名";
  return null;
}

export function inspectApkBytes(bytes) {
  const text = bytes.toString("utf8");
  return [
    ...findForbiddenRepositoryContent(text),
    ...internalMarkers.filter((pattern) => pattern.test(text)).map((pattern) => `內部資料 ${pattern.source}`),
  ];
}

async function filesUnder(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await filesUnder(path));
    else if (entry.isFile()) result.push(path);
  }
  return result;
}

async function main() {
  const entries = execFileSync("jar", ["tf", apk], { encoding: "utf8" }).split(/\r?\n/).filter(Boolean);
  const failures = entries.flatMap((entry) => {
    const reason = inspectApkEntryName(entry);
    return reason ? [`${entry}: ${reason}`] : [];
  });
  if (failures.length) throw new Error(failures.join("\n"));

  const auditRoot = await mkdtemp(join(tmpdir(), "denden-apk-audit-"));
  try {
    execFileSync("jar", ["xf", apk], { cwd: auditRoot });
    for (const path of await filesUnder(auditRoot)) {
      const bytes = await readFile(path);
      for (const reason of inspectApkBytes(bytes)) failures.push(`${path.slice(auditRoot.length + 1)}: ${reason}`);
    }
  } finally {
    const resolved = resolve(auditRoot);
    if (!resolved.startsWith(resolve(tmpdir()))) throw new Error(`拒絕清除非暫存路徑：${resolved}`);
    await rm(resolved, { recursive: true, force: true });
  }
  if (failures.length) throw new Error(failures.join("\n"));
  console.log(`debug APK safety check passed (${entries.length} entries)`);
}

if (basename(process.argv[1] ?? "") === basename(fileURLToPath(import.meta.url))) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
