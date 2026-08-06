import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { classifyValidationNeeds } from "./ci-scope.mjs";

const focusedNodeTests = [
  "cli/test/ci-scope.test.mjs",
  "cli/test/distribution.test.mjs",
  "cli/test/public-repository.test.mjs",
  "cli/test/verify-change.test.mjs",
];

function normalizePath(value) {
  return value.replaceAll("\\", "/");
}

function git(args, options = {}) {
  return execFileSync("git", args, { encoding: "utf8", ...options });
}

export function validationCommandPlan({ needs, diffBase, isWindows = process.platform === "win32" }) {
  const npm = isWindows ? "npm.cmd" : "npm";
  const gradle = isWindows ? ".\\gradlew.bat" : "./gradlew";
  const commands = [
    { label: "差異格式", command: "git", args: ["diff", "--check", diffBase] },
    { label: "Node 相依套件", command: npm, args: ["ci"] },
    needs.cli
      ? { label: "完整命令列工具測試", command: npm, args: ["test"] }
      : { label: "核心儲存庫測試", command: process.execPath, args: ["--test", ...focusedNodeTests] },
    { label: "儲存庫安全", command: npm, args: ["run", "verify:repository"] },
    { label: "文件契約", command: npm, args: ["run", "verify:docs"] },
  ];

  if (needs.android) {
    commands.push(
      {
        label: "Android 快速驗證",
        command: gradle,
        args: [
          "testDebugUnitTest",
          "assembleDebug",
          "assembleDebugAndroidTest",
          "lintDebug",
          "--build-cache",
          "--no-daemon",
        ],
      },
      { label: "APK 契約", command: npm, args: ["run", "verify:apk"] },
    );
  }
  return commands;
}

function parseArguments(args) {
  let base = "origin/main";
  let dryRun = false;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--base") {
      base = args[++index] ?? "";
      if (!base) throw new Error("--base 需要 Git ref");
    } else if (args[index] === "--dry-run") {
      dryRun = true;
    } else {
      throw new Error(`未知參數：${args[index]}`);
    }
  }
  return { base, dryRun };
}

function runCommand(entry) {
  console.log(`\n[${entry.label}] ${entry.command} ${entry.args.join(" ")}`);
  execFileSync(entry.command, entry.args, {
    cwd: process.cwd(),
    stdio: "inherit",
    shell: process.platform === "win32" && /\.(?:cmd|bat)$/i.test(entry.command),
  });
}

function main() {
  const { base, dryRun } = parseArguments(process.argv.slice(2));
  const diffBase = git(["merge-base", base, "HEAD"]).trim();
  const changed = git(["diff", "--name-only", "-z", diffBase], { encoding: "buffer" }).toString("utf8");
  const untracked = git(["ls-files", "--others", "--exclude-standard", "-z"], { encoding: "buffer" }).toString("utf8");
  const paths = [...new Set(`${changed}${untracked}`.split("\0").filter(Boolean).map(normalizePath))];
  const needs = classifyValidationNeeds(paths);
  const plan = validationCommandPlan({ needs, diffBase });
  console.log(
    `局部驗證：files=${paths.length}; android=${needs.android}; ` +
      `device=${needs.device}; cli=${needs.cli}`,
  );
  if (needs.device) console.log("裝置行為由草稿 PR 的 API 26 階段驗證；局部命令先驗證編譯、單元測試與 Lint。");
  if (dryRun) {
    for (const entry of plan) console.log(`${entry.label}: ${entry.command} ${entry.args.join(" ")}`);
    return;
  }
  for (const entry of plan) runCommand(entry);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
