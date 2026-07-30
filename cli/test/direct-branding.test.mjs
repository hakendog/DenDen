import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PNG } from "pngjs";
import { buildBrandReset, buildBrandTransfer, runDirectBrandCommand, sendBrandMessages } from "../src/direct-branding.mjs";
import { writePrivateJson } from "../src/config.mjs";
import { decryptDirectFcmPayload } from "../src/direct-fcm-protocol.mjs";
import { TEST_BRAND_CONFIG } from "./fixtures.mjs";

const config = TEST_BRAND_CONFIG;

test("brand preview stays local and keeps the transparent source", async () => {
  let written;
  const result = await runDirectBrandCommand([
    "preview", "--image", "candidate.png", "--output", "candidate-white.png",
  ], {
    createWhitePreview: async (path) => {
      assert.equal(path, resolve("candidate.png"));
      return Buffer.from("white-preview");
    },
    writeFile: async (path, bytes, options) => { written = { path, bytes, options }; },
  });
  assert.equal(result.sourcePath, resolve("candidate.png"));
  assert.equal(result.outputPath, resolve("candidate-white.png"));
  assert.equal(written.path, result.outputPath);
  assert.equal(written.bytes.toString(), "white-preview");
  assert.deepEqual(written.options, { flag: "wx" });
});

test("brand transfer encrypts one bounded manifest and chunks with brand authority", async () => {
  const image = new PNG({ width: 512, height: 512 });
  for (let index = 0; index < image.data.length; index += 4) {
    image.data[index] = 255;
    image.data[index + 3] = index % 16 === 0 ? 0 : 255;
  }
  const png = PNG.sync.write(image);
  const messages = buildBrandTransfer(png, {
    brandColor: "#123456",
    backgroundColor: "#F5F2EB",
  }, config, { generation: 1, nowMillis: 1_800_000_000_000 });
  assert.equal(messages[0].kind, "brand-manifest");
  assert.ok(messages.every((message) => message.dataBytes <= 2048));
  const manifest = decryptDirectFcmPayload(messages[0].data, config.brandKey);
  assert.equal(manifest.brandColor, "#123456");
  assert.equal(manifest.backgroundColor, "#F5F2EB");

  const requests = [];
  const result = await sendBrandMessages(messages, config, {
    accessToken: "token",
    fetchImpl: async (_url, init) => {
      requests.push(JSON.parse(init.body));
      return Response.json({ name: `message-${requests.length}` });
    },
  });
  assert.equal(result.acceptedByFcm, messages.length);
  assert.equal(result.delivered, false);
  assert.ok(requests.every((request) => request.message.android.ttl === "0s"));
});

test("brand resume skips packets already accepted by FCM", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-brand-checkpoint-"));
  const configPath = join(root, "brand.json");
  const pendingPath = join(root, "pending.json");
  try {
    await writePrivateJson(configPath, { ...config, generation: 0 });
    const image = new PNG({ width: 512, height: 512 });
    image.data.fill(0);
    image.data[0] = 255;
    image.data[3] = 255;
    const png = PNG.sync.write(image);
    let calls = 0;
    await assert.rejects(runDirectBrandCommand([
      "apply", "--image", "ignored.png", "--brand-color", "#123456",
      "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      nowMillis: 1_800_000_000_000,
      prepareImage: async () => png,
      accessToken: "token",
      maxAttempts: 1,
      fetchImpl: async () => {
        calls += 1;
        return calls === 1 ? Response.json({ name: "accepted-1" }) : new Response("{}", { status: 400 });
      },
    }), /拒絕/);
    const checkpoint = JSON.parse(await readFile(pendingPath, "utf8"));
    assert.equal(checkpoint.nextIndex, 1);
    const remainingIds = checkpoint.messages.slice(1).map((item) => item.data.messageId);
    const replayedIds = [];
    const resumed = await runDirectBrandCommand([
      "resume", "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      accessToken: "token",
      fetchImpl: async (_url, init) => {
        replayedIds.push(JSON.parse(init.body).message.data.messageId);
        return Response.json({ name: "accepted" });
      },
    });
    assert.deepEqual(replayedIds, remainingIds);
    assert.equal(resumed.acceptedByFcm, checkpoint.messages.length);
    const saved = JSON.parse(await readFile(configPath, "utf8"));
    assert.equal(saved.brandColor, "#123456");
    assert.equal(saved.backgroundColor, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("brand reset uses only the brand key", () => {
  const reset = buildBrandReset(config, {
    generation: 2,
    nowMillis: 1_800_000_000_000,
    messageId: "message-brand-reset",
    nonce: "gIGCg4SFhoeIiYqL",
  });
  assert.equal(decryptDirectFcmPayload(reset, config.brandKey).type, "brand-reset");
  assert.throws(() => decryptDirectFcmPayload(reset, "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8"), /驗證失敗/);
});

test("removed color alias is rejected", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-brand-color-reject-"));
  const configPath = join(root, "brand.json");
  try {
    await writePrivateJson(configPath, { ...config, generation: 0 });
    await assert.rejects(runDirectBrandCommand([
      "apply", "--image", "ignored.png", "--color", "#123456", "--brand-config-path", configPath,
    ]), /--color 已移除/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("brand command persists one encrypted transfer and resumes it after partial failure", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-direct-brand-"));
  const configPath = join(root, "brand.json");
  const pendingPath = join(root, "pending.json");
  try {
    await writePrivateJson(configPath, { ...config, generation: 0 });
    const image = new PNG({ width: 512, height: 512 });
    image.data.fill(0);
    image.data[0] = 255;
    image.data[3] = 255;
    const png = PNG.sync.write(image);
    let attempts = 0;
    await assert.rejects(runDirectBrandCommand([
      "apply", "--image", "ignored.png", "--background-color", "#123456",
      "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      nowMillis: 1_800_000_000_000,
      prepareImage: async () => png,
      sendBrandMessages: async () => { attempts += 1; throw new Error("partial"); },
    }), /partial/);
    const firstPending = await readFile(pendingPath, "utf8");
    const resumed = await runDirectBrandCommand([
      "resume", "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      sendBrandMessages: async (messages) => {
        attempts += 1;
        assert.equal(JSON.stringify(messages), JSON.stringify(JSON.parse(firstPending).messages));
        return { acceptedByFcm: messages.length, delivered: false };
      },
    });
    assert.equal(resumed.generation, 1);
    const saved = JSON.parse(await readFile(configPath, "utf8"));
    assert.equal(saved.generation, 1);
    assert.equal(saved.backgroundColor, "#123456");
    assert.equal(saved.brandColor, undefined);
    assert.equal(attempts, 2);

    const replay = JSON.parse(await readFile(pendingPath, "utf8"));
    assert.equal(replay.nextIndex, replay.messages.length);
    const replayedIds = [];
    await runDirectBrandCommand([
      "resume", "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      sendBrandMessages: async (messages) => {
        replayedIds.push(...messages.map((item) => item.data.messageId));
        return { acceptedByFcm: messages.length, delivered: false };
      },
    });
    assert.deepEqual(replayedIds, replay.messages.map((item) => item.data.messageId));

    const replaced = await runDirectBrandCommand([
      "reset", "--brand-config-path", configPath, "--pending-path", pendingPath,
    ], {
      sendBrandMessages: async (messages) => ({ acceptedByFcm: messages.length, delivered: false }),
    });
    assert.equal(replaced.generation, 2);
    assert.equal(JSON.parse(await readFile(pendingPath, "utf8")).action, "reset");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
