import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { chmod, mkdtemp, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { addChannel, assertPrivateDirectory, createRepoConfig, ensurePrivateDirectory, redact, removeChannel, selectChannel, setDefaultChannel, validateRepoConfig } from "../src/config.mjs";

const execFileAsync = promisify(execFile);

const config = {
  defaultChannelId: "main-id",
  channels: {
    "main-id": { channelId: "main-id", channelName: "main" },
    docs: { channelId: "docs", channelName: "Documentation" },
  },
};

test("selects explicit, environment, then default Channel", () => {
  assert.equal(selectChannel(config, "docs", {}).channelId, "docs");
  assert.equal(selectChannel(config, undefined, { DENDEN_CHANNEL_ID: "docs" }).channelId, "docs");
  assert.equal(selectChannel(config, undefined, {}).channelId, "main-id");
});

test("invalid default or channel fails instead of selecting the first Channel", () => {
  assert.throws(() => validateRepoConfig({ ...config, defaultChannelId: "missing" }), /defaultChannelId/);
  assert.throws(() => selectChannel(config, "missing", {}), /找不到 Channel/);
  assert.throws(() => validateRepoConfig({ defaultChannelId: "x", channels: {} }), /至少需要/);
  assert.throws(() => validateRepoConfig({ ...config, automationToken: "forbidden" }), /禁止欄位/);
  assert.throws(() => validateRepoConfig({
    ...config,
    channels: { ...config.channels, docs: { ...config.channels.docs, policy: { secret: "forbidden" } } },
  }), /credential/);
});

test("redaction covers every credential-shaped field", () => {
  const text = JSON.stringify(redact({ automationToken: "secret", nested: { Authorization: "Bearer secret" }, channelId: "main" }));
  assert.equal(text.includes("secret"), false);
  assert.equal(text.includes("Bearer"), false);
  assert.equal(text.includes("main"), true);
});

test("Channel lifecycle creates one default and protects its invariant", () => {
  const created = createRepoConfig("stable-id", "main");
  assert.deepEqual(Object.keys(created.channels), ["stable-id"]);
  assert.equal(created.defaultChannelId, "stable-id");
  const withDocs = addChannel(created, "docs", "Docs");
  assert.throws(() => removeChannel(withDocs, "stable-id"), /不得刪除/);
  const switched = setDefaultChannel(withDocs, "docs");
  const removed = removeChannel(switched, "stable-id");
  assert.equal(removed.defaultChannelId, "docs");
  assert.deepEqual(Object.keys(removed.channels), ["docs"]);
});

test("repository Channel policy cannot authorize ring", () => {
  const config = createRepoConfig("main", "Main");
  config.channels.main.policy = { events: { blocked: "ring" } };
  assert.throws(() => validateRepoConfig(config), /不得設定 ring/);
  config.channels.main.policy.events.blocked = "notify";
  assert.doesNotThrow(() => validateRepoConfig(config));
});

test("Windows private directory removes explicit access granted to another identity", { skip: process.platform !== "win32" }, async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-acl-"));
  const privateDirectory = join(root, "private");
  try {
    await ensurePrivateDirectory(privateDirectory);
    await execFileAsync("icacls.exe", [privateDirectory, "/grant", "*S-1-1-0:(OI)(CI)(RX)"]);
    await assert.rejects(assertPrivateDirectory(privateDirectory));
    await ensurePrivateDirectory(privateDirectory);
    await assert.doesNotReject(assertPrivateDirectory(privateDirectory));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("POSIX private directory rejects broad permissions and repairs them", { skip: process.platform === "win32" }, async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-mode-"));
  const privateDirectory = join(root, "private");
  try {
    await ensurePrivateDirectory(privateDirectory);
    assert.equal((await stat(privateDirectory)).mode & 0o077, 0);
    await chmod(privateDirectory, 0o755);
    await assert.rejects(assertPrivateDirectory(privateDirectory), /權限過寬/);
    await ensurePrivateDirectory(privateDirectory);
    await assert.doesNotReject(assertPrivateDirectory(privateDirectory));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
