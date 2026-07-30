import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const DDC_PREFIX = "DDC.";
const HEADER = "DENDEN-FCM-V2";
const DDC_FIELDS = new Set([
  "v", "projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName",
  "pairingId", "topic", "eventKeyId", "eventKey", "brandKeyId", "brandKey",
  "createdAtMillis", "displayExpiresAtMillis",
]);
const EVENT_KINDS = new Set(["event", "stop"]);
const BRAND_KINDS = new Set(["brand-manifest", "brand-chunk", "brand-reset"]);
const ID_PATTERN = /^[A-Za-z0-9_-]{16,86}$/;

export function encodeDirectFcmInvite(value, options = {}) {
  const normalized = normalizeDirectFcmInvite(value, options);
  return `${DDC_PREFIX}${Buffer.from(JSON.stringify(normalized), "utf8").toString("base64url")}`;
}

export function parseDirectFcmInvite(input, options = {}) {
  const value = String(input ?? "").trim();
  if (!value.startsWith(DDC_PREFIX)) {
    if (/^DD\d+\./.test(value)) throw new Error("不支援的 DenDen 配對版本");
    throw new Error("配對碼必須使用 DDC 格式");
  }
  const encoded = value.slice(DDC_PREFIX.length);
  const decoded = decodeCanonicalBase64Url(encoded, "配對碼 payload");
  let raw;
  try {
    raw = JSON.parse(decoded.toString("utf8"));
  } catch {
    throw new Error("配對碼 payload 無法解碼");
  }
  return normalizeDirectFcmInvite(raw, options);
}

export function normalizeDirectFcmInvite(raw, options = {}) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("配對碼 payload 必須是物件");
  rejectUnknownFields(raw, DDC_FIELDS, "配對碼");
  if (raw.v !== 2) throw new Error("不支援的 DenDen 配對版本");
  const projectId = requiredString(raw.projectId, "projectId");
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) throw new Error("projectId 無效");
  const firebaseAppId = requiredString(raw.firebaseAppId, "firebaseAppId");
  const apiKey = requiredString(raw.apiKey, "apiKey");
  const gcmSenderId = requiredString(raw.gcmSenderId, "gcmSenderId");
  if (!/^\d{6,20}$/.test(gcmSenderId)) throw new Error("gcmSenderId 無效");
  const firebaseAppMatch = firebaseAppId.match(/^1:(\d{6,20}):android:[A-Za-z0-9_-]{8,100}$/);
  if (!firebaseAppMatch || firebaseAppMatch[1] !== gcmSenderId) {
    throw new Error("firebaseAppId 無效或與 gcmSenderId 不一致");
  }
  if (!/^AIza[A-Za-z0-9_-]{28,60}$/.test(apiKey)) throw new Error("apiKey 無效");
  const androidPackageName = requiredString(raw.androidPackageName, "androidPackageName");
  if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(androidPackageName)) {
    throw new Error("androidPackageName 無效");
  }
  const pairingId = canonicalSecret(raw.pairingId, 16, "pairingId");
  const topic = canonicalSecret(raw.topic, 32, "topic");
  const eventKeyId = canonicalId(raw.eventKeyId, "eventKeyId");
  const eventKey = canonicalSecret(raw.eventKey, 32, "eventKey");
  const brandKeyId = canonicalId(raw.brandKeyId, "brandKeyId");
  const brandKey = canonicalSecret(raw.brandKey, 32, "brandKey");
  if (eventKeyId === brandKeyId) throw new Error("事件與品牌金鑰編號不得相同");
  const createdAtMillis = safeMillis(raw.createdAtMillis, "createdAtMillis");
  const displayExpiresAtMillis = safeMillis(raw.displayExpiresAtMillis, "displayExpiresAtMillis");
  if (displayExpiresAtMillis <= createdAtMillis) throw new Error("顯示期限無效");
  const nowMillis = options.nowMillis ?? Date.now();
  if (createdAtMillis > nowMillis + 2 * 60_000 || displayExpiresAtMillis - createdAtMillis > 15 * 60_000) {
    throw new Error("配對碼時間範圍無效");
  }
  if (options.allowExpired !== true && displayExpiresAtMillis <= nowMillis) throw new Error("配對碼顯示期限已過");
  return {
    v: 2, projectId, firebaseAppId, apiKey, gcmSenderId, androidPackageName, pairingId, topic,
    eventKeyId, eventKey, brandKeyId, brandKey, createdAtMillis, displayExpiresAtMillis,
  };
}

export function encryptDirectFcmPayload({ pairingId, kind, keyKind, keyId, messageId, key, payload, nonce, context }) {
  return encryptDirectFcmBytes({
    pairingId, kind, keyKind, keyId, messageId, key,
    plaintext: Buffer.from(JSON.stringify(payload), "utf8"), nonce, context,
  });
}

