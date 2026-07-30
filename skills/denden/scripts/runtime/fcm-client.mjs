import { randomUUID } from "node:crypto";
import { isAbsolute, join } from "node:path";
import { assertPrivateDirectory, assertPrivateFile, readJson } from "./config.mjs";
import { runExternal } from "./process.mjs";
import { validatePolicy } from "./policy.mjs";
import {
  directFcmDataSize,
  encryptDirectFcmPayload,
} from "./direct-fcm-protocol.mjs";

export const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
export const SENDER_SERVICE_ACCOUNT_FILE = "service-account.json";
const MAX_FCM_DATA_BYTES = 2048;
const RETRYABLE_STATUS = new Set([429, 500, 502, 503, 504]);

export function validateDirectFcmSenderConfig(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("DenDen 發送設定無效");
  const allowed = new Set([
    "schemaVersion", "projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName",
    "pairingId", "topic", "eventKeyId", "eventKey",
    "senderCredentialsDirectory", "policy", "channelPolicies",
  ]);
  const unknown = Object.keys(value).filter((key) => !allowed.has(key));
  if (unknown.length) throw new Error(`DenDen 發送設定包含未知欄位：${unknown.join(", ")}`);
  if (value.schemaVersion !== 2) throw new Error("DenDen 發送設定版本無效");
  validateDirectFirebasePublicConfig(value);
  validateCanonicalSecret(value.pairingId, 16, "pairingId");
  validateCanonicalSecret(value.topic, 32, "topic");
  validateCanonicalId(value.eventKeyId, "eventKeyId");
  validateCanonicalSecret(value.eventKey, 32, "eventKey");
  validateSenderCredentialsDirectory(value.senderCredentialsDirectory);
  if ("brandKey" in value || "brandKeyId" in value) throw new Error("日常發送設定不得包含品牌金鑰");
  validatePolicy(value.policy, "全域");
  if (value.channelPolicies !== undefined) {
    if (!value.channelPolicies || typeof value.channelPolicies !== "object" || Array.isArray(value.channelPolicies)) {
      throw new Error("channelPolicies 無效");
    }
    const entries = Object.entries(value.channelPolicies);
    if (entries.length > 200) throw new Error("channelPolicies 超過上限");
    for (const [channelId, policy] of entries) {
      if (!channelId.trim() || channelId.length > 200) throw new Error("channelPolicies Channel ID 無效");
      validatePolicy(policy, `Channel ${channelId}`);
    }
  }
  return value;
}

export function validateDirectFirebasePublicConfig(value) {
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(value?.projectId || "")) throw new Error("projectId 無效");
  if (!/^\d{6,20}$/.test(value?.gcmSenderId || "")) throw new Error("gcmSenderId 無效");
  const app = typeof value?.firebaseAppId === "string"
    ? value.firebaseAppId.match(/^1:(\d{6,20}):android:[A-Za-z0-9_-]{8,100}$/)
    : null;
  if (!app || app[1] !== value.gcmSenderId) throw new Error("firebaseAppId 無效或與 gcmSenderId 不一致");
  if (typeof value?.apiKey !== "string" || !/^AIza[A-Za-z0-9_-]{28,60}$/.test(value.apiKey)) throw new Error("apiKey 無效");
  if (typeof value?.androidPackageName !== "string" ||
      !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value.androidPackageName)) {
    throw new Error("androidPackageName 無效");
  }
}

export function validateCanonicalId(value, name) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{16,86}$/.test(value)) throw new Error(`${name} 無效`);
  return value;
}

export function validateCanonicalSecret(value, size, name) {
  validateCanonicalId(value, name);
  const decoded = Buffer.from(value, "base64url");
  if (decoded.length !== size || decoded.toString("base64url") !== value) throw new Error(`${name} 無效`);
  return value;
}

export function validateSenderCredentialsDirectory(value) {
  if (typeof value !== "string" || !isAbsolute(value)) throw new Error("senderCredentialsDirectory 無效");
  return value;
}

