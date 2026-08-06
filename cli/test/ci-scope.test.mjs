import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyValidationNeeds,
  determineCiScope,
  isFastValidationPath,
} from "../../scripts/ci-scope.mjs";

const base = {
  eventName: "pull_request",
  repository: "hakendog/DenDen-internal",
  headRef: "codex/feature",
  isMergeCommit: false,
  isDraft: false,
};

const fastDecision = (reason) => ({
  scope: "fast",
  reason,
  android: false,
  device: false,
  cli: false,
  heavy: false,
});

test("CI scope keeps documentation and manifest-only changes fast", () => {
  assert.equal(isFastValidationPath("docs/zh-TW/setup.md"), true);
  assert.equal(isFastValidationPath(".agents/public-files.txt"), true);
  assert.equal(isFastValidationPath("AGENTS.md"), true);
  assert.deepEqual(determineCiScope({ ...base, paths: ["README.md", "docs/zh-TW/setup.md"] }),
    fastDecision("documentation-only"));
});

test("validation needs separate Android, device, and CLI changes", () => {
  assert.deepEqual(classifyValidationNeeds(["app/src/test/java/example/Test.kt"]), {
    android: true,
    device: false,
    cli: false,
  });
  assert.deepEqual(classifyValidationNeeds(["app/src/main/java/example/App.kt"]), {
    android: true,
    device: true,
    cli: false,
  });
  assert.deepEqual(classifyValidationNeeds(["cli/src/commands.mjs"]), {
    android: false,
    device: false,
    cli: true,
  });
  assert.deepEqual(classifyValidationNeeds([".github/workflows/ci.yml"]), {
    android: true,
    device: true,
    cli: true,
  });
});

test("draft code changes receive focused validation without heavy jobs", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    isDraft: true,
    paths: ["app/src/main/java/com/tensal/denden/MainActivity.kt"],
  }), {
    scope: "focused",
    reason: "draft-change",
    android: true,
    device: true,
    cli: false,
    heavy: false,
  });
});

test("ready code changes receive the relevant full matrix", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    paths: ["app/src/main/java/com/tensal/denden/MainActivity.kt"],
  }), {
    scope: "full",
    reason: "ready-change",
    android: true,
    device: true,
    cli: false,
    heavy: true,
  });
  assert.deepEqual(determineCiScope({ ...base, paths: ["cli/src/commands.mjs"] }), {
    scope: "full",
    reason: "ready-change",
    android: false,
    device: false,
    cli: true,
    heavy: true,
  });
});

test("private release promotion defers the one full matrix to the public candidate", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    headRef: "codex/release/1.0.3",
    paths: ["app/build.gradle.kts", "package.json"],
  }), fastDecision("private-release-promotion"));
  assert.deepEqual(determineCiScope({
    ...base,
    repository: "hakendog/DenDen",
    headRef: "codex/release/1.0.3",
    paths: ["app/build.gradle.kts", "package.json"],
  }), {
    scope: "full",
    reason: "ready-change",
    android: true,
    device: true,
    cli: true,
    heavy: true,
  });
});

test("merge pushes stay fast while direct pushes and manual runs stay full", () => {
  assert.deepEqual(determineCiScope({
    ...base,
    eventName: "push",
    paths: ["app/src/main/AndroidManifest.xml"],
    isMergeCommit: true,
  }), fastDecision("already-validated-merge"));
  assert.deepEqual(determineCiScope({
    ...base,
    eventName: "push",
    paths: ["app/src/main/AndroidManifest.xml"],
  }), {
    scope: "full",
    reason: "ready-change",
    android: true,
    device: true,
    cli: false,
    heavy: true,
  });
  assert.deepEqual(determineCiScope({ ...base, eventName: "workflow_dispatch", paths: ["README.md"] }), {
    scope: "full",
    reason: "manual-full-run",
    android: true,
    device: true,
    cli: true,
    heavy: true,
  });
});

test("unknown paths fail safe into every validation lane", () => {
  assert.deepEqual(classifyValidationNeeds(["unexpected/config.custom"]), {
    android: true,
    device: true,
    cli: true,
  });
  assert.deepEqual(determineCiScope({ ...base, paths: [] }), {
    scope: "full",
    reason: "unknown-change-set",
    android: true,
    device: true,
    cli: true,
    heavy: true,
  });
});
