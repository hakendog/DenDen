import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildDirectSetupPlan, createDirectPairingBundle, parseAndroidAppConfig, provisionDirectFirebaseProject, runDirectInstallCommand, runDirectSetupCommand, runDirectSetupPlanCommand, writeDirectPairingBundle } from "../src/direct-setup.mjs";
import { parseDirectFcmInvite } from "../src/direct-fcm-protocol.mjs";

const firebase = {
  projectId: "denden-demo-123",
  firebaseAppId: "1:123456789012:android:0123456789abcdef",
  apiKey: "AIzaSyDendenProtocolTestOnly000000000",
  gcmSenderId: "123456789012",
  androidPackageName: "com.tensal.denden",
};
const managementAccount = "owner@example.test";

test("one pairing bundle separates daily and brand authority and writes one QR", async () => {
  let byte = 0;
  const bundle = createDirectPairingBundle(firebase, {
    nowMillis: 1_800_000_000_000,
    senderCredentialsDirectory: "C:/private/denden-sender",
    randomBytes: (size) => Buffer.alloc(size, byte++),
  });
  assert.equal(bundle.senderConfig.brandKey, undefined);
  assert.equal(bundle.brandConfig.eventKey, undefined);
  assert.equal(bundle.senderConfig.firebaseAppId, firebase.firebaseAppId);
  assert.equal(bundle.brandConfig.androidPackageName, firebase.androidPackageName);
  assert.equal(parseDirectFcmInvite(bundle.encodedInvite, { nowMillis: 1_800_000_000_000 }).brandKey, bundle.invite.brandKey);

  const root = await mkdtemp(join(tmpdir(), "denden-direct-setup-"));
  try {
    const files = await writeDirectPairingBundle(bundle, {
      configPath: join(root, "config.json"),
      brandConfigPath: join(root, "brand.json"),
      qrPath: join(root, "pairing.png"),
    });
    assert.equal(JSON.parse(await readFile(files.configPath, "utf8")).brandKey, undefined);
    assert.equal(JSON.parse(await readFile(files.brandConfigPath, "utf8")).eventKey, undefined);
    assert.deepEqual((await readFile(files.qrPath)).subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Firebase Android config is selected by exact package name", () => {
  const file = Buffer.from(JSON.stringify({
    project_info: { project_id: firebase.projectId, project_number: firebase.gcmSenderId },
    client: [{
      client_info: {
        mobilesdk_app_id: firebase.firebaseAppId,
        android_client_info: { package_name: firebase.androidPackageName },
      },
      api_key: [{ current_key: firebase.apiKey }],
    }],
  })).toString("base64");
  assert.deepEqual(parseAndroidAppConfig(file, firebase.androidPackageName), firebase);
  assert.throws(() => parseAndroidAppConfig(file, "other.app"), /套件名稱/);
});

test("provisioning creates an unbilled project, adds Firebase, and registers only the Android app", async () => {
  const commands = [];
  let billingChecks = 0;
  let firebaseProjectReads = 0;
  let androidAppReads = 0;
  let firebaseWrites = 0;
  let androidAppWrites = 0;
  const runner = async (command, args) => {
    commands.push([command, ...args]);
    if (args[0] === "projects" && args[1] === "list") return { stdout: "[]" };
    if (args[0] === "projects" && args[1] === "create") {
      return { stdout: JSON.stringify({ projectId: firebase.projectId, projectNumber: firebase.gcmSenderId }) };
    }
    if (args[0] === "billing") {
      billingChecks += 1;
      return { stdout: JSON.stringify({ billingEnabled: false }) };
    }
    if (args[0] === "services") return { stdout: "" };
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.test" }]) };
    if (args[0] === "auth") return { stdout: "management-token" };
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  const googleServices = Buffer.from(JSON.stringify({
    project_info: { project_id: firebase.projectId, project_number: firebase.gcmSenderId },
    client: [{
      client_info: {
        mobilesdk_app_id: firebase.firebaseAppId,
        android_client_info: { package_name: firebase.androidPackageName },
      },
      api_key: [{ current_key: firebase.apiKey }],
    }],
  })).toString("base64");
  const fetchImpl = async (url, init = {}) => {
    const path = url.replace("https://firebase.googleapis.com/v1beta1/", "");
    assert.equal(init.headers.Authorization, "Bearer management-token");
    assert.equal(init.headers["x-goog-user-project"], firebase.projectId);
    if (path === `projects/${firebase.projectId}`) {
      firebaseProjectReads += 1;
      return new Response("{}", { status: firebaseProjectReads < 3 ? 503 : 404 });
    }
    if (path.endsWith(":addFirebase")) {
      firebaseWrites += 1;
      return Response.json({ name: "operations/add" });
    }
    if (path === "operations/add") return Response.json({ done: true, response: { projectId: firebase.projectId } });
    if (path.endsWith("/androidApps") && init.method !== "POST") {
      androidAppReads += 1;
      return androidAppReads === 1 ? new Response("{}", { status: 429 }) : Response.json({ apps: [] });
    }
    if (path.endsWith("/androidApps") && init.method === "POST") {
      androidAppWrites += 1;
      return Response.json({ name: "operations/app" });
    }
    if (path === "operations/app") return Response.json({ done: true, response: { name: `projects/${firebase.projectId}/androidApps/app1` } });
    if (path.endsWith("/androidApps/app1")) return Response.json({
      name: `projects/${firebase.projectId}/androidApps/app1`,
      projectId: firebase.projectId,
      packageName: firebase.androidPackageName,
      appId: firebase.firebaseAppId,
    });
    if (path.endsWith("/androidApps/app1/config")) return Response.json({ configFileContents: googleServices });
    throw new Error(`unexpected URL: ${url}`);
  };

  assert.deepEqual(await provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    expectedManagementAccount: managementAccount,
    runner,
    fetchImpl,
    delay: async () => {},
  }), firebase);
  assert.equal(billingChecks, 2);
  assert.ok(commands.some((args) => args[1] === "projects" && args[2] === "create"));
  const enabled = commands.find((args) => args[1] === "services" && args[2] === "enable");
  assert.ok(enabled.includes("firebase.googleapis.com"));
  assert.ok(enabled.includes("fcm.googleapis.com"));
  assert.ok(enabled.includes("iam.googleapis.com"));
  assert.equal(firebaseProjectReads, 3);
  assert.equal(androidAppReads, 2);
  assert.equal(firebaseWrites, 1);
  assert.equal(androidAppWrites, 1);
});

test("project quota rejection leaves no pre-write state that would block choosing an existing project", async () => {
  let state = null;
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: managementAccount }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: "[]" };
    if (args[0] === "projects" && args[1] === "create") throw new Error("RESOURCE_EXHAUSTED: project creation quota");
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  await assert.rejects(provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    managementConfigDirectory: "C:/private/denden-management",
    expectedManagementAccount: managementAccount,
    runner,
    readState: async () => state,
    writeState: async (value) => { state = structuredClone(value); },
    removeState: async () => { state = null; },
  }), /建立配額不足/);
  assert.equal(state, null);
});

