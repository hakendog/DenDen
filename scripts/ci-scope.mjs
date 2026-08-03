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
  "LICENSE",
  "README.md",
  "README.zh-TW.md",
  "SECURITY.md",
]);

function normalizePath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//, "");
}

export function isFastValidationPath(value) {
  const path = normalizePath(value);
  return fastExactPaths.has(path) || path.startsWith("docs/");
}

export function determineCiScope({ eventName, repository, headRef = "", paths, isMergeCommit = false }) {
  if (eventName === "workflow_dispatch") return { scope: "full", reason: "manual-full-run" };
  if (!paths.length) return { scope: "full", reason: "unknown-change-set" };
  if (paths.every(isFastValidationPath)) return { scope: "fast", reason: "documentation-only" };
  if (eventName === "pull_request" && repository === privateRepository && /(^|\/)release\//.test(headRef)) {
    return { scope: "fast", reason: "private-release-promotion" };
  }
  if (eventName === "push" && isMergeCommit) return { scope: "fast", reason: "already-validated-merge" };
  return { scope: "full", reason: "code-or-build-change" };
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
  let paths = [];
  let isMergeCommit = false;
  try {
    paths = changedPaths(baseSha, headSha);
    isMergeCommit = eventName === "push" && mergeCommit(headSha);
  } catch (error) {
    console.warn(`無法判斷變更範圍，改用完整驗證：${error.message}`);
  }
  const decision = determineCiScope({ eventName, repository, headRef, paths, isMergeCommit });
  console.log(`CI scope: ${decision.scope} (${decision.reason}); files=${paths.length}`);
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `full=${decision.scope === "full"}\nscope=${decision.scope}\nreason=${decision.reason}\n`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
