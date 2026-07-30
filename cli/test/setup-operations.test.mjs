import test from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createDirectPairingBundle, writeDirectPairingBundle } from "../src/direct-setup.mjs";
import { ensurePrivateDirectory, writePrivateJson } from "../src/config.mjs";
import { testServiceAccountKey } from "./fixtures.mjs";
import {
  runDirectDoctorCommand,
  runDailySkillInstallCommand,
  runDailySkillPlanCommand,
  runFirstSetupCommand,
  runFirstSetupPlanCommand,
  runManagementAuthCommand,
  runManagementRevokeCommand,
  runManagementRevokePlanCommand,
  runPairingStatusCommand,
  runReissueQrCommand,
  runRemoveQrCommand,
  runRotatePairingCommand,
  runRotatePlanCommand,
  runSenderAuthCommand,
  runSenderAuthPlanCommand,
  runSenderRevokeCommand,
  runSenderRevokePlanCommand,
  runSenderVerifyCommand,
} from "../src/setup-operations.mjs";

const firebase = {
  projectId: "denden-demo-123",
  firebaseAppId: "1:123456789012:android:0123456789abcdef",
  apiKey: "AIzaSyDendenProtocolTestOnly000000000",
  gcmSenderId: "123456789012",
  androidPackageName: "com.tensal.denden",
};