test("provisioning refuses a management-account switch before Firebase writes", async () => {
  let writes = 0;
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") {
      return { stdout: JSON.stringify([{ account: "other@example.test" }]) };
    }
    writes += 1;
    return { stdout: "{}" };
  };
  await assert.rejects(provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    expectedManagementAccount: managementAccount,
    runner,
    fetchImpl: async () => { writes += 1; return Response.json({}); },
  }), /管理帳號與已核准規劃不符/);
  assert.equal(writes, 0);
});

test("Firebase Management requests have a bounded per-request timeout", async () => {
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: managementAccount }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: JSON.stringify([{ projectId: firebase.projectId }]) };
    if (args[0] === "projects" && args[1] === "describe") return { stdout: JSON.stringify({
      projectId: firebase.projectId,
      projectNumber: firebase.gcmSenderId,
      lifecycleState: "ACTIVE",
    }) };
    if (args[0] === "billing") return { stdout: JSON.stringify({ billingEnabled: false }) };
    if (args[0] === "services") return { stdout: "" };
    if (args[0] === "auth") return { stdout: "management-token" };
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  await assert.rejects(provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    expectedManagementAccount: managementAccount,
    allowExistingDedicated: true,
    runner,
    httpTimeoutMillis: 1_000,
    totalWaitMillis: 10_000,
    fetchImpl: async (_url, options) => new Promise((_resolve, reject) => {
      const timer = setTimeout(() => {}, 2_000);
      options.signal.addEventListener("abort", () => {
        clearTimeout(timer);
        reject(options.signal.reason);
      }, { once: true });
    }),
  }), /專案檢查逾時.*可安全重跑/);
});

