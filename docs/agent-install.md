# Install the DenDen setup assistant

This page installs only the `denden-setup` skill. Use that skill immediately after installation.

1. Ask how the user wants to use it, then resolve the exact skill location without changing it:

   1. Install globally for any project.
   2. Install in the current project only.
   3. Use it temporarily for this task, then remove the temporary skill after setup.

2. Accept this page only from the exact Raw URL shape below. Extract `{commit}` from the original URL; it must be a full 40-character commit SHA. Do not ask the user to calculate, find, or replace it.

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/docs/agent-install.md
   ```

   Reject branches, tags, short SHAs, other repositories, GitHub `blob` URLs, redirects, and non-Raw hosts. Use the same `{commit}` for every download.

3. Before downloading, inspect `<skill location>/denden-setup` if it already exists. Reuse it only when `scripts/source.json` records the same commit SHA. Otherwise ask before replacing it; never overwrite it automatically. Temporary use must use a newly created temporary directory.

   Download the complete file list from:

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/install-manifest.json
   ```

   Validate it in memory first. Require `schemaVersion: 1`, `skill: denden-setup`, and a non-empty `files` array. Then create `<skill location>/denden-setup` if needed and save the manifest as `<skill location>/denden-setup/install-manifest.json`.

4. For each `files` entry:

   - Download: `https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/<files entry>`
   - Save as: `<skill location>/denden-setup/<files entry>`

   For example, `SKILL.md` comes from:

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/SKILL.md
   ```

   Reject absolute paths, `..`, backslashes, duplicates, and anything outside the manifest. Preserve original bytes and relative directories. The manifest includes:

   - `SKILL.md`: setup and management instructions.
   - `agents/openai.yaml`: skill metadata.
   - `assets/denden-generation-mask.png`: the custom-image mask.
   - `references/denden-generation.md`: image-generation rules.
   - `scripts/`: the setup CLI, locked dependencies, and optional daily `denden` skill.

   When Node.js and npm are already available, let `denden-setup` install missing locked dependencies inside `scripts` automatically. Installing Node.js, npm, gcloud, or other computer-level software still requires the user's consent.

5. Create `scripts/source.json`:

   ```json
   {
     "schemaVersion": 3,
     "commitSha": "{commit}",
     "runtimeProtocol": "direct-fcm-v2"
   }
   ```

6. Verify that every manifest file exists and `SKILL.md` declares `name: denden-setup`. Read `SKILL.md` and every required reference in full, then use the skill to set up DenDen. Do not clone the repository and do not follow another installation flow.

7. If the user chose temporary use, remove only the temporary `denden-setup` skill directory after setup and phone verification. Keep the user's DenDen configuration and any separately installed daily `denden` skill.