export function buildDirectFcmMessage({ action, payload, config, nowMillis = Date.now(), messageId = randomUUID(), nonce }) {
  validateDirectFcmSenderConfig(config);
  const isStop = action === "stop";
  if (!new Set(["quiet", "notify", "ring", "stop"]).has(action)) throw new Error("通知動作無效");
  const expiresAtMillis = nowMillis + (action === "ring" || isStop ? 60_000 : 5 * 60_000);
  const plaintext = isStop
    ? {
        version: 2,
        type: "stop",
        targetEventId: requiredId(payload.targetEventId, "targetEventId"),
        issuedAtMillis: nowMillis,
        expiresAtMillis,
      }
    : buildEventPayload(action, payload, nowMillis, expiresAtMillis);
  const data = encryptDirectFcmPayload({
    pairingId: config.pairingId,
    kind: isStop ? "stop" : "event",
    keyKind: "event",
    keyId: config.eventKeyId,
    messageId,
    key: config.eventKey,
    payload: plaintext,
    ...(nonce ? { nonce } : {}),
  });
  const dataBytes = directFcmDataSize(data);
  if (dataBytes > MAX_FCM_DATA_BYTES) throw new Error(`FCM data 超過 ${MAX_FCM_DATA_BYTES} 位元組（實際 ${dataBytes}）`);
  return {
    eventId: plaintext.eventId,
    dataBytes,
    request: {
      message: {
        topic: config.topic,
        data,
        android: {
          priority: action === "ring" || isStop ? "HIGH" : "NORMAL",
          ttl: "0s",
        },
      },
    },
  };
}

export async function sendDirectFcmMessage({
  action,
  payload,
  config,
  fetchImpl = fetch,
  accessToken,
  tokenProvider,
  nowMillis,
  messageId,
  nonce,
  validateOnly = false,
  timeoutMillis = 15_000,
  maxAttempts = 4,
  sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis)),
  random = Math.random,
}) {
  const built = buildDirectFcmMessage({ action, payload, config, nowMillis, messageId, nonce });
  let token;
  try {
    token = accessToken || await (tokenProvider || getServiceAccountAccessToken)(config.senderCredentialsDirectory, { projectId: config.projectId });
  } catch {
    const error = new Error("Google 日常授權無法取得 FCM 存取權杖");
    error.code = "GOOGLE_AUTH_FAILED";
    throw error;
  }
  if (typeof token !== "string" || !token.trim()) throw new Error("無法取得 FCM 存取權杖");
  const attempts = normalizeAttempts(maxAttempts);
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetchImpl(
        `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(config.projectId)}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token.trim()}`,
            "Content-Type": "application/json",
            "x-goog-user-project": config.projectId,
          },
          body: JSON.stringify(validateOnly ? { ...built.request, validate_only: true } : built.request),
          signal: AbortSignal.timeout(normalizeTimeout(timeoutMillis)),
        },
      );
      const text = await response.text();
      if (!response.ok) {
        const error = new Error(`FCM 拒絕訊息（HTTP ${response.status}）`);
        error.code = response.status === 401 || response.status === 403 ? "GOOGLE_AUTH_FAILED" : "FCM_REJECTED";
        error.status = response.status;
        error.retryAfter = response.headers.get("retry-after") || undefined;
        if (!RETRYABLE_STATUS.has(response.status) || attempt === attempts) throw error;
        lastError = error;
        await sleep(retryDelayMillis(error.retryAfter, attempt, random));
        continue;
      }
      let result = {};
      try { result = text ? JSON.parse(text) : {}; } catch { /* FCM 名稱不是產品狀態。 */ }
      return {
        acceptedByFcm: !validateOnly,
        validatedByFcm: validateOnly,
        eventId: built.eventId,
        dataBytes: built.dataBytes,
        messageName: typeof result.name === "string" ? result.name : undefined,
        delivered: false,
        attempts: attempt,
      };
    } catch (error) {
      if (error?.code === "GOOGLE_AUTH_FAILED" || error?.code === "FCM_REJECTED") throw error;
      lastError = error;
      if (attempt === attempts) break;
      await sleep(retryDelayMillis(undefined, attempt, random));
    }
  }
  const error = new Error("FCM 傳輸失敗；尚未取得 Google 接受結果");
  error.code = "FCM_TRANSPORT_FAILED";
  error.cause = lastError;
  throw error;
}

export async function getServiceAccountAccessToken(senderCredentialsDirectory, options = {}) {
  if (typeof senderCredentialsDirectory !== "string" || !senderCredentialsDirectory.trim()) {
    throw new Error("DenDen 日常授權目錄無效");
  }
  await (options.assertPrivateDirectory || assertPrivateDirectory)(senderCredentialsDirectory);
  const keyPath = join(senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE);
  await (options.assertPrivateFile || assertPrivateFile)(keyPath);
  const credential = validateSenderServiceAccountKey(await readJson(keyPath), options.projectId);
  const result = await runGcloud([
    "auth", "application-default", "print-access-token", `--scopes=${FCM_SCOPE}`, "--quiet",
  ], { ...options, senderCredentialsDirectory, keyPath });
  const token = result.stdout.trim();
  if (!token) throw new Error("gcloud 未回傳 FCM 存取權杖");
  return token;
}

