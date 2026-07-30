import { randomBytes } from "node:crypto";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { lstat, open, readFile, rm, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import QRCode from "qrcode";
import {
  assertPrivateDirectory,
  assertPrivateFile,
  ensurePrivateDirectory,
  readJson,
  userConfigPath,
  writePrivateFile,
  writePrivateJson,
} from "./config.mjs";
import {
  buildDirectSetupPlan,
  createDirectPairingBundle,
  runDirectSetupCommand,
  writeDirectPairingBundle,
} from "./direct-setup.mjs";
import { encodeDirectFcmInvite } from "./direct-fcm-protocol.mjs";
import {
  getServiceAccountAccessToken,
  SENDER_SERVICE_ACCOUNT_FILE,
  sendDirectFcmMessage,
  validateSenderServiceAccountKey,
  validateDirectFcmSenderConfig,
} from "./fcm-client.mjs";
import { validateBrandConfig } from "./direct-branding.mjs";
import { runExternal } from "./process.mjs";
import { buildRotateApproval, digest, fingerprint, validateProjectId } from "./setup-plan.mjs";
import { buildDailySkillInstallPlan, hashTree, installDailySkill } from "./integration-install.mjs";
import { validatePolicy } from "./policy.mjs";
import { RUNTIME_PROTOCOL } from "./source.mjs";

const INSTALLED_SOURCE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const SENDER_ROLE_ID = "dendenFcmSender";
const SENDER_PERMISSIONS = ["cloudmessaging.messages.create", "serviceusage.services.use"];
const NOTIFICATION_EVENTS = ["completed", "failed", "partial", "blocked", "needs-reply", "manual"];
const FIRST_SETUP_STAGES = [
  "approved",
  "firebase-configured",
  "sender-authorized",
  "sender-verified",
  "management-revoked",
  "skill-finished",
  "complete",
];

async function buildFirstSetupApproval(options, paths, managementAccount, senderAccountId, context) {
  const sourceRoot = resolve(options["source-root"] || context.sourceRoot || INSTALLED_SOURCE_ROOT);
  const skillChoice = required(options["skill-choice"], "--skill-choice");
  const runtimeSourceDigest = await hashTree(join(sourceRoot, "cli"));
  const dailySkillSourceDigest = skillChoice === "skip" ? null : await hashTree(join(sourceRoot, "skills", "denden"));
  const metadata = await readJson(join(sourceRoot, "source.json"), { required: false });
  const sourceVersion = metadata?.schemaVersion === 3 && metadata.runtimeProtocol === RUNTIME_PROTOCOL &&
      /^[0-9a-f]{40}$/.test(metadata.commitSha || "")
    ? metadata.commitSha
    : `unpublished-local-${runtimeSourceDigest.slice(0, 12)}`;
  const { approvalDigest: _directDigest, ...direct } = buildDirectSetupPlan(directSetupArgs(options), { managementAccount });
  const { approvalDigest: _senderDigest, ...sender } = senderServiceAccountApprovalValue({
    projectId: direct.projectId,
    accountId: senderAccountId,
    managementAccount,
    senderCredentialsDirectory: paths.senderCredentialsDirectory,
  });
  const { digest: _dailySkillDigest, ...dailySkill } = buildDailySkillInstallPlan({
    sourceRoot,
    choice: skillChoice,
    destination: options["skill-destination"],
    agentType: options["skill-agent"] || "codex",
    sourceDigest: dailySkillSourceDigest,
    configPath: paths.configPath,
    notificationPolicy: notificationPolicyFromOptions(options),
    env: context.env,
  });
  const { approvalDigest: _managementDigest, ...managementRevocation } = managementRevokeApproval(
    managementAccount,
    paths.managementConfigDirectory,
  );
  const value = {
    schemaVersion: 1,
    action: "complete-first-direct-fcm-setup",
    runtimeProtocol: RUNTIME_PROTOCOL,
    sourceVersion,
    sourceRoot,
    runtimeSourceDigest,
    managementAccount,
    projectId: direct.projectId,
    projectMode: direct.projectMode,
    androidPackageName: direct.androidPackageName,
    direct,
    sender,
    senderVerification: {
      fcm: "FCM validate_only 必須成功",
      deniedManagement: ["project create/list", "service enable", "IAM modify", "Firebase add/list"],
    },
    managementRevocation,
    dailySkill,
    checkpointPath: paths.firstSetupStatePath,
    lockPath: paths.firstSetupLockPath,
    writes: [
      ...direct.writes,
      sender.effect,
      "以 FCM validate_only 與管理 API 負向探測驗證最低權限",
      managementRevocation.effect,
      dailySkill.behavior,
    ],
    excluded: [
      "不連結 billing",
      "不啟用 Analytics、Authentication、Firestore、Functions、Hosting、Storage、Secret Manager 或 Budget",
      "不讀寫使用者既有 gcloud 設定或 ADC",
      "不安裝或使用 Firebase CLI",
      "Agent 不取得、保存或使用手機 FCM 裝置權杖；只向私有 message.topic 發送",
    ],
  };
  return { ...value, approvalDigest: digest(value) };
}

export async function runFirstSetupPlanCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const accounts = await activeAccounts(context.runner || runExternal, paths.managementConfigDirectory, context.env);
  if (accounts.length !== 1) throw new Error("初次設定摘要需要恰好一個隔離管理登入");
  const senderAccountId = options["sender-account-id"]
    ? validateSenderAccountId(options["sender-account-id"])
    : `denden-${(context.randomBytes || randomBytes)(6).toString("hex")}`;
  return buildFirstSetupApproval(options, paths, accounts[0], senderAccountId, context);
}

export async function runFirstSetupCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const releaseLock = await acquireFirstSetupLock(paths.firstSetupLockPath, context);
  try {
    return await runFirstSetupLocked(options, paths, context);
  } finally {
    await releaseLock();
  }
}

export async function runWithSetupMutationLock(argv, operation, context = {}) {
  if (typeof operation !== "function") throw new Error("DenDen 設定變更缺少執行內容");
  const options = {};
  for (const name of ["config-path", "first-setup-lock-path"]) {
    const index = argv.indexOf(`--${name}`);
    if (index >= 0) options[name] = argv[index + 1];
  }
  const releaseLock = await acquireFirstSetupLock(setupPaths(options, context.env).firstSetupLockPath, context);
  try {
    return await operation();
  } finally {
    await releaseLock();
  }
}

