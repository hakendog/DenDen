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
  assert.match(setup, /單一 DenDen 配對碼/);
  assert.match(setup, /全域、使用者指定位置或略過/);
  assert.match(setup, /每次完成安靜通知.*預設/);
  assert.match(setup, /要求自訂時.*分批詢問/);
  assert.match(setup, /`ring` 另用一題詢問精確事件/);

  for (const relative of ["integrations/claude-code/settings.example.json", "integrations/gemini-cli/settings.example.json"]) {
    const integration = await readFile(join(root, relative), "utf8");
    assert.doesNotMatch(integration, /"command": "denden integration hook"/);
    assert.match(integration, /__DENDEN_PERSISTENT_LAUNCHER__/);
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
  assert.ok(skill.length <= 1400, `daily skill is too verbose: ${skill.length} characters`);
  assert.doesNotMatch(skill, /@tensal\/denden/);
  assert.match(skill, /程式庫政策.*永遠不能授權 `ring`/);
  assert.match(skill, /不執行 `denden setup`/);
  assert.match(skill, /~\/\.config\/denden\/config\.json/);
  assert.match(skill, /本技能目錄>\/scripts\/denden\.mjs/);
  assert.doesNotMatch(skill, /DENDEN_INSTALL_ROOT|USERPROFILE|XDG_DATA_HOME/);
  assert.match(skill, /capabilities/);
  assert.match(skill, /requiresAutomationToken/);
  assert.doesNotMatch(skill, /agent-skills-sync|git pull/i);
});

