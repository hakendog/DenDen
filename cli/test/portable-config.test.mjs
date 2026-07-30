import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readJson, writePrivateJson } from "../src/config.mjs";
import { decryptPortable, encryptPortable, runPortableConfigCommand } from "../src/portable-config.mjs";
import { TEST_SENDER_CONFIG } from "./fixtures.mjs";

const sender = {
  ...TEST_SENDER_CONFIG,
};

test("portable sender package uses scrypt and AES-GCM and rejects a wrong password", async () => {
  let byte = 1;
  const envelope = await encryptPortable("sender", { projectId: sender.projectId }, "correct horse battery", {
    randomBytes: (size) => Buffer.alloc(size, byte++),
  });
  assert.equal(envelope.kdf.name, "scrypt");
  assert.equal(envelope.cipher.name, "aes-256-gcm");
  assert.deepEqual(await decryptPortable(envelope, "correct horse battery", "sender"), { projectId: sender.projectId });
  await assert.rejects(decryptPortable(envelope, "wrong password value", "sender"), /密碼錯誤/);
});

test("sender export never carries Google credentials or brand authority and import binds a local credential directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-"));
  const sourcePath = join(root, "source.json");
  const outputPath = join(root, "sender.denden");
  const importedPath = join(root, "imported.json");
  try {
    await writePrivateJson(sourcePath, sender);
    const exported = await runPortableConfigCommand([
      "export", "sender", "--config-path", sourcePath, "--output", outputPath,
    ], { passwordProvider: async () => "correct horse battery" });
    assert.equal(exported.containsGoogleCredential, false);
    const envelope = JSON.parse(await readFile(outputPath, "utf8"));
    const plaintext = await decryptPortable(envelope, "correct horse battery", "sender");
    assert.equal(plaintext.senderCredentialsDirectory, undefined);
    assert.equal(plaintext.brandKey, undefined);

    await runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--sender-config-dir", join(root, "new-adc"),
    ], { passwordProvider: async () => "correct horse battery" });
    const imported = await readJson(importedPath);
    assert.match(imported.senderCredentialsDirectory, /new-adc$/);
    assert.equal(imported.eventKey, sender.eventKey);

    await writePrivateJson(importedPath, { ...imported, pairingId: "EBESExQVFhcYGRobHB0eHw" });
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--config-path", importedPath, "--input", outputPath,
      "--sender-config-dir", join(root, "new-adc"),
    ], { passwordProvider: async () => "correct horse battery" }), /拒絕覆寫/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("non-interactive export fails before writing when no hidden password provider exists", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-noninteractive-"));
  const sourcePath = join(root, "source.json");
  try {
    await writePrivateJson(sourcePath, sender);
    await assert.rejects(runPortableConfigCommand([
      "export", "sender", "--config-path", sourcePath, "--output", join(root, "out.denden"),
    ]), /本機互動終端/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("portable import cannot place credentials outside the DenDen configuration directory", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-portable-boundary-"));
  try {
    const envelope = await encryptPortable("sender", sender, "correct horse battery staple");
    const inputPath = join(root, "sender.denden");
    await writeFile(inputPath, JSON.stringify(envelope));
    await assert.rejects(runPortableConfigCommand([
      "import", "sender", "--input", inputPath,
      "--config-path", join(root, "private", "config.json"),
      "--sender-config-dir", join(root, "outside"),
    ], { passwordProvider: async () => "correct horse battery staple" }), /必須位於 DenDen 設定目錄內/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