async function runFirstSetupLocked(options, paths, context) {
  let checkpoint = await readJson(paths.firstSetupStatePath, { required: false });
  let approval;
  if (checkpoint) {
    if (checkpoint.schemaVersion !== 1 || !FIRST_SETUP_STAGES.includes(checkpoint.stage) ||
        checkpoint.approval?.approvalDigest !== options["approved-digest"]) {
      throw new Error("初次設定續作狀態與核准摘要不符");
    }
    approval = await buildFirstSetupApproval(
      options,
      paths,
      checkpoint.approval.managementAccount,
      checkpoint.approval.sender.senderAccountId,
      context,
    );
    if (approval.approvalDigest !== checkpoint.approval.approvalDigest) {
      throw new Error("初次設定來源、目的地或受保護輸入已改變；請重新產生摘要");
    }
    if (checkpoint.projectNumber) {
      const setupState = await readJson(paths.setupStatePath, { required: false });
      if (setupState?.projectNumber && String(setupState.projectNumber) !== checkpoint.projectNumber) {
        throw new Error("Google Cloud project number 與初次設定續作狀態不符");
      }
    }
  } else {
    if (!options["sender-account-id"]) throw new Error("初次設定執行必須帶入摘要中的 --sender-account-id");
    const accounts = await activeAccounts(context.runner || runExternal, paths.managementConfigDirectory, context.env);
    if (accounts.length !== 1) throw new Error("初次設定需要恰好一個隔離管理登入");
    approval = await buildFirstSetupApproval(
      options,
      paths,
      accounts[0],
      validateSenderAccountId(options["sender-account-id"]),
      context,
    );
    if (options["approved-digest"] !== approval.approvalDigest) {
      throw new Error("初次設定摘要尚未取得明確同意，或核准後內容已改變；請先執行 denden setup plan");
    }
    checkpoint = { schemaVersion: 1, stage: "approved", approval };
    await (context.writeFirstSetupState || writePrivateJson)(paths.firstSetupStatePath, checkpoint);
  }

  const alreadyComplete = checkpoint.stage === "complete";
  const saveStage = async (stage, result = undefined, patch = {}) => {
    checkpoint = { ...checkpoint, ...patch, stage, ...(result === undefined ? {} : { [stage]: result }) };
    await (context.writeFirstSetupState || writePrivateJson)(paths.firstSetupStatePath, checkpoint);
  };
  const needs = (stage) => FIRST_SETUP_STAGES.indexOf(checkpoint.stage) < FIRST_SETUP_STAGES.indexOf(stage);

  if (needs("firebase-configured")) {
    reportProgress(context, "設定 Firebase 與 Android App");
    const result = await (context.directSetup || runDirectSetupCommand)([
      ...directSetupArgs(options), "--approved-digest", digest(approval.direct),
    ], { ...context, managementAccount: approval.managementAccount });
    const setupState = await readJson(paths.setupStatePath, { required: false });
    const projectNumber = /^\d{6,20}$/.test(String(setupState?.projectNumber || ""))
      ? String(setupState.projectNumber)
      : undefined;
    await saveStage("firebase-configured", sanitizePhaseResult(result), projectNumber ? { projectNumber } : {});
  }
  if (needs("sender-authorized")) {
    reportProgress(context, "建立 DenDen 共用的最低權限發送身分");
    const result = await (context.senderAuth || runSenderAuthCommand)([
      ...senderOperationArgs(options, approval.sender.senderAccountId),
      "--approved-digest", digest(approval.sender),
    ], context);
    await saveStage("sender-authorized", sanitizePhaseResult(result));
  }
  if (needs("sender-verified")) {
    reportProgress(context, "驗證 FCM 與管理 API 權限邊界");
    const result = await (context.senderVerify || runSenderVerifyCommand)(senderOperationArgs(options), context);
    if (result?.verified !== true) throw new Error("日常發送身分未通過最低權限驗證；停止初次設定");
    await saveStage("sender-verified", sanitizePhaseResult(result));
  }
  if (needs("management-revoked")) {
    reportProgress(context, "撤銷隔離管理登入");
    const result = await (context.managementRevoke || runManagementRevokeCommand)([
      ...managementOperationArgs(options),
      "--management-account", approval.managementAccount,
      "--approved-digest", digest(approval.managementRevocation),
    ], context);
    await saveStage("management-revoked", sanitizePhaseResult(result));
  }
  if (needs("skill-finished")) {
    reportProgress(context, "完成預選的日常技能動作");
    const dailySkillPlan = { ...approval.dailySkill, digest: digest(approval.dailySkill) };
    const result = await (context.installDailySkill || installDailySkill)(dailySkillPlan, {
      approvedDigest: dailySkillPlan.digest,
      writeNotificationPolicy: context.writeNotificationPolicy,
    });
    await saveStage("skill-finished", sanitizePhaseResult(result));
  }
  if (needs("complete")) await saveStage("complete");
  return {
    configured: true,
    reused: alreadyComplete,
    approvalDigest: approval.approvalDigest,
    projectId: approval.projectId,
    senderAccountId: approval.sender.senderAccountId,
    senderServiceAccount: approval.sender.serviceAccountEmail,
    managementCredentialRemoved: true,
    dailySkill: approval.dailySkill.choice,
    checkpointPath: paths.firstSetupStatePath,
  };
}

export async function runManagementAuthCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  await (context.ensurePrivateDirectory || ensurePrivateDirectory)(paths.managementConfigDirectory);
  const runner = context.runner || runExternal;
  await runner("gcloud", ["auth", "login"], {
    env: { ...(context.env || process.env), CLOUDSDK_CONFIG: paths.managementConfigDirectory },
    interactive: true,
    timeoutMillis: context.interactiveTimeoutMillis || 15 * 60_000,
  });
  const accounts = await activeAccounts(runner, paths.managementConfigDirectory, context.env);
  if (accounts.length !== 1) throw new Error("隔離管理登入必須恰有一個有效 Google 帳號");
  return {
    authorized: true,
    account: accounts[0],
    managementConfigDirectory: paths.managementConfigDirectory,
    next: "先執行 denden setup plan；使用者核准摘要後才能建立或修改 Firebase 專案",
  };
}

export async function runSenderAuthPlanCommand(argv, context = {}) {
  return senderServiceAccountApproval(parseOptions(argv), context, false);
}

