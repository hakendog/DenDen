import { createHash, randomBytes, randomUUID } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { PNG } from "pngjs";
import { directFcmDataSize, encryptDirectFcmBytes, encryptDirectFcmPayload } from "./direct-fcm-protocol.mjs";
import {
  getServiceAccountAccessToken,
  retryDelayMillis,
  validateCanonicalId,
  validateCanonicalSecret,
  validateDirectFirebasePublicConfig,
  validateSenderCredentialsDirectory,
} from "./fcm-client.mjs";
import { createWhiteBrandPreview, prepareDirectBrandImage, validateBackgroundColor, validateBrandColor } from "./direct-image.mjs";
import { readJson, userConfigPath, writePrivateJson } from "./config.mjs";

const MAX_IMAGE_BYTES = 64 * 1024;
const MAX_CHUNKS = 64;
const CHUNK_BYTES = 1024;
const MAX_DATA_BYTES = 2048;

export function buildBrandTransfer(image, appearance, config, options = {}) {
  validateBrandConfig(config);
  const bytes = Buffer.from(image);
  validateBrandPng(bytes);
  const colors = validateAppearanceColors(appearance);
  const nowMillis = options.nowMillis ?? Date.now();
  const expiresAtMillis = nowMillis + 10 * 60_000;
  const transferId = options.transferId || randomBytes(16).toString("base64url");
  const generation = Number(options.generation);
  if (!Number.isSafeInteger(generation) || generation <= 0) throw new Error("品牌 generation 無效");
  const chunks = [];
  for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) chunks.push(bytes.subarray(offset, offset + CHUNK_BYTES));
  if (chunks.length > MAX_CHUNKS) throw new Error("品牌分片超過 64 片");
  const common = { version: 2, transferId, generation, issuedAtMillis: nowMillis, expiresAtMillis };
  const payloads = [{
    kind: "brand-manifest",
    payload: {
      ...common,
      type: "brand-manifest",
      byteLength: bytes.length,
      sha256: createHash("sha256").update(bytes).digest("hex"),
      ...colors,
      chunkCount: chunks.length,
    },
  }, ...chunks.map((chunk, index) => ({
    kind: "brand-chunk",
    payload: {
      ...common,
      type: "brand-chunk",
      index,
      chunkCount: chunks.length,
      data: chunk.toString("base64url"),
    },
  }))];
  return payloads.map(({ kind, payload }, index) => {
    const isChunk = kind === "brand-chunk";
    const chunk = isChunk ? chunks[payload.index] : null;
    const context = isChunk ? `${transferId}.${generation}.${payload.index}.${chunks.length}` : undefined;
    const data = isChunk ? encryptDirectFcmBytes({
      pairingId: config.pairingId,
      kind,
      keyKind: "brand",
      keyId: config.brandKeyId,
      messageId: options.messageIds?.[index] || randomUUID(),
      key: config.brandKey,
      plaintext: Buffer.concat([Buffer.from(`${nowMillis},${expiresAtMillis}\n`), chunk]),
      context,
      ...(options.nonces?.[index] ? { nonce: options.nonces[index] } : {}),
    }) : encryptDirectFcmPayload({
      pairingId: config.pairingId,
      kind,
      keyKind: "brand",
      keyId: config.brandKeyId,
      messageId: options.messageIds?.[index] || randomUUID(),
      key: config.brandKey,
      payload,
      ...(options.nonces?.[index] ? { nonce: options.nonces[index] } : {}),
    });
    const dataBytes = directFcmDataSize(data);
    if (dataBytes > MAX_DATA_BYTES) throw new Error(`品牌 FCM data 超過 ${MAX_DATA_BYTES} 位元組（分片 ${index}：${dataBytes}）`);
    return { data, dataBytes, kind };
  });
}

export function buildBrandReset(config, options = {}) {
  validateBrandConfig(config);
  const nowMillis = options.nowMillis ?? Date.now();
  const generation = Number(options.generation);
  if (!Number.isSafeInteger(generation) || generation <= 0) throw new Error("品牌 generation 無效");
  return encryptDirectFcmPayload({
    pairingId: config.pairingId,
    kind: "brand-reset",
    keyKind: "brand",
    keyId: config.brandKeyId,
    messageId: options.messageId || randomUUID(),
    key: config.brandKey,
    payload: {
      version: 2,
      type: "brand-reset",
      generation,
      issuedAtMillis: nowMillis,
      expiresAtMillis: nowMillis + 10 * 60_000,
    },
    ...(options.nonce ? { nonce: options.nonce } : {}),
  });
}

export async function sendBrandMessages(messages, config, options = {}) {
  validateBrandConfig(config);
  let token;
  try {
    token = options.accessToken || await (options.tokenProvider || getServiceAccountAccessToken)(
      config.senderCredentialsDirectory,
      { projectId: config.projectId },
    );
  } catch {
    const error = new Error("Google 日常授權無法取得 FCM 存取權杖");
    error.code = "GOOGLE_AUTH_FAILED";
    throw error;
  }
  const accepted = [];
  for (let index = 0; index < messages.length; index += 1) {
    const item = messages[index];
    const data = item.data || item;
    await sendOneBrandMessage(data, config, token, options);
    accepted.push(data.messageId);
    await options.onAccepted?.(index, data.messageId);
  }
  return { acceptedByFcm: accepted.length, delivered: false };
}

