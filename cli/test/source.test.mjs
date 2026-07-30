import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  acquireVerifiedSource,
  buildUserInstallPlan,
  cleanupAcquiredSource,
  installUserTools,
  activateUserTools,
  checkVerifiedSourceUpdate,
  normalizeGitRemote,
  parseInstallGuideUrl,
  rollbackUserTools,
} from "../src/source.mjs";

const SHA = "1234567890abcdef1234567890abcdef12345678";
const GUIDE = `https://raw.githubusercontent.com/hakendog/DenDen/${SHA}/docs/agent-install.md`;

test("install guide accepts only a Raw URL pinned to a full commit SHA", () => {
  assert.deepEqual(parseInstallGuideUrl(GUIDE), {
    owner: "hakendog",
    repository: "DenDen",
    commitSha: SHA,
    remoteUrl: "https://github.com/hakendog/DenDen.git",
    guideUrl: GUIDE,
  });
  assert.throws(() => parseInstallGuideUrl("https://github.com/hakendog/DenDen/blob/main/docs/agent-install.md"), /GitHub Raw/);
  assert.throws(() => parseInstallGuideUrl("https://raw.githubusercontent.com/hakendog/DenDen/main/docs/agent-install.md"), /40 碼/);
  assert.throws(() => parseInstallGuideUrl(`https://raw.githubusercontent.com/tensal/DenDen/${SHA}/docs/agent-install.md`), /官方/);
  assert.equal(normalizeGitRemote("git@github.com:hakendog/DenDen.git"), "github.com/hakendog/denden");
});

test("verified version lookup only follows a pinned guide and offline lookup keeps the current version usable", async () => {
  const nextSha = "a".repeat(40);
  const readmeUrl = "https://raw.githubusercontent.com/hakendog/DenDen/main/README.md";
  const checked = await checkVerifiedSourceUpdate({
    readmeUrl,
    currentCommitSha: SHA,
    fetchImpl: async () => new Response(`請讀取：https://raw.githubusercontent.com/hakendog/DenDen/${nextSha}/docs/agent-install.md`, { status: 200 }),
  });
  assert.equal(checked.checked, true);
  assert.equal(checked.updateAvailable, true);
  assert.equal(checked.verifiedCommitSha, nextSha);

  const offline = await checkVerifiedSourceUpdate({ readmeUrl, currentCommitSha: SHA, fetchImpl: async () => { throw new Error("offline"); } });
  assert.equal(offline.checked, false);
  assert.equal(offline.existingVersionUsable, true);
  assert.equal(offline.updateAvailable, null);
});

