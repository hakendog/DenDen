import assert from "node:assert/strict";
import test from "node:test";
import { determineCiScope, isFastValidationPath } from "../../scripts/ci-scope.mjs";

const base = {
  eventName: "pull_request",
  repository: "hakendog/DenDen-internal",
  headRef: "codex/feature",
  isMergeCommit: false,
};

test("CI scope keeps documentation and manifest-only changes fast", () => {
  assert.equal(isFastValidationPath("docs/zh-TW/setup.md"), true);
  assert.equal(isFastValidationPath(".agents/public-files.txt"), true);
  assert.deepEqual(determineCiScope({ ...base, paths: ["README.md", "docs/zh-TW/setup.md"] }), {
    scope: "fast",
    reason: "documentation-only",
  });
});

test("CI scope sends ordinary code changes through the full matrix", () => {
  assert.equal(isFastValidationPath("app/src/main/java/com/tensal/denden/MainActivity.kt"), false);
  assert.deepEqual(determineCiScope({ ...base, paths: ["app/src/main/java/com/tensal/denden/MainActivity.kt"] }), {
    scope: "full",
    reason: "code-or-build-change",
  });
});

test("private release promotion defers the one full matrix to the public candidate", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    headRef: "codex/release/v1.0.3",
    paths: ["app/build.gradle.kts", "package.json"],
  }), { scope: "fast", reason: "private-release-promotion" });
  assert.deepEqual(determineCiScope({
    ...base,
    repository: "hakendog/DenDen",
    headRef: "codex/release/v1.0.3",
    paths: ["app/build.gradle.kts", "package.json"],
  }), { scope: "full", reason: "code-or-build-change" });
});

test("merge pushes stay fast while direct code pushes and manual runs stay full", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    eventName: "push",
    paths: ["app/src/main/AndroidManifest.xml"],
    isMergeCommit: true,
  }), { scope: "fast", reason: "already-validated-merge" });
  assert.deepEqual(determineCiScope({
    ...base,
    eventName: "push",
    paths: ["app/src/main/AndroidManifest.xml"],
  }), { scope: "full", reason: "code-or-build-change" });
  assert.deepEqual(determineCiScope({ ...base, eventName: "workflow_dispatch", paths: ["README.md"] }), {
    scope: "full",
    reason: "manual-full-run",
  });
});
