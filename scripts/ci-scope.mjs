import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const privateRepository = "hakendog/DenDen-internal";
const fastExactPaths = new Set([
  ".agents/public-files.txt",
  ".github/ISSUE_TEMPLATE/bug_report.yml",
  ".github/ISSUE_TEMPLATE/config.yml",
  ".github/ISSUE_TEMPLATE/feature_request.yml",
  ".github/pull_request_template.md",
  "CONTRIBUTING.md",
  "CONTEXT.md",
  "LICENSE",
  "README.md",
  "README.zh-TW.md",
  "SECURITY.md",
  "AGENTS.md",
]);

const androidBuildPaths = new Set([
  "build.gradle.kts",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "settings.gradle.kts",
]);

const ciInfrastructurePaths = new Set([
  ".github/workflows/ci.yml",
  "scripts/ci-scope.mjs",
  "cli/test/ci-scope.test.mjs",
]);

function normalizePath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//, "");
}

export function isFastValidationPath(value) {
  const path = normalizePath(value);
  return fastExactPaths.has(path) || path.startsWith("docs/");
}

export function classifyValidationNeeds(paths) {
  let android = false;
  let device = false;
  let cli = false;
  let unknown = false;

  for (const value of paths) {
    const path = normalizePath(value);
    if (isFastValidationPath(path)) continue;
    if (ciInfrastructurePaths.has(path) || path.startsWith(".github/workflows/")) {
      android = true;
      device = true;
      cli = true;
    } else if (path.startsWith("app/") || androidBuildPaths.has(path) || path.startsWith("gradle/")) {
      android = true;
      if (!path.startsWith("app/src/test/")) device = true;
    } else if (
      path.startsWith("cli/") ||
      path.startsWith("integrations/") ||
      path.startsWith("skills/") ||
      path.startsWith("scripts/") ||
      path === "package.json" ||
      path === "package-lock.json"
    ) {
      cli = true;
    } else {
      unknown = true;
    }
  }

  if (unknown) return { android: true, device: true, cli: true };
  return { android, device, cli };
}

function decision(scope, reason, needs = {}, heavy = false) {
  return {
    scope,
    reason,
    android: needs.android ?? false,
    device: needs.device ?? false,
    cli: needs.cli ?? false,
    heavy,
  };
}

export function determineCiScope({
  eventName,
  repository,
  headRef = "",
  paths,
  isMergeCommit = false,
  isDraft = false,
}) {
  if (eventName === "workflow_dispatch") {
    return decision("full", "manual-full-run", { android: true, device: true, cli: true }, true);
  }
  if (!paths.length) {
    return decision("full", "unknown-change-set", { android: true, device: true, cli: true }, true);
  }
  if (paths.every(isFastValidationPath)) return decision("fast", "documentation-only");
  if (eventName === "pull_request" && repository === privateRepository && /(^|\/)release\//.test(headRef)) {
    return decision("fast", "private-release-promotion");
  }
  if (eventName === "push" && isMergeCommit) return decision("fast", "already-validated-merge");

  const needs = classifyValidationNeeds(paths);
  if (eventName === "pull_request" && isDraft) {
    return decision("focused", "draft-change", needs);
  }
  return decision("full", "ready-change", needs, true);
}

function git(args) {
  return execFileSync("git", args, { encoding: "utf8" });
}

function changedPaths(baseSha, headSha) {
  if (!baseSha || !headSha || /^0+$/.test(baseSha)) return [];
  return git(["diff", "--name-only", "-z", baseSha, headSha])
    .split("\0")
    .filter(Boolean)
    .map(normalizePath);
}

function mergeCommit(headSha) {
  if (!headSha) return false;
  return git(["rev-list", "--parents", "-n", "1", headSha]).trim().split(/\s+/).length > 2;
}

function main() {
  const eventName = process.env.DENDEN_EVENT_NAME ?? "";
  const repository = process.env.DENDEN_REPOSITORY ?? "";
  const headRef = process.env.DENDEN_HEAD_REF ?? "";
  const baseSha = process.env.DENDEN_BASE_SHA ?? "";
  const headSha = process.env.DENDEN_HEAD_SHA ?? "";
  const isDraft = (process.env.DENDEN_PR_DRAFT ?? "").toLowerCase() === "true";
  let paths = [];
  let isMergeCommit = false;
  try {
    paths = changedPaths(baseSha, headSha);
    isMergeCommit = eventName === "push" && mergeCommit(headSha);
  } catch (error) {
    console.warn(`無法判斷變更範圍，改用完整驗證：${error.message}`);
  }
  const decision = determineCiScope({ eventName, repository, headRef, paths, isMergeCommit, isDraft });
  console.log(
    `CI scope: ${decision.scope} (${decision.reason}); files=${paths.length}; ` +
      `android=${decision.android}; device=${decision.device}; cli=${decision.cli}; heavy=${decision.heavy}`,
  );
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(
      process.env.GITHUB_OUTPUT,
      `full=${decision.scope === "full"}\nscope=${decision.scope}\nreason=${decision.reason}\n` +
        `android=${decision.android}\ndevice=${decision.device}\ncli=${decision.cli}\nheavy=${decision.heavy}\n`,
    );
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