test("provisioning refuses every existing project before Firebase writes", async () => {
  let writes = 0;
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.com" }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: JSON.stringify([{ projectId: firebase.projectId }]) };
    if (args[0] === "projects" && args[1] === "describe") return { stdout: JSON.stringify({ labels: {} }) };
    writes += 1;
    return { stdout: "{}" };
  };
  await assert.rejects(provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    runner,
    fetchImpl: async () => { writes += 1; return Response.json({}); },
  }), /allow-existing-dedicated/);
  assert.equal(writes, 0);
});

test("provisioning explicitly adopts an existing unbilled Firebase project without creating another project", async () => {
  const commands = [];
  const app = {
    name: `projects/${firebase.projectId}/androidApps/app1`,
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    appId: firebase.firebaseAppId,
  };
  const configFileContents = Buffer.from(JSON.stringify({
    project_info: { project_id: firebase.projectId, project_number: firebase.gcmSenderId },
    client: [{
      client_info: { mobilesdk_app_id: firebase.firebaseAppId, android_client_info: { package_name: firebase.androidPackageName } },
      api_key: [{ current_key: firebase.apiKey }],
    }],
  })).toString("base64");
  const runner = async (_command, args) => {
    commands.push(args);
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: managementAccount }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: JSON.stringify([{ projectId: firebase.projectId }]) };
    if (args[0] === "projects" && args[1] === "describe") return { stdout: JSON.stringify({
      projectId: firebase.projectId,
      projectNumber: firebase.gcmSenderId,
      lifecycleState: "ACTIVE",
    }) };
    if (args[0] === "billing") return { stdout: JSON.stringify({ billingEnabled: false }) };
    if (args[0] === "services") return { stdout: "" };
    if (args[0] === "auth") return { stdout: "management-token" };
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  const fetchImpl = async (url, init = {}) => {
    const path = url.replace("https://firebase.googleapis.com/v1beta1/", "");
    if (path === `projects/${firebase.projectId}`) return Response.json({ projectId: firebase.projectId });
    if (path === `projects/${firebase.projectId}/androidApps`) return Response.json({ apps: [app] });
    if (path === app.name) return Response.json(app);
    if (path === `${app.name}/config`) return Response.json({ configFileContents });
    throw new Error(`unexpected ${init.method || "GET"} URL: ${url}`);
  };

  assert.deepEqual(await provisionDirectFirebaseProject({
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    expectedManagementAccount: managementAccount,
    allowExistingDedicated: true,
    runner,
    fetchImpl,
  }), firebase);
  assert.equal(commands.some((args) => args[0] === "projects" && args[1] === "create"), false);
});

