import { createCipheriv, createDecipheriv, randomBytes, scrypt as scryptCallback } from "node:crypto";
import { promisify } from "node:util";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { readFile } from "node:fs/promises";
import {
  assertPrivateDirectory,
  assertPrivateFile,
  readJson,
  userConfigPath,
  writePrivateJson,
  writeProtectedExportFile,
} from "./config.mjs";
import { validateBrandConfig } from "./direct-branding.mjs";
import {
  SENDER_SERVICE_ACCOUNT_FILE,
  validateDirectFcmSenderConfig,
  validateSenderServiceAccountKey,
} from "./fcm-client.mjs";
import { digest, fingerprint } from "./setup-plan.mjs";

const scrypt = promisify(scryptCallback);
const FORMAT = "DENDEN-PORTABLE-V1";
const SENDER_FORMAT = "DENDEN-SENDER-TRANSFER-V1";
const MAX_FILE_BYTES = 256 * 1024;

export async function runPortableConfigCommand(argv, context = {}) {
  const operation = argv[0];
  const kind = argv[1];
  if (!new Set(["export", "import", "import-plan"]).has(operation) || !new Set(["sender", "brand"]).has(kind)) {
    throw new Error("用法：denden setup <export|import|import-plan> <sender|brand> --output/--input <檔案>");
  }
  const options = parseOptions(argv.slice(2));
  const configPath = resolve(options["config-path"] || userConfigPath(context.env));
  const root = dirname(configPath);
  const brandConfigPath = resolve(options["brand-config-path"] || join(root, "brand-config.json"));
  assertContained(root, brandConfigPath, "品牌設定");
  const passwordProvider = context.passwordProvider || readHiddenPasswordTwice;
  if (operation === "export") {
    const outputPath = resolve(required(options.output, "--output"));
    const sender = kind === "sender";
    const source = sender
      ? await exportableSender(validateDirectFcmSenderConfig(await readJson(configPath)), context)
      : exportableBrand(validateBrandConfig(await readJson(brandConfigPath)));
    const envelope = sender
      ? senderTransfer(source)
      : await encryptPortable(kind, source, await passwordProvider({ confirm: true, purpose: `匯出 ${kind}` }), context);
    await (context.writeProtectedExportFile || writeProtectedExportFile)(outputPath, `${JSON.stringify(envelope, null, 2)}\n`);
    return {
      exported: true,
      kind,
      outputPath,
      encrypted: !sender,
      containsGoogleCredential: sender,
      note: sender
        ? "一般轉移包未加密，包含共用 Google 發送私鑰與通知金鑰；取得檔案即取得完整發送權限"
        : "品牌管理設定包含品牌金鑰，請離線保管",
    };
  }
  const inputPath = resolve(required(options.input, "--input"));
  const bytes = await readFile(inputPath);
  if (!bytes.length || bytes.length > MAX_FILE_BYTES) throw new Error("可攜設定包大小無效");
  const parsed = JSON.parse(bytes.toString("utf8"));
  const senderCredentialsDirectory = resolve(options["sender-config-dir"] || join(root, "gcloud-sender"));
  assertContained(root, senderCredentialsDirectory, "日常發送憑證目錄");
  if (kind === "sender") {
    const payload = parseSenderTransfer(parsed);
    validateSenderPayload(payload);
    const value = validateDirectFcmSenderConfig({ ...payload.config, senderCredentialsDirectory });
    validateSenderServiceAccountKey(payload.senderCredential, value.projectId);
    if (operation === "import-plan") {
      return (await inspectImportedSender(configPath, value, payload.senderCredential)).plan;
    }
    const replaceExisting = booleanOption(options["replace-existing"], "--replace-existing");
    const result = await writeImportedSender(
      configPath,
      value,
      payload.senderCredential,
      replaceExisting,
      options["approved-digest"],
    );
    return { imported: true, ...result, kind, configPath, requiresOwnGoogleAuthorization: false };
  }
  if (operation === "import-plan") throw new Error("import-plan 只支援 sender 設定包");
  const payload = await decryptPortable(
    parsed,
    await passwordProvider({ confirm: false, purpose: `匯入 ${kind}` }),
    kind,
  );
  const value = validateBrandConfig({ ...payload, senderCredentialsDirectory });
  const reused = await writeImportedConfig(brandConfigPath, value);
  return { imported: true, reused, kind, brandConfigPath, requiresOwnGoogleAuthorization: false, requiresSenderConfig: true };
}

