import test from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { normalizeTags, parseArgs, runDailyCommand } from "../src/commands.mjs";
import { TEST_SENDER_CONFIG } from "./fixtures.mjs";

test("ring/notify accepts repeated tag options", () => {
  const parsed = parseArgs(["notify", "--tag", "urgent", "--tag", "維運"]);
  assert.deepEqual(parsed.options.tag, ["urgent", "維運"]);
});

test("tags are trimmed, deduplicated and bounded", () => {
  assert.deepEqual(normalizeTags([" urgent ", "urgent", "維運"]), ["urgent", "維運"]);
  assert.throws(() => normalizeTags(Array.from({ length: 21 }, (_, i) => `t${i}`)), /最多 20/);
  assert.throws(() => normalizeTags(["x".repeat(101)]), /最多 100/);
});

test("direct notify dry-run includes all normalized tags", async () => {
  const cwd = await mkdtemp(join(tmpdir(), "denden-tags-"));
  try {
    await writeFile(join(cwd, ".denden.json"), JSON.stringify({
      defaultChannelId: "main",
      channels: { main: { channelId: "main", channelName: "Main" } },
    }));
    const result = await runDailyCommand(
      ["notify", "--dry-run", "--tag", " urgent ", "--tag", "維運"],
      { cwd, env: { DENDEN_CONFIG_PATH: join(cwd, "missing-user.json") } },
    );
    assert.deepEqual(result.payload.tags, ["urgent", "維運"]);
    assert.equal("deviceId" in result, false);
    await assert.rejects(
      runDailyCommand(["notify", "--dry-run", "--device", "legacy-device"], {
        cwd,
        env: { DENDEN_CONFIG_PATH: join(cwd, "missing-user.json") },
      }),
      /不支援指定單一裝置/,
    );
  } finally {
    await rm(cwd, { recursive: true, force: true });
  }
});

test("daily commands read project config from an ancestor directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-project-config-"));
  const cwd = join(root, "nested");
  try {
    await mkdir(cwd);
    await writeFile(join(root, ".denden.json"), JSON.stringify({
      defaultChannelId: "project",
      channels: { project: { channelId: "project", channelName: "Project" } },
    }));
    const result = await runDailyCommand(["notify", "--dry-run"], {
      cwd,
      env: { DENDEN_CONFIG_PATH: join(root, "missing-user.json") },
    });
    assert.equal(result.payload.channelId, "project");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("daily notify sends encrypted data directly to the configured FCM topic", async () => {
  const cwd = await mkdtemp(join(tmpdir(), "denden-direct-fcm-"));
  try {
    await writeFile(join(cwd, ".denden.json"), JSON.stringify({
      defaultChannelId: "main",
      channels: { main: { channelId: "main", channelName: "Main" } },
    }));
    const configPath = join(cwd, "config.json");
    await writeFile(configPath, JSON.stringify({
      ...TEST_SENDER_CONFIG,
    }));
    let body;
    const result = await runDailyCommand(["notify", "--title", "完成"], {
      cwd,
      env: { DENDEN_CONFIG_PATH: configPath },
      accessToken: "test-access-token",
      nowMillis: 1_800_000_000_000,
      messageId: "message-00000003",
      nonce: "oKGio6Slpqeoqaqr",
      fetchImpl: async (_url, init) => {
        body = JSON.parse(init.body);
        return new Response(JSON.stringify({ name: "projects/demo/messages/3" }), { status: 200 });
      },
    });
    assert.equal(body.message.topic, "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8");
    assert.equal(body.message.data.kind, "event");
    assert.equal(result.acceptedByFcm, true);
    assert.equal(result.delivered, false);
  } finally {
    await rm(cwd, { recursive: true, force: true });
  }
});
