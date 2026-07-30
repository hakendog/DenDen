# 安裝 DenDen 設定助手

本頁只負責安裝 `denden-setup` 技能。完成後立即使用該技能。

1. 詢問使用者要如何使用設定助手：

   1. 安裝到全域，之後可在任何專案使用。
   2. 安裝到目前專案，只在使用者指定的專案使用。
   3. 僅本次暫時使用，完成本次設定後刪除。

2. 只接受符合下列格式的本頁 Raw 網址。`{commit}` 必須是網址中原有的 40 碼完整 commit SHA；由 AI 助理自行讀取，不要要求使用者提供或替換。

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/docs/agent-install.md
   ```

   拒絕 `main`、標籤、短 SHA、其他 repository、GitHub `blob` 網址、重新導向及非 Raw 主機。後續所有檔案必須使用同一個 `{commit}`。

3. 從下列完整網址下載安裝清單，並儲存為 `<技能安裝位置>/denden-setup/install-manifest.json`：

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/install-manifest.json
   ```

   清單必須使用 `schemaVersion: 1`、`skill: denden-setup`，並包含非空的 `files` 陣列。

4. 建立 `<技能安裝位置>/denden-setup` 技能資料夾。清單 `files` 中的每個項目都是相對於 `skills/denden-setup/` 的路徑：

   - 下載網址：`https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/<files 項目>`
   - 儲存位置：`<技能安裝位置>/denden-setup/<files 項目>`

   例如清單中的 `SKILL.md` 必須從：

   ```text
   https://raw.githubusercontent.com/hakendog/DenDen/{commit}/skills/denden-setup/SKILL.md
   ```

   儲存為 `<技能安裝位置>/denden-setup/SKILL.md`。

   拒絕絕對路徑、`..`、反斜線、重複路徑及清單外的檔案；保留原始位元組與相對目錄。完整檔名以清單為準，內容分為：

   - `SKILL.md`：設定與管理流程。
   - `agents/openai.yaml`：技能顯示資訊。
   - `assets/denden-generation-mask.png`：自訂 DenDen 圖片使用的遮罩。
   - `references/denden-generation.md`：圖片生成規則。
   - `scripts/`：設定用命令列工具、相依套件清單，以及可選安裝的日常 `denden` 技能。

5. 目的地已存在時，只有 `scripts/source.json` 記錄相同 commit SHA 才可沿用；否則先詢問使用者是否更新，不得自行覆寫。

6. 在技能資料夾建立 `scripts/source.json`：

   ```json
   {
     "schemaVersion": 3,
     "commitSha": "{commit}",
     "runtimeProtocol": "direct-fcm-v2"
   }
   ```

7. 確認清單本身與 `files` 中的所有檔案齊全，且 `SKILL.md` 的名稱是 `denden-setup`。然後完整讀取 `SKILL.md` 及其要求的參考檔，直接協助使用者設定 DenDen。不執行其他安裝流程。