export async function encryptPortable(kind, value, password, options = {}) {
  validatePassword(password);
  const salt = (options.randomBytes || randomBytes)(16);
  const nonce = (options.randomBytes || randomBytes)(12);
  const key = await deriveKey(password, salt);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(`${FORMAT}\n${kind}`));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
  return {
    format: FORMAT,
    kind,
    kdf: { name: "scrypt", n: 16384, r: 8, p: 1, salt: salt.toString("base64url") },
    cipher: {
      name: "aes-256-gcm",
      nonce: nonce.toString("base64url"),
      ciphertext: ciphertext.toString("base64url"),
      tag: cipher.getAuthTag().toString("base64url"),
    },
  };
}

export async function decryptPortable(envelope, password, expectedKind) {
  validatePassword(password);
  if (!envelope || envelope.format !== FORMAT || envelope.kind !== expectedKind) throw new Error("可攜設定包格式或種類無效");
  if (envelope.kdf?.name !== "scrypt" || envelope.kdf.n !== 16384 || envelope.kdf.r !== 8 || envelope.kdf.p !== 1) {
    throw new Error("可攜設定包 KDF 參數無效");
  }
  if (envelope.cipher?.name !== "aes-256-gcm") throw new Error("可攜設定包加密格式無效");
  try {
    const salt = canonicalBase64(envelope.kdf.salt, 16);
    const nonce = canonicalBase64(envelope.cipher.nonce, 12);
    const tag = canonicalBase64(envelope.cipher.tag, 16);
    const ciphertext = canonicalBase64(envelope.cipher.ciphertext);
    if (!ciphertext.length || ciphertext.length > MAX_FILE_BYTES) throw new Error("ciphertext size");
    const key = await deriveKey(password, salt);
    const decipher = createDecipheriv("aes-256-gcm", key, nonce);
    decipher.setAAD(Buffer.from(`${FORMAT}\n${expectedKind}`));
    decipher.setAuthTag(tag);
    return JSON.parse(Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8"));
  } catch {
    throw new Error("密碼錯誤或可攜設定包已損壞");
  }
}

async function exportableSender(config, context) {
  const value = { ...config };
  delete value.senderCredentialsDirectory;
  if ("brandKey" in value || "brandKeyId" in value) throw new Error("一般設定意外包含品牌權限");
  const directory = config.senderCredentialsDirectory;
  const keyPath = join(directory, SENDER_SERVICE_ACCOUNT_FILE);
  await (context.assertPrivateDirectory || assertPrivateDirectory)(directory);
  await (context.assertPrivateFile || assertPrivateFile)(keyPath);
  const senderCredential = await readJson(keyPath);
  validateSenderServiceAccountKey(senderCredential, config.projectId);
  return { config: value, senderCredential };
}

function senderTransfer(value) {
  validateSenderPayload(value);
  return { format: SENDER_FORMAT, kind: "sender", ...value };
}

function parseSenderTransfer(value) {
  if (value?.format === FORMAT && value?.kind === "sender") {
    throw new Error("舊版一般設定包需要密碼且不支援直接移轉；請由已設定電腦重新匯出");
  }
  if (!value || typeof value !== "object" || Array.isArray(value) || value.format !== SENDER_FORMAT || value.kind !== "sender" ||
      Object.keys(value).sort().join("\n") !== "config\nformat\nkind\nsenderCredential") {
    throw new Error("一般轉移包內容無效");
  }
  return { config: value.config, senderCredential: value.senderCredential };
}

function exportableBrand(config) {
  const value = { ...config };
  delete value.senderCredentialsDirectory;
  if ("eventKey" in value || "eventKeyId" in value) throw new Error("品牌設定意外包含通知權限");
  return value;
}

function validateSenderPayload(value) {
  if (!value?.config || !value?.senderCredential) {
    throw new Error("舊版一般設定包不含共用 Google 發送私鑰；請由已設定電腦重新匯出");
  }
  if (typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).sort().join("\n") !== "config\nsenderCredential") {
    throw new Error("一般設定包內容無效");
  }
}

async function deriveKey(password, salt) {
  return scrypt(password, salt, 32, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
}

function validatePassword(value) {
  if (typeof value !== "string" || value.length < 12) throw new Error("設定包密碼至少需要 12 個字元");
  if (value.length > 1024) throw new Error("設定包密碼過長");
}

function canonicalBase64(value, size) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("base64");
  const bytes = Buffer.from(value, "base64url");
  if (bytes.toString("base64url") !== value || (size !== undefined && bytes.length !== size)) throw new Error("base64");
  return bytes;
}

async function readHiddenPasswordTwice({ confirm }) {
  if (!process.stdin.isTTY || !process.stdout.isTTY || typeof process.stdin.setRawMode !== "function") {
    throw new Error("此操作需要可關閉回顯的本機互動終端；請勿把密碼放入命令列或環境變數");
  }
  const first = await readHiddenLine("設定包密碼：");
  if (!confirm) return first;
  const second = await readHiddenLine("再次輸入密碼：");
  if (first !== second) throw new Error("兩次密碼不一致");
  return first;
}

