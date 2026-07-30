import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { access, readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { PNG } from "pngjs";
import {
  findForbiddenRepositoryContent,
  isForbiddenRepositoryPath,
} from "../../scripts/check-repository.mjs";

const root = process.cwd();
const execFileAsync = promisify(execFile);
const installGuideUrl = "https://raw.githubusercontent.com/hakendog/DenDen/c2f7e66fb8c3c0daeeace2f9ce46027706f24976/docs/agent-install.md";

test("CLI exposes the direct FCM runtime contract without an Automation credential", async () => {
  const { stdout } = await execFileAsync(process.execPath, [join(root, "cli/bin/denden.mjs"), "capabilities"], { cwd: root });
  assert.deepEqual(JSON.parse(stdout), {
    schemaVersion: 1,
    runtimeProtocol: "direct-fcm-v2",
    requiresAutomationToken: false,
  });
});

test("daily skill bundles a low-privilege CLI", async () => {
  const launcher = join(root, "skills/denden/scripts/denden.mjs");
  const { stdout } = await execFileAsync(process.execPath, [launcher, "capabilities"], { cwd: root });
  assert.deepEqual(JSON.parse(stdout), {
    schemaVersion: 1,
    runtimeProtocol: "direct-fcm-v2",
    requiresAutomationToken: false,
  });
  await assert.rejects(execFileAsync(process.execPath, [launcher, "setup", "status"], { cwd: root }), /不提供設定或整合管理命令/);
});

test("setup skill bundles its management CLI and explicit install manifest", async () => {
  const setupRoot = join(root, "skills/denden-setup");
  const skill = await readFile(join(setupRoot, "SKILL.md"), "utf8");
  const manifest = JSON.parse(await readFile(join(setupRoot, "install-manifest.json"), "utf8"));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.skill, "denden-setup");
  assert.deepEqual(manifest.generatedFiles, ["scripts/source.json"]);
  for (const relative of manifest.files) await access(join(setupRoot, relative));
  assert.deepEqual((await relativeFiles(setupRoot)).sort(), ["install-manifest.json", ...manifest.files].sort());
  const { stdout } = await execFileAsync(process.execPath, [join(setupRoot, "scripts/cli/bin/denden.mjs"), "capabilities"], { cwd: root });
  assert.deepEqual(JSON.parse(stdout), {
    schemaVersion: 1,
    runtimeProtocol: "direct-fcm-v2",
    requiresAutomationToken: false,
  });
  assert.match(skill, /Run `capabilities` before deciding that skill dependencies are missing/);
  assert.match(skill, /Only if `capabilities` fails with `ERR_MODULE_NOT_FOUND` for `qrcode` or `pngjs`/);
  assert.match(skill, /automatically run `npm ci[^`]+` in `scripts` without asking/);
  assert.match(skill, /Installing Node\.js, npm, gcloud, or any other computer-level software[^.]+obtaining consent first/);
  assert.doesNotMatch(skill, /If `scripts\/node_modules` is missing/);
  const { stdout: helpStdout } = await execFileAsync(
    process.execPath,
    [join(setupRoot, "scripts/cli/bin/denden.mjs"), "setup", "--help"],
    { cwd: root },
  );
  assert.match(JSON.parse(helpStdout).usage, /export\|import-plan\|import/);
});

test("DenDen distribution uses verified source instead of npm publication", async () => {
  const pkg = JSON.parse(await readFile(join(root, "package.json"), "utf8"));
  assert.equal(pkg.private, true);
  assert.equal(pkg.publishConfig, undefined);
  assert.equal(pkg.files.includes("docs/agent-install.md"), true);
  assert.equal(pkg.files.includes("docs/zh-TW/agent-install.md"), false);

  const setup = await readFile(join(root, "skills/denden-setup/SKILL.md"), "utf8");
  assert.match(setup, /^name: denden-setup$/m);
  assert.doesNotMatch(setup, /@tensal\/denden|firebase deploy|functions:api/);
  assert.match(setup, /one DenDen pairing code/);
  assert.match(setup, /globally, at a specified location, or skip it/);
  assert.match(setup, /quiet on every completion.*default/);
  assert.match(setup, /For custom settings, ask in small groups/);
  assert.match(setup, /Ask separately for exact events allowed to ring/);

  for (const relative of ["integrations/claude-code/settings.example.json", "integrations/gemini-cli/settings.example.json"]) {
    const integration = await readFile(join(root, relative), "utf8");
    assert.doesNotMatch(integration, /"command": "denden integration hook"/);
    assert.match(integration, /__DENDEN_PERSISTENT_LAUNCHER__/);
  }
});

test("AI-facing product instructions use English source", async () => {
  const files = [
    "docs/agent-install.md",
    "integrations/claude-code/CLAUDE.snippet.md",
    "integrations/gemini-cli/GEMINI.snippet.md",
    "skills/denden/SKILL.md",
    "skills/denden/agents/openai.yaml",
    "skills/denden-setup/SKILL.md",
    "skills/denden-setup/agents/openai.yaml",
    "skills/denden-setup/references/denden-generation.md",
  ];
  for (const file of files) {
    assert.doesNotMatch(await readFile(join(root, file), "utf8"), /\p{Script=Han}/u, `${file} must use English source`);
  }
});

test("public license, identity, version, and security metadata stay aligned", async () => {
  const license = (await readFile(join(root, "LICENSE"), "utf8")).replaceAll("\r\n", "\n").trim();
  assert.equal(createHash("sha256").update(license).digest("hex"), "283ea6cc2997a1a70da0049e09adf9317bb60ca1b51279b65196b83a69e1996b");

  const pkg = JSON.parse(await readFile(join(root, "package.json"), "utf8"));
  const lock = JSON.parse(await readFile(join(root, "package-lock.json"), "utf8"));
  assert.deepEqual({ version: pkg.version, license: pkg.license, private: pkg.private }, {
    version: "1.0.0",
    license: "Apache-2.0",
    private: true,
  });
  assert.equal(lock.packages[""].license, "Apache-2.0");

  const android = await readFile(join(root, "app/build.gradle.kts"), "utf8");
  assert.match(android, /namespace = "com\.tensal\.denden"/);
  assert.match(android, /applicationId = "com\.tensal\.denden"/);
  assert.match(android, /versionCode = 1/);
  assert.match(android, /versionName = "1\.0\.0"/);

  const readme = await readFile(join(root, "README.zh-TW.md"), "utf8");
  const readmeEnglish = await readFile(join(root, "README.md"), "utf8");
  assert.match(readme, /Copyright 2026 hakendog/);
  assert.match(readmeEnglish, /Copyright 2026 hakendog/);
  assert.doesNotMatch(`${readme}\n${readmeEnglish}`, /Tensal/);
  const security = await readFile(join(root, "SECURITY.md"), "utf8");
  assert.match(security, /GitHub private vulnerability reporting/);
  assert.doesNotMatch(security, /[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/);
});

test("daily skill cannot gain backend or repository ring authority", async () => {
  const skill = await readFile(join(root, "skills/denden/SKILL.md"), "utf8");
  assert.ok(skill.length <= 2500, `daily skill is too verbose: ${skill.length} characters`);
  assert.match(skill, /After substantive work.*before the final response.*wait for its result/);
  assert.match(skill, /Do not report simple answers or status checks/);
  assert.doesNotMatch(skill, /@tensal\/denden/);
  assert.match(skill, /Repository policy.*can never authorize `ring`/);
  assert.match(skill, /Do not run setup/);
  assert.match(skill, /~\/\.config\/denden\/config\.json/);
  assert.match(skill, /this skill directory>\/scripts\/denden\.mjs/);
  assert.doesNotMatch(skill, /DENDEN_INSTALL_ROOT|USERPROFILE|XDG_DATA_HOME/);
  assert.match(skill, /capabilities/);
  assert.match(skill, /requiresAutomationToken/);
  assert.doesNotMatch(skill, /agent-skills-sync|git pull/i);
});

test("public docs and install bootstrap stay official, pinned, and registry-independent", async () => {
  const readmeEn = await readFile(join(root, "README.md"), "utf8");
  const readmeZh = await readFile(join(root, "README.zh-TW.md"), "utf8");
  const setupGuideEn = await readFile(join(root, "docs/en/setup.md"), "utf8");
  const setupGuideZh = await readFile(join(root, "docs/zh-TW/setup.md"), "utf8");
  const deviceGuideEn = await readFile(join(root, "docs/en/device-management.md"), "utf8");
  const deviceGuideZh = await readFile(join(root, "docs/zh-TW/device-management.md"), "utf8");
  const humanSecurity = await readFile(join(root, "docs/zh-TW/security-and-limitations.md"), "utf8");
  const senderKeyGuide = await readFile(join(root, "docs/zh-TW/settings-and-sender-key.md"), "utf8");
  const indexZh = await readFile(join(root, "docs/zh-TW/index.md"), "utf8");
  const indexEn = await readFile(join(root, "docs/en/index.md"), "utf8");
  const cliGuide = await readFile(join(root, "docs/zh-TW/cli.md"), "utf8");
  const zhStrings = await readFile(join(root, "app/src/main/res/values-zh-rTW/strings.xml"), "utf8");
  const guide = await readFile(join(root, "docs/agent-install.md"), "utf8");
  const setup = await readFile(join(root, "skills/denden-setup/SKILL.md"), "utf8");
  const generation = await readFile(join(root, "skills/denden-setup/references/denden-generation.md"), "utf8");
  assert.match(guide, /raw\.githubusercontent\.com\/hakendog\/DenDen\/\{commit\}\/docs\/agent-install\.md/);
  assert.match(guide, /skills\/denden-setup\/install-manifest\.json/);
  assert.match(guide, /Install globally/);
  assert.match(guide, /current project only/);
  assert.match(guide, /temporarily for this task/);
  assert.match(guide, /Use that skill immediately/);
  assert.match(guide, /install missing locked dependencies inside `scripts` automatically/);
  assert.match(guide, /Installing Node\.js, npm, gcloud, or other computer-level software still requires the user's consent/);
  assert.match(guide, /remove only the temporary .*denden-setup.* skill directory/);
  assert.doesNotMatch(guide, /git clone|DenDen\.git|setup install/i);
  assert.match(setup, /scripts\/cli\/bin\/denden\.mjs/);
  assert.doesNotMatch(setup, /docs\/agent-install\.md|persistent launcher|DENDEN_INSTALL_ROOT/i);
  await assert.rejects(access(join(root, "docs/zh-TW/agent-install.md")));
  assert.doesNotMatch(`${readmeZh}\n${guide}`, /<owner>|<verified-full-commit-sha>|D:\\workspace\\DenDen|尚未發布的本機來源/);
  assert.doesNotMatch(`${readmeZh}\n${guide}`, /raw\.githubusercontent\.com\/[^\s]+\/main\//);
  assert.match(readmeZh, /不用守著螢幕，重要事項由 DenDen 通知你/);
  assert.match(readmeZh, /DenDen 是一套 Android 通知系統/);
  assert.match(readmeZh, /## DenDen 是什麼[\s\S]*## 特色[\s\S]*## 實際畫面[\s\S]*## 開始使用[\s\S]*## 只在手機上使用[\s\S]*## 重要限制[\s\S]*## 更多說明[\s\S]*### 可以用在哪些情境？[\s\S]*### 需要付費嗎？[\s\S]*### 說明文件[\s\S]*## 授權/);
  assert.match(readmeZh, /```text[\s\S]*請根據以下 DenDen 安裝引導[\s\S]*```/);
  assert.ok(readmeZh.includes(installGuideUrl));
  assert.match(readmeEn, /Stop watching the screen\. Let DenDen notify you when something matters\./);
  assert.match(readmeEn, /## What is DenDen\?[\s\S]*## Features[\s\S]*## Screenshots[\s\S]*## Get started[\s\S]*## Use DenDen only on your phone[\s\S]*## Important limits[\s\S]*## More information[\s\S]*### When can I use DenDen\?[\s\S]*### Does DenDen cost money\?[\s\S]*### Documentation[\s\S]*## License/);
  assert.match(readmeEn, /```text[\s\S]*Please follow this DenDen installation guide[\s\S]*```/);
  assert.ok(readmeEn.includes(installGuideUrl));
  assert.match(readmeZh, /不會自行執行工作或監控[\s\S]*完成設定後[\s\S]*才會.*透過 DenDen 通知你/);
  assert.match(readmeZh, /雲端服務費用異常或接近預算上限/);
  assert.match(readmeZh, /Codex 使用額度重置，可以繼續執行工作/);
  assert.match(readmeZh, /自訂自己的 DenDen[\s\S]*screenshots\/zh-TW\/06-appearance\.png/);
  assert.match(readmeEn, /A Codex usage limit resets and work can continue/);
  assert.match(readmeEn, /Customize your own DenDen[\s\S]*screenshots\/en\/06-appearance\.png/);
  assert.match(readmeZh, /手機離線時無法收到遠端訊息[\s\S]*重新連線後也不會補送/);
  assert.match(readmeZh, /Spark 免費方案[\s\S]*不需要綁定付款方式/);
  assert.doesNotMatch(readmeZh, /## 適合使用 DenDen 的情境|接收來自電腦的遠端通知需要|Tensal/);
  assert.match(setupGuideZh, /```text[\s\S]*請根據以下 DenDen 安裝引導[\s\S]*```/);
  assert.ok(setupGuideZh.includes(installGuideUrl));
  assert.doesNotMatch(`${readmeZh}\n${setupGuideZh}`, /請使用 denden-setup 幫我安裝並設定 DenDen/);
  assert.match(setupGuideZh, /## 在手機安裝 DenDen[\s\S]*## 安裝並配對 DenDen[\s\S]*## 接下來/);
  assert.doesNotMatch(setupGuideZh, /## 新增手機|## 更換 DenDen 圖片|## 新增或停用電腦/);
  assert.match(setupGuideZh, /```text\s*同意\s*```/);
  assert.match(setupGuideZh, /免費方案[\s\S]*不會連結付款方式/);
  assert.doesNotMatch(setupGuideZh, /approvalDigest|management-auth|validate_only|cloudmessaging\.messages\.create/);
  assert.doesNotMatch(readmeZh, /頁面尚未顯示版本|沒有正式安裝檔可下載/);
  assert.match(readmeZh, /Bixby/);
  assert.match(readmeZh, /Tasker/);
  for (const document of [setupGuideZh, cliGuide]) assert.match(document, /Windows、macOS 與 Linux/);
  assert.doesNotMatch(`${readmeZh}\n${setupGuideZh}\n${humanSecurity}\n${indexZh}\n${zhStrings}`, /DDC v\d|QR Code|QR 配對碼/);
  assert.match(indexZh, /CLI 技術文件/);
  assert.doesNotMatch(indexZh, /AI 助理安裝流程|agent-install\.md/);
  assert.doesNotMatch(indexZh, /api\//i);
  assert.match(cliGuide, /沒有網站 API 或 REST API/);
  assert.match(cliGuide, /DenDen 配對碼/);
  assert.doesNotMatch(`${readmeZh}\n${setupGuideZh}`, /## DenDen 如何運作|Get-FileHash|sha256sum|計算.*SHA-256|計算檔案雜湊|設定 Firebase/);
  assert.doesNotMatch(humanSecurity, /12-byte nonce|AAD|OAuth access token|Activity|Runner/);
  assert.match(humanSecurity, /不包含使用行為分析或遠端錯誤回報功能/);
  assert.match(humanSecurity, /停用 Android 雲端備份與裝置轉移/);
  assert.match(humanSecurity, /Google FCM[\s\S]*中繼資料/);
  assert.match(senderKeyGuide, /\.denden\.json[\s\S]*\.config\/denden\/config\.json[\s\S]*service-account\.json/);
  assert.match(senderKeyGuide, /--channel-id[\s\S]*DENDEN_CHANNEL_ID[\s\S]*defaultChannelId/);
  assert.match(senderKeyGuide, /Firebase Messaging[\s\S]*短期存取權杖/);
  assert.match(senderKeyGuide, /未加密[\s\S]*共用 Google 發送私鑰[\s\S]*完整發送權限[\s\S]*不需要 Google 管理登入/);
  assert.match(deviceGuideZh, /## 新增或停用電腦[\s\S]*匯出一般通知設定[\s\S]*未加密[\s\S]*不需要輸入密碼[\s\S]*不會要求 Google 登入[\s\S]*測試通知/);
  assert.match(deviceGuideZh, /聊天[\s\S]*email[\s\S]*雲端同步[\s\S]*刪除兩台電腦上的轉移包副本/);
  assert.match(deviceGuideZh, /新電腦[\s\S]*安裝 DenDen 日常通知功能/);
  assert.ok(deviceGuideZh.includes(installGuideUrl));
  assert.match(deviceGuideEn, /## Add or disable a computer[\s\S]*new computer[\s\S]*install the daily DenDen notification feature/);
  assert.ok(deviceGuideEn.includes(installGuideUrl));
  assert.match(senderKeyGuide, /## 讓另一台電腦發送通知[\s\S]*Windows、macOS 與 Linux[\s\S]*匯入共用發送身分/);
  assert.match(senderKeyGuide, /Android 上的 Bixby 與 Tasker[\s\S]*同一支手機本機[\s\S]*不支援.*遠端發送端/);
  assert.match(indexZh, /DenDen 如何讀取設定與發送金鑰/);
  assert.match(indexEn, /How DenDen reads settings and sender keys/);
  const zhPages = (await readdir(join(root, "docs/zh-TW"))).filter((name) => name.endsWith(".md")).sort();
  const enPages = (await readdir(join(root, "docs/en"))).filter((name) => name.endsWith(".md")).sort();
  assert.deepEqual(enPages, zhPages);
  for (const name of ["01-welcome.png", "03-inbox.png", "04-channel-timeline.png", "05-alarm.png", "06-appearance.png"]) {
    const zhImage = PNG.sync.read(await readFile(join(root, "docs/assets/screenshots/zh-TW", name)));
    const enImage = PNG.sync.read(await readFile(join(root, "docs/assets/screenshots/en", name)));
    assert.deepEqual([enImage.width, enImage.height], [zhImage.width, zhImage.height]);
  }
  assert.doesNotMatch(readmeZh, /主要賣點|第一版|預發布|本機驗收|刻意保持不可執行|UNLICENSED/);
  const initialSkill = setup.slice(setup.indexOf("## First-time setup"), setup.indexOf("## Custom DenDen appearance"));
  assert.match(initialSkill, /Obtain approval once|Do not request a second DenDen approval/);
  assert.doesNotMatch(initialSkill, /sender-auth-plan|management-revoke-plan|skill-plan/);
  assert.match(setup, /Choose a DenDen appearance/);
  assert.match(setup, /built-in brand and themed surface colors/);
  assert.match(setup, /optional `#RRGGBB` brand or fixed background colors/);
  assert.match(setup, /node <this skill directory>\/scripts\/cli\/bin\/denden\.mjs/);
  assert.match(setup, /If “regenerate” has no object, ask/);
  assert.match(setup, /Codex desktop must use a Markdown image with its absolute local path/);
  assert.match(setup, /Never read or decode the code, convert it to base64 or a data URL, upload it/);
  assert.match(setup, /read `references\/denden-generation\.md` in full/);
  assert.match(setup, /only generation contract/);
  assert.match(generation, /only reference sent to an image service is `assets\/denden-generation-mask\.png`/);
  assert.doesNotMatch(generation, /denden-family-[a-z-]+\.png/);
  assert.match(generation, /equal probability/);
  assert.match(generation, /Cute Courier/);
  assert.match(generation, /Turbo Agent/);
  assert.match(generation, /Mischief Signal/);
  assert.match(generation, /create three distinct complete concepts/);
  assert.match(generation, /one or two named accessories/);
  assert.match(generation, /zero to two named motion effects/);
  assert.match(generation, /large round shell on the left/);
  assert.match(generation, /Never add limbs, extra protrusions, a long upright neck, standing, sitting/);
  assert.match(generation, /exactly two eyes on the face/);
  assert.match(generation, /Tentacle tips are never eyes/);
  assert.match(generation, /no pupils, irises, sclera, dark centers, or eye-like rings/);
  assert.match(generation, /HARD CONSTRAINTS/);
  assert.match(generation, /FINALIZED DESIGN/);
  assert.match(generation, /STYLE/);
  assert.match(generation, /Each image-service call produces exactly one visible image/);
  assert.match(generation, /Polished 2D vector mascot/);
  assert.match(generation, /deep-navy or near-black outer stroke/);
  assert.match(generation, /two or three levels of vector shading/);
  assert.match(generation, /original PNG/);
  assert.match(generation, /transparent background, square canvas, complete centered subject, and safe margins/);
  assert.match(generation, /absolute path to `assets\/denden-generation-mask\.png`/);
  assert.match(generation, /at most `N` image-service calls/);
  assert.match(setup, /If no image tool is available, follow the reference's manual handoff/);
  assert.match(setupGuideZh, /AI 助理本身無法產生圖片時.*完整提示詞與參考遮罩的位置/);
  assert.doesNotMatch(generation, /禁止[^。\n]*漸層/);
  assert.match(setup, /Apply only after explicit acceptance/);
  assert.match(setup, /--background-color/);
  assert.doesNotMatch(setup, /gradlew|connectedDebugAndroidTest|adb devices/i);
});

test("DenDen generation ships only one neutral silhouette mask", async () => {
  const assetDirectory = join(root, "skills/denden-setup/assets");
  assert.deepEqual((await readdir(assetDirectory)).sort(), ["denden-generation-mask.png"]);

  const image = PNG.sync.read(await readFile(join(assetDirectory, "denden-generation-mask.png")));
  assert.equal(image.width, 1024);
  assert.equal(image.height, 1024);
  let hasDark = false;
  let hasLight = false;
  let grayscaleOpaque = true;
  for (let index = 0; index < image.data.length; index += 4) {
    const [red, green, blue, alpha] = image.data.subarray(index, index + 4);
    grayscaleOpaque &&= red === green && green === blue && alpha === 255;
    hasDark ||= red < 16;
    hasLight ||= red > 239;
  }
  assert.equal(grayscaleOpaque && hasDark && hasLight, true);
});

test("runtime code uses one protected user config and never reads or deletes the legacy skill credential", async () => {
  const files = await sourceFiles(join(root, "cli", "src"));
  const runtime = (await Promise.all(files.map((path) => readFile(path, "utf8")))).join("\n");
  assert.match(runtime, /"\.config"\), "denden", "config\.json"/);
  assert.doesNotMatch(runtime, /agent-skills[\\/]denden\.json/);
  assert.doesNotMatch(runtime, /DENDEN_AUTOMATION_TOKEN|backendUrl/);
});

