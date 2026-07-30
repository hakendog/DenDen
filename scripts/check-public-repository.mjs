import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findForbiddenRepositoryContent } from "./check-repository.mjs";

const exactPaths = new Set([
  ".github/workflows/ci.yml",
  ".gitignore",
  "LICENSE",
  "README.md",
  "README.zh-TW.md",
  "SECURITY.md",
  "app/build.gradle.kts",
  "build.gradle.kts",
  "docs/README.md",
  "docs/agent-install.md",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "package-lock.json",
  "package.json",
  "scripts/check-docs.mjs",
  "scripts/check-debug-apk.mjs",
  "scripts/check-public-repository.mjs",
  "scripts/check-repository.mjs",
  "scripts/install-debug.ps1",
  "settings.gradle.kts",
]);

const allowedPrefixes = [
  "app/schemas/",
  "app/src/androidTest/",
  "app/src/main/",
  "app/src/test/",
  "cli/",
  "docs/assets/screenshots/en/",
  "docs/assets/screenshots/zh-TW/",
  "docs/en/",
  "docs/zh-TW/",
  "gradle/wrapper/",
  "integrations/",
  "skills/denden-setup/",
  "skills/denden/",
];

const requiredPaths = [
  ".github/workflows/ci.yml",
  ".gitignore",
  "LICENSE",
  "README.md",
  "README.zh-TW.md",
  "SECURITY.md",
  "docs/README.md",
  "docs/agent-install.md",
  "docs/en/index.md",
  "docs/zh-TW/index.md",
  "package-lock.json",
  "package.json",
];

const forbiddenPatterns = [
  /(^|\/)\.agents\//,
  /(^|\/)\.adw\//,
  /(^|\/)\.codex\//,
  /(^|\/)(?:AGENTS|CONTEXT|LESSONS|CONTRIBUTING|spec)\.md$/i,
  /^design\//,
  /^docs\/(?:adr|agent-retro)\//,
  /^docs\/(?:demo-storyboard|manual-acceptance|release-checklist)\.md$/,
  /OpenDesignVisualAcceptanceTest\.kt$/,
  /stitch/i,
  /(?:^|\/)(?:google-services\.json|\.denden\.json)$/i,
  /\.(?:jks|keystore|p12|pfx|pem|key)$/i,
];

function normalizePath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//, "");
}

export function isForbiddenPublicPath(value) {
  const path = normalizePath(value);
  return forbiddenPatterns.some((pattern) => pattern.test(path));
}

export function isAllowedPublicPath(value) {
  const path = normalizePath(value);
  if (!path || path.startsWith("/") || path.split("/").includes("..") || isForbiddenPublicPath(path)) return false;
  return exactPaths.has(path) || allowedPrefixes.some((prefix) => path.startsWith(prefix));
}

function git(args, cwd, options = {}) {
  return execFileSync("git", args, { cwd, encoding: "utf8", ...options });
}

function parseExpectedCommits(argv) {
  const index = argv.indexOf("--expected-commits");
  if (index === -1) return undefined;
  const value = Number(argv[index + 1]);
  if (!Number.isInteger(value) || value < 1) throw new Error("--expected-commits 必須是正整數");
  return value;
}

function inspectBlob(oid, path, failures, cwd) {
  const type = git(["cat-file", "-t", oid], cwd).trim();
  if (type !== "blob") return;
  const size = Number(git(["cat-file", "-s", oid], cwd).trim());
  if (size > 5 * 1024 * 1024) {
    failures.push(`${path}: blob 超過 5 MiB，需人工審核`);
    return;
  }
  const bytes = execFileSync("git", ["cat-file", "blob", oid], { cwd, encoding: "buffer", maxBuffer: 6 * 1024 * 1024 });
  if (bytes.includes(0)) return;
  for (const label of findForbiddenRepositoryContent(bytes.toString("utf8"))) {
    failures.push(`${path}: 歷史物件疑似 ${label}`);
  }
}

export function inspectPublicRepository(expectedCommits, cwd = process.cwd()) {
  const failures = [];
  const indexLines = git(["ls-files", "--stage", "-z"], cwd).split("\0").filter(Boolean);
  const paths = new Set();
  for (const line of indexLines) {
    const match = /^(\d+) [0-9a-f]+ \d+\t(.+)$/.exec(line);
    if (!match) {
      failures.push(`無法解析 index：${line}`);
      continue;
    }
    const [, mode, path] = match;
    paths.add(path);
    if (mode === "120000") failures.push(`${path}: 公開 tree 禁止符號連結`);
    if (!isAllowedPublicPath(path)) failures.push(`${path}: 不在公開 allowlist`);
  }
  for (const path of requiredPaths) if (!paths.has(path)) failures.push(`${path}: 缺少必要公開檔案`);

  const commits = git(["rev-list", "--all"], cwd).trim().split(/\r?\n/).filter(Boolean);
  if (expectedCommits !== undefined && commits.length !== expectedCommits) failures.push(`公開歷史應有 ${expectedCommits} 個 commit，目前為 ${commits.length}`);
  for (const commit of commits) {
    const historicalPaths = git(["ls-tree", "-r", "--name-only", "-z", commit], cwd).split("\0").filter(Boolean);
    for (const path of historicalPaths) {
      if (!isAllowedPublicPath(path)) failures.push(`${path}: 歷史含非公開路徑`);
    }
  }

  const refs = git(["for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes", "refs/tags"], cwd)
    .trim().split(/\r?\n/).filter(Boolean);
  for (const ref of refs) {
    if (/(^|\/)dev$/.test(ref)) failures.push(`${ref}: 公開 repository 禁止 dev 分支`);
    if (ref.startsWith("refs/tags/") && ref !== "refs/tags/v1.0.0") failures.push(`${ref}: 非預期公開 tag`);
  }

  const seenBlobs = new Set();
  const objects = git(["rev-list", "--objects", "--all"], cwd).trim().split(/\r?\n/).filter(Boolean);
  for (const line of objects) {
    const separator = line.indexOf(" ");
    if (separator === -1) continue;
    const oid = line.slice(0, separator);
    const path = line.slice(separator + 1);
    if (!seenBlobs.has(oid)) {
      seenBlobs.add(oid);
      inspectBlob(oid, path, failures, cwd);
    }
  }
  return failures;
}

async function main() {
  const failures = inspectPublicRepository(parseExpectedCommits(process.argv.slice(2)));
  if (failures.length) {
    console.error([...new Set(failures)].join("\n"));
    process.exitCode = 1;
  } else {
    console.log("public tree and history check passed");
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