export async function runSenderAuthCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const approval = await senderServiceAccountApproval(options, context, true);
  if (options["approved-digest"] !== approval.approvalDigest) {
    throw new Error("日常發送身分摘要尚未取得明確同意；請先執行 sender-auth-plan");
  }
  const paths = setupPaths(options, context.env);
  await (context.ensurePrivateDirectory || ensurePrivateDirectory)(paths.senderCredentialsDirectory);
  const runner = context.runner || runExternal;
  const env = { ...(context.env || process.env), CLOUDSDK_CONFIG: paths.managementConfigDirectory };
  await ensureSenderRole(runner, approval.projectId, approval.role, approval.permissions, env);
  await ensureDedicatedSenderServiceAccount(runner, approval, env);
  await runner("gcloud", [
    "projects", "add-iam-policy-binding", approval.projectId,
    `--member=serviceAccount:${approval.serviceAccountEmail}`,
    `--role=${approval.role}`,
    "--condition=None",
    "--quiet",
  ], { env });
  await ensureSenderServiceAccountKey(runner, approval, paths.senderCredentialsDirectory, env, context);
  const tokenProvider = context.tokenProvider || getServiceAccountAccessToken;
  const token = await retryPropagation("等待 IAM 與服務帳戶金鑰生效", () => tokenProvider(
    paths.senderCredentialsDirectory,
    { projectId: approval.projectId, env: context.env, timeoutMillis: context.commandTimeoutMillis },
  ), context);
  if (typeof token !== "string" || !token.trim()) throw new Error("DenDen 發送服務帳戶無法取得短效 FCM 存取權杖");
  return {
    authorized: true,
    projectId: approval.projectId,
    serviceAccountEmail: approval.serviceAccountEmail,
    senderCredentialsDirectory: paths.senderCredentialsDirectory,
    permissions: approval.permissions,
    note: "日常工具以本機受保護的共用服務帳戶金鑰取得短效 FCM 權杖；不使用人類 Google 憑證",
  };
}

export async function runSenderVerifyCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const config = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  const tokenProvider = context.tokenProvider || getServiceAccountAccessToken;
  const token = await tokenProvider(config.senderCredentialsDirectory, { projectId: config.projectId, env: context.env });
  if (typeof token !== "string" || !token.trim()) throw new Error("DenDen 發送服務帳戶無法取得短效 FCM 存取權杖");
  const fetchImpl = context.fetchImpl || fetch;
  const positive = await retryPropagation("等待 FCM 發送權限生效", () => sendDirectFcmMessage({
      action: "quiet",
      payload: {
        eventId: `sender-verify-${context.nowMillis ?? Date.now()}`,
        channelId: "denden-setup",
        message: "授權驗證，不會實際傳送",
      },
      config,
      accessToken: token,
      validateOnly: true,
      fetchImpl,
      nowMillis: context.nowMillis,
      maxAttempts: 1,
    }), context, (error) => ["GOOGLE_AUTH_FAILED", "FCM_TRANSPORT_FAILED"].includes(error?.code));
  const management = [];
  for (const probe of managementProbes(config.projectId)) {
    management.push(await authorizationProbe(probe, token, config.projectId, fetchImpl));
  }
  const unsafe = management.filter((probe) => !probe.deniedBeforeValidation);
  const topicManagement = await authorizationProbe({
    key: "legacy_topic_management",
    url: "https://iid.googleapis.com/iid/v1:batchAdd",
    method: "POST",
    body: { to: `/topics/denden-auth-probe-${context.nowMillis ?? Date.now()}`, registration_tokens: ["invalid-registration-token"] },
  }, token, config.projectId, fetchImpl);
  return {
    verified: positive.validatedByFcm === true && unsafe.length === 0,
    projectId: config.projectId,
    fcmValidateOnly: positive.validatedByFcm === true,
    management,
    topicManagement: {
      status: topicManagement.status,
      reachableWithScope: !topicManagement.deniedBeforeValidation,
      note: topicManagement.deniedBeforeValidation
        ? "舊式主題管理介面拒絕此存取權杖"
        : "此授權範圍仍可觸及主題管理介面；日常工具不使用該介面",
    },
    next: unsafe.length
      ? "安全停止：日常存取權杖可觸及一般 Google Cloud 管理 API，請撤銷並重新授權"
      : "FCM 驗證成功，且一般 Google Cloud 管理探測均在要求驗證前遭拒絕",
  };
}

export async function runSenderRevokePlanCommand(argv, context = {}) {
  return senderServiceAccountRevokeApproval(parseOptions(argv), context);
}

export async function runSenderRevokeCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const approval = await senderServiceAccountRevokeApproval(options, context);
  if (options["approved-digest"] !== approval.approvalDigest) {
    throw new Error("日常發送身分撤銷摘要尚未取得明確同意；請先執行 sender-revoke-plan");
  }
  const paths = setupPaths(options, context.env);
  await (context.assertPrivateDirectory || assertPrivateDirectory)(paths.senderCredentialsDirectory);
  const runner = context.runner || runExternal;
  const env = { ...(context.env || process.env), CLOUDSDK_CONFIG: paths.managementConfigDirectory };
  const account = await ensureDedicatedSenderServiceAccount(runner, approval, env, { allowMissing: true });
  if (account) {
    await runner("gcloud", [
      "projects", "remove-iam-policy-binding", approval.projectId,
      `--member=serviceAccount:${approval.serviceAccountEmail}`,
      `--role=${approval.role}`,
      "--condition=None",
      "--quiet",
    ], { env });
    await runner("gcloud", [
      "iam", "service-accounts", "delete", approval.serviceAccountEmail,
      "--project", approval.projectId,
      "--quiet",
    ], { env });
  }
  await (context.removeDirectory || rm)(paths.senderCredentialsDirectory, { recursive: true, force: true });
  return {
    revoked: true,
    reused: !account,
    projectId: approval.projectId,
    serviceAccountEmail: approval.serviceAccountEmail,
    senderCredentialsDirectory: paths.senderCredentialsDirectory,
    localCacheRemoved: true,
    note: "已刪除所有發送電腦共用的服務帳戶與此電腦的本機金鑰；其他電腦將停止發送，疑似配對資料外洩時仍須旋轉整組配對",
  };
}