test("CI runs local gates and repository safety checks reject release secrets", async () => {
  const workflow = await readFile(join(root, ".github/workflows/ci.yml"), "utf8");
  const ignore = await readFile(join(root, ".gitignore"), "utf8");
  assert.match(workflow, /pull_request:/);
  assert.match(workflow, /branches: \[main\]/);
  assert.match(workflow, /fetch-depth: 0/);
  assert.doesNotMatch(workflow, /branches:.*\bdev\b/);
  assert.match(workflow, /npm test/);
  assert.match(workflow, /npm run verify:repository/);
  assert.match(workflow, /github\.repository == 'hakendog\/DenDen'[\s\S]*npm run verify:public/);
  assert.match(workflow, /os: \[windows-latest, macos-latest\]/);
  assert.match(workflow, /runs-on: ubuntu-latest/);
  assert.match(workflow, /\.\/gradlew test assembleDebug lint --no-daemon/);
  assert.match(workflow, /npm run verify:apk/);
  const actionRefs = [...workflow.matchAll(/uses: actions\/(?:checkout|setup-node|setup-java)@(\S+)/g)].map((match) => match[1]);
  assert.equal(actionRefs.length >= 3 && actionRefs.every((ref) => /^[0-9a-f]{40}$/.test(ref)), true);
  assert.match(workflow, /connectedDebugAndroidTest/);
  assert.doesNotMatch(workflow, /assembleRelease|DENDEN_RELEASE_/);
  assert.match(ignore, /^\.signing\/$/m);
  assert.equal(isForbiddenRepositoryPath(".signing/release.jks"), true);
  assert.equal(isForbiddenRepositoryPath("release.pem"), true);
  assert.equal(isForbiddenRepositoryPath("release.key"), true);
  assert.equal(isForbiddenRepositoryPath("app/google-services.json"), true);
  assert.deepEqual(findForbiddenRepositoryContent("test-only fixture"), []);
  assert.deepEqual(findForbiddenRepositoryContent(`AIza${"A".repeat(35)}`), ["Google API key"]);

  const { stdout } = await execFileAsync(process.execPath, [join(root, "scripts/check-repository.mjs")], { cwd: root });
  assert.match(stdout, /repository safety check passed/);
});

async function sourceFiles(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await sourceFiles(path));
    else if (entry.name.endsWith(".mjs")) result.push(path);
  }
  return result;
}

async function relativeFiles(directory, prefix = "") {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) result.push(...await relativeFiles(join(directory, entry.name), path));
    else result.push(path);
  }
  return result;
}
