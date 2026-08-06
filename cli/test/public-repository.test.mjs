import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import {
  findUnreviewedPublicPaths,
  inspectPublicRepository,
  isAllowedPublicPath,
  isForbiddenPublicPath,
} from "../../scripts/check-public-repository.mjs";

test("public path allowlist accepts product files and rejects private material", () => {
  for (const path of [
    "README.md",
    "app/src/main/AndroidManifest.xml",
    "cli/src/commands.mjs",
    "docs/en/index.md",
    "docs/zh-TW/cli.md",
    "skills/denden/SKILL.md",
  ]) assert.equal(isAllowedPublicPath(path), true, path);

  for (const path of [
    ".agents/plans/release.md",
    "AGENTS.md",
    "docs/adr/0001.md",
    "design/stitch/DESIGN.md",
    "app/src/androidTest/java/com/tensal/denden/ui/OpenDesignVisualAcceptanceTest.kt",
    "secrets/release.key",
    "scripts/prepare-public-candidate.mjs",
  ]) assert.equal(isAllowedPublicPath(path), false, path);
});

test("public path rejection normalizes Windows separators", () => {
  assert.equal(isForbiddenPublicPath("docs\\adr\\0001.md"), true);
  assert.equal(isAllowedPublicPath("..\\README.md"), false);
});

test("release preflight catches tracked and untracked public candidates missing from the manifest", () => {
  assert.deepEqual(findUnreviewedPublicPaths(
    ["README.md"],
    [
      "README.md",
      "app/src/main/java/com/tensal/denden/NewFeature.kt",
      "app/src/test/java/com/tensal/denden/NewFeatureTest.kt",
      "docs/adr/private.md",
    ],
  ), [
    "app/src/main/java/com/tensal/denden/NewFeature.kt",
    "app/src/test/java/com/tensal/denden/NewFeatureTest.kt",
  ]);
});

test("public history check detects a forbidden path deleted from the current tree", async () => {
  const repository = await mkdtemp(join(tmpdir(), "denden-public-check-"));
  const required = [
    ".github/workflows/ci.yml", ".gitignore", "LICENSE", "README.md", "README.zh-TW.md", "SECURITY.md",
    "docs/README.md", "docs/agent-install.md", "docs/en/index.md", "docs/zh-TW/index.md", "package-lock.json", "package.json",
  ];
  try {
    execFileSync("git", ["init", "-b", "main"], { cwd: repository });
    for (const path of [...required, ".agents/private.md", "docs/en/retired.md"]) {
      await mkdir(dirname(join(repository, path)), { recursive: true });
      await writeFile(join(repository, path), "fixture\n");
    }
    await writeFile(join(repository, "docs/en/retired.md"), `AIza${"A".repeat(35)}\n`);
    execFileSync("git", ["add", "."], { cwd: repository });
    execFileSync("git", ["-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "root"], { cwd: repository });
    await rm(join(repository, ".agents", "private.md"));
    await rm(join(repository, "docs", "en", "retired.md"));
    execFileSync("git", ["add", "-u"], { cwd: repository });
    execFileSync("git", ["-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "remove private path"], { cwd: repository });

    const failures = inspectPublicRepository(2, repository);
    assert(failures.some((failure) => failure.includes(".agents/private.md: 歷史含非公開路徑")));
    assert(failures.some((failure) => failure.includes("docs/en/retired.md: 歷史物件疑似 Google API key")));
  } finally {
    await rm(repository, { recursive: true, force: true });
  }
});