export async function runManagementRevokeCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const runner = context.runner || runExternal;
  const accounts = await exists(paths.managementConfigDirectory) || !options["management-account"]
    ? await activeAccounts(runner, paths.managementConfigDirectory, context.env)
    : [];
  if (accounts.length > 1) throw new Error("隔離管理登入不得有多個有效 Google 帳號");
  const approvedAccount = options["management-account"]
    ? validateEmail(options["management-account"])
    : accounts[0];
  if (!approvedAccount || accounts[0] && accounts[0] !== approvedAccount) {
    throw new Error("隔離管理登入與核准撤銷帳號不符");
  }
  const approval = managementRevokeApproval(approvedAccount, paths.managementConfigDirectory);
  if (options["approved-digest"] !== approval.approvalDigest) throw new Error("撤銷摘要尚未取得明確同意；請先執行 management-revoke-plan");
  if (accounts.length === 1) {
    await runner("gcloud", ["auth", "revoke", approvedAccount, "--quiet"], {
      env: { ...(context.env || process.env), CLOUDSDK_CONFIG: paths.managementConfigDirectory },
    });
  }
  await (context.removeDirectory || rm)(paths.managementConfigDirectory, { recursive: true, force: true });
  return {
    revoked: true,
    reused: accounts.length === 0,
    account: approvedAccount,
    managementConfigDirectory: paths.managementConfigDirectory,
    localCacheRemoved: true,
    note: "只撤銷並移除 DenDen 隔離管理登入；日常低權限發送服務帳戶未修改",
  };
}

export async function runManagementRevokePlanCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const accounts = await activeAccounts(context.runner || runExternal, paths.managementConfigDirectory, context.env);
  if (accounts.length !== 1) throw new Error("隔離管理登入必須恰有一個有效 Google 帳號");
  return managementRevokeApproval(accounts[0], paths.managementConfigDirectory);
}

export async function runDirectDoctorCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const runner = context.runner || runExternal;
  const rawConfig = await readJson(paths.configPath, { required: false });
  const rawBrand = await readJson(paths.brandConfigPath, { required: false });
  let config = null;
  let configError = null;
  let brandConfigOk = rawBrand === null;
  let brandConfigError = null;
  try {
    if (!rawConfig) throw new Error("日常發送設定缺少");
    config = validateDirectFcmSenderConfig(rawConfig);
    assertSenderCredentialDirectory(config, paths.senderCredentialsDirectory);
  } catch (error) {
    configError = error;
  }
  if (rawBrand) {
    try {
      if (!config) throw new Error("日常發送設定無效，無法核對品牌設定");
      assertMatchingConfigs(config, validateBrandConfig(rawBrand));
      brandConfigOk = true;
    } catch (error) {
      brandConfigError = error;
    }
  }
  const checks = [];
  checks.push(await commandCheck("git", ["--version"], runner));
  checks.push({ key: "node", ok: Number(process.versions.node.split(".")[0]) >= 22, detail: `Node.js ${process.versions.node}` });
  checks.push(await commandCheck("npm", ["--version"], runner));
  checks.push(await commandCheck("gcloud", ["version"], runner));
  checks.push({ key: "firebase_cli", ok: true, required: false, detail: "不需要 Firebase CLI" });
  if (await exists(paths.managementConfigDirectory)) {
    checks.push(await directoryCheck("management_acl", paths.managementConfigDirectory, context));
    const accounts = await activeAccounts(runner, paths.managementConfigDirectory, context.env).catch(() => []);
    if (rawConfig) {
      checks.push({ key: "management_credential_removed", ok: false, detail: "一次性管理憑證仍存在，請完成核准後撤銷" });
    } else {
      checks.push({ key: "management_login", ok: accounts.length === 1, detail: accounts.length === 1 ? accounts[0] : "沒有恰好一個有效帳號" });
    }
  } else {
    checks.push(rawConfig
      ? { key: "management_credential_removed", ok: true, detail: "一次性管理憑證已移除" }
      : { key: "management_login", ok: false, detail: "尚未建立隔離管理登入" });
  }
  if (await exists(paths.senderCredentialsDirectory)) {
    checks.push(await directoryCheck("sender_acl", paths.senderCredentialsDirectory, context));
    const keyPath = join(paths.senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE);
    let keyOk = false;
    try {
      await (context.assertPrivateFile || assertPrivateFile)(keyPath);
      validateSenderServiceAccountKey(await readJson(keyPath), config?.projectId);
      keyOk = true;
    } catch { /* 由檢查結果呈現，不輸出金鑰內容。 */ }
    checks.push({
      key: "sender_service_account_key",
      ok: keyOk,
      detail: keyOk ? "專用低權限發送服務帳戶金鑰有效" : "發送服務帳戶金鑰缺少、權限過寬或專案不符",
    });
    const legacyAdc = await exists(join(paths.senderCredentialsDirectory, "application_default_credentials.json"));
    checks.push({ key: "legacy_user_adc_absent", ok: !legacyAdc, detail: legacyAdc ? "偵測到舊的人類使用者 ADC" : "沒有舊的人類使用者 ADC" });
    const tokenProvider = context.tokenProvider || getServiceAccountAccessToken;
    const ok = config && keyOk
      ? await tokenProvider(paths.senderCredentialsDirectory, { projectId: config.projectId, env: context.env }).then(Boolean).catch(() => false)
      : false;
    checks.push({ key: "sender_service_account_token", ok, detail: ok ? "可取得短效 FCM 存取權杖" : "發送服務帳戶無法取得短效權杖" });
  } else {
    checks.push({ key: "sender_service_account_key", ok: false, detail: "尚未建立專用發送服務帳戶" });
    checks.push({ key: "sender_service_account_token", ok: false, detail: "尚未建立專用發送服務帳戶" });
  }
  checks.push({
    key: "sender_config",
    ok: Boolean(config),
    detail: config ? "日常發送設定有效" : (configError?.message || "尚未建立"),
  });
  checks.push({
    key: "brand_config",
    ok: brandConfigOk,
    required: rawBrand !== null,
    detail: rawBrand === null ? "此電腦沒有品牌管理權限（可略過）" : (brandConfigOk ? "品牌設定與日常配對一致" : brandConfigError?.message),
  });
  return { ready: checks.filter((item) => item.required !== false).every((item) => item.ok), checks, paths };
}

