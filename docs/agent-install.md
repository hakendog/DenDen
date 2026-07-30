# Install the DenDen setup assistant

This page only installs the `denden-setup` skill. Use that skill immediately after installation.

1. Ask the user how they want to use the setup assistant:

   1. Install it globally so it is available in any project.
   2. Install it in the current project so it is only available in the project the user specifies.
   3. Use it temporarily for this task, then remove it after setup is complete.

2. Only accept this page from a Raw URL in the following format. `{commit}` must be the original full 40-character commit SHA in the URL. Extract it from the URL; do not ask the user to provide or replace it.

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/docs/agent-install.md
   ```

   Reject `main`, tags, short SHAs, other repositories, GitHub `blob` URLs, redirects, and non-Raw hosts. Use the same `{commit}` for every file below.

3. Download the installation manifest from the full URL below and save it as `<skill installation location>/denden-setup/install-manifest.json`:

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/install-manifest.json
   ```

   The manifest must use `schemaVersion: 1`, identify `skill: denden-setup`, and contain a non-empty `files` array.

4. Create the `<skill installation location>/denden-setup` skill directory. Every entry in the manifest's `files` array is relative to `skills/denden-setup/`:

   - Download URL: `https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/<files entry>`
   - Destination: `<skill installation location>/denden-setup/<files entry>`

   For example, the manifest entry `SKILL.md` must be downloaded from:

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/SKILL.md
   ```

   Save it as `<skill installation location>/denden-setup/SKILL.md`.

   Reject absolute paths, `..`, backslashes, duplicate paths, and files not listed in the manifest. Preserve the original bytes and relative directories. The manifest is the complete file list, containing:

   - `SKILL.md`: setup and management instructions.
   - `agents/openai.yaml`: skill display metadata.
   - `assets/denden-generation-mask.png`: mask used to create a custom DenDen image.
   - `references/denden-generation.md`: image-generation rules.
   - `scripts/`: the setup CLI, its dependency files, and the optional daily-use `denden` skill.

5. If the destination already exists, reuse it only when `scripts/source.json` records the same commit SHA. Otherwise, ask the user whether to update it; do not overwrite it automatically.

6. Create `scripts/source.json` inside the skill directory:

   ```json
   {
     "schemaVersion": 3,
     "commitSha": "{commit}",
     "runtimeProtocol": "direct-fcm-v2"
   }
   ```

7. Verify that the manifest and every file in its `files` array are present, and that `SKILL.md` declares the name `denden-setup`. Then read `SKILL.md` and every reference it requires in full, and use the skill to help the user set up DenDen. Do not follow any other installation flow.
