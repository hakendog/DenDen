import { randomBytes as cryptoRandomBytes, randomUUID } from "node:crypto";
import { rm } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import QRCode from "qrcode";
import { encodeDirectFcmInvite, parseDirectFcmInvite } from "./direct-fcm-protocol.mjs";
import { readJson, userConfigPath, writePrivateFile, writePrivateJson } from "./config.mjs";
import { runExternal } from "./process.mjs";
import { buildUserInstallPlan, inspectSourceCheckout, installUserTools, parseInstallGuideUrl, rollbackUserTools } from "./source.mjs";
import { buildDirectSetupApproval, validateGoogleAccount } from "./setup-plan.mjs";
import { validateDirectFcmSenderConfig } from "./fcm-client.mjs";
import { validateBrandConfig } from "./direct-branding.mjs";

export function createDirectPairingBundle(firebase, options = {}) {
  const randomBytes = options.randomBytes || cryptoRandomBytes;
  const nowMillis = options.nowMillis ?? Date.now();
  const displayExpiresAtMillis = nowMillis + (options.displayTtlMillis ?? 10 * 60_000);
  const secret = (size) => randomBytes(size).toString("base64url");
  const invite = {
    v: 2,
    projectId: required(firebase.projectId, "projectId"),
    firebaseAppId: required(firebase.firebaseAppId, "firebaseAppId"),
    apiKey: required(firebase.apiKey, "apiKey"),
    gcmSenderId: required(firebase.gcmSenderId, "gcmSenderId"),
    androidPackageName: required(firebase.androidPackageName, "androidPackageName"),
    pairingId: secret(16),
    topic: secret(32),
    eventKeyId: secret(16),
    eventKey: secret(32),
    brandKeyId: secret(16),
    brandKey: secret(32),
    createdAtMillis: nowMillis,
    displayExpiresAtMillis,
  };
  const encodedInvite = encodeDirectFcmInvite(invite, { nowMillis });
  const senderCredentialsDirectory = required(options.senderCredentialsDirectory, "senderCredentialsDirectory");
  return pairingBundleFromInvite(invite, encodedInvite, senderCredentialsDirectory);
}

function pairingBundleFromInvite(invite, encodedInvite, senderCredentialsDirectory) {
  return {
    encodedInvite,
    invite,
    senderConfig: {
      schemaVersion: 2,
      projectId: invite.projectId,
      firebaseAppId: invite.firebaseAppId,
      apiKey: invite.apiKey,
      gcmSenderId: invite.gcmSenderId,
      androidPackageName: invite.androidPackageName,
      pairingId: invite.pairingId,
      topic: invite.topic,
      eventKeyId: invite.eventKeyId,
      eventKey: invite.eventKey,
      senderCredentialsDirectory,
    },
    brandConfig: {
      schemaVersion: 2,
      projectId: invite.projectId,
      firebaseAppId: invite.firebaseAppId,
      apiKey: invite.apiKey,
      gcmSenderId: invite.gcmSenderId,
      androidPackageName: invite.androidPackageName,
      pairingId: invite.pairingId,
      topic: invite.topic,
      brandKeyId: invite.brandKeyId,
      brandKey: invite.brandKey,
      senderCredentialsDirectory,
      generation: 0,
    },
  };
}

export async function writeDirectPairingBundle(bundle, options = {}) {
  const configPath = resolve(options.configPath || userConfigPath(options.env));
  const root = dirname(configPath);
  const brandConfigPath = resolve(options.brandConfigPath || join(root, "brand-config.json"));
  const qrPath = resolve(options.qrPath || join(root, "pairing-qr.png"));
  assertPrivateArtifactPath(brandConfigPath, root, "品牌設定");
  assertPrivateArtifactPath(qrPath, root, "QR 圖檔");
  if (!qrPath.toLowerCase().endsWith(".png")) throw new Error("QR 圖檔必須使用 .png 副檔名");
  if (qrPath === configPath || qrPath === brandConfigPath) throw new Error("QR 圖檔不得覆蓋其他 DenDen 設定");
  await writePrivateJson(configPath, bundle.senderConfig);
  await writePrivateJson(brandConfigPath, bundle.brandConfig);
  await writePrivateFile(qrPath, await QRCode.toBuffer(bundle.encodedInvite, {
    type: "png",
    errorCorrectionLevel: "M",
    margin: 2,
    width: 768,
  }));
  return { configPath, brandConfigPath, qrPath, displayExpiresAtMillis: bundle.invite.displayExpiresAtMillis };
}