export async function runPairingStatusCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const daily = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  const brand = validateBrandConfig(await readJson(paths.brandConfigPath));
  assertMatchingConfigs(daily, brand);
  return {
    configured: true,
    projectId: daily.projectId,
    pairingFingerprint: fingerprint(daily.pairingId),
    topicFingerprint: fingerprint(daily.topic),
    eventKeyFingerprint: fingerprint(daily.eventKeyId),
    brandKeyFingerprint: fingerprint(brand.brandKeyId),
    brandGeneration: brand.generation || 0,
    configPath: paths.configPath,
    brandConfigPath: paths.brandConfigPath,
  };
}

export async function runReissueQrCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const daily = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  const brand = validateBrandConfig(await readJson(paths.brandConfigPath));
  assertMatchingConfigs(daily, brand);
  const nowMillis = context.nowMillis ?? Date.now();
  const invite = inviteFromConfigs(daily, brand, nowMillis);
  const qrPath = resolve(options["qr-path"] || paths.qrPath);
  await writePrivateFile(qrPath, await QRCode.toBuffer(encodeDirectFcmInvite(invite, { nowMillis }), {
    type: "png", errorCorrectionLevel: "M", margin: 2, width: 768,
  }));
  return {
    reissued: true,
    projectId: daily.projectId,
    pairingFingerprint: fingerprint(daily.pairingId),
    qrPath,
    displayExpiresAtMillis: invite.displayExpiresAtMillis,
    note: "這是同一組配對的短效 QR；刪除圖檔不能撤銷已外洩內容",
  };
}

export async function runRemoveQrCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  if (!await exists(paths.qrPath)) {
    return { removed: false, qrPath: paths.qrPath, note: "QR 圖檔已不存在" };
  }
  const info = await stat(paths.qrPath);
  if (!info.isFile()) throw new Error("QR 路徑不是一般檔案，拒絕刪除");
  await (context.removeFile || rm)(paths.qrPath, { force: false });
  return {
    removed: true,
    qrPath: paths.qrPath,
    note: "只刪除本機 QR 圖檔；若內容可能外洩，仍須旋轉整組配對",
  };
}

export async function runRotatePlanCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  return buildRotateApproval(validateDirectFcmSenderConfig(await readJson(paths.configPath)));
}

export async function runRotatePairingCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const paths = setupPaths(options, context.env);
  const daily = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  const rawBrand = await readJson(paths.brandConfigPath, { required: false });
  const brand = rawBrand === null ? null : validateBrandConfig(rawBrand);
  if (brand) assertMatchingConfigs(daily, brand);
  const approval = buildRotateApproval(daily);
  if (options["approved-digest"] !== approval.approvalDigest) {
    throw new Error("旋轉摘要尚未取得明確同意，或核准後配對已改變；請先執行 denden setup rotate-plan");
  }
  if (await exists(paths.brandPendingPath)) throw new Error("仍有待續傳品牌；完成或明確放棄前不得旋轉配對");
  const firebase = publicFirebaseConfig(daily);
  const bundle = createDirectPairingBundle(firebase, {
    senderCredentialsDirectory: daily.senderCredentialsDirectory,
    nowMillis: context.nowMillis,
    randomBytes: context.randomBytes,
  });
  if (brand?.activeImageBase64) {
    bundle.brandConfig.activeImageBase64 = brand.activeImageBase64;
    for (const name of ["brandColor", "backgroundColor"]) {
      if (brand[name] !== undefined) bundle.brandConfig[name] = brand[name];
    }
  }
  const files = await writeDirectPairingBundle(bundle, {
    configPath: paths.configPath,
    brandConfigPath: paths.brandConfigPath,
    qrPath: resolve(options["qr-path"] || paths.qrPath),
    env: context.env,
  });
  return {
    rotated: true,
    projectId: daily.projectId,
    previousPairingFingerprint: approval.currentPairingFingerprint,
    pairingFingerprint: fingerprint(bundle.invite.pairingId),
    qrPath: files.qrPath,
    displayExpiresAtMillis: files.displayExpiresAtMillis,
    next: "所有保留手機都必須掃描新 QR Code；舊 QR 無法再產生合法新訊息",
  };
}

export async function runDailySkillPlanCommand(argv, context = {}) {
  const options = parseOptions(argv);
  return buildDailySkillInstallPlan({
    sourceRoot: resolve(options["source-root"] || context.sourceRoot || INSTALLED_SOURCE_ROOT),
    choice: required(options.choice, "--choice"),
    destination: options.destination,
    agentType: options.agent || "codex",
    configPath: resolve(options["config-path"] || userConfigPath(context.env)),
    notificationPolicy: notificationPolicyFromOptions(options),
    env: context.env,
  });
}

export async function runDailySkillInstallCommand(argv, context = {}) {
  const options = parseOptions(argv);
  const plan = await runDailySkillPlanCommand(argv, context);
  return (context.installDailySkill || installDailySkill)(plan, {
    approvedDigest: options["approved-digest"],
    writeNotificationPolicy: context.writeNotificationPolicy,
  });
}

function setupPaths(options, env = process.env) {
  const configPath = resolve(options["config-path"] || userConfigPath(env));
  const root = dirname(configPath);
  const paths = {
    configPath,
    brandConfigPath: resolve(options["brand-config-path"] || join(root, "brand-config.json")),
    brandPendingPath: resolve(options["pending-path"] || join(root, "brand-pending.json")),
    qrPath: resolve(options["qr-path"] || join(root, "pairing-qr.png")),
    setupStatePath: resolve(options["setup-state-path"] || join(root, "setup-state.json")),
    pairingStatePath: resolve(options["pairing-state-path"] || join(root, "pairing-state.json")),
    firstSetupStatePath: resolve(options["first-setup-state-path"] || join(root, "first-setup-state.json")),
    firstSetupLockPath: resolve(options["first-setup-lock-path"] || join(root, "first-setup.lock")),
    managementConfigDirectory: resolve(options["management-config-dir"] || join(root, "gcloud-management")),
    senderCredentialsDirectory: resolve(options["sender-config-dir"] || join(root, "gcloud-sender")),
  };
  for (const [label, target] of [
    ["品牌設定", paths.brandConfigPath],
    ["品牌續傳", paths.brandPendingPath],
    ["QR 圖檔", paths.qrPath],
    ["設定續作", paths.setupStatePath],
    ["配對續作", paths.pairingStatePath],
    ["初次設定續作", paths.firstSetupStatePath],
    ["初次設定鎖", paths.firstSetupLockPath],
  ]) assertPrivateArtifactPath(target, root, label);
  if (!paths.qrPath.toLowerCase().endsWith(".png")) throw new Error("QR 圖檔必須使用 .png 副檔名");
  if (new Set([
    paths.configPath,
    paths.brandConfigPath,
    paths.brandPendingPath,
    paths.setupStatePath,
    paths.pairingStatePath,
    paths.firstSetupStatePath,
    paths.firstSetupLockPath,
  ]).has(paths.qrPath)) {
    throw new Error("QR 圖檔不得覆蓋其他 DenDen 設定");
  }
  assertIsolatedCredentialDirectory(paths.managementConfigDirectory, root, "管理憑證目錄");
  assertIsolatedCredentialDirectory(paths.senderCredentialsDirectory, root, "日常憑證目錄");
  if (paths.managementConfigDirectory === paths.senderCredentialsDirectory) throw new Error("管理與日常憑證目錄不得共用");
  return paths;
}