test("no-clone acquisition verifies the same remote, commit, and clean checkout and can clean it", async () => {
  const parent = await mkdtemp(join(tmpdir(), "denden-source-test-"));
  const calls = [];
  const runner = async (command, args, options = {}) => {
    calls.push({ command, args, cwd: options.cwd });
    if (args[0] === "clone") {
      await mkdir(args.at(-1), { recursive: true });
      return { stdout: "", stderr: "" };
    }
    if (args[0] === "checkout") return { stdout: "", stderr: "" };
    if (args[0] === "remote") return { stdout: "https://github.com/hakendog/DenDen.git", stderr: "" };
    if (args[0] === "rev-parse") return { stdout: SHA, stderr: "" };
    if (args[0] === "status") return { stdout: "", stderr: "" };
    throw new Error(`unexpected ${command} ${args.join(" ")}`);
  };
  try {
    const acquired = await acquireVerifiedSource(GUIDE, { tempParent: parent, runner });
    assert.equal(acquired.inspection.verified, true);
    assert.deepEqual(calls[0].args.slice(0, 3), ["clone", "--no-checkout", "--filter=blob:none"]);
    assert.deepEqual(calls[1].args, ["checkout", "--detach", SHA]);
    await cleanupAcquiredSource(acquired);
    await assert.rejects(readFile(join(acquired.checkout, "package.json")), /ENOENT/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test("failed no-clone source verification removes its temporary checkout", async () => {
  const parent = await mkdtemp(join(tmpdir(), "denden-source-test-"));
  let tempRoot;
  const runner = async (_command, args) => {
    if (args[0] === "clone") {
      tempRoot = join(args.at(-1), "..");
      await mkdir(args.at(-1), { recursive: true });
      return { stdout: "", stderr: "" };
    }
    if (args[0] === "checkout") return { stdout: "", stderr: "" };
    if (args[0] === "remote") return { stdout: "https://github.com/attacker/DenDen.git", stderr: "" };
    if (args[0] === "rev-parse") return { stdout: SHA, stderr: "" };
    if (args[0] === "status") return { stdout: "", stderr: "" };
    throw new Error("unexpected command");
  };
  try {
    await assert.rejects(acquireVerifiedSource(GUIDE, { tempParent: parent, runner }), /來源驗證失敗/);
    await assert.rejects(readFile(join(tempRoot, "missing")), /ENOENT/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test("user tool installation is versioned, validated, switched atomically, and conflict-safe", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-install-test-"));
  const installRoot = join(root, "installed");
  const plan = buildUserInstallPlan({
    verified: true,
    sourceRoot: process.cwd(),
    remoteUrl: "https://github.com/hakendog/DenDen.git",
    commitSha: SHA,
  }, { DENDEN_INSTALL_ROOT: installRoot });
  try {
    const installCalls = [];
    const runner = async (command, args, { cwd }) => {
      installCalls.push({ command, args, cwd });
      await mkdir(join(cwd, "node_modules", "qrcode"), { recursive: true });
      await writeFile(join(cwd, "node_modules", "qrcode", "package.json"), "{}\n");
      return { stdout: "", stderr: "" };
    };
    const result = await installUserTools(plan, { runner });
    assert.equal(result.installed, true);
    assert.deepEqual(installCalls[0].args, ["ci", "--omit=dev", "--ignore-scripts"]);
    assert.equal(installCalls.length, 1);
    const current = JSON.parse(await readFile(plan.currentPointer, "utf8"));
    assert.equal(current.commitSha, SHA);
    assert.match(await readFile(join(plan.versionDirectory, "skills/denden-setup/SKILL.md"), "utf8"), /name: denden-setup/);
    assert.match(await readFile(join(installRoot, "bin", "denden.cmd"), "utf8"), /denden\.mjs/);
    const reused = await installUserTools(plan, { runner });
    assert.equal(reused.reused, true);

    const installedSkill = join(plan.versionDirectory, "skills", "denden", "SKILL.md");
    const originalSkill = await readFile(installedSkill, "utf8");
    await writeFile(installedSkill, `${originalSkill}\n遭修改`);
    await assert.rejects(activateUserTools(plan), /內容雜湊不符/);
    await writeFile(installedSkill, originalSkill);

    const conflicting = { ...plan, commitSha: "abcdef1234567890abcdef1234567890abcdef12" };
    const metadataPath = join(plan.versionDirectory, "source.json");
    const metadata = JSON.parse(await readFile(metadataPath, "utf8"));
    await writeFile(metadataPath, `${JSON.stringify({ ...metadata, commitSha: conflicting.commitSha })}\n`);
    await assert.rejects(installUserTools(plan, { runner }), /衝突內容/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("candidate user tools can be verified before the current version pointer switches", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-install-candidate-test-"));
  const plan = buildUserInstallPlan({ verified: true, sourceRoot: process.cwd(), remoteUrl: "https://github.com/hakendog/DenDen.git", commitSha: SHA }, { DENDEN_INSTALL_ROOT: join(root, "installed") });
  const runner = async (_command, args, { cwd }) => {
    const packagePath = join(cwd, "node_modules", "qrcode", "package.json");
    await mkdir(join(packagePath, ".."), { recursive: true });
    await writeFile(packagePath, "{}\n");
    return { stdout: "", stderr: "" };
  };
  try {
    await installUserTools(plan, { runner, activate: false });
    await assert.rejects(readFile(plan.currentPointer, "utf8"), /ENOENT/);
    await activateUserTools(plan);
    assert.equal(JSON.parse(await readFile(plan.currentPointer, "utf8")).commitSha, SHA);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("persistent install keeps one verified previous version and can roll back", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-install-rollback-test-"));
  const installRoot = join(root, "installed");
  const runner = async (_command, _args, { cwd }) => {
    const packagePath = join(cwd, "node_modules", "qrcode", "package.json");
    await mkdir(join(packagePath, ".."), { recursive: true });
    await writeFile(packagePath, "{}\n");
    return { stdout: "", stderr: "" };
  };
  const env = { DENDEN_INSTALL_ROOT: installRoot };
  const first = buildUserInstallPlan({ verified: true, sourceRoot: process.cwd(), remoteUrl: "https://github.com/hakendog/DenDen.git", commitSha: SHA }, env);
  const secondSha = "abcdef1234567890abcdef1234567890abcdef12";
  const second = buildUserInstallPlan({ verified: true, sourceRoot: process.cwd(), remoteUrl: "https://github.com/hakendog/DenDen.git", commitSha: secondSha }, env);
  try {
    await installUserTools(first, { runner });
    await installUserTools(second, { runner });
    assert.equal(JSON.parse(await readFile(second.currentPointer, "utf8")).commitSha, secondSha);
    assert.equal(JSON.parse(await readFile(join(installRoot, "previous.json"), "utf8")).commitSha, SHA);

    const result = await rollbackUserTools(env);
    assert.equal(result.commitSha, SHA);
    assert.equal(JSON.parse(await readFile(first.currentPointer, "utf8")).commitSha, SHA);
    assert.equal(JSON.parse(await readFile(join(installRoot, "previous.json"), "utf8")).commitSha, secondSha);

    const legacyMetadataPath = join(second.versionDirectory, "source.json");
    const legacyMetadata = JSON.parse(await readFile(legacyMetadataPath, "utf8"));
    delete legacyMetadata.runtimeProtocol;
    legacyMetadata.schemaVersion = 2;
    await writeFile(legacyMetadataPath, `${JSON.stringify(legacyMetadata)}\n`);
    await assert.rejects(rollbackUserTools(env), /執行協定/);
    assert.equal(JSON.parse(await readFile(first.currentPointer, "utf8")).commitSha, SHA);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