test("management login uses a visible interactive command path", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-management-auth-"));
  const calls = [];
  try {
    const result = await runManagementAuthCommand(["--config-path", join(root, "config.json")], {
      runner: async (_command, args, options) => {
        calls.push({ args, options });
        return args[0] === "auth" && args[1] === "list"
          ? { stdout: JSON.stringify([{ account: "owner@example.test" }]) }
          : { stdout: "" };
      },
    });
    assert.equal(result.authorized, true);
    assert.equal(calls[0].options.interactive, true);
    assert.equal(calls[0].options.timeoutMillis, 15 * 60_000);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("first setup uses one approval digest for Firebase, sender, verification, revocation, and selected skill action", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-first-setup-"));
  const env = {
    DENDEN_INSTALL_ROOT: join(root, "installed"),
    CODEX_HOME: join(root, "codex"),
  };
  await mkdir(join(root, "cli"));
  await writeFile(join(root, "cli", "runtime.mjs"), "export const runtime = 'direct-fcm-v2';\n");
  const args = [
    "--project-id", firebase.projectId,
    "--config-path", join(root, "config.json"),
    "--management-config-dir", join(root, "management"),
    "--sender-config-dir", join(root, "sender"),
    "--first-setup-state-path", join(root, "first-setup-state.json"),
    "--skill-choice", "skip",
    "--source-root", root,
  ];
  const runner = async (_command, commandArgs) => {
    assert.deepEqual(commandArgs.slice(0, 2), ["auth", "list"]);
    return { stdout: JSON.stringify([{ account: "owner@example.test" }]) };
  };
  try {
    const plan = await runFirstSetupPlanCommand(args, {
      env,
      runner,
      randomBytes: (size) => Buffer.alloc(size, 0xab),
    });
    assert.equal(plan.action, "complete-first-direct-fcm-setup");
    assert.equal(plan.dailySkill.choice, "skip");
    assert.equal(plan.dailySkill.notificationPolicy, null);
    assert.equal(plan.sender.permissions.length, 2);
    assert.match(plan.sender.senderAccountId, /^denden-[a-f0-9]{12}$/);
    assert.ok(plan.runtimeSourceDigest);
    assert.equal(plan.direct.approvalDigest, undefined);
    assert.equal(plan.sender.approvalDigest, undefined);
    assert.equal(plan.managementRevocation.approvalDigest, undefined);
    assert.equal(plan.dailySkill.digest, undefined);

    const calls = [];
    const executionArgs = [
      ...args,
      "--sender-account-id", plan.sender.senderAccountId,
      "--approved-digest", plan.approvalDigest,
    ];
    const lockPath = join(root, "first-setup.lock");
    await writeFile(lockPath, JSON.stringify({ schemaVersion: 1, pid: process.pid, startedAtMillis: Date.now() }));
    await assert.rejects(runFirstSetupCommand(executionArgs, { env, runner }), /另一個 DenDen 設定變更程序/);
    await rm(lockPath, { force: true });
    await assert.rejects(runFirstSetupCommand([
      ...args,
      "--sender-account-id", plan.sender.senderAccountId,
    ], { env, runner }), /明確同意/);
    await assert.rejects(runFirstSetupCommand(executionArgs, {
      env,
      runner,
      directSetup: async () => {
        calls.push("direct-paused");
        throw new Error("Firebase terms 尚未接受");
      },
    }), /terms/);
    await assert.rejects(runFirstSetupCommand(executionArgs, {
      env,
      runner,
      directSetup: async () => {
        calls.push("direct-state-loss");
        return { configured: true, projectId: firebase.projectId };
      },
      writeFirstSetupState: async (_path, value) => {
        if (value.stage === "firebase-configured") throw new Error("模擬遠端成功後本機 checkpoint 寫入失敗");
        await writePrivateJson(_path, value);
      },
    }), /checkpoint 寫入失敗/);
    const result = await runFirstSetupCommand(executionArgs, {
      env,
      runner,
      directSetup: async (phaseArgs) => {
        calls.push("direct");
        assert.match(phaseArgs[phaseArgs.indexOf("--approved-digest") + 1], /^[a-f0-9]{64}$/);
        return { configured: true, projectId: firebase.projectId };
      },
      senderAuth: async (phaseArgs) => {
        calls.push("sender");
        assert.match(phaseArgs[phaseArgs.indexOf("--approved-digest") + 1], /^[a-f0-9]{64}$/);
        return { authorized: true, serviceAccountEmail: plan.sender.serviceAccountEmail };
      },
      senderVerify: async () => {
        calls.push("verify");
        return { verified: true, fcmValidateOnly: true };
      },
      managementRevoke: async (phaseArgs) => {
        calls.push("revoke");
        assert.match(phaseArgs[phaseArgs.indexOf("--approved-digest") + 1], /^[a-f0-9]{64}$/);
        return { revoked: true };
      },
      installDailySkill: async (skillPlan, options) => {
        calls.push("skill");
        assert.equal(options.approvedDigest, skillPlan.digest);
        return { installed: false, skipped: true };
      },
    });
    assert.deepEqual(calls, ["direct-paused", "direct-state-loss", "direct", "sender", "verify", "revoke", "skill"]);
    assert.equal(result.approvalDigest, plan.approvalDigest);
    assert.equal(result.managementCredentialRemoved, true);
    assert.doesNotMatch(JSON.stringify(result), /eventKey|brandKey|private_key|DDC\./);

    const resumed = await runFirstSetupCommand(executionArgs, {
      env,
      runner: async () => { throw new Error("完成後不得重跑 Google 操作"); },
    });
    assert.equal(resumed.reused, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("daily skill setup binds and saves the selected notification policy", async () => {
  const fixture = await pairingFixture();
  const destination = join(fixture.root, "skills", "denden");
  const baseArgs = [
    "--choice", "specified",
    "--destination", destination,
    "--source-root", process.cwd(),
    "--config-path", fixture.configPath,
  ];
  try {
    const defaults = await runDailySkillPlanCommand(baseArgs);
    assert.deepEqual(defaults.notificationPolicy, { preset: "all-completed" });

    const customArgs = [
      ...baseArgs,
      "--notification-preset", "important",
      "--notification-completed", "quiet",
      "--notification-blocked", "ring",
    ];
    const plan = await runDailySkillPlanCommand(customArgs);
    assert.deepEqual(plan.notificationPolicy, {
      preset: "important",
      events: { completed: "quiet", blocked: "ring" },
    });
    assert.notEqual(plan.digest, defaults.digest);
    await assert.rejects(
      runDailySkillPlanCommand([...baseArgs, "--notification-failed", "loud"]),
      /政策事件或動作無效/,
    );

    const installed = await runDailySkillInstallCommand([...customArgs, "--approved-digest", plan.digest]);
    assert.equal(installed.destination, destination);
    const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
    assert.deepEqual(config.policy, plan.notificationPolicy);
  } finally {
    await fixture.cleanup();
  }
});

test("pairing status and QR reissue expose fingerprints but no bearer secrets", async () => {
  const fixture = await pairingFixture();
  try {
    const args = ["--config-path", fixture.configPath, "--brand-config-path", fixture.brandConfigPath, "--qr-path", fixture.qrPath];
    const status = await runPairingStatusCommand(args);
    assert.equal(status.projectId, firebase.projectId);
    assert.doesNotMatch(JSON.stringify(status), /"eventKey"|"brandKey"|ICEiIy/);
    const reissued = await runReissueQrCommand(args, { nowMillis: 1_800_000_100_000 });
    assert.equal(reissued.pairingFingerprint, status.pairingFingerprint);
    assert.deepEqual((await readFile(reissued.qrPath)).subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    const removed = await runRemoveQrCommand(args);
    assert.equal(removed.removed, true);
    await assert.rejects(readFile(reissued.qrPath), /ENOENT/);
    assert.equal((await runRemoveQrCommand(args)).removed, false);
    assert.equal((await readFile(fixture.configPath, "utf8")).includes("eventKey"), true);
  } finally {
    await fixture.cleanup();
  }
});

test("pairing rotation requires a digest bound to the current pairing", async () => {
  const fixture = await pairingFixture();
  try {
    const args = ["--config-path", fixture.configPath, "--brand-config-path", fixture.brandConfigPath, "--qr-path", fixture.qrPath];
    const plan = await runRotatePlanCommand(args);
    await assert.rejects(runRotatePairingCommand(args), /明確同意/);
    let byte = 90;
    const result = await runRotatePairingCommand([...args, "--approved-digest", plan.approvalDigest], {
      nowMillis: 1_800_000_100_000,
      randomBytes: (size) => Buffer.alloc(size, byte++),
    });
    assert.notEqual(result.pairingFingerprint, plan.currentPairingFingerprint);
    const nextPlan = await runRotatePlanCommand(args);
    assert.notEqual(nextPlan.approvalDigest, plan.approvalDigest);
  } finally {
    await fixture.cleanup();
  }
});

test("sender authorization creates one approval-bound low-privilege service account", async () => {
  const fixture = await pairingFixture();
  const calls = [];
  let tokenAttempts = 0;
  const accountId = "denden-abababababab";
  const accountEmail = `${accountId}@${firebase.projectId}.iam.gserviceaccount.com`;
  let roleExists = false;
  let senderExists = false;
  const runner = async (command, args, options) => {
    calls.push({ command, args, config: options?.env?.CLOUDSDK_CONFIG });
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.com" }]) };
    if (args[0] === "iam" && args[1] === "roles" && args[2] === "list") {
      return { stdout: JSON.stringify(roleExists ? [{ name: `projects/${firebase.projectId}/roles/dendenFcmSender` }] : []) };
    }
    if (args[0] === "iam" && args[1] === "roles" && args[2] === "create") roleExists = true;
    if (args[0] === "iam" && args[1] === "roles" && args[2] === "describe") {
      return { stdout: JSON.stringify({ includedPermissions: ["cloudmessaging.messages.create", "serviceusage.services.use"] }) };
    }
    if (args[0] === "iam" && args[1] === "service-accounts" && args[2] === "list") {
      return { stdout: JSON.stringify(senderExists ? [{ email: accountEmail }] : []) };
    }
    if (args[0] === "iam" && args[1] === "service-accounts" && args[2] === "create") senderExists = true;
    if (args[0] === "iam" && args[1] === "service-accounts" && args[2] === "describe") {
      return { stdout: JSON.stringify({
        email: accountEmail,
        displayName: "DenDen FCM Sender",
        description: `DenDen dedicated FCM sender ${accountId}`,
        disabled: false,
      }) };
    }
    if (args[0] === "iam" && args[1] === "service-accounts" && args[2] === "keys" && args[3] === "list") {
      return { stdout: "[]" };
    }
    if (args[0] === "iam" && args[1] === "service-accounts" && args[2] === "keys" && args[3] === "create") {
      await writeFile(args[4], JSON.stringify(testServiceAccountKey(accountId)));
    }
    return { stdout: "" };
  };
  try {
    const args = [
      "--project-id", firebase.projectId,
      "--sender-config-dir", join(fixture.root, "sender"),
      "--management-config-dir", join(fixture.root, "management"),
      "--config-path", fixture.configPath,
    ];
    const plan = await runSenderAuthPlanCommand(args, { runner, randomBytes: () => Buffer.alloc(6, 0xab) });
    assert.equal(plan.senderAccountId, accountId);
    assert.equal(plan.managementAccount, "owner@example.com");
    await assert.rejects(runSenderAuthCommand([...args, "--sender-account-id", accountId], { runner }), /明確同意/);
    await assert.rejects(runSenderAuthCommand([
      ...args,
      "--sender-account-id", accountId,
      "--approved-digest", plan.approvalDigest,
    ], {
      runner,
      writePrivateFile: async () => { throw new Error("模擬金鑰遠端建立後本機寫入失敗"); },
    }), /本機寫入失敗/);
    const result = await runSenderAuthCommand([
      ...args,
      "--sender-account-id", accountId,
      "--approved-digest", plan.approvalDigest,
    ], {
      tokenProvider: async () => {
        tokenAttempts += 1;
        if (tokenAttempts < 3) throw new Error("IAM 尚未傳播");
        return "short-lived-token";
      },
      delay: async () => {},
      runner,
    });
    const role = calls.find((call) => call.args[0] === "iam" && call.args[1] === "roles" && call.args[2] === "create");
    assert.match(role.args.join(" "), /cloudmessaging\.messages\.create,serviceusage\.services\.use/);
    assert.ok(calls.some((call) => call.args.includes("add-iam-policy-binding") && call.args.includes(`--member=serviceAccount:${accountEmail}`)));
    assert.ok(calls.some((call) => call.args[3] === "create" && call.args[4]?.endsWith(".service-account.pending.json")));
    assert.equal(calls.some((call) => call.args.includes("application-default") || call.args.includes("login")), false);
    assert.equal(result.projectId, firebase.projectId);
    assert.equal(result.serviceAccountEmail, accountEmail);
    assert.equal(tokenAttempts, 3);
    assert.equal(calls.filter((call) => call.args[0] === "iam" && call.args[1] === "roles" && call.args[2] === "create").length, 1);
    assert.equal(calls.filter((call) => call.args[0] === "iam" && call.args[1] === "service-accounts" && call.args[2] === "create").length, 1);
    assert.equal(calls.filter((call) => call.args.includes("add-iam-policy-binding")).length, 2);
    assert.equal(calls.filter((call) => call.args[3] === "create" && call.args[4]?.endsWith(".service-account.pending.json")).length, 1);
    assert.doesNotMatch(JSON.stringify(result), /short-lived-token/);
  } finally {
    await fixture.cleanup();
  }
});

test("sender revocation deletes only this computer service account and local key directory", async () => {
  const fixture = await pairingFixture();
  const calls = [];
  const removed = [];
  const accountId = "denden-012345abcdef";
  const accountEmail = `${accountId}@${firebase.projectId}.iam.gserviceaccount.com`;
  let accountExists = true;
  const runner = async (_command, commandArgs, options) => {
    calls.push({ commandArgs, directory: options.env.CLOUDSDK_CONFIG });
    if (commandArgs[0] === "auth" && commandArgs[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.com" }]) };
    if (commandArgs[0] === "iam" && commandArgs[1] === "service-accounts" && commandArgs[2] === "list") {
      return { stdout: JSON.stringify(accountExists ? [{ email: accountEmail }] : []) };
    }
    if (commandArgs[0] === "iam" && commandArgs[1] === "service-accounts" && commandArgs[2] === "delete") {
      accountExists = false;
    }
    if (commandArgs[0] === "iam" && commandArgs[1] === "service-accounts" && commandArgs[2] === "describe") {
      return { stdout: JSON.stringify({
        email: accountEmail,
        displayName: "DenDen FCM Sender",
        description: `DenDen dedicated FCM sender ${accountId}`,
        disabled: false,
      }) };
    }
    return { stdout: "" };
  };
  try {
    await ensurePrivateDirectory(join(fixture.root, "sender"));
    await writePrivateJson(join(fixture.root, "sender", "service-account.json"), testServiceAccountKey(accountId));
    const args = [
      "--config-path", fixture.configPath,
      "--brand-config-path", fixture.brandConfigPath,
      "--qr-path", fixture.qrPath,
      "--sender-config-dir", join(fixture.root, "sender"),
      "--management-config-dir", join(fixture.root, "management"),
    ];
    const plan = await runSenderRevokePlanCommand(args, { runner });
    await assert.rejects(runSenderRevokeCommand(args, { runner }), /尚未取得明確同意/);
    await assert.rejects(runSenderRevokeCommand([...args, "--approved-digest", plan.approvalDigest], {
      runner,
      removeDirectory: async () => { throw new Error("模擬遠端撤銷後本機清理失敗"); },
    }), /本機清理失敗/);
    const resumed = await runSenderRevokeCommand([...args, "--approved-digest", plan.approvalDigest], {
      runner,
      removeDirectory: async (path, options) => removed.push({ path, options }),
    });
    assert.equal(resumed.reused, true);
    assert.ok(calls.some((call) => call.commandArgs[0] === "iam" && call.commandArgs[2] === "delete" && call.commandArgs.includes(accountEmail)));
    assert.equal(calls.some((call) => call.commandArgs.includes("application-default")), false);
    assert.equal(removed[0].path, join(fixture.root, "sender"));
    assert.equal(await readFile(fixture.configPath, "utf8").then(Boolean), true);
  } finally {
    await fixture.cleanup();
  }
});

test("sender verification accepts validate-only FCM and rejects management reachability", async () => {
  const fixture = await pairingFixture();
  try {
    const calls = [];
    let fcmAttempts = 0;
    const result = await runSenderVerifyCommand(["--config-path", fixture.configPath], {
      nowMillis: 1_800_000_100_000,
      tokenProvider: async () => "short-lived-token",
      fetchImpl: async (url, options) => {
        calls.push({ url, options });
        if (url.startsWith("https://fcm.googleapis.com/")) {
          fcmAttempts += 1;
          assert.equal(JSON.parse(options.body).validate_only, true);
          return fcmAttempts < 3 ? response(403, {}) : response(200, { name: "validated" });
        }
        if (url.startsWith("https://iid.googleapis.com/")) return response(400, {});
        return response(403, {});
      },
      delay: async () => {},
    });
    assert.equal(result.verified, true);
    assert.equal(fcmAttempts, 3);
    assert.equal(result.management.every((probe) => probe.deniedBeforeValidation), true);
    assert.equal(result.topicManagement.reachableWithScope, true);
    assert.equal(calls.every((call) => call.options.headers.Authorization === "Bearer short-lived-token"), true);
    assert.doesNotMatch(JSON.stringify(result), /short-lived-token/);
  } finally {
    await fixture.cleanup();
  }
});

test("sender verification fails closed when a management API validates the request", async () => {
  const fixture = await pairingFixture();
  try {
    const result = await runSenderVerifyCommand(["--config-path", fixture.configPath], {
      tokenProvider: async () => "token",
      fetchImpl: async (url) => url.startsWith("https://fcm.googleapis.com/")
        ? response(200, {})
        : response(url.includes("cloudresourcemanager.googleapis.com/v1/projects?pageSize") ? 400 : 403, {}),
    });
    assert.equal(result.verified, false);
    assert.match(result.next, /安全停止/);
  } finally {
    await fixture.cleanup();
  }
});

test("management credential removal requires a digest and removes only the isolated directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-management-revoke-"));
  const configPath = join(root, "config.json");
  const removed = [];
  const runner = async (_command, args) => args[0] === "auth" && args[1] === "list"
    ? { stdout: JSON.stringify([{ account: "owner@example.com" }]) }
    : { stdout: "" };
  try {
    const args = ["--config-path", configPath];
    const plan = await runManagementRevokePlanCommand(args, { runner });
    await assert.rejects(runManagementRevokeCommand(args, { runner }), /尚未取得明確同意/);
    await assert.rejects(runManagementRevokeCommand([...args, "--approved-digest", plan.approvalDigest], {
      runner,
      removeDirectory: async () => { throw new Error("模擬遠端撤銷後本機清理失敗"); },
    }), /本機清理失敗/);
    const resumed = await runManagementRevokeCommand([
      ...args,
      "--management-account", "owner@example.com",
      "--approved-digest", plan.approvalDigest,
    ], {
      runner: async () => { throw new Error("遠端撤銷完成後不得再次呼叫 gcloud"); },
      removeDirectory: async (path, options) => removed.push({ path, options }),
    });
    assert.equal(resumed.reused, true);
    assert.equal(removed.length, 1);
    assert.match(removed[0].path, /gcloud-management$/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("credential directories cannot escape the DenDen configuration directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-directory-boundary-"));
  try {
    await assert.rejects(runSenderAuthCommand([
      "--config-path", join(root, "config.json"),
      "--project-id", firebase.projectId,
      "--sender-config-dir", join(root, "..", "shared-gcloud"),
    ], {}), /必須位於 DenDen 設定目錄內/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("doctor treats Firebase CLI as unnecessary", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-doctor-"));
  try {
    const result = await runDirectDoctorCommand(["--config-path", join(root, "config.json")], {
      runner: async () => ({ stdout: "ok" }),
    });
    const firebaseCli = result.checks.find((check) => check.key === "firebase_cli");
    assert.equal(firebaseCli.required, false);
    assert.equal(firebaseCli.ok, true);
    assert.equal(result.checks.find((check) => check.key === "sender_service_account_key")?.ok, false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("doctor requires the one-time management credential to be absent after setup", async () => {
  const fixture = await pairingFixture();
  const senderDirectory = join(fixture.root, "sender");
  const managementDirectory = join(fixture.root, "management");
  try {
    await ensurePrivateDirectory(senderDirectory);
    await writePrivateJson(join(senderDirectory, "service-account.json"), testServiceAccountKey());
    const args = [
      "--config-path", fixture.configPath,
      "--brand-config-path", fixture.brandConfigPath,
      "--qr-path", fixture.qrPath,
      "--sender-config-dir", senderDirectory,
      "--management-config-dir", managementDirectory,
    ];
    const completed = await runDirectDoctorCommand(args, {
      runner: async () => ({ stdout: "ok" }),
      assertPrivateDirectory: async () => true,
      tokenProvider: async () => "token",
    });
    assert.equal(completed.checks.find((check) => check.key === "management_credential_removed")?.ok, true);
    assert.equal(completed.ready, true);

    await import("node:fs/promises").then(({ mkdir }) => mkdir(managementDirectory, { recursive: true }));
    const credentialPresent = await runDirectDoctorCommand(args, {
      runner: async (_command, commandArgs) => commandArgs[0] === "auth"
        ? { stdout: JSON.stringify([{ account: "owner@example.com" }]) }
        : { stdout: "ok" },
      assertPrivateDirectory: async () => true,
      tokenProvider: async () => "token",
    });
    assert.equal(credentialPresent.checks.find((check) => check.key === "management_credential_removed")?.ok, false);
    assert.equal(credentialPresent.ready, false);
  } finally {
    await fixture.cleanup();
  }
});

async function pairingFixture() {
  const root = await mkdtemp(join(tmpdir(), "denden-pairing-ops-"));
  const configPath = join(root, "config.json");
  const brandConfigPath = join(root, "brand.json");
  const qrPath = join(root, "pairing.png");
  let byte = 1;
  const bundle = createDirectPairingBundle(firebase, {
    nowMillis: 1_800_000_000_000,
    senderCredentialsDirectory: join(root, "sender"),
    randomBytes: (size) => Buffer.alloc(size, byte++),
  });
  await writeDirectPairingBundle(bundle, { configPath, brandConfigPath, qrPath });
  return {
    root,
    configPath,
    brandConfigPath,
    qrPath,
    cleanup: () => rm(root, { recursive: true, force: true }),
  };
}

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  };
}