export function encryptDirectFcmBytes({ pairingId, kind, keyKind, keyId, messageId, key, plaintext, nonce, context }) {
  const meta = normalizeEnvelopeMeta({ pairingId, kind, keyKind, keyId, messageId, context });
  const keyBytes = decodeSecret(key, 32, "加密金鑰");
  const nonceBytes = nonce === undefined ? randomBytes(12) : decodeSecret(nonce, 12, "nonce");
  const cipher = createCipheriv("aes-256-gcm", keyBytes, nonceBytes);
  cipher.setAAD(aad(meta));
  const encrypted = Buffer.concat([cipher.update(Buffer.from(plaintext)), cipher.final(), cipher.getAuthTag()]);
  return {
    v: "2",
    ...meta,
    nonce: nonceBytes.toString("base64url"),
    ciphertext: encrypted.toString("base64url"),
  };
}

export function decryptDirectFcmPayload(data, key) {
  try {
    return JSON.parse(decryptDirectFcmBytes(data, key).toString("utf8"));
  } catch (error) {
    if (error?.message === "FCM 資料驗證失敗") throw error;
    throw new Error("FCM 資料驗證失敗");
  }
}

export function decryptDirectFcmBytes(data, key) {
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("FCM 資料格式無效");
  const allowed = new Set(["v", "pairingId", "kind", "keyKind", "keyId", "messageId", "context", "nonce", "ciphertext"]);
  rejectUnknownFields(data, allowed, "FCM 資料");
  if (data.v !== "2") throw new Error("不支援的 FCM 協定版本");
  const meta = normalizeEnvelopeMeta(data);
  const nonce = decodeSecret(data.nonce, 12, "nonce");
  const encrypted = decodeCanonicalBase64Url(requiredString(data.ciphertext, "ciphertext"), "ciphertext");
  if (encrypted.length < 17) throw new Error("ciphertext 無效");
  const keyBytes = decodeSecret(key, 32, "解密金鑰");
  const body = encrypted.subarray(0, -16);
  const tag = encrypted.subarray(-16);
  try {
    const decipher = createDecipheriv("aes-256-gcm", keyBytes, nonce);
    decipher.setAAD(aad(meta));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(body), decipher.final()]);
  } catch {
    throw new Error("FCM 資料驗證失敗");
  }
}

export function directFcmDataSize(data) {
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("FCM 資料格式無效");
  return Object.entries(data).reduce((total, [key, value]) => {
    if (typeof value !== "string") throw new Error("FCM data 的鍵和值都必須是字串");
    return total + Buffer.byteLength(key, "utf8") + Buffer.byteLength(value, "utf8");
  }, 0);
}

function normalizeEnvelopeMeta(raw) {
  const pairingId = canonicalSecret(raw.pairingId, 16, "pairingId");
  const kind = requiredString(raw.kind, "kind");
  const keyKind = requiredString(raw.keyKind, "keyKind");
  const expectedKeyKind = EVENT_KINDS.has(kind) ? "event" : BRAND_KINDS.has(kind) ? "brand" : null;
  if (!expectedKeyKind) throw new Error("訊息類型無效");
  if (keyKind !== expectedKeyKind) throw new Error("訊息類型與金鑰種類不一致");
  const keyId = canonicalId(raw.keyId, "keyId");
  const messageId = canonicalId(raw.messageId, "messageId");
  const context = raw.context === undefined ? undefined : requiredString(raw.context, "context");
  if (context && (context.length > 200 || !/^[A-Za-z0-9_.:-]+$/.test(context))) throw new Error("context 無效");
  if ((kind === "brand-chunk") !== Boolean(context)) throw new Error("訊息類型與 context 不一致");
  return { pairingId, kind, keyKind, keyId, messageId, ...(context ? { context } : {}) };
}

function aad(meta) {
  const fields = [HEADER, meta.pairingId, meta.kind, meta.keyKind, meta.keyId, meta.messageId];
  if (meta.context) fields.push(meta.context);
  return Buffer.from(fields.join("\n"), "utf8");
}

function requiredString(value, name) {
  if (typeof value !== "string" || value.length === 0 || value !== value.trim()) throw new Error(`${name} 無效`);
  return value;
}

function canonicalId(value, name) {
  const normalized = requiredString(value, name);
  if (!ID_PATTERN.test(normalized)) throw new Error(`${name} 無效`);
  return normalized;
}

function canonicalSecret(value, size, name) {
  const normalized = requiredString(value, name);
  decodeSecret(normalized, size, name);
  return normalized;
}

function decodeSecret(value, size, name) {
  const decoded = decodeCanonicalBase64Url(requiredString(value, name), name);
  if (decoded.length !== size) throw new Error(`${name} 長度無效`);
  return decoded;
}

function decodeCanonicalBase64Url(value, name) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error(`${name} 無法解碼`);
  const decoded = Buffer.from(value, "base64url");
  if (decoded.toString("base64url") !== value) throw new Error(`${name} 無法解碼`);
  return decoded;
}

function safeMillis(value, name) {
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} 無效`);
  return value;
}

function rejectUnknownFields(raw, allowed, name) {
  const unknown = Object.keys(raw).filter((key) => !allowed.has(key));
  if (unknown.length) throw new Error(`${name} 包含未知欄位：${unknown.join(", ")}`);
}
