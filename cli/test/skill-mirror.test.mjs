import assert from "node:assert/strict";
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { DAILY_RUNTIME_FILES, syncDendenSkillMirror, syncDendenSkillRuntime } from "../../scripts/sync-denden-skill.mjs";

test("DenDen skill packages the exact daily CLI runtime", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-skill-runtime-"));
  try {
    await mkdir(join(root, "cli", "src"), { recursive: true });
    await mkdir(join(root, "skills", "denden", "scripts", "runtime"), { recursive: true });
    for (const file of DAILY_RUNTIME_FILES) await writeFile(join(root, "cli", "src", file), `source:${file}\n`);
    await writeFile(join(root, "skills", "denden", "scripts", "runtime", "stale.mjs"), "stale\n");

    assert.equal(await syncDendenSkillRuntime({ sourceRoot: root }), "drift");
    assert.equal(await syncDendenSkillRuntime({ sourceRoot: root, write: true }), "synced");
    assert.deepEqual((await Promise.all(DAILY_RUNTIME_FILES.map(async (file) => [file, await readFile(join(root, "skills", "denden", "scripts", "runtime", file), "utf8")]))), DAILY_RUNTIME_FILES.map((file) => [file, `source:${file}\n`]));
    await assert.rejects(access(join(root, "skills", "denden", "scripts", "runtime", "stale.mjs")));
    assert.equal(await syncDendenSkillRuntime({ sourceRoot: root }), "current");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("DenDen skill sync replaces drift and stale mirror files", async () => {
  const root = await mkdtemp(join(tmpdir(), "denden-skill-sync-"));
  const sourceRoot = join(root, "DenDen");
  const agentSkillsRoot = join(root, "agent-skills");
  try {
    await mkdir(join(sourceRoot, "skills", "denden"), { recursive: true });
    await mkdir(join(agentSkillsRoot, ".git"), { recursive: true });
    await mkdir(join(agentSkillsRoot, "skills", "denden"), { recursive: true });
    await writeFile(join(sourceRoot, "skills", "denden", "SKILL.md"), "canonical\n");
    await writeFile(join(agentSkillsRoot, "skills", "denden", "SKILL.md"), "old\n");
    await writeFile(join(agentSkillsRoot, "skills", "denden", "stale.txt"), "stale\n");

    assert.equal(await syncDendenSkillMirror({ sourceRoot, agentSkillsRoot }), "drift");
    assert.equal(await syncDendenSkillMirror({ sourceRoot, agentSkillsRoot, write: true }), "synced");
    assert.equal(await readFile(join(agentSkillsRoot, "skills", "denden", "SKILL.md"), "utf8"), "canonical\n");
    await assert.rejects(access(join(agentSkillsRoot, "skills", "denden", "stale.txt")));
    assert.equal(await syncDendenSkillMirror({ sourceRoot, agentSkillsRoot }), "current");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