test("English-first docs and install bootstrap stay official, pinned, and registry-independent", async () => {
  const readmeEn = await readFile(join(root, "README.md"), "utf8");
  const readmeZh = await readFile(join(root, "README.zh-TW.md"), "utf8");
  const setupGuideEn = await readFile(join(root, "docs/en/setup.md"), "utf8");
  const setupGuideZh = await readFile(join(root, "docs/zh-TW/setup.md"), "utf8");
  const humanSecurity = await readFile(join(root, "docs/zh-TW/security-and-limitations.md"), "utf8");
  const senderKeyGuide = await readFile(join(root, "docs/zh-TW/settings-and-sender-key.md"), "utf8");
  const indexZh = await readFile(join(root, "docs/zh-TW/index.md"), "utf8");
  const indexEn = await readFile(join(root, "docs/en/index.md"), "utf8");
  const cliGuide = await readFile(join(root, "docs/zh-TW/cli.md"), "utf8");
  const zhStrings = await readFile(join(root, "app/src/main/res/values-zh-rTW/strings.xml"), "utf8");
  const docsIndex = await readFile(join(root, "docs/README.md"), "utf8");
  const guide = await readFile(join(root, "docs/agent-install.md"), "utf8");
  const setup = await readFile(join(root, "skills/denden-setup/SKILL.md"), "utf8");
  const generation = await readFile(join(root, "skills/denden-setup/references/denden-generation.md"), "utf8");
  assert.match(docsIndex, /English is the canonical language for DenDen documentation/);
  assert.doesNotMatch(`${docsIndex}\n${guide}`, /\p{Script=Han}/u);
  assert.doesNotMatch(guide, /<full 40-character commit SHA>/i);
  assert.match(guide, /raw\.githubusercontent\.com\/hakendog\/DenDen\/\{commit\}\/docs\/agent-install\.md/);
  assert.match(guide, /raw\.githubusercontent\.com\/hakendog\/DenDen\/\{commit\}\/skills\/denden-setup\/install-manifest\.json/);
  assert.match(guide, /relative to `skills\/denden-setup\/`/);
  assert.match(guide, /denden-setup\/SKILL\.md/);
  assert.match(guide, /Install it globally/);
  assert.match(guide, /Install it in the current project/);
  assert.match(guide, /Use it temporarily for this task/);
  assert.match(guide, /Use that skill immediately/);
  assert.doesNotMatch(guide, /git clone|DenDen\.git|setup install/i);
  assert.match(setup, /scripts\/cli\/bin\/denden\.mjs/);
  assert.doesNotMatch(setup, /docs\/agent-install\.md|持久啟動器|DENDEN_INSTALL_ROOT/);
  await assert.rejects(access(join(root, "docs/zh-TW/agent-install.md")));
  assert.doesNotMatch(`${readmeZh}\n${guide}`, /<owner>|<verified-full-commit-sha>|D:\\workspace\\DenDen|尚未發布的本機來源/);
  assert.doesNotMatch(`${readmeZh}\n${guide}`, /raw\.githubusercontent\.com\/[^\s]+\/main\//);
  assert.match(readmeZh, /不用守著螢幕，重要事項由 DenDen 通知你/);
  assert.match(readmeZh, /DenDen 是一套 Android 通知系統/);
  assert.match(readmeZh, /## DenDen 是什麼[\s\S]*## 特色[\s\S]*## 實際畫面[\s\S]*## 開始使用[\s\S]*## 只在手機上使用[\s\S]*## 重要限制[\s\S]*## 更多說明[\s\S]*### 可以用在哪些情境？[\s\S]*### 需要付費嗎？[\s\S]*### 說明文件[\s\S]*## 授權/);
  assert.match(readmeZh, /```text[\s\S]*請根據以下 DenDen 安裝引導[\s\S]*(?:docs\/agent-install\.md|raw\.githubusercontent\.com\/hakendog\/DenDen\/[0-9a-f]{40}\/docs\/agent-install\.md)[\s\S]*```/);
  assert.match(readmeEn, /Stop watching the screen\. Let DenDen notify you when something matters\./);
  assert.match(readmeEn, /## What is DenDen\?[\s\S]*## Features[\s\S]*## Screenshots[\s\S]*## Get started[\s\S]*## Use DenDen only on your phone[\s\S]*## Important limits[\s\S]*## More information[\s\S]*### When can I use DenDen\?[\s\S]*### Does DenDen cost money\?[\s\S]*### Documentation[\s\S]*## License/);
  assert.match(readmeEn, /```text[\s\S]*Please follow this DenDen installation guide[\s\S]*(?:docs\/agent-install\.md|raw\.githubusercontent\.com\/hakendog\/DenDen\/[0-9a-f]{40}\/docs\/agent-install\.md)[\s\S]*```/);
  assert.match(readmeZh, /不會自行執行工作或監控[\s\S]*完成設定後[\s\S]*才會.*透過 DenDen 通知你/);
  assert.match(readmeZh, /雲端服務費用異常或接近預算上限/);
  assert.match(readmeZh, /Codex 使用額度重置，可以繼續執行工作/);
  assert.match(readmeZh, /自訂自己的 DenDen[\s\S]*screenshots\/zh-TW\/06-appearance\.png/);
  assert.match(readmeEn, /A Codex usage limit resets and work can continue/);
  assert.match(readmeEn, /Customize your own DenDen[\s\S]*screenshots\/en\/06-appearance\.png/);
  assert.match(readmeZh, /手機離線時無法收到遠端訊息[\s\S]*重新連線後也不會補送/);
  assert.match(readmeZh, /Spark 免費方案[\s\S]*不需要綁定付款方式/);
  assert.doesNotMatch(readmeZh, /## 適合使用 DenDen 的情境|接收來自電腦的遠端通知需要|Tensal/);
  assert.match(setupGuideZh, /```text[\s\S]*請根據以下 DenDen 安裝引導[\s\S]*(?:docs\/agent-install\.md|raw\.githubusercontent\.com\/hakendog\/DenDen\/[0-9a-f]{40}\/docs\/agent-install\.md)[\s\S]*```/);
  assert.doesNotMatch(`${readmeZh}\n${setupGuideZh}`, /請使用 denden-setup 幫我安裝並設定 DenDen/);
  assert.match(setupGuideZh, /## 在手機安裝 DenDen[\s\S]*## 安裝並配對 DenDen[\s\S]*## 檢查或修復設定[\s\S]*## 新增手機[\s\S]*## 更換整組配對資料[\s\S]*## 更換 DenDen 圖片[\s\S]*## 匯出或匯入設定[\s\S]*## 新增或停用電腦[\s\S]*## 安裝日常通知功能[\s\S]*## 更新 DenDen[\s\S]*## 安全與使用提醒/);
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
  assert.match(senderKeyGuide, /不包含任何電腦的 Google 發送金鑰/);
  assert.match(setupGuideZh, /## 新增或停用電腦[\s\S]*匯出一般通知設定[\s\S]*加密設定包[\s\S]*新電腦[\s\S]*Google 帳戶[\s\S]*測試通知/);
  assert.match(setupGuideZh, /新電腦[\s\S]*(?:docs\/agent-install\.md|raw\.githubusercontent\.com)[\s\S]*安裝 DenDen 日常通知功能/);
  assert.match(setupGuideEn, /## Add or disable a computer[\s\S]*new computer[\s\S]*(?:docs\/agent-install\.md|raw\.githubusercontent\.com)[\s\S]*install the daily DenDen notification feature/);
  assert.match(senderKeyGuide, /## 讓另一台電腦發送通知[\s\S]*Windows、macOS 與 Linux[\s\S]*自己的發送身分與金鑰/);
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
  const initialSkill = setup.slice(setup.indexOf("## 初次設定"), setup.indexOf("## 自訂 DenDen 外觀"));
  assert.match(initialSkill, /只取得一次完全相同摘要的核准|不得再要求第二份 DenDen 核准/);
  assert.doesNotMatch(initialSkill, /sender-auth-plan|management-revoke-plan|skill-plan/);
  assert.match(setup, /請選擇 DenDen 外觀/);
  assert.match(setup, /角色背景跟隨 App 亮／暗主題的內建表面色/);
  assert.match(setup, /品牌色控制 App 強調色/);
  assert.match(setup, /兩個顏色參數都可省略/);
  assert.doesNotMatch(setup, /再選背景：純亮|純暗 `#000000`/);
  assert.match(setup, /node <技能根目錄>\/scripts\/cli\/bin\/denden\.mjs/);
  assert.match(setup, /不得因「重新生成」字樣改走配對碼/);
  assert.match(setup, /支援本機圖片的對話介面必須直接顯示該 PNG/);
  assert.match(setup, /Codex desktop 使用絕對本機路徑的 Markdown 圖片/);
  assert.match(setup, /不得讀取或辨識配對碼、轉成 base64／data URL、上傳外部服務/);
  assert.doesNotMatch(setup, /只顯示 PNG 路徑/);
  assert.match(setup, /完整讀取 `references\/denden-generation\.md`/);
  assert.match(setup, /作為唯一生成契約/);
  assert.match(generation, /唯一傳給圖片服務的參考資產.*`assets\/denden-generation-mask\.png`/);
  assert.doesNotMatch(generation, /denden-family-[a-z-]+\.png/);
  assert.match(generation, /三個家族等機率選一個/);
  assert.match(generation, /可愛快遞員/);
  assert.match(generation, /渦輪特務/);
  assert.match(generation, /搗蛋訊號/);
  assert.match(generation, /Agent 在內部提出三份彼此不同的完整概念/);
  assert.match(generation, /`1:2 = 1:2`/);
  assert.match(generation, /不產生零裝飾候選/);
  assert.match(generation, /沒有預設清單，也不綁定家族/);
  assert.match(generation, /不必全部整合進殼體/);
  assert.match(generation, /與蝸牛合理互動並服務單一主題/);
  assert.match(generation, /單張候選抽零至兩種動態輔助效果/);
  assert.match(generation, /至少一張有速度線，另至少一張使用非速度線效果或零種效果/);
  assert.match(generation, /整體外輪廓、左側大圓殼、右側僅略微抬起且仍向前延伸的頭部/);
  assert.match(generation, /近水平低伏的腹足與尾巴/);
  assert.match(generation, /不得新增手腳、額外凸起物、垂直長頸、直立、坐起、站起/);
  assert.doesNotMatch(generation, /小跑|彈跳/);
  assert.match(generation, /固定在臉上的一對、共兩顆眼睛/);
  assert.match(generation, /觸角末端永遠不是眼睛/);
  assert.match(generation, /不得有瞳孔、虹膜、眼白、黑色中心或環狀眼睛結構/);
  assert.match(generation, /單純高光，但不得讀成眼睛/);
  assert.doesNotMatch(generation, /眼睛若在觸角|眼睛位置/);
  assert.match(generation, /HARD CONSTRAINTS/);
  assert.match(generation, /FINALIZED DESIGN/);
  assert.match(generation, /STYLE/);
  assert.match(generation, /不得列出長串與定稿無關的禁止物件/);
  assert.match(generation, /每次圖片服務呼叫只產生一張可見圖/);
  assert.match(generation, /高完成度 2D 向量吉祥物/);
  assert.match(generation, /深海軍藍或近黑色連續粗描邊/);
  assert.match(generation, /二至三階向量式明暗/);
  assert.match(generation, /殼面寬弧高光/);
  assert.match(generation, /殼下和腹部的有色內陰影/);
  assert.match(generation, /沿用同一定稿做針對性重生，不重新抽題/);
  assert.match(generation, /本輪最多呼叫圖片服務 `N` 次/);
  assert.match(generation, /概念預覽使用乾淨淺色單色背景/);
  assert.match(generation, /透明正式候選/);
  assert.match(setup, /沒有圖片工具時，不得直接取消生成選項/);
  assert.match(setup, /「無圖片工具時的手動生成交接」/);
  assert.match(generation, /單一可複製的程式碼區塊提供完整提示詞/);
  assert.match(generation, /`assets\/denden-generation-mask\.png` 的絕對路徑/);
  assert.match(generation, /透明背景、正方形畫布、主體完整置中且四周保留安全留白/);
  assert.match(generation, /原始 PNG 附回對話/);
  assert.match(generation, /尚不存在、檔名以 `\.png` 結尾的目標/);
  assert.match(generation, /不得只給相對路徑/);
  assert.match(generation, /不得要求存進技能目錄、來源 checkout、DenDen 設定目錄或憑證目錄/);
  assert.match(setupGuideZh, /AI 助理本身無法產生圖片時.*完整提示詞與參考遮罩的位置/);
  assert.doesNotMatch(generation, /禁止[^。\n]*漸層/);
  assert.match(setup, /只有使用者明確接受後才可執行外觀分片傳送/);
  assert.match(setup, /使用者拒絕手動使用外部圖片服務或上傳遮罩時/);
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
  assert.doesNotMatch(workflow, /connectedDebugAndroidTest|assembleRelease|DENDEN_RELEASE_/);
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
    if (entry.name === "node_modules") continue;
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) result.push(...await relativeFiles(join(directory, entry.name), path));
    else result.push(path);
  }
  return result;
}
