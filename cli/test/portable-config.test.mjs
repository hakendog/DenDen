import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ensurePrivateDirectory, readJson, writePrivateJson } from "../src/config.mjs";
import { decryptPortable, encryptPortable, runPortableConfigCommand } from "../src/portable-config.mjs";
import { TEST_BRAND_CONFIG, TEST_SENDER_CONFIG, testServiceAccountKey } from "./fixtures.mjs";

const sender = {
  ...TEST_SENDER_CONFIG,
};

test("portable brand backup uses scrypt and AES-GCM and requires a real password", async () => {
  let byte = 1;
  const envelope = await encryptPortable("brand", { projectId: sender.projectId }, "correct horse battery", {
    randomBytes: (size) => Buffer.alloc(size, byte++),
  });
  assert.equal(envelope.kdf.name, "scrypt");
  assert.equal(envelope.cipher.name, "aes-256-gcm");
  assert.deepEqual(await decryptPortable(envelope, "correct horse battery", "brand"), { projectId: sender.projectId });
  await assert.rejects(decryptPortable(envelope, "wrong horse battery", "brand"), /密碼錯誤/);
  await assert.rejects(encryptPortable("brand", {}, "too short"), /至少需要 12/);
  await assert.rejects(encryptPortable("brand", {}, "x".repeat(1025)), /過長/);
});

test("sender transfer exports the shared credential without a password and import needs no Google authorization", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-"));
  const sourcePath = join(root, "source.json");
  const outputPath = join(root, "sender.denden");
  const importedPath = join(root, "imported.json");
  const sourceCredentialsDirectory = join(root, "source-adc");
  const source = { ...sender, senderCredentialsDirectory: sourceCredentialsDirectory };
  const credential = testServiceAccountKey();
  try {
    await ensurePrivateDirectory(sourceCredentialsDirectory);
    await writePrivateJson(join(sourceCredentialsDirectory, "service-account.json"), credential);
    await writePrivateJson(sourcePath, source);
    const exported = await runPortableConfigCommand([
      "export", "sender", "--config-path", sourcePath, "--output", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.equal(exported.containsGoogleCredential, true);
    assert.equal(exported.encrypted, false);
    const transfer = JSON.parse(await readFile(outputPath, "utf8"));
    assert.equal(transfer.format, "DENDEN-SENDER-TRANSFER-V1");
    assert.match(JSON.stringify(transfer), /BEGIN PRIVATE KEY/);
    assert.equal(transfer.config.senderCredentialsDirectory, undefined);
    assert.equal(transfer.config.brandKey, undefined);
    assert.deepEqual(transfer.senderCredential, credential);

    const initialPlan = await runPortableConfigCommand([
      "import-plan", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.equal(initialPlan.replacementRequired, false);
    assert.match(initialPlan.approvalDigest, /^[a-f0-9]{64}$/);
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt }), /匯入摘要/);
    const importedResult = await runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--approved-digest", initialPlan.approvalDigest,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.equal(importedResult.requiresOwnGoogleAuthorization, false);
    const imported = await readJson(importedPath);
    assert.match(imported.senderCredentialsDirectory, /gcloud-sender$/);
    assert.equal(imported.eventKey, sender.eventKey);
    const importedCredentialPath = join(root, "gcloud-sender", "service-account.json");
    assert.deepEqual(await readJson(importedCredentialPath), credential);

    await writePrivateJson(importedCredentialPath, testServiceAccountKey("denden-ffffffffffff"));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt }), /不同 Google 發送私鑰/);
    const plan = await runPortableConfigCommand([
      "import-plan", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.equal(plan.replacementRequired, true);
    assert.match(plan.approvalDigest, /^[a-f0-9]{64}$/);
    assert.match(plan.currentFingerprint, /^[a-f0-9]{12}$/);
    assert.match(plan.incomingFingerprint, /^[a-f0-9]{12}$/);
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--replace-existing", "true",
    ], { passwordProvider: unexpectedPasswordPrompt }), /匯入摘要/);
    await writePrivateJson(importedCredentialPath, testServiceAccountKey("denden-eeeeeeeeeeee"));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--replace-existing", "true", "--approved-digest", plan.approvalDigest,
    ], { passwordProvider: unexpectedPasswordPrompt }), /匯入摘要/);
    const refreshedPlan = await runPortableConfigCommand([
      "import-plan", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.notEqual(refreshedPlan.approvalDigest, plan.approvalDigest);
    const replaced = await runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--replace-existing", "true", "--approved-digest", refreshedPlan.approvalDigest,
    ], { passwordProvider: unexpectedPasswordPrompt });
    assert.equal(replaced.replaced, true);
    assert.deepEqual(await readJson(importedCredentialPath), credential);

    await writePrivateJson(importedPath, { ...imported, pairingId: "EBESExQVFhcYGRobHB0eHw" });
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
    ], { passwordProvider: unexpectedPasswordPrompt }), /拒絕覆寫/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("sender transfer works non-interactively while brand backup still requires a hidden password", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-noninteractive-"));
  const sourcePath = join(root, "source.json");
  const senderCredentialsDirectory = join(root, "sender");
  try {
    await ensurePrivateDirectory(senderCredentialsDirectory);
    await writePrivateJson(join(senderCredentialsDirectory, "service-account.json"), testServiceAccountKey());
    await writePrivateJson(sourcePath, { ...sender, senderCredentialsDirectory });
    const senderResult = await runPortableConfigCommand([
      "export", "sender", "--config-path", sourcePath, "--output", join(root, "out.denden"),
    ]);
    assert.equal(senderResult.encrypted, false);
    const brandPath = join(root, "brand-config.json");
    await writePrivateJson(brandPath, { ...TEST_BRAND_CONFIG, senderCredentialsDirectory });
    await assert.rejects(runPortableConfigCommand([
      "export", "brand", "--config-path", sourcePath, "--brand-config-path", brandPath,
      "--output", join(root, "brand.denden"),
    ]), /本機互動終端/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("portable import cannot place credentials outside the DenDen configuration directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-boundary-"));
  try {
    const inputPath = join(root, "sender.denden");
    await writeFile(inputPath, JSON.stringify(senderTransfer()));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--input", inputPath,
      "--config-path", join(root, "private", "config.json"),
      "--sender-config-dir", join(root, "outside"),
    ], { passwordProvider: unexpectedPasswordPrompt }), /必須位於 DenDen 設定目錄內/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("portable import rejects old sender packages that cannot avoid a Google sign-in", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-legacy-"));
  try {
    const config = { ...sender };
    delete config.senderCredentialsDirectory;
    const inputPath = join(root, "sender.denden");
    await writeFile(inputPath, JSON.stringify(await encryptPortable("sender", config, "correct horse battery staple")));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--input", inputPath,
    ], { passwordProvider: unexpectedPasswordPrompt }), /重新匯出/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("portable import rejects unknown sender payload fields", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-fields-"));
  try {
    const inputPath = join(root, "sender.denden");
    await writeFile(inputPath, JSON.stringify({ ...senderTransfer(), extra: true }));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--input", inputPath,
    ], { passwordProvider: unexpectedPasswordPrompt }), /內容無效/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function senderTransfer() {
  const config = { ...sender };
  delete config.senderCredentialsDirectory;
  return {
    format: "DENDEN-SENDER-TRANSFER-V1",
    kind: "sender",
    config,
    senderCredential: testServiceAccountKey(),
  };
}

async function unexpectedPasswordPrompt() {
  throw new Error("sender transfer must not request a password");
}