function directSetupArgs(options) {
  const args = ["--project-id", required(options["project-id"], "--project-id")];
  for (const name of [
    "package-name",
    "config-path",
    "brand-config-path",
    "qr-path",
    "setup-state-path",
    "pairing-state-path",
    "management-config-dir",
    "sender-config-dir",
  ]) pushOption(args, options, name);
  if (options["allow-existing-dedicated"] === true) args.push("--allow-existing-dedicated");
  return args;
}

function senderOperationArgs(options, senderAccountId = null) {
  const args = ["--project-id", required(options["project-id"], "--project-id")];
  for (const name of ["config-path", "brand-config-path", "management-config-dir", "sender-config-dir"]) {
    pushOption(args, options, name);
  }
  if (senderAccountId) args.push("--sender-account-id", senderAccountId);
  return args;
}

function managementOperationArgs(options) {
  const args = [];
  for (const name of ["config-path", "management-config-dir", "sender-config-dir"]) pushOption(args, options, name);
  return args;
}

function pushOption(args, options, name) {
  if (options[name] !== undefined) args.push(`--${name}`, options[name]);
}

function sanitizePhaseResult(value) {
  const safe = {};
  for (const key of [
    "configured", "reused", "authorized", "verified", "revoked", "installed", "skipped",
    "projectId", "serviceAccountEmail", "configPath", "brandConfigPath", "qrPath",
    "senderCredentialsDirectory", "managementConfigDirectory", "destination", "fcmValidateOnly",
  ]) {
    if (["string", "number", "boolean"].includes(typeof value?.[key])) safe[key] = value[key];
  }
  return safe;
}

function reportProgress(context, message) {
  if (context.progress) context.progress(message);
  else process.stderr.write(`[DenDen] ${message}\n`);
}

async function acquireFirstSetupLock(lockPath, context) {
  await (context.ensurePrivateDirectory || ensurePrivateDirectory)(dirname(lockPath));
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const handle = await open(lockPath, "wx", 0o600);
      await handle.writeFile(`${JSON.stringify({ schemaVersion: 1, pid: process.pid, startedAtMillis: Date.now() })}\n`);
      return async () => {
        await handle.close();
        await (context.removeLockFile || rm)(lockPath, { force: true });
      };
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      let stale = false;
      try {
        const lock = JSON.parse(await readFile(lockPath, "utf8"));
        stale = !Number.isSafeInteger(lock.pid) || lock.pid <= 0 || !processIsAlive(lock.pid);
      } catch {
        stale = true;
      }
      if (!stale) throw new Error("另一個 DenDen 設定變更程序正在執行；拒絕交錯摘要或 checkpoint");
      await (context.removeLockFile || rm)(lockPath, { force: true });
    }
  }
  throw new Error("無法取得 DenDen 設定變更鎖");
}

function processIsAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

function inviteFromConfigs(daily, brand, nowMillis) {
  const firebase = publicFirebaseConfig(daily);
  return {
    v: 2,
    ...firebase,
    pairingId: daily.pairingId,
    topic: daily.topic,
    eventKeyId: daily.eventKeyId,
    eventKey: daily.eventKey,
    brandKeyId: brand.brandKeyId,
    brandKey: brand.brandKey,
    createdAtMillis: nowMillis,
    displayExpiresAtMillis: nowMillis + 10 * 60_000,
  };
}

function publicFirebaseConfig(config) {
  for (const key of ["projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName"]) {
    if (typeof config[key] !== "string" || !config[key]) throw new Error(`設定缺少 ${key}，請修復或重新執行初次設定`);
  }
  return {
    projectId: config.projectId,
    firebaseAppId: config.firebaseAppId,
    apiKey: config.apiKey,
    gcmSenderId: config.gcmSenderId,
    androidPackageName: config.androidPackageName,
  };
}

function assertMatchingConfigs(daily, brand) {
  for (const key of ["projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName", "pairingId", "topic", "senderCredentialsDirectory"]) {
    if (daily[key] !== brand[key]) throw new Error(`日常與品牌管理設定的 ${key} 不一致`);
  }
}

async function activeAccounts(runner, directory, env = process.env) {
  const result = await runner("gcloud", ["auth", "list", "--filter=status:ACTIVE", "--format=json"], {
    env: { ...env, CLOUDSDK_CONFIG: directory },
  });
  const rows = JSON.parse(result.stdout || "[]");
  return Array.isArray(rows)
    ? rows.map((row) => row?.account).filter((item) => typeof item === "string").map(validateEmail)
    : [];
}

async function commandCheck(command, args, runner) {
  try {
    await runner(command, args);
    return { key: command, ok: true, detail: "可用" };
  } catch {
    return { key: command, ok: false, detail: "找不到或無法執行" };
  }
}

