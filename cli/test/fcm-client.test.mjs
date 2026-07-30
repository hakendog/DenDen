import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ensurePrivateDirectory, writePrivateJson } from "../src/config.mjs";
import {
  buildDirectFcmMessage,
  FCM_SCOPE,
  getServiceAccountAccessToken,
  retryDelayMillis,
  sendDirectFcmMessage,
  validateDirectFcmSenderConfig,
} from "../src/fcm-client.mjs";
import { decryptDirectFcmPayload } from "../src/direct-fcm-protocol.mjs";
import { TEST_SENDER_CONFIG, testServiceAccountKey } from "./fixtures.mjs";

const config = TEST_SENDER_CONFIG;

test("direct FCM event is encrypted, bounded, and uses topic data message policy", () => {
  const built = buildDirectFcmMessage({
    action: "ring",
    payload: { channelId: "main", channelName: "Main", title: "完成", duration: 30 },
    config,
    nowMillis: 1_800_000_000_000,
    messageId: "message-00000001",
    nonce: "gIGCg4SFhoeIiYqL",
  });
  assert.equal(built.request.message.topic, config.topic);
  assert.equal(Object.hasOwn(built.request.message, "token"), false);
  assert.deepEqual(built.request.message.android, { priority: "HIGH", ttl: "0s" });
  assert.ok(built.dataBytes <= 2048);
  const plaintext = decryptDirectFcmPayload(built.request.message.data, config.eventKey);
  assert.equal(plaintext.mode, "ring");
  assert.equal(plaintext.ringUntilMillis, 1_800_000_030_000);
  assert.equal(plaintext.expiresAtMillis, 1_800_000_060_000);
});

test("FCM accepted means accepted only, and credential never appears in result", async () => {
  let request;
  const result = await sendDirectFcmMessage({
    action: "stop",
    payload: { targetEventId: "event-0001" },
    config,
    accessToken: "access-token-secret",
    nowMillis: 1_800_000_000_000,
    messageId: "message-00000002",
    nonce: "kJGSk5SVlpeYmZqb",
    fetchImpl: async (url, init) => {
      request = { url, init };
      return new Response(JSON.stringify({ name: "projects/demo/messages/1" }), { status: 200 });
    },
  });
  assert.match(request.url, /denden-demo-123\/messages:send$/);
  assert.equal(request.init.headers.Authorization, "Bearer access-token-secret");
  assert.equal(request.init.headers["x-goog-user-project"], config.projectId);
  assert.equal(result.acceptedByFcm, true);
  assert.equal(result.validatedByFcm, false);
  assert.equal(result.delivered, false);
  assert.doesNotMatch(JSON.stringify(result), /access-token-secret|eventKey/);
});

test("FCM retry reuses one encrypted request and honors Retry-After", async () => {
  const bodies = [];
  const delays = [];
  let calls = 0;
  const result = await sendDirectFcmMessage({
    action: "notify",
    payload: { channelId: "main", title: "完成" },
    config,
    accessToken: "access-token-secret",
    nowMillis: 1_800_000_000_000,
    messageId: "message-00000003",
    nonce: "oaKjpKWmp6ipqqus",
    sleep: async (millis) => { delays.push(millis); },
    fetchImpl: async (_url, init) => {
      bodies.push(init.body);
      calls += 1;
      if (calls === 1) return new Response("{}", { status: 503, headers: { "retry-after": "2" } });
      return Response.json({ name: "projects/demo/messages/2" });
    },
  });
  assert.equal(result.attempts, 2);
  assert.deepEqual(delays, [2000]);
  assert.equal(bodies[0], bodies[1]);
});

test("validate-only never claims that FCM accepted a delivery", async () => {
  let body;
  const result = await sendDirectFcmMessage({
    action: "notify",
    payload: { channelId: "main", title: "驗證" },
    config,
    accessToken: "access-token-secret",
    validateOnly: true,
    nowMillis: 1_800_000_000_000,
    messageId: "message-00000004",
    nonce: "sLGys7S1tre4ubq7",
    fetchImpl: async (_url, init) => {
      body = JSON.parse(init.body);
      return Response.json({});
    },
  });
  assert.equal(body.validate_only, true);
  assert.equal(result.acceptedByFcm, false);
  assert.equal(result.validatedByFcm, true);
});

test("retry delay is bounded", () => {
  assert.equal(retryDelayMillis("99", 1), 30_000);
  assert.equal(retryDelayMillis(undefined, 1, () => 0), 375);
});

test("daily sender configuration rejects brand authority and unknown backend fields", () => {
  assert.throws(() => validateDirectFcmSenderConfig({ ...config, brandKey: config.eventKey }), /未知欄位|品牌/);
  assert.throws(() => validateDirectFcmSenderConfig({ ...config, backendUrl: "https://example.test" }), /未知欄位/);
});

test("service account key mints only a scoped short-lived FCM token", async () => {
  const directory = await mkdtemp(join(tmpdir(), "denden-fcm-key-"));
  try {
    await ensurePrivateDirectory(directory);
    const keyPath = join(directory, "service-account.json");
    await writePrivateJson(keyPath, testServiceAccountKey());
    const token = await getServiceAccountAccessToken(directory, {
      projectId: config.projectId,
      runner: async (command, args, options) => {
        assert.equal(command, "gcloud");
        assert.deepEqual(args, ["auth", "application-default", "print-access-token", `--scopes=${FCM_SCOPE}`, "--quiet"]);
        assert.equal(options.env.GOOGLE_APPLICATION_CREDENTIALS, keyPath);
        assert.equal(options.env.CLOUDSDK_CONFIG, directory);
        assert.doesNotMatch(args.join(" "), /cloud-platform/);
        return { stdout: "short-lived-token\n" };
      },
    });
    assert.equal(token, "short-lived-token");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