test("project setup resumes only the project labeled by its protected setup state", async () => {
  let state = null;
  let projectExists = false;
  let createCount = 0;
  let failServices = true;
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.com" }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: JSON.stringify(projectExists ? [{ projectId: firebase.projectId }] : []) };
    if (args[0] === "projects" && args[1] === "create") {
      projectExists = true;
      createCount += 1;
      return { stdout: JSON.stringify({ projectId: firebase.projectId, projectNumber: firebase.gcmSenderId }) };
    }
    if (args[0] === "projects" && args[1] === "describe") {
      return { stdout: JSON.stringify({
        projectId: firebase.projectId,
        projectNumber: firebase.gcmSenderId,
        labels: { "denden-managed": "true", "denden-setup": state.setupId },
      }) };
    }
    if (args[0] === "billing") return { stdout: JSON.stringify({ billingEnabled: false }) };
    if (args[0] === "services" && failServices) {
      failServices = false;
      throw new Error("暫時離線");
    }
    if (args[0] === "services") return { stdout: "" };
    if (args[0] === "auth") return { stdout: "management-token" };
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  const googleServices = Buffer.from(JSON.stringify({
    project_info: { project_id: firebase.projectId, project_number: firebase.gcmSenderId },
    client: [{
      client_info: { mobilesdk_app_id: firebase.firebaseAppId, android_client_info: { package_name: firebase.androidPackageName } },
      api_key: [{ current_key: firebase.apiKey }],
    }],
  })).toString("base64");
  const fetchImpl = async (url, init = {}) => {
    const path = url.replace("https://firebase.googleapis.com/v1beta1/", "");
    if (path === `projects/${firebase.projectId}`) return new Response("{}", { status: 404 });
    if (path.endsWith(":addFirebase")) return Response.json({ name: "operations/add" });
    if (path === "operations/add") return Response.json({ done: true });
    if (path.endsWith("/androidApps") && init.method !== "POST") return Response.json({ apps: [] });
    if (path.endsWith("/androidApps") && init.method === "POST") return Response.json({ name: "operations/app" });
    if (path === "operations/app") return Response.json({ done: true, response: { name: `projects/${firebase.projectId}/androidApps/app1` } });
    if (path.endsWith("/androidApps/app1")) return Response.json({
      name: `projects/${firebase.projectId}/androidApps/app1`,
      projectId: firebase.projectId,
      packageName: firebase.androidPackageName,
      appId: firebase.firebaseAppId,
    });
    if (path.endsWith("/androidApps/app1/config")) return Response.json({ configFileContents: googleServices });
    throw new Error(`unexpected URL: ${url}`);
  };
  const options = {
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    runner,
    fetchImpl,
    delay: async () => {},
    readState: async () => state,
    writeState: async (value) => { state = structuredClone(value); },
  };
  await assert.rejects(provisionDirectFirebaseProject(options), /暫時離線/);
  assert.equal(state.step, "project-created");
  assert.deepEqual(await provisionDirectFirebaseProject(options), firebase);
  assert.equal(createCount, 1);
});

