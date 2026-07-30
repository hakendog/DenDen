import assert from "node:assert/strict";
import test from "node:test";
import { inspectApkBytes, inspectApkEntryName } from "../../scripts/check-debug-apk.mjs";

test("APK safety check rejects traversal and release secrets", () => {
  assert.equal(inspectApkEntryName("res/drawable/icon.png"), null);
  assert.match(inspectApkEntryName("../private.key"), /不安全/);
  assert.match(inspectApkEntryName("assets/google-services.json"), /禁止/);
  assert.deepEqual(inspectApkBytes(Buffer.from("ordinary app content")), []);
  assert(inspectApkBytes(Buffer.from(`AIza${"A".repeat(35)}`)).includes("Google API key"));
  assert(inspectApkBytes(Buffer.from('{"project_id":"private-project"}')).some((value) => value.startsWith("內部資料")));
});