async function directoryCheck(key, path, context) {
  try {
    await (context.assertPrivateDirectory || assertPrivateDirectory)(path);
    return { key, ok: true, detail: "只有目前使用者可讀寫" };
  } catch {
    return { key, ok: false, detail: "權限過寬或無法確認" };
  }
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

function managementRevokeApproval(account, directory) {
  const value = {
    schemaVersion: 1,
    action: "revoke-management-credential",
    account,
    managementConfigDirectory: directory,
    effect: "撤銷 DenDen 隔離管理登入，並刪除該隔離目錄的本機快取；日常低權限發送服務帳戶不變",
  };
  return { ...value, approvalDigest: digest(value) };
}

async function senderServiceAccountApproval(options, context, requireAccountId) {
  const paths = setupPaths(options, context.env);
  const projectId = validateProjectId(options["project-id"]);
  const config = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  if (config.projectId !== projectId) throw new Error("日常設定與發送服務帳戶 project ID 不一致");
  assertSenderCredentialDirectory(config, paths.senderCredentialsDirectory);
  let accountId;
  if (options["sender-account-id"]) {
    accountId = validateSenderAccountId(options["sender-account-id"]);
  } else if (requireAccountId) {
    throw new Error("sender-auth 必須帶入 sender-auth-plan 產生的 --sender-account-id");
  } else {
    accountId = `denden-${(context.randomBytes || randomBytes)(6).toString("hex")}`;
  }
  const accounts = await activeAccounts(context.runner || runExternal, paths.managementConfigDirectory, context.env);
  if (accounts.length !== 1) throw new Error("建立發送服務帳戶前必須有恰好一個隔離管理登入");
  return senderServiceAccountApprovalValue({
    projectId,
    accountId,
    managementAccount: accounts[0],
    senderCredentialsDirectory: paths.senderCredentialsDirectory,
  });
}

function senderServiceAccountApprovalValue({ projectId, accountId, managementAccount, senderCredentialsDirectory }) {
  const serviceAccountEmail = `${accountId}@${projectId}.iam.gserviceaccount.com`;
  const value = {
    schemaVersion: 1,
    action: "create-sender-service-account",
    projectId,
    senderAccountId: accountId,
    serviceAccountEmail,
    role: `projects/${projectId}/roles/${SENDER_ROLE_ID}`,
    permissions: SENDER_PERMISSIONS,
    managementAccount,
    senderCredentialsDirectory,
    keyFile: join(senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE),
    effect: "建立 DenDen 共用的低權限服務帳戶、綁定 FCM 發送角色，並在本機受保護目錄保存一把可透過受保護轉移包移轉的長效私鑰",
  };
  return { ...value, approvalDigest: digest(value) };
}

async function senderServiceAccountRevokeApproval(options, context) {
  const paths = setupPaths(options, context.env);
  const config = validateDirectFcmSenderConfig(await readJson(paths.configPath));
  assertSenderCredentialDirectory(config, paths.senderCredentialsDirectory);
  await (context.assertPrivateDirectory || assertPrivateDirectory)(paths.senderCredentialsDirectory);
  const keyPath = join(paths.senderCredentialsDirectory, SENDER_SERVICE_ACCOUNT_FILE);
  await (context.assertPrivateFile || assertPrivateFile)(keyPath);
  const credential = validateSenderServiceAccountKey(await readJson(keyPath), config.projectId);
  const accounts = await activeAccounts(context.runner || runExternal, paths.managementConfigDirectory, context.env);
  if (accounts.length !== 1) throw new Error("撤銷發送服務帳戶前必須有恰好一個隔離管理登入");
  const value = {
    schemaVersion: 1,
    action: "delete-sender-service-account",
    projectId: config.projectId,
    senderAccountId: credential.serviceAccountId,
    serviceAccountEmail: credential.serviceAccountEmail,
    role: `projects/${config.projectId}/roles/${SENDER_ROLE_ID}`,
    permissions: SENDER_PERMISSIONS,
    managementAccount: accounts[0],
    senderCredentialsDirectory: paths.senderCredentialsDirectory,
    effect: "刪除所有發送電腦共用的服務帳戶及全部遠端金鑰，再刪除此電腦的本機私鑰；其他電腦將立即失去發送權限",
  };
  return { ...value, approvalDigest: digest(value) };
}

function assertSenderCredentialDirectory(config, directory) {
  if (resolve(config.senderCredentialsDirectory) !== directory) {
    throw new Error("日常設定指向不同的發送憑證目錄，拒絕操作未知憑證");
  }
}

async function ensureDedicatedSenderServiceAccount(runner, approval, env, options = {}) {
  const listed = JSON.parse((await runner("gcloud", [
    "iam", "service-accounts", "list", "--project", approval.projectId,
    `--filter=email=${approval.serviceAccountEmail}`, "--format=json",
  ], { env })).stdout || "[]");
  let account = Array.isArray(listed) ? listed.find((item) => item?.email === approval.serviceAccountEmail) : null;
  if (!account && approval.action === "create-sender-service-account") {
    await runner("gcloud", [
      "iam", "service-accounts", "create", approval.senderAccountId,
      "--project", approval.projectId,
      "--display-name=DenDen FCM Sender",
      `--description=${senderServiceAccountDescription(approval.senderAccountId)}`,
      "--quiet",
    ], { env });
  } else if (!account && options.allowMissing) {
    return null;
  } else if (!account) {
    throw new Error("要撤銷的 DenDen 發送服務帳戶已不存在；請先人工確認遠端狀態");
  }
  account = JSON.parse((await runner("gcloud", [
    "iam", "service-accounts", "describe", approval.serviceAccountEmail,
    "--project", approval.projectId, "--format=json",
  ], { env })).stdout || "{}");
  if (account.email !== approval.serviceAccountEmail || account.disabled === true ||
      account.displayName !== "DenDen FCM Sender" || account.description !== senderServiceAccountDescription(approval.senderAccountId)) {
    throw new Error("既有服務帳戶內容與 DenDen 核准身分衝突；拒絕修改或刪除");
  }
  return account;
}

async function ensureSenderServiceAccountKey(runner, approval, directory, env, context) {
  const keyPath = join(directory, SENDER_SERVICE_ACCOUNT_FILE);
  const pendingPath = join(directory, ".service-account.pending.json");
  if (await exists(join(directory, "application_default_credentials.json"))) {
    throw new Error("發送憑證目錄含舊的人類使用者 ADC；拒絕混用，請先核准撤銷舊憑證");
  }
  if (await exists(keyPath)) {
    await (context.assertPrivateFile || assertPrivateFile)(keyPath);
    const existing = validateSenderServiceAccountKey(await readJson(keyPath), approval.projectId);
    if (existing.serviceAccountEmail !== approval.serviceAccountEmail) throw new Error("本機已有不同的 DenDen 發送服務帳戶金鑰");
    return existing;
  }
  if (!await exists(pendingPath)) {
    const keys = JSON.parse((await runner("gcloud", [
      "iam", "service-accounts", "keys", "list",
      `--iam-account=${approval.serviceAccountEmail}`,
      "--managed-by=user",
      "--format=json",
    ], { env })).stdout || "[]");
    if (Array.isArray(keys) && keys.length) {
      throw new Error("共用發送服務帳戶已有無法在本機驗證的遠端私鑰；停止建立另一把金鑰");
    }
    await runner("gcloud", [
      "iam", "service-accounts", "keys", "create", pendingPath,
      `--iam-account=${approval.serviceAccountEmail}`,
      "--key-file-type=json",
      "--quiet",
    ], { env });
  }
  const info = await lstat(pendingPath);
  if (!info.isFile() || info.size < 100 || info.size > 64 * 1024) throw new Error("Google 產生的服務帳戶金鑰檔案無效");
  const raw = await readFile(pendingPath);
  const credential = validateSenderServiceAccountKey(JSON.parse(raw.toString("utf8")), approval.projectId);
  if (credential.serviceAccountEmail !== approval.serviceAccountEmail) throw new Error("Google 產生了非預期的服務帳戶金鑰");
  await (context.writePrivateFile || writePrivateFile)(keyPath, raw);
  await (context.removeFile || rm)(pendingPath, { force: false });
  await (context.assertPrivateFile || assertPrivateFile)(keyPath);
  return credential;
}

async function ensureSenderRole(runner, projectId, roleName, permissions, env) {
  const result = await runner("gcloud", ["iam", "roles", "list", "--project", projectId, "--format=json"], { env });
  const roles = JSON.parse(result.stdout || "[]");
  const listed = Array.isArray(roles) ? roles.find((role) => role?.name === roleName) : null;
  if (!listed) {
    await runner("gcloud", [
      "iam", "roles", "create", SENDER_ROLE_ID, "--project", projectId,
      "--title=DenDen FCM Sender", `--permissions=${permissions.join(",")}`, "--stage=GA", "--quiet",
    ], { env });
    return;
  }
  const described = await runner("gcloud", ["iam", "roles", "describe", SENDER_ROLE_ID, "--project", projectId, "--format=json"], { env });
  const role = JSON.parse(described.stdout || "{}");
  const actual = [...(role.includedPermissions || [])].sort();
  if (role.deleted || actual.join("\n") !== [...permissions].sort().join("\n")) {
    throw new Error("既有 dendenFcmSender 自訂角色內容衝突；拒絕覆寫未知 IAM 角色");
  }
}

function assertIsolatedCredentialDirectory(target, root, label) {
  const path = relative(root, target);
  if (!path || path.startsWith("..") || isAbsolute(path)) throw new Error(`${label}必須位於 DenDen 設定目錄內`);
}

function assertPrivateArtifactPath(target, root, label) {
  const path = relative(root, target);
  if (!path || path.startsWith("..") || isAbsolute(path)) throw new Error(`${label}必須位於 DenDen 設定目錄內`);
}

function validateEmail(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) || normalized.length > 254) throw new Error("Google 帳號電子郵件無效");
  return normalized;
}