export function parseAndroidAppConfig(configFileContents, expectedPackageName) {
  const decoded = Buffer.from(required(configFileContents, "configFileContents"), "base64").toString("utf8");
  const value = JSON.parse(decoded);
  const projectId = required(value.project_info?.project_id, "projectId");
  const gcmSenderId = required(value.project_info?.project_number, "gcmSenderId");
  const clients = Array.isArray(value.client) ? value.client : [];
  const client = clients.find((item) => item.client_info?.android_client_info?.package_name === expectedPackageName);
  if (!client) throw new Error("Firebase Android App 設定與 DenDen 套件名稱不符");
  return {
    projectId,
    gcmSenderId,
    firebaseAppId: required(client.client_info?.mobilesdk_app_id, "firebaseAppId"),
    apiKey: required(client.api_key?.[0]?.current_key, "apiKey"),
    androidPackageName: expectedPackageName,
  };
}

export async function provisionDirectFirebaseProject(options = {}) {
  const projectId = required(options.projectId, "projectId");
  const projectMode = options.allowExistingDedicated ? "existing-project" : "new-project";
  const packageName = options.packageName || "com.tensal.denden";
  const managementConfigDirectory = required(options.managementConfigDirectory, "managementConfigDirectory");
  const env = { ...(options.env || process.env), CLOUDSDK_CONFIG: managementConfigDirectory };
  const commandTimeoutMillis = normalizeSetupTimeout(options.commandTimeoutMillis ?? 60_000, "外部命令");
  const httpTimeoutMillis = normalizeSetupTimeout(options.httpTimeoutMillis ?? 20_000, "Firebase HTTP");
  const totalWaitMillis = normalizeSetupTotalWait(options.totalWaitMillis ?? 180_000);
  const runner = options.runner || ((command, args) => runExternal(command, args, { env, timeoutMillis: commandTimeoutMillis }));
  const fetchImpl = options.fetchImpl || fetch;
  const setupStatePath = options.setupStatePath;
  const readState = options.readState || (setupStatePath ? () => readJson(setupStatePath, { required: false }) : async () => null);
  const writeState = options.writeState || (setupStatePath ? (value) => writePrivateJson(setupStatePath, value) : async () => {});
  const removeState = options.removeState || (setupStatePath ? () => rm(setupStatePath, { force: true }) : async () => {});
  let setupState = await readState();
  if (!setupState) {
    setupState = { schemaVersion: 2, projectId, packageName, projectMode, setupId: randomUUID(), step: "start" };
  }
  const savedProjectMode = setupState.projectMode || "new-project";
  if (setupState.schemaVersion !== 2 || setupState.projectId !== projectId || setupState.packageName !== packageName ||
      savedProjectMode !== projectMode || typeof setupState.setupId !== "string" || !/^[0-9a-f-]{36}$/.test(setupState.setupId)) {
    throw new Error("既有設定續作狀態屬於不同專案或套件，拒絕覆寫");
  }
  const saveState = async (patch) => {
    setupState = { ...setupState, ...patch, updatedAtMillis: Date.now() };
    await writeState(setupState);
  };
  const managementAccount = await assertSingleManagementAccount(runner);
  if (options.expectedManagementAccount && managementAccount !== validateGoogleAccount(options.expectedManagementAccount)) {
    throw new Error("目前 Google 管理帳號與已核准規劃不符；請重新執行 denden setup plan");
  }
  const projects = JSON.parse((await runner("gcloud", [
    "projects", "list", `--filter=projectId=${projectId}`, "--format=json",
  ])).stdout || "[]");
  let cloudProject;
  if (!projects.some((project) => project.projectId === projectId)) {
    if (projectMode === "existing-project") {
      throw new Error("指定的既有 Google Cloud 專案不存在，或目前帳號無權存取");
    }
    await saveState({ step: "project-create-planned" });
    try {
      cloudProject = JSON.parse((await runner("gcloud", [
        "projects", "create", projectId, "--name=DenDen",
        `--labels=denden-managed=true,denden-setup=${setupState.setupId}`,
        "--format=json",
      ])).stdout || "{}");
    } catch (error) {
      if (/quota|resource_exhausted|project(?: creation)? limit/i.test(String(error?.message || error))) {
        await removeState();
        throw new Error("Google Cloud 專案建立配額不足；請改選既有未連結帳務的專案", { cause: error });
      }
      throw error;
    }
    await saveState({ step: "project-created" });
  } else {
    cloudProject = JSON.parse((await runner("gcloud", ["projects", "describe", projectId, "--format=json"])).stdout || "{}");
    if (projectMode === "new-project" && (setupState.step === "start" || cloudProject.labels?.["denden-managed"] !== "true" ||
        cloudProject.labels?.["denden-setup"] !== setupState.setupId)) {
      throw new Error("project ID 已存在；若它是使用者確認的未連帳務 DenDen 專用專案，請重新規劃並加入 --allow-existing-dedicated");
    }
  }
  if (cloudProject.projectId !== projectId || (cloudProject.lifecycleState && cloudProject.lifecycleState !== "ACTIVE") ||
      !/^\d{6,20}$/.test(String(cloudProject.projectNumber || ""))) {
    throw new Error("Google Cloud 專案識別資料無效");
  }
  const projectNumber = String(cloudProject.projectNumber);
  if (setupState.projectNumber && setupState.projectNumber !== projectNumber) throw new Error("既有專案識別已改變，拒絕續作");
  await assertUnbilled(projectId, runner);
  if (projectMode === "existing-project" && setupState.step === "start") {
    await saveState({ step: "existing-project-selected", projectNumber });
  }
  await runner("gcloud", [
    "services", "enable", "firebase.googleapis.com", "fcm.googleapis.com", "iam.googleapis.com", "--project", projectId, "--quiet",
  ]);
  const accessToken = (await runner("gcloud", ["auth", "print-access-token"])).stdout.trim();
  if (!accessToken) throw new Error("無法取得 Google 管理存取權杖");
  const request = async (path, init = {}, timeoutMillis = httpTimeoutMillis) => {
    const safeRead = !init.method || init.method === "GET";
    const deadline = Date.now() + Math.max(1, Math.min(totalWaitMillis, timeoutMillis));
    for (let attempt = 0; attempt < (safeRead ? 3 : 1); attempt += 1) {
      let response;
      try {
        response = await fetchImpl(`https://firebase.googleapis.com/v1beta1/${path}`, {
          ...init,
          headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json", "x-goog-user-project": projectId, ...(init.headers || {}) },
          signal: init.signal || AbortSignal.timeout(Math.max(1, Math.min(httpTimeoutMillis, deadline - Date.now()))),
        });
      } catch (error) {
        if (error?.name === "AbortError" || error?.name === "TimeoutError") {
          throw new Error(`Firebase Management API 請求逾時（${path}）；可安全重跑目前階段`, { cause: error });
        }
        throw error;
      }
      const text = await response.text();
      const body = text ? JSON.parse(text) : {};
      if (safeRead && (response.status === 429 || response.status >= 500) && attempt < 2 && Date.now() < deadline) {
        await (options.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis))))(
          Math.min(250 * (2 ** attempt), Math.max(0, deadline - Date.now())),
        );
        continue;
      }
      if (!response.ok) {
        if (response.status === 403 && /terms|tos/i.test(text)) {
          throw new Error("請在 Firebase Console 點『建立專案』，再點頁面底部『將 Firebase 新增至 Google Cloud 專案』並選擇現有專案；接受必要條款並進入下一步後立即停止，不要完成新增，再重新執行")
        }
        throw new Error(`Firebase Management API 失敗（HTTP ${response.status}）`);
      }
      return body;
    }
    throw new Error(`Firebase Management API 讀取逾時（${path}）；可安全重跑目前階段`);
  };
  if (setupState.operationKind === "addFirebase" && setupState.operationName) {
    await waitForOperation(setupState.operationName, request, { delay: options.delay, totalWaitMillis });
    await saveState({ step: "firebase-ready", operationKind: null, operationName: null });
  } else {
    let firebaseProject = await fetchFirebaseProject(fetchImpl, projectId, accessToken, httpTimeoutMillis, { delay: options.delay });
    if (firebaseProject.status === 404 && setupState.step === "add-firebase-planned") {
      firebaseProject = await waitForFirebaseProject(fetchImpl, projectId, accessToken, {
        delay: options.delay,
        httpTimeoutMillis,
        totalWaitMillis,
      });
    }
    if (firebaseProject.status === 404) {
      await saveState({ step: "add-firebase-planned", operationKind: null, operationName: null });
      const operation = await request(`projects/${projectId}:addFirebase`, { method: "POST", body: "{}" });
      await saveState({ step: "add-firebase-running", operationKind: "addFirebase", operationName: operation.name });
      await waitForOperation(operation.name, request, { delay: options.delay, totalWaitMillis });
      await saveState({ step: "firebase-ready", operationKind: null, operationName: null });
    } else if (!firebaseProject.ok) {
      throw new Error(`Firebase 專案檢查失敗（HTTP ${firebaseProject.status}）`);
    } else if (!isAndroidAppStep(setupState.step)) {
      await saveState({ step: "firebase-ready" });
    }
  }
  let apps = await request(`projects/${projectId}/androidApps`);
  let app = (apps.apps || []).find((value) => value.packageName === packageName);
  if (!app && setupState.operationKind === "androidApp" && setupState.operationName) {
    const completed = await waitForOperation(setupState.operationName, request, { delay: options.delay, totalWaitMillis });
    app = completed.response;
    await saveState({ step: "android-app-ready", operationKind: null, operationName: null, appName: app?.name });
  } else if (!app && setupState.step === "android-app-planned") {
    apps = await waitForAndroidApp(request, projectId, packageName, { delay: options.delay, totalWaitMillis });
    app = (apps.apps || []).find((value) => value.packageName === packageName);
  }
  if (!app) {
    await saveState({ step: "android-app-planned", operationKind: null, operationName: null });
    const operation = await request(`projects/${projectId}/androidApps`, {
      method: "POST",
      body: JSON.stringify({ displayName: "DenDen Android", packageName }),
    });
    await saveState({ step: "android-app-running", operationKind: "androidApp", operationName: operation.name });
    const completed = await waitForOperation(operation.name, request, { delay: options.delay, totalWaitMillis });
    app = completed.response;
    await saveState({ step: "android-app-ready", operationKind: null, operationName: null, appName: app?.name });
  }
  if (!app?.name) throw new Error("Firebase Android App 建立結果無效");
  const appDetails = await request(app.name);
  if (appDetails.name !== app.name || appDetails.projectId !== projectId || appDetails.packageName !== packageName ||
      typeof appDetails.appId !== "string" || !appDetails.appId) {
    throw new Error("Firebase Android App 識別資料與核准內容不符");
  }
  const config = await request(`${app.name}/config`);
  await assertUnbilled(projectId, runner);
  await saveState({ step: "complete", operationKind: null, operationName: null, appName: app.name });
  const parsed = parseAndroidAppConfig(config.configFileContents, packageName);
  if (parsed.projectId !== projectId || parsed.gcmSenderId !== projectNumber || parsed.firebaseAppId !== appDetails.appId) {
    throw new Error("Firebase Android 設定與 Google Cloud 專案識別資料不一致");
  }
  return parsed;
}

