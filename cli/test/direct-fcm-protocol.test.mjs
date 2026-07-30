import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  decryptDirectFcmPayload,
  directFcmDataSize,
  encodeDirectFcmInvite,
  encryptDirectFcmBytes,
  encryptDirectFcmPayload,
  parseDirectFcmInvite,
} from "../src/direct-fcm-protocol.mjs";

const vectors = JSON.parse(readFileSync(
  new URL("../../app/src/test/resources/direct-fcm-v2-test-vectors.json", import.meta.url),
  "utf8",
));

test("DDC round trip contains only direct FCM pairing material", () => {
  const invite = encodeDirectFcmInvite(vectors.invite, { nowMillis: vectors.nowMillis });
  assert.deepEqual(parseDirectFcmInvite(invite, { nowMillis: vectors.nowMillis }), vectors.invite);
  assert.equal(invite, vectors.encodedInvite);
  assert.doesNotMatch(invite, /backend|automation|credential|token/i);
});

test("DDC rejects old versions, unknown fields, invalid secrets, and expiry", () => {
  assert.throws(() => parseDirectFcmInvite("DD1.abc"), /版本/);
  assert.throws(() => encodeDirectFcmInvite({ ...vectors.invite, backendUrl: "https://example.test" }), /未知欄位/);
  assert.throws(() => encodeDirectFcmInvite({ ...vectors.invite, eventKey: "short" }), /eventKey/);
  assert.throws(() => encodeDirectFcmInvite({ ...vectors.invite, firebaseAppId: "1:999999999999:android:abcdef123456" }), /gcmSenderId/);
  assert.throws(() => encodeDirectFcmInvite({ ...vectors.invite, apiKey: "public-but-invalid" }), /apiKey/);
  assert.throws(() => encodeDirectFcmInvite({
    ...vectors.invite,
    createdAtMillis: vectors.nowMillis + 3 * 60_000,
    displayExpiresAtMillis: vectors.nowMillis + 4 * 60_000,
  }, { nowMillis: vectors.nowMillis }), /時間範圍/);
  assert.throws(() => encodeDirectFcmInvite({
    ...vectors.invite,
    displayExpiresAtMillis: vectors.invite.createdAtMillis + 16 * 60_000,
  }, { nowMillis: vectors.nowMillis }), /時間範圍/);
  assert.throws(() => parseDirectFcmInvite(vectors.encodedInvite, { nowMillis: vectors.invite.displayExpiresAtMillis }), /期限/);
});

test("AES-GCM vector is stable and binds pairing, kind, key kind, key id, and message id", () => {
  const encrypted = encryptDirectFcmPayload(vectors.encryption.input);
  assert.deepEqual(encrypted, vectors.encryption.envelope);
  assert.deepEqual(decryptDirectFcmPayload(encrypted, vectors.encryption.input.key), vectors.encryption.input.payload);

  for (const field of ["pairingId", "kind", "keyKind", "keyId", "messageId"]) {
    const tampered = { ...encrypted, [field]: field === "kind" ? "stop" : `${encrypted[field]}x` };
    assert.throws(() => decryptDirectFcmPayload(tampered, vectors.encryption.input.key));
  }
  assert.throws(() => decryptDirectFcmPayload(encrypted, vectors.invite.brandKey), /驗證失敗/);
});

test("event and brand message types cannot cross key boundaries and payload size is exact", () => {
  assert.throws(() => encryptDirectFcmPayload({ ...vectors.encryption.input, keyKind: "brand" }), /不一致/);
  const brand = encryptDirectFcmPayload({
    ...vectors.encryption.input,
    kind: "brand-reset",
    keyKind: "brand",
    keyId: vectors.invite.brandKeyId,
    key: vectors.invite.brandKey,
  });
  assert.throws(() => decryptDirectFcmPayload(brand, vectors.invite.eventKey), /驗證失敗/);
  assert.throws(() => encryptDirectFcmPayload({ ...vectors.encryption.input, context: "unexpected" }), /context/);
  assert.throws(() => encryptDirectFcmBytes({
    ...vectors.encryption.input,
    kind: "brand-chunk",
    keyKind: "brand",
    keyId: vectors.invite.brandKeyId,
    key: vectors.invite.brandKey,
    plaintext: Buffer.from("chunk"),
  }), /context/);
  assert.equal(directFcmDataSize(vectors.encryption.envelope), vectors.encryption.dataSizeBytes);
});