function readHiddenLine(prompt) {
  return new Promise((resolvePromise, reject) => {
    const input = process.stdin;
    const output = process.stdout;
    let value = "";
    const cleanup = () => {
      input.off("data", onData);
      input.setRawMode(false);
      input.pause();
      output.write("\n");
    };
    const onData = (chunk) => {
      const text = String(chunk);
      for (const char of text) {
        if (char === "\r" || char === "\n") {
          cleanup();
          resolvePromise(value);
          return;
        }
        if (char === "\u0003") {
          cleanup();
          reject(new Error("使用者取消密碼輸入"));
          return;
        }
        if (char === "\u007f" || char === "\b") value = [...value].slice(0, -1).join("");
        else if (char >= " ") value += char;
      }
    };
    output.write(prompt);
    input.setEncoding("utf8");
    input.setRawMode(true);
    input.resume();
    input.on("data", onData);
  });
}

function parseOptions(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key?.startsWith("--")) throw new Error(`未知設定包參數：${key}`);
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

function booleanOption(value, name) {
  if (value === undefined) return false;
  if (value !== "true") throw new Error(`${name} 只接受 true`);
  return true;
}

function assertContained(root, target, label) {
  const path = relative(root, target);
  if (!path || path.startsWith("..") || isAbsolute(path)) throw new Error(`${label}必須位於 DenDen 設定目錄內`);
}

async function writeImportedConfig(path, value) {
  const existing = await readJson(path, { required: false });
  if (existing) {
    if (stable(existing) !== stable(value)) throw new Error("匯入目的地已有不同 DenDen 設定，拒絕覆寫");
    return true;
  }
  await writePrivateJson(path, value);
  return false;
}

async function writeImportedSender(configPath, value, senderCredential, replaceExisting, approvedDigest) {
  const state = await inspectImportedSender(configPath, value, senderCredential);
  const { existingConfig, existingCredential, sameConfig, sameCredential } = state;
  if (existingConfig && !sameConfig && !replaceExisting) {
    throw new Error("匯入目的地已有不同 DenDen 設定，拒絕覆寫");
  }
  if (existingCredential && !sameCredential && !replaceExisting) {
    throw new Error("匯入目的地已有不同 Google 發送私鑰，拒絕覆寫");
  }
  if (approvedDigest !== state.plan.approvalDigest) {
    throw new Error("發送設定匯入摘要尚未取得明確同意；請先執行 denden setup import-plan sender");
  }
  const keyPath = join(value.senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE);
  // ponytail: two protected files can briefly diverge after a disk failure; rerun import-plan before repairing them.
  if (!sameCredential) await writePrivateJson(keyPath, senderCredential);
  if (!sameConfig) await writePrivateJson(configPath, value);
  return {
    reused: Boolean(sameConfig && sameCredential),
    replaced: state.plan.replacementRequired,
  };
}

async function inspectImportedSender(configPath, value, senderCredential) {
  const keyPath = join(value.senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE);
  const existingConfig = await readJson(configPath, { required: false });
  const existingCredential = await readJson(keyPath, { required: false });
  if (existingConfig) await assertPrivateFile(configPath);
  if (existingCredential) await assertPrivateFile(keyPath);
  const sameConfig = existingConfig && stable(existingConfig) === stable(value);
  const sameCredential = existingCredential && stable(existingCredential) === stable(senderCredential);
  const replacementRequired = Boolean(existingConfig && !sameConfig || existingCredential && !sameCredential);
  const approvalValue = {
    schemaVersion: 1,
    action: replacementRequired ? "replace-shared-sender-transfer" : "import-shared-sender-transfer",
    configPath,
    keyPath,
    existingConfig,
    existingCredential,
    incomingConfig: value,
    incomingCredential: senderCredential,
  };
  const plan = {
    schemaVersion: 1,
    action: "import-shared-sender-transfer",
    projectId: value.projectId,
    configPath,
    senderCredentialsDirectory: value.senderCredentialsDirectory,
    currentFingerprint: existingConfig || existingCredential
      ? fingerprint(stable({ config: existingConfig, senderCredential: existingCredential }))
      : null,
    incomingFingerprint: fingerprint(stable({ config: value, senderCredential })),
    replacementRequired,
    effect: replacementRequired
      ? "覆寫目前 DenDen 發送設定與共用 Google 私鑰；只有在共用發送身分已核准更換後才能執行"
      : sameConfig && sameCredential
        ? "現有 DenDen 發送設定與轉移包相同，不需寫入"
        : "把未加密轉移包內的 DenDen 發送設定與共用 Google 私鑰寫入受保護目錄",
  };
  plan.approvalDigest = digest(approvalValue);
  return {
    existingConfig,
    existingCredential,
    sameConfig,
    sameCredential,
    plan,
  };
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}
