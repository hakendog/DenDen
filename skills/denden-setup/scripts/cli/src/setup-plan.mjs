import { createHash } from "node:crypto";

export function buildDirectSetupApproval({
  projectId,
  packageName = "com.tensal.denden",
  managementAccount,
  managementConfigDirectory,
  senderCredentialsDirectory,
  configPath,
  brandConfigPath,
  qrPath,
  setupStatePath,
  pairingStatePath,
  projectMode = "new-project",
}) {
  const normalizedProjectId = validateProjectId(projectId);
  if (!["new-project", "existing-project"].includes(projectMode)) throw new Error("projectMode 無效");
  const value = {
    schemaVersion: 1,
    action: "prepare-direct-fcm",
    projectId: normalizedProjectId,
    androidPackageName: validatePackageName(packageName),
    managementAccount: validateGoogleAccount(managementAccount),
    projectMode,
    billingRequirement: "billing-disabled",
    managementConfigDirectory: requiredPath(managementConfigDirectory, "managementConfigDirectory"),
    senderCredentialsDirectory: requiredPath(senderCredentialsDirectory, "senderCredentialsDirectory"),
    configPath: requiredPath(configPath, "configPath"),
    brandConfigPath: requiredPath(brandConfigPath, "brandConfigPath"),
    qrPath: requiredPath(qrPath, "qrPath"),
    setupStatePath: requiredPath(setupStatePath, "setupStatePath"),
    pairingStatePath: requiredPath(pairingStatePath, "pairingStatePath"),
    writes: [
      projectMode === "new-project"
        ? "建立全新且未連結帳務的 Google Cloud 專案，並加入 DenDen 擁有權標籤"
        : "採用使用者指定的既有未連結帳務 Google Cloud 專案；不建立新專案",
      "啟用 Firebase Management API、Firebase Cloud Messaging API 與建立專用發送身分所需的 IAM API",
      "視需要執行不可完整復原的 projects.addFirebase",
      `視需要註冊 Android App ${packageName}`,
      "在本機受保護目錄暫存可續跑的設定狀態，完成後刪除配對暫存檔",
      "在本機受保護目錄建立日常設定、DenDen 外觀管理設定與單一 QR Code",
    ],
    excluded: [
      "不連結帳務",
      "不啟用 Analytics、Firestore、Functions、Hosting、Storage、Budget 或 Secret Manager",
      "初次 Firebase 建立階段不建立服務帳戶金鑰；後續另經核准，只建立一把可透過受保護轉移包共用的最低權限金鑰",
      "不安裝或使用 Firebase CLI",
    ],
  };
  return { ...value, approvalDigest: digest(value) };
}

export function buildRotateApproval(config) {
  const value = {
    schemaVersion: 1,
    action: "rotate-pairing",
    projectId: validateProjectId(config?.projectId),
    currentPairingFingerprint: fingerprint(config?.pairingId),
    effect: "產生新的私有主題與兩把新金鑰；所有保留手機都必須重新掃描",
  };
  return { ...value, approvalDigest: digest(value) };
}

export function digest(value) {
  return createHash("sha256").update(stable(value)).digest("hex");
}

export function fingerprint(value) {
  if (typeof value !== "string" || !value) return null;
  return createHash("sha256").update(value).digest("hex").slice(0, 12);
}

export function validateProjectId(value) {
  const normalized = String(value || "").trim();
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(normalized)) throw new Error("project ID 無效");
  return normalized;
}

export function validateGoogleAccount(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) || normalized.length > 254) {
    throw new Error("Google 管理帳號電子郵件無效");
  }
  return normalized;
}

function validatePackageName(value) {
  const normalized = String(value || "").trim();
  if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(normalized)) throw new Error("Android 套件名稱無效");
  return normalized;
}

function requiredPath(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} 無效`);
  return value.trim();
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}
