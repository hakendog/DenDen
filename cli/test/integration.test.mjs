import test from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import { runIntegrationHook } from "../src/integration.mjs";
import { decryptDirectFcmPayload } from "../src/direct-fcm-protocol.mjs";
import { buildDailySkillInstallPlan, buildRepositoryIntegrationPlan, detectRepositoryAgent, installDailySkill, installRepositoryIntegration } from "../src/integration-install.mjs";
import { TEST_SENDER_CONFIG } from "./fixtures.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

test("hook fails closed when the agent did not report a standard event", async () => {
  const result = await runIntegrationHook({ stdin: Readable.from([JSON.stringify({ prompt_response: "Finished maybe" })]) });
  assert.deepEqual(result, { notified: false, reason: "missing-standard-event" });
});

test("Claude and Gemini hook payloads share one CLI mapping and reliable duration", async () => {
  const cwd = await mkdtemp(join(tmpdir(), "denden-hook-test-"));
  const configPath = join(cwd, "user.json");
  await writeFile(join(cwd, ".denden.json"), JSON.stringify({ defaultChannelId: "main", channels: { main: { channelId: "main", channelName: "main" } } }));
  const directConfig = {
    ...TEST_SENDER_CONFIG,
    policy: { preset: "balanced" },
  };
  await writeFile(configPath, JSON.stringify(directConfig));
  let request;
  const result = await runIntegrationHook({
    stdin: Readable.from([JSON.stringify({ cwd, prompt_response: "Done\n<!-- denden:event=completed;durationSeconds=75;durationReliable=true -->" })]),
    env: { DENDEN_CONFIG_PATH: configPath },
    accessToken: "test-access-token",
    nowMillis: 1_800_000_000_000,
    messageId: "message-00000004",
    nonce: "sLGys7S1tre4ubq7",
    fetchImpl: async (url, options) => {
      request = { url: url.toString(), body: JSON.parse(options.body) };
      return new Response(JSON.stringify({ name: "projects/demo/messages/4" }), { status: 200 });
    },
  });
  assert.equal(result.notified, true);
  assert.equal(result.event, "completed");
  assert.equal(result.action, "quiet");
  assert.equal(request.url, "https://fcm.googleapis.com/v1/projects/denden-demo-123/messages:send");
  const plaintext = decryptDirectFcmPayload(request.body.message.data, directConfig.eventKey);
  assert.equal(plaintext.mode, "quiet");
});

test("Claude integration plan preserves unrelated settings, appends one marked instruction, and is idempotent", async () => {
  const repositoryRoot = await mkdtemp(join(tmpdir(), "denden-claude-integration-"));
  const settingsPath = join(repositoryRoot, ".claude", "settings.json");
  const installRoot = join(repositoryRoot, "installed-denden");
  await mkdir(dirname(settingsPath), { recursive: true });
  await writeFile(settingsPath, JSON.stringify({
    permissions: { allow: ["Read"] },
    hooks: { Stop: [{ hooks: [{ type: "command", command: "denden integration hook" }] }] },
  }));
  await writeFile(join(repositoryRoot, "CLAUDE.md"), "# Existing instructions\n");
  try {
    const env = { DENDEN_INSTALL_ROOT: installRoot };
    const plan = await buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "claude", env });
    assert.equal(JSON.stringify(plan).includes("automationToken"), false);
    const result = await installRepositoryIntegration(plan, { approvedDigest: plan.digest });
    assert.equal(result.installed, true);
    const settings = JSON.parse(await readFile(settingsPath, "utf8"));
    assert.deepEqual(settings.permissions.allow, ["Read"]);
    assert.notEqual(settings.hooks.Stop[0].hooks[0].command, "denden integration hook");
    assert.match(settings.hooks.Stop[0].hooks[0].command, /installed-denden.*denden\.(?:cmd|mjs).*integration hook/);
    const instructions = await readFile(join(repositoryRoot, "CLAUDE.md"), "utf8");
    assert.equal((instructions.match(/denden:integration:start/g) || []).length, 1);

    const repeated = await buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "claude", env });
    const repeatedResult = await installRepositoryIntegration(repeated, { approvedDigest: repeated.digest });
    assert.deepEqual(repeatedResult.changedFiles, []);
  } finally {
    await rm(repositoryRoot, { recursive: true, force: true });
  }
});

