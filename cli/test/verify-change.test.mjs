import assert from "node:assert/strict";
import test from "node:test";
import { validationCommandPlan } from "../../scripts/verify-change.mjs";

function labels(needs) {
  return validationCommandPlan({ needs, diffBase: "base", isWindows: false }).map((entry) => entry.label);
}

test("documentation changes keep the local validation plan small", () => {
  assert.deepEqual(labels({ android: false, device: false, cli: false }), [
    "差異格式",
    "Node 相依套件",
    "核心儲存庫測試",
    "儲存庫安全",
    "文件契約",
  ]);
});

test("CLI changes run the full Node suite without Android", () => {
  const plan = labels({ android: false, device: false, cli: true });
  assert.equal(plan.includes("完整命令列工具測試"), true);
  assert.equal(plan.includes("Android 快速驗證"), false);
});

test("Android changes compile test APKs before device CI", () => {
  const plan = validationCommandPlan({
    needs: { android: true, device: true, cli: false },
    diffBase: "base",
    isWindows: true,
  });
  const android = plan.find((entry) => entry.label === "Android 快速驗證");
  assert.equal(android.command, ".\\gradlew.bat");
  assert.equal(android.args.includes("assembleDebugAndroidTest"), true);
  assert.equal(plan.some((entry) => entry.label === "APK 契約"), true);
});