export function buildDirectSetupPlan(argv, context = {}) {
  const options = parseOptions(argv);
  const configPath = resolve(options["config-path"] || userConfigPath(context.env));
  const root = dirname(configPath);
  const brandConfigPath = resolve(options["brand-config-path"] || join(root, "brand-config.json"));
  const qrPath = resolve(options["qr-path"] || join(root, "pairing-qr.png"));
  const setupStatePath = resolve(options["setup-state-path"] || join(root, "setup-state.json"));
  const pairingStatePath = resolve(options["pairing-state-path"] || join(root, "pairing-state.json"));
  const managementConfigDirectory = resolve(options["management-config-dir"] || join(root, "gcloud-management"));
  const senderCredentialsDirectory = resolve(options["sender-config-dir"] || join(root, "gcloud-sender"));
  for (const [label, target] of [
    ["品牌設定", brandConfigPath],
    ["QR 圖檔", qrPath],
    ["設定續作", setupStatePath],
    ["配對續作", pairingStatePath],
  ]) assertPrivateArtifactPath(target, root, label);
  if (!qrPath.toLowerCase().endsWith(".png")) throw new Error("QR 圖檔必須使用 .png 副檔名");
  if (new Set([configPath, brandConfigPath, setupStatePath, pairingStatePath]).has(qrPath)) throw new Error("QR 圖檔不得覆蓋其他 DenDen 設定");
  assertIsolatedCredentialDirectory(managementConfigDirectory, root, "管理憑證目錄");
  assertIsolatedCredentialDirectory(senderCredentialsDirectory, root, "日常憑證目錄");
  if (managementConfigDirectory === senderCredentialsDirectory) throw new Error("管理與日常憑證目錄不得共用");
  return buildDirectSetupApproval({
    projectId: required(options["project-id"], "--project-id"),
    packageName: options["package-name"] || "com.tensal.denden",
    managementAccount: context.managementAccount,
    managementConfigDirectory,
    senderCredentialsDirectory,
    configPath,
    brandConfigPath,
    qrPath,
    setupStatePath,
    pairingStatePath,
    projectMode: options["allow-existing-dedicated"] ? "existing-project" : "new-project",
  });
}