function validateSenderAccountId(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (!/^denden-[a-f0-9]{12}$/.test(normalized)) throw new Error("DenDen 發送服務帳戶 ID 無效");
  return normalized;
}

function senderServiceAccountDescription(accountId) {
  return `DenDen dedicated FCM sender ${validateSenderAccountId(accountId)}`;
}

function managementProbes(projectId) {
  const invalidProject = "denden-scope-probe-invalid";
  return [
    { key: "project_list", url: "https://cloudresourcemanager.googleapis.com/v1/projects?pageSize=1", method: "GET" },
    { key: "project_create", url: "https://cloudresourcemanager.googleapis.com/v3/projects", method: "POST", body: { projectId: "invalid!" } },
    { key: "service_enable", url: `https://serviceusage.googleapis.com/v1/projects/0/services/denden.invalid:enable`, method: "POST", body: {} },
    { key: "iam_modify", url: `https://cloudresourcemanager.googleapis.com/v1/projects/${invalidProject}:setIamPolicy`, method: "POST", body: { policy: {} } },
    { key: "firebase_add", url: `https://firebase.googleapis.com/v1beta1/projects/${invalidProject}:addFirebase`, method: "POST", body: {} },
    { key: "firebase_list", url: "https://firebase.googleapis.com/v1beta1/projects?pageSize=1", method: "GET" },
  ].map((probe) => ({ ...probe, quotaProject: projectId }));
}

async function authorizationProbe(probe, token, projectId, fetchImpl) {
  let response;
  try {
    response = await fetchImpl(probe.url, {
      method: probe.method,
      headers: {
        Authorization: `Bearer ${token.trim()}`,
        "Content-Type": "application/json",
        "x-goog-user-project": projectId,
      },
      ...(probe.body ? { body: JSON.stringify(probe.body) } : {}),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    return { key: probe.key, status: null, deniedBeforeValidation: false, transportError: true };
  }
  return {
    key: probe.key,
    status: response.status,
    deniedBeforeValidation: response.status === 401 || response.status === 403,
  };
}

async function retryPropagation(label, operation, context, retryable = () => true) {
  const delay = context.delay || ((millis) => new Promise((resolve) => setTimeout(resolve, millis)));
  const maxAttempts = context.propagationAttempts || 8;
  if (!Number.isSafeInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 12) throw new Error("IAM 傳播重試次數無效");
  let lastError;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      if (!retryable(error) || attempt === maxAttempts) break;
      context.progress?.(`${label}（${attempt}/${maxAttempts}）`);
      await delay(Math.min(10_000, 500 * (2 ** (attempt - 1))));
    }
  }
  throw new Error(`${label}逾時；未建立第二把金鑰，可安全沿用原摘要續作`, { cause: lastError });
}

function parseOptions(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key?.startsWith("--")) throw new Error(`未知設定參數：${key}`);
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

function notificationPolicyFromOptions(options) {
  const events = {};
  for (const event of NOTIFICATION_EVENTS) {
    const action = options[`notification-${event}`];
    if (action !== undefined) events[event] = action;
  }
  const policy = {
    preset: options["notification-preset"] || "all-completed",
    ...(Object.keys(events).length ? { events } : {}),
  };
  validatePolicy(policy, "日常通知");
  return policy;
}

function required(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} 無效`);
  return value.trim();
}