test("provisioning recovers remote writes whose operation response was lost", async () => {
  let state = null;
  let projectExists = false;
  let addFirebaseRequests = 0;
  let addFirebaseVisibilityChecks = 0;
  let appCreateRequests = 0;
  let appVisibilityChecks = 0;
  let addFirebaseRequested = false;
  let appCreateRequested = false;
  const appResource = {
    name: `projects/${firebase.projectId}/androidApps/app1`,
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    appId: firebase.firebaseAppId,
  };
  const googleServices = Buffer.from(JSON.stringify({
    project_info: { project_id: firebase.projectId, project_number: firebase.gcmSenderId },
    client: [{
      client_info: { mobilesdk_app_id: firebase.firebaseAppId, android_client_info: { package_name: firebase.androidPackageName } },
      api_key: [{ current_key: firebase.apiKey }],
    }],
  })).toString("base64");
  const runner = async (_command, args) => {
    if (args[0] === "auth" && args[1] === "list") return { stdout: JSON.stringify([{ account: "owner@example.com" }]) };
    if (args[0] === "projects" && args[1] === "list") return { stdout: JSON.stringify(projectExists ? [{ projectId: firebase.projectId }] : []) };
    if (args[0] === "projects" && args[1] === "create") {
      projectExists = true;
      return { stdout: JSON.stringify({ projectId: firebase.projectId, projectNumber: firebase.gcmSenderId }) };
    }
    if (args[0] === "projects" && args[1] === "describe") return { stdout: JSON.stringify({
      projectId: firebase.projectId,
      projectNumber: firebase.gcmSenderId,
      labels: { "denden-managed": "true", "denden-setup": state.setupId },
    }) };
    if (args[0] === "billing") return { stdout: JSON.stringify({ billingEnabled: false }) };
    if (args[0] === "services") return { stdout: "" };
    if (args[0] === "auth") return { stdout: "management-token" };
    throw new Error(`unexpected command: ${args.join(" ")}`);
  };
  const fetchImpl = async (url, init = {}) => {
    const path = url.replace("https://firebase.googleapis.com/v1beta1/", "");
    if (path === `projects/${firebase.projectId}`) {
      if (!addFirebaseRequested) return new Response("{}", { status: 404 });
      addFirebaseVisibilityChecks += 1;
      return addFirebaseVisibilityChecks < 2 ? new Response("{}", { status: 404 }) : Response.json({ projectId: firebase.projectId });
    }
    if (path.endsWith(":addFirebase")) {
      addFirebaseRequests += 1;
      addFirebaseRequested = true;
      throw new Error("模擬 addFirebase 回應遺失");
    }
    if (path.endsWith("/androidApps") && init.method !== "POST") {
      if (!appCreateRequested) return Response.json({ apps: [] });
      appVisibilityChecks += 1;
      return Response.json({ apps: appVisibilityChecks < 2 ? [] : [appResource] });
    }
    if (path.endsWith("/androidApps") && init.method === "POST") {
      appCreateRequests += 1;
      appCreateRequested = true;
      throw new Error("模擬 Android App 回應遺失");
    }
    if (path === appResource.name) return Response.json(appResource);
    if (path === `${appResource.name}/config`) return Response.json({ configFileContents: googleServices });
    throw new Error(`unexpected URL: ${url}`);
  };
  const options = {
    projectId: firebase.projectId,
    packageName: firebase.androidPackageName,
    managementConfigDirectory: "C:/private/denden-management",
    runner,
    fetchImpl,
    delay: async () => {},
    readState: async () => state,
    writeState: async (value) => { state = structuredClone(value); },
  };

  await assert.rejects(provisionDirectFirebaseProject(options), /addFirebase 回應遺失/);
  assert.equal(state.step, "add-firebase-planned");
  await assert.rejects(provisionDirectFirebaseProject(options), /Android App 回應遺失/);
  assert.equal(state.step, "android-app-planned");
  assert.deepEqual(await provisionDirectFirebaseProject(options), firebase);
  assert.equal(state.step, "complete");
  assert.equal(addFirebaseRequests, 1);
  assert.equal(appCreateRequests, 1);
});