export async function runDirectSetupPlanCommand(argv, context = {}) {
  const managementAccount = context.managementAccount || await currentManagementAccount(argv, context);
  return buildDirectSetupPlan(argv, { ...context, managementAccount });
}

export async function runDirectSetupCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const configPath = resolve(options["config-path"] || userConfigPath(context.env));
  const root = dirname(configPath);
  const managementConfigDirectory = resolve(options["management-config-dir"] || join(root, "gcloud-management"));
  const senderCredentialsDirectory = resolve(options["sender-config-dir"] || join(root, "gcloud-sender"));
  const managementAccount = context.managementAccount || await currentManagementAccount(argv, context);
  const approval = buildDirectSetupPlan(argv, { ...context, managementAccount });
  if (options["approved-digest"] !== approval.approvalDigest) {
    throw new Error("設定摘要尚未取得明確同意，或核准後內容已改變；請先執行 denden setup plan");
  }
  const existingDaily = await readJson(configPath, { required: false });
  const existingBrand = await readJson(approval.brandConfigPath, { required: false });
  let checkpoint = await readJson(approval.pairingStatePath, { required: false });
  if (!checkpoint && (existingDaily || existingBrand)) {
    if (!existingDaily || !existingBrand) throw new Error("本機配對設定不完整且沒有安全續作檢查點；拒絕產生另一組金鑰");
    assertExistingPairingFiles(existingDaily, existingBrand, approval, senderCredentialsDirectory);
    return {
      configured: true,
      reused: true,
      projectId: approval.projectId,
      configPath,
      brandConfigPath: approval.brandConfigPath,
      qrPath: approval.qrPath,
      approvalDigest: approval.approvalDigest,
      next: "既有配對設定已沿用；請執行 denden setup status，需新 QR 時使用 denden setup qr",
    };
  }
  let bundle;
  if (checkpoint) {
    bundle = validatePairingCheckpoint(checkpoint, approval, senderCredentialsDirectory, context.nowMillis ?? Date.now());
    checkpoint = { ...checkpoint, bundle };
    await writePrivateJson(approval.pairingStatePath, checkpoint);
  } else {
    const firebase = await (context.provision || provisionDirectFirebaseProject)({
      projectId: required(options["project-id"], "--project-id"),
      packageName: options["package-name"] || "com.tensal.denden",
      managementConfigDirectory,
      expectedManagementAccount: approval.managementAccount,
      env: context.env,
      runner: context.runner,
      fetchImpl: context.fetchImpl,
      delay: context.delay,
      commandTimeoutMillis: context.commandTimeoutMillis,
      httpTimeoutMillis: context.httpTimeoutMillis,
      totalWaitMillis: context.totalWaitMillis,
      setupStatePath: approval.setupStatePath,
      allowExistingDedicated: options["allow-existing-dedicated"] === true,
    });
    bundle = createDirectPairingBundle(firebase, {
      senderCredentialsDirectory,
      nowMillis: context.nowMillis,
      randomBytes: context.randomBytes,
    });
    checkpoint = {
      schemaVersion: 1,
      projectId: approval.projectId,
      androidPackageName: approval.androidPackageName,
      senderCredentialsDirectory,
      bundle,
    };
    await writePrivateJson(approval.pairingStatePath, checkpoint);
  }
  const files = await (context.writePairingBundle || writeDirectPairingBundle)(bundle, {
    configPath,
    brandConfigPath: approval.brandConfigPath,
    qrPath: approval.qrPath,
    env: context.env,
  });
  await (context.removeFile || rm)(approval.pairingStatePath, { force: true });
  return {
    configured: true,
    reused: false,
    projectId: approval.projectId,
    ...files,
    managementConfigDirectory,
    senderCredentialsDirectory,
    approvalDigest: approval.approvalDigest,
    next: "請在手機開啟 DenDen 並掃描 QR Code；App 顯示已訂閱後再傳送測試通知",
  };
}