test("integration conflicts stop before overwrite, unsupported agents stay manual, and ambiguous detection asks", async () => {
  const repositoryRoot = await mkdtemp(join(tmpdir(), "denden-integration-conflict-"));
  try {
    await mkdir(join(repositoryRoot, ".claude"));
    await mkdir(join(repositoryRoot, ".gemini"));
    await assert.rejects(() => detectRepositoryAgent(repositoryRoot), /多種 Agent/);
    const unsupported = await buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "unsupported" });
    assert.equal(unsupported.manualOnly, true);
    assert.deepEqual(unsupported.files, []);

    await writeFile(join(repositoryRoot, ".claude", "settings.json"), JSON.stringify({ hooks: { Stop: [{ hooks: [{ type: "command", command: "denden unsafe hook" }] }] } }));
    await assert.rejects(
      () => buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "claude" }),
      /拒絕覆寫/,
    );
  } finally {
    await rm(repositoryRoot, { recursive: true, force: true });
  }
});

test("Codex integration installs only the low-privilege user skill and refuses a different existing destination", async () => {
  const repositoryRoot = await mkdtemp(join(tmpdir(), "denden-codex-integration-"));
  const codexHome = join(repositoryRoot, "user-codex");
  try {
    const plan = await buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "codex", env: { CODEX_HOME: codexHome } });
    assert.equal(plan.files.length, 1);
    assert.match(plan.files[0].destination, /skills[\\/]denden$/);
    const result = await installRepositoryIntegration(plan, { approvedDigest: plan.digest });
    assert.equal(result.installed, true);
    assert.match(await readFile(join(codexHome, "skills", "denden", "SKILL.md"), "utf8"), /低權限|low-privilege/i);
    await writeFile(join(codexHome, "skills", "denden", "conflict.txt"), "different");
    await assert.rejects(
      () => buildRepositoryIntegrationPlan({ repositoryRoot, sourceRoot: ROOT, agentType: "codex", env: { CODEX_HOME: codexHome } }),
      /拒絕覆寫/,
    );
  } finally {
    await rm(repositoryRoot, { recursive: true, force: true });
  }
});

test("daily skill installation supports global, specified, and skip choices with digest approval", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-skill-choice-"));
  try {
    const destination = join(root, "custom", "denden");
    const installRoot = join(root, "installed-denden");
    const specified = buildDailySkillInstallPlan({
      sourceRoot: ROOT,
      choice: "specified",
      destination,
      agentType: "codex",
      env: { DENDEN_INSTALL_ROOT: installRoot },
    });
    assert.equal(specified.persistentCli, join(installRoot, "bin", "denden.mjs"));
    assert.equal(specified.notificationPolicy, null);
    await assert.rejects(installDailySkill(specified), /明確同意/);
    await assert.rejects(installDailySkill(specified, {
      approvedDigest: specified.digest,
      copyDirectory: async (source, temporary, options) => {
        const { cp } = await import("node:fs/promises");
        await cp(source, temporary, options);
        throw new Error("模擬技能候選複製中斷");
      },
    }), /候選複製中斷/);
    const installed = await installDailySkill(specified, { approvedDigest: specified.digest });
    assert.equal(installed.destination, destination);
    assert.match(await readFile(join(destination, "SKILL.md"), "utf8"), /^name: denden$/m);

    const skipped = buildDailySkillInstallPlan({ sourceRoot: ROOT, choice: "skip", agentType: "codex" });
    assert.equal(skipped.notificationPolicy, null);
    assert.equal((await installDailySkill(skipped, { approvedDigest: skipped.digest })).skipped, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
