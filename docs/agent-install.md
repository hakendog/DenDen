# 安裝 DenDen 設定助手

本頁只負責安裝 `denden-setup` 技能。完成後立即使用該技能。

1. 詢問使用者要如何使用設定助手：

   1. 安裝到全域，之後可在任何專案使用。
   2. 安裝到目前專案，只在使用者指定的專案使用。
   3. 僅本次暫時使用，完成本次設定後刪除。

2. 從本頁的 Raw 網址確認來源是固定在 40 碼 commit SHA 的 `hakendog/DenDen`，不要要求使用者處理 SHA。以相同來源取得 `skills/denden-setup/install-manifest.json`。
3. 在所選位置建立名為 `denden-setup` 的技能資料夾，儲存清單，並下載清單 `files` 中的所有檔案。拒絕絕對路徑、`..`、反斜線及清單外的檔案；保留原始位元組與相對目錄。完整檔名以清單為準，內容分為：

   - `SKILL.md`：設定與管理流程。
   - `agents/openai.yaml`：技能顯示資訊。
   - `assets/denden-generation-mask.png`：自訂 DenDen 圖片使用的遮罩。
   - `references/denden-generation.md`：圖片生成規則。
   - `scripts/`：設定用命令列工具、相依套件清單，以及可選安裝的日常 `denden` 技能。

   目的地已存在時，只有 `scripts/source.json` 記錄相同 commit SHA 才可沿用；否則先詢問使用者是否更新，不得自行覆寫。

4. 在技能資料夾建立 `scripts/source.json`：

   ```json
   {
     "schemaVersion": 3,
     "commitSha": "<本頁網址中的 40 碼完整 commit SHA>",
     "runtimeProtocol": "direct-fcm-v2"
   }
   ```

5. 確認清單中的檔案齊全且 `SKILL.md` 的名稱是 `denden-setup`，然後完整讀取 `SKILL.md` 及其要求的參考檔，直接協助使用者設定 DenDen。不執行其他安裝流程。