export async function runDirectInstallCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const source = parseInstallGuideUrl(required(options["guide-url"], "--guide-url"));
  const sourceRoot = options["source-root"] || context.sourceRoot || process.cwd();
  const inspection = await (context.inspect || inspectSourceCheckout)(sourceRoot, source, context.runner);
  if (!inspection.verified) throw new Error(`DenDen 來源驗證失敗：${inspection.errors.join("；")}`);
  const plan = buildUserInstallPlan(inspection, context.env);
  const installed = await (context.install || installUserTools)(plan, context.installOptions);
  return {
    installed: installed.installed,
    reused: installed.reused,
    commitSha: inspection.commitSha,
    versionDirectory: installed.versionDirectory,
    launcher: installed.launcher,
    setupSkill: join(installed.versionDirectory, "skills", "denden-setup", "SKILL.md"),
    dailySkill: join(installed.versionDirectory, "skills", "denden", "SKILL.md"),
  };
}

export async function runDirectRollbackCommand(argv, context = {}) {
  if (argv.length) throw new Error("setup rollback 不接受參數");
  return (context.rollback || rollbackUserTools)(context.env, context.rollbackOptions);
}

async function assertUnbilled(projectId, runner) {
  const billing = JSON.parse((await runner("gcloud", [
    "billing", "projects", "describe", projectId, "--format=json",
  ])).stdout || "{}");
  if (billing.billingEnabled === true || billing.billingAccountName) throw new Error("DenDen 專案已連結帳務，拒絕繼續")
}