export function validateSenderServiceAccountKey(value, expectedProjectId) {
  if (!value || value.type !== "service_account") throw new Error("DenDen 發送服務帳戶金鑰格式無效");
  const projectId = String(value.project_id || "");
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId) || expectedProjectId && projectId !== expectedProjectId) {
    throw new Error("DenDen 發送服務帳戶專案不符");
  }
  const account = String(value.client_email || "");
  const match = account.match(/^(denden-[a-f0-9]{12})@([a-z][a-z0-9-]{4,28}[a-z0-9])\.iam\.gserviceaccount\.com$/);
  if (!match || match[2] !== projectId) throw new Error("DenDen 發送服務帳戶身分無效");
  if (!/^[a-f0-9]{40}$/.test(String(value.private_key_id || ""))) throw new Error("DenDen 發送服務帳戶 key ID 無效");
  if (typeof value.private_key !== "string" || !value.private_key.startsWith("-----BEGIN PRIVATE KEY-----\n") ||
      !value.private_key.endsWith("\n-----END PRIVATE KEY-----\n")) {
    throw new Error("DenDen 發送服務帳戶私鑰格式無效");
  }
  if (!new Set(["https://oauth2.googleapis.com/token", "https://accounts.google.com/o/oauth2/token"]).has(value.token_uri)) {
    throw new Error("DenDen 發送服務帳戶 token URI 無效");
  }
  return { ...value, projectId, serviceAccountId: match[1], serviceAccountEmail: account };
}

function buildEventPayload(action, payload, nowMillis, expiresAtMillis) {
  const eventId = requiredId(payload.eventId || randomUUID(), "eventId");
  const channelId = requiredText(payload.channelId, "channelId", 200);
  const channelName = optionalText(payload.channelName, "channelName", 200);
  const title = optionalText(payload.title, "title", 200);
  const message = optionalText(payload.message, "message", 1000);
  const tags = payload.tags || [];
  if (!Array.isArray(tags) || tags.length > 20 || tags.some((tag) => typeof tag !== "string" || [...tag].length > 100)) {
    throw new Error("tags 無效");
  }
  const duration = payload.duration === undefined ? undefined : Number(payload.duration);
  if (duration !== undefined && (!Number.isSafeInteger(duration) || duration < 0 || duration > 300)) throw new Error("duration 無效");
  return compact({
    version: 2,
    type: "event",
    eventId,
    mode: action,
    channelId,
    channelName,
    title,
    message,
    tags: tags.length ? tags : undefined,
    durationSeconds: duration,
    ringUntilMillis: action === "ring" ? nowMillis + (duration || 30) * 1000 : undefined,
    issuedAtMillis: nowMillis,
    expiresAtMillis,
  });
}

function compact(value) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined));
}

function requiredId(value, name) {
  const normalized = requiredText(value, name, 200);
  if (!/^[A-Za-z0-9_-]+$/.test(normalized)) throw new Error(`${name} 只能包含英數字、底線與連字號`);
  return normalized;
}

function requiredText(value, name, maxLength) {
  if (typeof value !== "string" || !value.trim() || [...value].length > maxLength) throw new Error(`${name} 無效`);
  return value.trim();
}

function optionalText(value, name, maxLength) {
  if (value === undefined) return undefined;
  if (typeof value !== "string" || [...value].length > maxLength) throw new Error(`${name} 無效`);
  return value;
}

function runGcloud(args, { senderCredentialsDirectory, keyPath, env = process.env, runner, timeoutMillis = 60_000 } = {}) {
  return (runner || runExternal)("gcloud", args, {
    env: { ...env, CLOUDSDK_CONFIG: senderCredentialsDirectory, GOOGLE_APPLICATION_CREDENTIALS: keyPath },
    timeoutMillis,
  });
}

function normalizeAttempts(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > 6) throw new Error("FCM 重試次數無效");
  return parsed;
}

function normalizeTimeout(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1_000 || parsed > 60_000) throw new Error("FCM 逾時設定無效");
  return parsed;
}

export function retryDelayMillis(retryAfter, attempt, random = Math.random, nowMillis = Date.now()) {
  const seconds = Number(retryAfter);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.min(30_000, Math.ceil(seconds * 1000));
  if (typeof retryAfter === "string" && retryAfter.trim()) {
    const at = Date.parse(retryAfter);
    if (Number.isFinite(at)) return Math.min(30_000, Math.max(0, at - nowMillis));
  }
  const jitter = 0.75 + Math.max(0, Math.min(1, Number(random()) || 0)) * 0.5;
  return Math.min(30_000, Math.round(500 * (2 ** Math.max(0, attempt - 1)) * jitter));
}