test("setup command returns safe artifact paths without printing pairing secrets", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-setup-command-"));
  try {
    let byte = 7;
    const args = [
      "--project-id", firebase.projectId,
      "--config-path", join(root, "config.json"),
      "--brand-config-path", join(root, "brand.json"),
      "--qr-path", join(root, "pairing.png"),
      "--management-config-dir", join(root, "management"),
      "--sender-config-dir", join(root, "sender"),
    ];
    const approval = buildDirectSetupPlan(args, { managementAccount });
    const result = await runDirectSetupCommand([...args, "--approved-digest", approval.approvalDigest], {
      managementAccount,
      provision: async () => firebase,
      nowMillis: 1_800_000_000_000,
      randomBytes: (size) => Buffer.alloc(size, byte++),
    });
    assert.equal(result.configured, true);
    assert.equal(result.projectId, firebase.projectId);
    assert.equal(result.approvalDigest, approval.approvalDigest);
    assert.doesNotMatch(JSON.stringify(result), /eventKey|brandKey|DDC\./);
    const reused = await runDirectSetupCommand([...args, "--approved-digest", approval.approvalDigest], {
      managementAccount,
      provision: async () => { throw new Error("不應重新修改 Firebase 專案"); },
    });
    assert.equal(reused.reused, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("local pairing checkpoint resumes the same keys after an interrupted multi-file write", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-pairing-checkpoint-"));
  const args = [
    "--project-id", firebase.projectId,
    "--config-path", join(root, "config.json"),
    "--brand-config-path", join(root, "brand.json"),
    "--qr-path", join(root, "pairing.png"),
    "--pairing-state-path", join(root, "pairing-state.json"),
    "--management-config-dir", join(root, "management"),
    "--sender-config-dir", join(root, "sender"),
  ];
  const approval = buildDirectSetupPlan(args, { managementAccount });
  let firstBundle;
  let randomByte = 7;
  try {
    await assert.rejects(runDirectSetupCommand([...args, "--approved-digest", approval.approvalDigest], {
      managementAccount,
      provision: async () => firebase,
      nowMillis: 1_800_000_000_000,
      randomBytes: (size) => Buffer.alloc(size, randomByte++),
      writePairingBundle: async (bundle) => { firstBundle = structuredClone(bundle); throw new Error("模擬中斷"); },
    }), /模擬中斷/);
    assert.equal(JSON.parse(await readFile(approval.pairingStatePath, "utf8")).schemaVersion, 1);

    const resumed = await runDirectSetupCommand([...args, "--approved-digest", approval.approvalDigest], {
      managementAccount,
      provision: async () => { throw new Error("不應再次修改 Firebase 專案"); },
      nowMillis: 1_800_000_100_000,
      writePairingBundle: async (bundle, paths) => {
        assert.deepEqual(bundle, firstBundle);
        return {
          configPath: paths.configPath,
          brandConfigPath: paths.brandConfigPath,
          qrPath: paths.qrPath,
          displayExpiresAtMillis: bundle.invite.displayExpiresAtMillis,
        };
      },
    });
    assert.equal(resumed.reused, false);
    await assert.rejects(readFile(approval.pairingStatePath, "utf8"), /ENOENT/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("setup refuses Firebase writes until the exact plan digest is approved", async () => {
  let provisioned = false;
  const args = ["--project-id", firebase.projectId, "--config-path", "C:/private/denden/config.json"];
  const approval = buildDirectSetupPlan(args, { managementAccount });
  await assert.rejects(
    runDirectSetupCommand(args, { managementAccount, provision: async () => { provisioned = true; return firebase; } }),
    /明確同意/,
  );
  await assert.rejects(
    runDirectSetupCommand([...args, "--approved-digest", approval.approvalDigest, "--qr-path", "C:/private/denden/other.png"], {
      managementAccount,
      provision: async () => { provisioned = true; return firebase; },
    }),
    /明確同意/,
  );
  assert.equal(provisioned, false);
});

test("setup plan displays the active management account and binds it to approval", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-account-plan-"));
  const managementConfigDirectory = join(root, "management");
  const args = [
    "--project-id", firebase.projectId,
    "--config-path", join(root, "config.json"),
    "--management-config-dir", managementConfigDirectory,
  ];
  const planFor = (account) => runDirectSetupPlanCommand(args, {
    runner: async (_command, commandArgs, options) => {
      assert.deepEqual(commandArgs.slice(0, 2), ["auth", "list"]);
      assert.equal(options.env.CLOUDSDK_CONFIG, managementConfigDirectory);
      return { stdout: JSON.stringify([{ account }]) };
    },
  });
  try {
    const approval = await planFor("Owner@Example.Test");
    const changed = await planFor("other@example.test");
    const existing = buildDirectSetupPlan([...args, "--allow-existing-dedicated"], { managementAccount });
    assert.equal(approval.managementAccount, managementAccount);
    assert.equal(approval.projectMode, "new-project");
    assert.equal(existing.projectMode, "existing-project");
    assert.notEqual(existing.approvalDigest, approval.approvalDigest);
    assert.notEqual(changed.approvalDigest, approval.approvalDigest);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("install command verifies the pinned guide source before persistent activation", async () => {
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const guideUrl = `https://raw.githubusercontent.com/hakendog/DenDen/${sha}/docs/agent-install.md`;
  let installedPlan;
  const result = await runDirectInstallCommand(["--guide-url", guideUrl, "--source-root", "C:/tmp/checkout"], {
    inspect: async (_root, expected) => ({
      verified: true,
      sourceRoot: "C:/tmp/checkout",
      remoteUrl: expected.remoteUrl,
      commitSha: sha,
      errors: [],
    }),
    install: async (plan) => {
      installedPlan = plan;
      return { installed: true, reused: false, versionDirectory: plan.versionDirectory, launcher: "C:/denden/bin/denden.mjs" };
    },
    env: { DENDEN_INSTALL_ROOT: "C:/denden" },
  });
  assert.equal(installedPlan.commitSha, sha);
  assert.equal(result.commitSha, sha);
  assert.match(result.setupSkill, /denden-setup[\\/]SKILL\.md$/);
});