async function assertSingleManagementAccount(runner) {
  const accounts = JSON.parse((await runner("gcloud", [
    "auth", "list", "--filter=status:ACTIVE", "--format=json",
  ])).stdout || "[]");
  if (!Array.isArray(accounts) || accounts.length !== 1) {
    throw new Error("隔離管理登入必須恰有一個有效 Google 帳號");
  }
  return validateGoogleAccount(accounts[0]?.account);
}

async function currentManagementAccount(argv, context) {
  const options = parseOptions(argv);
  const configPath = resolve(options["config-path"] || userConfigPath(context.env));
  const managementConfigDirectory = resolve(options["management-config-dir"] || join(dirname(configPath), "gcloud-management"));
  const env = { ...(context.env || process.env), CLOUDSDK_CONFIG: managementConfigDirectory };
  const external = context.runner || runExternal;
  const runner = (command, args) => external(command, args, { env });
  return assertSingleManagementAccount(runner);
}

async function waitForOperation(name, request, options = {}) {
  if (!name) throw new Error("Firebase operation 缺少名稱");
  const delay = options.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  const startedAt = Date.now();
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const remaining = options.totalWaitMillis - (Date.now() - startedAt);
    if (remaining <= 0) break;
    const operation = await request(name, {}, remaining);
    if (operation.done) {
      if (operation.error) throw new Error(`Firebase operation 失敗：${operation.error.message || operation.error.code}`);
      return operation;
    }
    await delay(Math.min(1000, remaining));
  }
  throw new Error("Firebase operation 等候逾時；可安全重跑目前階段");
}