async function sendOneBrandMessage(data, config, token, options) {
  const attempts = Number.isSafeInteger(options.maxAttempts) ? options.maxAttempts : 4;
  if (attempts < 1 || attempts > 6) throw new Error("品牌 FCM 重試次數無效");
  const fetchImpl = options.fetchImpl || fetch;
  const sleep = options.sleep || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  const body = JSON.stringify({ message: { topic: config.topic, data, android: { priority: "NORMAL", ttl: "0s" } } });
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    let response;
    try {
      response = await fetchImpl(
        `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(config.projectId)}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
            "x-goog-user-project": config.projectId,
          },
          body,
          signal: AbortSignal.timeout(options.timeoutMillis || 15_000),
        },
      );
    } catch {
      if (attempt === attempts) {
        const error = new Error("品牌 FCM 傳輸失敗；尚未取得 Google 接受結果");
        error.code = "FCM_TRANSPORT_FAILED";
        throw error;
      }
      await sleep(retryDelayMillis(undefined, attempt, options.random));
      continue;
    }
    if (response.ok) return;
    if (![429, 500, 502, 503, 504].includes(response.status) || attempt === attempts) {
      const error = new Error(`品牌訊息被 FCM 拒絕（HTTP ${response.status}）`);
      error.code = response.status === 401 || response.status === 403 ? "GOOGLE_AUTH_FAILED" : "FCM_REJECTED";
      error.status = response.status;
      throw error;
    }
    await sleep(retryDelayMillis(response.headers.get("retry-after") || undefined, attempt, options.random));
  }
}

export function validateBrandConfig(value) {
  const allowed = new Set([
    "schemaVersion", "projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName",
    "pairingId", "topic", "brandKeyId", "brandKey",
    "senderCredentialsDirectory", "generation", "activeImageBase64", "brandColor", "backgroundColor",
  ]);
  if (!value || value.schemaVersion !== 2 || Object.keys(value).some((key) => !allowed.has(key))) throw new Error("品牌管理設定無效");
  validateDirectFirebasePublicConfig(value);
  validateCanonicalSecret(value.pairingId, 16, "品牌設定 pairingId");
  validateCanonicalSecret(value.topic, 32, "品牌設定 topic");
  validateCanonicalId(value.brandKeyId, "品牌設定 brandKeyId");
  validateCanonicalSecret(value.brandKey, 32, "品牌設定 brandKey");
  validateSenderCredentialsDirectory(value.senderCredentialsDirectory);
  if ("eventKey" in value) throw new Error("品牌管理設定不得包含通知金鑰");
  if (value.generation !== undefined && (!Number.isSafeInteger(value.generation) || value.generation < 0)) {
    throw new Error("品牌設定 generation 無效");
  }
  if (value.activeImageBase64 !== undefined) {
    const bytes = Buffer.from(value.activeImageBase64, "base64url");
    if (!bytes.length || bytes.length > MAX_IMAGE_BYTES || bytes.toString("base64url") !== value.activeImageBase64) {
      throw new Error("品牌設定圖片無效");
    }
    validateBrandPng(bytes);
  }
  if (value.brandColor !== undefined) validateBrandColor(value.brandColor);
  if (value.backgroundColor !== undefined) validateBackgroundColor(value.backgroundColor);
  if (value.activeImageBase64 === undefined &&
      (value.brandColor !== undefined || value.backgroundColor !== undefined)) {
    throw new Error("DenDen 顏色不能脫離圖片存在");
  }
  return value;
}

export async function runDirectBrandCommand(argv, context = {}) {
  const action = argv[0];
  if (!new Set(["preview", "apply", "reset", "resume"]).has(action)) {
    throw new Error("用法：denden setup brand <preview|apply|reset|resume>");
  }
  const options = parseOptions(argv.slice(1));
  if (action === "preview") {
    const sourcePath = resolve(required(options.image, "--image"));
    const outputPath = resolve(required(options.output, "--output"));
    if (sourcePath === outputPath) throw new Error("白底預覽不能覆寫透明原圖");
    const preview = await (context.createWhitePreview || createWhiteBrandPreview)(sourcePath);
    await (context.writeFile || writeFile)(outputPath, preview, { flag: "wx" });
    return {
      action,
      sourcePath,
      outputPath,
      next: "請顯示白底預覽；使用者採用後，直接將 sourcePath 的透明 PNG 傳給 brand apply，不得重新生成",
    };
  }
  const dailyPath = options["config-path"] || userConfigPath(context.env);
  const configPath = options["brand-config-path"] || join(dirname(dailyPath), "brand-config.json");
  const pendingPath = options["pending-path"] || join(dirname(configPath), "brand-pending.json");
  const read = context.readJson || readJson;
  const write = context.writePrivateJson || writePrivateJson;
  const send = context.sendBrandMessages || sendBrandMessages;
  const config = validateBrandConfig(await read(configPath));
  let pending = await read(pendingPath, { required: false });
  if (pending && (pending.version !== 2 || !Array.isArray(pending.messages) || !Number.isSafeInteger(pending.generation) ||
      !Number.isSafeInteger(pending.nextIndex) || pending.nextIndex < 0 || pending.nextIndex > pending.messages.length)) {
    throw new Error("待續傳品牌格式無效");
  }

  if (action === "resume") {
    if (!pending) throw new Error("沒有待續傳的品牌");
  } else {
    if (pending && pending.nextIndex < pending.messages.length) {
      throw new Error("已有待續傳品牌，請先執行 denden setup brand resume");
    }
    const generation = (config.generation || 0) + 1;
    if (action === "apply") {
      const imagePath = required(options.image, "--image");
      if ("color" in options) throw new Error("--color 已移除，請使用 --background-color");
      const appearance = validateAppearanceColors({
        ...(options["brand-color"] ? { brandColor: options["brand-color"] } : {}),
        ...(options["background-color"] ? { backgroundColor: options["background-color"] } : {}),
      });
      const image = await (context.prepareImage || prepareDirectBrandImage)(imagePath);
      pending = {
        version: 2,
        action,
        generation,
        nextIndex: 0,
        activeImageBase64: Buffer.from(image).toString("base64url"),
        ...appearance,
        messages: buildBrandTransfer(image, appearance, config, {
          generation,
          nowMillis: context.nowMillis,
        }),
      };
    } else {
      pending = {
        version: 2,
        action,
        generation,
        nextIndex: 0,
        messages: [{ data: buildBrandReset(config, { generation, nowMillis: context.nowMillis }), kind: "brand-reset" }],
      };
    }
    await write(pendingPath, pending);
  }

  const startIndex = action === "resume" && pending.nextIndex === pending.messages.length ? 0 : pending.nextIndex;
  const remaining = pending.messages.slice(startIndex);
  const result = await send(remaining, config, {
    ...context,
    onAccepted: async (relativeIndex, messageId) => {
      pending.nextIndex = startIndex + relativeIndex + 1;
      pending.lastAcceptedMessageId = messageId;
      await write(pendingPath, pending);
      await context.onAccepted?.(pending.nextIndex, messageId);
    },
  });
  pending.nextIndex = pending.messages.length;
  await write(pendingPath, pending);
  const nextConfig = pending.action === "apply"
    ? withActiveBrand(config, pending)
    : withoutActiveBrand({ ...config, generation: pending.generation });
  await write(configPath, nextConfig);
  return {
    action: pending.action,
    generation: pending.generation,
    acceptedByFcm: startIndex + result.acceptedByFcm,
    delivered: false,
    next: "請確認手機前景顯示新的 DenDen 外觀；若尚未套用，立即執行 denden setup brand resume 重送同一代封包",
  };
}

function withoutActiveBrand(config) {
  const value = { ...config };
  delete value.activeImageBase64;
  delete value.brandColor;
  delete value.backgroundColor;
  return value;
}

function withActiveBrand(config, pending) {
  const value = { ...withoutActiveBrand(config), generation: pending.generation, activeImageBase64: pending.activeImageBase64 };
  for (const name of ["brandColor", "backgroundColor"]) {
    if (pending[name] !== undefined) value[name] = pending[name];
  }
  return value;
}

function validateAppearanceColors(value = {}) {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).some((name) => !new Set(["brandColor", "backgroundColor"]).has(name))) {
    throw new Error("DenDen 顏色設定無效");
  }
  return {
    ...(value.brandColor !== undefined ? { brandColor: validateBrandColor(value.brandColor) } : {}),
    ...(value.backgroundColor !== undefined ? { backgroundColor: validateBackgroundColor(value.backgroundColor) } : {}),
  };
}

function parseOptions(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key?.startsWith("--")) throw new Error(`未知品牌參數：${key}`);
    const value = argv[++index];
    if (!value || value.startsWith("--")) throw new Error(`${key} 缺少值`);
    options[key.slice(2)] = value;
  }
  return options;
}

function required(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} 無效`);
  return value.trim();
}

function validateBrandPng(bytes) {
  if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) throw new Error("遠端品牌 PNG 大小無效");
  const image = runPng(bytes);
  if (image.width !== 512 || image.height !== 512) throw new Error("遠端品牌 PNG 必須是 512×512");
  let transparent = false;
  for (let index = 3; index < image.data.length; index += 4) {
    if (image.data[index] < 255) { transparent = true; break; }
  }
  if (!transparent) throw new Error("遠端品牌 PNG 必須包含透明區域");
}

function runPng(bytes) {
  try { return PNG.sync.read(bytes); } catch { throw new Error("遠端品牌圖片不是有效 PNG"); }
}
