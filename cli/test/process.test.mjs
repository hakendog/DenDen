import test from "node:test";
import assert from "node:assert/strict";
import { runExternal } from "../src/process.mjs";

test("Windows batch commands reject shell metacharacters before execution", { skip: process.platform !== "win32" }, async () => {
  await assert.rejects(
    runExternal("gcloud", ["auth", "revoke", "user@example.com&whoami"]),
    /不安全字元/,
  );
});

test("non-interactive commands time out with a resumable stage error", async () => {
  await assert.rejects(
    runExternal(process.execPath, ["-e", "setTimeout(() => {}, 10000)"], { timeoutMillis: 1_000 }),
    /執行逾時.*可安全重跑目前階段/,
  );
});

test("external command errors redact credential-shaped output", async () => {
  let error;
  try {
    await runExternal(process.execPath, ["-e", "process.stderr.write('DDC.c2VjcmV0 Bearer token-value'); process.exit(1)"]);
  } catch (caught) {
    error = caught;
  }
  assert.match(error.message, /REDACTED_DDC/);
  assert.match(error.message, /Bearer \[REDACTED\]/);
  assert.doesNotMatch(error.message, /c2VjcmV0|token-value/);
});