async function fetchFirebaseProject(fetchImpl, projectId, accessToken, timeoutMillis, options = {}) {
  const deadline = Date.now() + timeoutMillis;
  const delay = options.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await fetchImpl(`https://firebase.googleapis.com/v1beta1/projects/${projectId}`, {
        headers: { Authorization: `Bearer ${accessToken}`, "x-goog-user-project": projectId },
        signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())),
      });
      if ((response.status !== 429 && response.status < 500) || attempt === 2 || Date.now() >= deadline) return response;
      await delay(Math.min(250 * (2 ** attempt), Math.max(0, deadline - Date.now())));
    } catch (error) {
      if (error?.name === "AbortError" || error?.name === "TimeoutError") {
        throw new Error("Firebase 專案檢查逾時；可安全重跑目前階段", { cause: error });
      }
      throw error;
    }
  }
  throw new Error("Firebase 專案檢查逾時；可安全重跑目前階段");
}

async function waitForFirebaseProject(fetchImpl, projectId, accessToken, options = {}) {
  const delay = options.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  const startedAt = Date.now();
  let response = new Response("{}", { status: 404 });
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const remaining = options.totalWaitMillis - (Date.now() - startedAt);
    if (remaining <= 0) break;
    await delay(Math.min(1000, remaining));
    response = await fetchFirebaseProject(fetchImpl, projectId, accessToken, Math.min(options.httpTimeoutMillis, remaining), { delay });
    if (response.status !== 404) return response;
  }
  return response;
}

async function waitForAndroidApp(request, projectId, packageName, options = {}) {
  const delay = options.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  const startedAt = Date.now();
  let apps = { apps: [] };
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const remaining = options.totalWaitMillis - (Date.now() - startedAt);
    if (remaining <= 0) break;
    await delay(Math.min(1000, remaining));
    apps = await request(`projects/${projectId}/androidApps`, {}, remaining);
    if ((apps.apps || []).some((value) => value.packageName === packageName)) return apps;
  }
  return apps;
}

function isAndroidAppStep(step) {
  return new Set(["android-app-planned", "android-app-running", "android-app-ready", "complete"]).has(step);
}

