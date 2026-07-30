import { createCipheriv, createDecipheriv, randomBytes, scrypt as scryptCallback } from "node:crypto";
import { promisify } from "node:util";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { readFile } from "node:fs/promises";
import { readJson, userConfigPath, writePrivateJson, writeProtectedExportFile } from "./config.mjs";
import { validateBrandConfig } from "./direct-branding.mjs";
import { validateDirectFcmSenderConfig } from "./fcm-client.mjs";

const scrypt = promisify(scryptCallback);
const FORMAT = "DENDEN-PORTABLE-V1";
const MAX_FILE_BYTES = 256 * 1024;

export async function runPortableConfigCommand(argv, context = {}) {
  const operation = argv[0];
  const kind = argv[1];
  if (!new Set(["export", "import"]).has(operation) || !new Set(["sender", "brand"]).has(kind)) {
    throw new Error("用法：denden setup <export|import> <sender|brand> --output/--input <檔案>");
  }
  const options = parseOptions(argv.slice(2));
  const configPath = resolve(options["config-path"] || userConfigPath(context.env));
  const root = dirname(configPath);
  const brandConfigPath = resolve(options["brand-config-path"] || join(root, "brand-config.json"));
  assertContained(root, brandConfigPath, "品牌設定");
  const passwordProvider = context.passwordProvider || readHiddenPasswordTwice;
  if (operation === "export") {
    const outputPath = resolve(required(options.output, "--output"));
    const source = kind === "sender"
      ? exportableSender(validateDirectFcmSenderConfig(await readJson(configPath)))
      : exportableBrand(validateBrandConfig(await readJson(brandConfigPath)));
    const password = await passwordProvider({ confirm: true, purpose: `匯出 ${kind}` });
    const envelope = await encryptPortable(kind, source, password, context);
    await (context.writeProtectedExportFile || writeProtectedExportFile)(outputPath, `${JSON.stringify(envelope, null, 2)}\n`);
    return {
      exported: true,
      kind,
      outputPath,
      containsGoogleCredential: false,
      note: kind === "sender" ? "一般設定包不含品牌金鑰或 Google credential" : "品牌管理設定包含品牌金鑰，請離線保管",
    };
  }
  const inputPath = resolve(required(options.input, "--input"));
  const bytes = await readFile(inputPath);
  if (!bytes.length || bytes.length > MAX_FILE_BYTES) throw new Error("可攜設定包大小無效");
  const password = await passwordProvider({ confirm: false, purpose: `匯入 ${kind}` });
  const payload = await decryptPortable(JSON.parse(bytes.toString("utf8")), password, kind);
  const senderCredentialsDirectory = resolve(required(options["sender-config-dir"], "--sender-config-dir"));
  assertContained(root, senderCredentialsDirectory, "日常發送憑證目錄");
  if (kind === "sender") {
    const value = validateDirectFcmSenderConfig({ ...payload, senderCredentialsDirectory });
    const reused = await writeImportedConfig(configPath, value);
    return { imported: true, reused, kind, configPath, requiresOwnGoogleAuthorization: true };
  }
  const value = validateBrandConfig({ ...payload, senderCredentialsDirectory });
  const reused = await writeImportedConfig(brandConfigPath, value);
  return { imported: true, reused, kind, brandConfigPath, requiresOwnGoogleAuthorization: true };
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

function exportableSender(config) {
  const value = { ...config };
  delete value.senderCredentialsDirectory;
  if ("brandKey" in value || "brandKeyId" in value) throw new Error("一般設定意外包含品牌權限");
  return value;
}

function exportableBrand(config) {
  const value = { ...config };
  delete value.senderCredentialsDirectory;
  if ("eventKey" in value || "eventKeyId" in value) throw new Error("品牌設定意外包含通知權限");
  return value;
}

async function deriveKey(password, salt) {
  return scrypt(password, salt, 32, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
}

function validatePassword(value) {
  if (typeof value !== "string" || value.length < 12 || value.length > 1024) throw new Error("設定包密碼至少需要 12 個字元");
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

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}