function normalizeSetupTimeout(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1_000 || parsed > 120_000) throw new Error(`${label} timeout 無效`);
  return parsed;
}

function normalizeSetupTotalWait(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 10_000 || parsed > 10 * 60_000) throw new Error("Firebase 總等待上限無效");
  return parsed;
}

function parseOptions(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith("--")) throw new Error(`未知 setup 參數：${key}`);
    if (key === "--allow-existing-dedicated") {
      options["allow-existing-dedicated"] = true;
      continue;
    }
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

function assertIsolatedCredentialDirectory(target, root, label) {
  const path = relative(root, target);
  if (!path || path.startsWith("..") || isAbsolute(path)) throw new Error(`${label}必須位於 DenDen 設定目錄內`);
}

function assertPrivateArtifactPath(target, root, label) {
  const path = relative(root, target);
  if (!path || path.startsWith("..") || isAbsolute(path)) throw new Error(`${label}必須位於 DenDen 設定目錄內`);
}

function validatePairingCheckpoint(value, approval, senderCredentialsDirectory, nowMillis) {
  if (!value || value.schemaVersion !== 1 || value.projectId !== approval.projectId ||
      value.androidPackageName !== approval.androidPackageName ||
      resolve(value.senderCredentialsDirectory || "") !== senderCredentialsDirectory) {
    throw new Error("本機配對續作檢查點與核准內容不符");
  }
  const encoded = value.bundle?.encodedInvite;
  const parsed = parseDirectFcmInvite(encoded, { allowExpired: true, nowMillis });
  if (parsed.projectId !== approval.projectId || parsed.androidPackageName !== approval.androidPackageName) {
    throw new Error("本機配對續作內容與核准專案不符");
  }
  const originalExpected = pairingBundleFromInvite(parsed, encoded, senderCredentialsDirectory);
  if (JSON.stringify(originalExpected) !== JSON.stringify(value.bundle)) throw new Error("本機配對續作內容已損壞");
  const currentInvite = parsed.displayExpiresAtMillis > nowMillis
    ? parsed
    : { ...parsed, createdAtMillis: nowMillis, displayExpiresAtMillis: nowMillis + 10 * 60_000 };
  const currentEncoded = currentInvite === parsed ? encoded : encodeDirectFcmInvite(currentInvite, { nowMillis });
  const expected = pairingBundleFromInvite(currentInvite, currentEncoded, senderCredentialsDirectory);
  return expected;
}

function assertExistingPairingFiles(daily, brand, approval, senderCredentialsDirectory) {
  validateDirectFcmSenderConfig(daily);
  validateBrandConfig(brand);
  const nowMillis = Date.now();
  const invite = {
    v: 2,
    projectId: daily.projectId,
    firebaseAppId: daily.firebaseAppId,
    apiKey: daily.apiKey,
    gcmSenderId: daily.gcmSenderId,
    androidPackageName: daily.androidPackageName,
    pairingId: daily.pairingId,
    topic: daily.topic,
    eventKeyId: daily.eventKeyId,
    eventKey: daily.eventKey,
    brandKeyId: brand.brandKeyId,
    brandKey: brand.brandKey,
    createdAtMillis: nowMillis,
    displayExpiresAtMillis: nowMillis + 60_000,
  };
  encodeDirectFcmInvite(invite, { nowMillis: invite.createdAtMillis });
  if (daily.schemaVersion !== 2 || brand.schemaVersion !== 2 || daily.projectId !== approval.projectId ||
      daily.androidPackageName !== approval.androidPackageName || resolve(daily.senderCredentialsDirectory || "") !== senderCredentialsDirectory) {
    throw new Error("既有本機配對設定與核准內容不符");
  }
  for (const key of ["projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName", "pairingId", "topic", "senderCredentialsDirectory"]) {
    if (daily[key] !== brand[key]) throw new Error(`既有日常與品牌設定的 ${key} 不一致`);
  }
}
