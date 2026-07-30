---
name: denden-setup
description: 設定與管理無自建後端的 DenDen，包括固定來源安裝、未連帳務 Firebase 專案、分離 Google 授權、單一 DenDen 配對碼、自訂 DenDen 外觀、其他收發裝置、配對旋轉、加密備份，以及日常 denden 技能安裝。使用者要求安裝、設定、配對、重新配對、加入發送端或變更 DenDen 外觀時使用；不要用於日常通知。
---

# DenDen 設定與管理

本技能可獨立使用，不需要 DenDen 原始碼 checkout 或另行安裝管理工具。所有命令都使用技能根目錄下的 `scripts/cli/bin/denden.mjs`；不得執行其中的 `setup install` 或 `setup rollback`。命令列工具是本技能的確定性執行層，不得自行重寫 FCM、加密、配對碼或品牌分片。

首次執行命令前，先檢查 Node.js 22 以上版本與 npm。缺少工具時，說明用途與官方來源，取得使用者同意後才安裝。技能的 `scripts/node_modules` 尚不存在時，先說明只會依 `scripts/package-lock.json` 安裝本技能所需套件，取得同意後在 `scripts` 目錄執行 `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`。接著用技能內命令列工具執行 `capabilities`，只接受 `direct-fcm-v2` 與 `requiresAutomationToken: false`；不符時停止，不得要求 Automation credential。

除 `brand` 命令、協定欄位與「品牌金鑰」等必要技術名稱外，所有面向使用者的選單與回報一律稱「DenDen」或「DenDen 外觀」，不稱「品牌」。

## 工具與配對

- 先解析技能根目錄的絕對路徑，後續一律用 `node <技能根目錄>/scripts/cli/bin/denden.mjs` 執行本文件中的命令。
- 執行 `setup status`。設定不存在或驗證失敗時才進入初次設定；只回報 project ID 與短指紋，不顯示原始設定或秘密。

## 固定安全邊界

- 不部署 DenDen 後端，不安裝 Firebase CLI，不使用 Firebase Authentication、Firestore、Cloud Functions、Hosting、Storage、Analytics、Secret Manager 或 Budget。只允許每台發送電腦一把專用最低權限服務帳戶金鑰。
- 管理登入與日常發送服務帳戶金鑰必須位於 DenDen 設定目錄內的不同隔離目錄；不得搜尋、讀取或覆寫使用者其他 gcloud 設定、ADC 或服務帳戶金鑰。
- 使用者可明確選擇既有或新建的未連帳務 DenDen 專用專案。既有專案必須先顯示帳號、project ID、用途與不可逆影響，並在 `plan` 與 `direct` 同時使用 `--allow-existing-dedicated`；不得自動挑選、採用用途不明或已連帳務的專案。
- 正常初次設定只顯示一份工具產生的完整摘要，涵蓋 Firebase、IAM、逐機私鑰、最低權限驗證、管理登入撤銷與預選的日常技能動作；只取得一次完全相同摘要的核准。日後旋轉、撤銷、刪除等獨立管理操作仍各自要求摘要與核准。
- DenDen 配對碼含私有主題與兩把金鑰。工具只回報受保護 PNG 的路徑、期限及短指紋；在支援本機圖片的對話介面必須直接顯示該 PNG，Codex desktop 使用絕對本機路徑的 Markdown 圖片。不得讀取或辨識配對碼、轉成 base64／data URL、上傳外部服務、貼出原始內容、私有主題或金鑰；介面不支援本機圖片時才改為顯示路徑並開啟本機檢視器。
- 手機不登入 Google，也不把 FCM 裝置權杖交給 Agent。FCM 接受不代表手機收到，必須由使用者確認實際畫面。
- Android App 由使用者自行下載、安裝、更新、清除資料、授權及操作。本技能不執行 `npm test`、Gradle、Android 測試、其他開發驗收或 ADB。
- 只使用使用者選定的單一 Firebase 專案；不建立維護者 OAuth 用戶端或額外專案。日常發送以每台電腦一個專用服務帳戶執行，角色只能含 `cloudmessaging.messages.create` 與 `serviceusage.services.use`。私鑰不得輸出、複製或匯入其他電腦。
- 確認為 DenDen 安裝工具缺陷時保存續作狀態、回報並停止；不得要求一般使用者核准修改原始碼。修復應由另外的開發任務完成。
- 無伺服器時不能保證送達、補回歷史或只撤銷一支手機。秘密外洩時旋轉整組配對，所有保留手機重新掃描。

## 進入時的管理選單

先判定使用者指的是哪種圖片：

- 「圖片」、「角色」、「蝸牛」、「圖示」、「外觀」、「品牌色」或「背景色」一律視為 DenDen 外觀，走選項 5；不得因「重新生成」字樣改走配對碼。
- 只有明確提到「QR」、「配對碼」或新增接收手機／裝置時才走選項 3。
- 只說「重新生成」而沒有指出對象時，先詢問要重新生成 DenDen 圖片還是 DenDen 配對碼，不得自行猜測。

先執行唯讀狀態檢查，再讓使用者選擇：

1. 初次安裝與配對。
2. 檢查或修復管理／日常授權。
3. 新增接收手機或重新產生同組 DenDen 配對碼。
4. 旋轉整組配對。
5. 建立、更新、續傳或恢復內建 DenDen 外觀。
6. 匯出／匯入一般發送設定或 DenDen 外觀管理備份。
7. 新增或撤銷另一台發送電腦。
8. 安裝日常 `denden` 技能。
9. 離開，不變更任何內容。

已存在且驗證有效的設定直接沿用，不重新建立專案或配對。

## 初次設定

1. 執行 `setup doctor`；說明 Google 仍處理 FCM 中繼資料，且 `projects.addFirebase` 會建立必要支援 API、API key 與 Firebase 管理的服務帳戶。Firebase Authentication 不能取代 FCM HTTP v1 發送授權，因此不啟用。
2. 執行 `setup management-auth`，再唯讀列出目前帳號可存取的專案。讓使用者明確選擇既有未連帳務的 DenDen 專用專案，或在有建立配額時選擇新的全域唯一 project ID；不得自動選擇。摘要前同時詢問日常 `denden` 技能要安裝到全域、使用者指定位置或略過；若不略過，再詢問通知偏好。
   通知偏好優先用一題三選一：1. 每次完成安靜通知，其他結果一般通知（預設）；2. 完成超過一分鐘才安靜通知，其他結果一般通知；3. 只通知失敗、受阻、需要回覆與手動通知。使用者選其他或要求自訂時，分批詢問完成、失敗、部分完成、受阻、需要回覆與手動通知要「不送、安靜或一般通知」；`ring` 另用一題詢問精確事件，預設永不響鈴，不得由 Agent 推斷。
3. 新專案執行 `setup plan --project-id <id> --skill-choice <global|specified|skip> --skill-agent <codex|claude|gemini>`；指定位置另加 `--skill-destination <路徑>`，既有專案另加 `--allow-existing-dedicated`。通知選項 1 至 3 分別加 `--notification-preset <all-completed|balanced|important>`；自訂再用 `--notification-<event> <off|quiet|notify|ring>` 覆寫。呈現管理帳號、專案模式、來源版本與雜湊、全部路徑、逐機服務帳戶、兩項權限、長效私鑰風險、管理登入撤銷、技能動作、通知政策、排除項目、`senderAccountId` 與唯一 `approvalDigest`。
4. 使用者核准這一份摘要後，以完全相同選項執行 `setup direct`，並加上摘要輸出的 `--sender-account-id <id> --approved-digest <摘要>`。此命令依序完成 Firebase、配對設定、逐機發送身分、FCM `validate_only` 與管理 API 負向驗證、管理登入撤銷及預選技能動作；不得再要求第二份 DenDen 核准。
5. 遇到 Firebase 條款問題，說明沒有獨立條款頁；讓使用者在 Firebase Console 點「建立專案」→頁面底部「將 Firebase 新增至 Google Cloud 專案」→選擇現有專案，只接受必要條款並進入下一步後立即停止。不得啟用 Analytics、AI 協助、完成手動新增，或改用其他部署方式；以相同參數與摘要重跑 `setup direct`。
6. 若 FCM 驗證失敗或任何一般 Google Cloud 管理探測未被拒絕，停止並保留精確續作狀態；不得改用人類 ADC、維護者 OAuth 用戶端、共用服務帳戶或 `cloud-platform`。管理帳號、專案、模式、來源、路徑、服務帳戶或技能目的地改變時，舊摘要立即失效。
   命令或 API 逾時時，先確認沒有另一個設定程序仍在執行，再以同一摘要與完全相同參數重跑；不得刪除 checkpoint、盲目重送非冪等寫入、建立第二把私鑰或擴權。
7. 在支援本機圖片的對話介面以受保護 PNG 的絕對本機路徑直接顯示唯一 DenDen 配對碼，再請使用者自行安裝並開啟正式 APK、授予通知權限及掃描。手機可使用行動網路，不需與電腦同網路。等待使用者確認 App 顯示已訂閱。
8. 配對成功後執行 `setup qr-remove` 刪除本機 QR 圖檔；疑似外洩時仍須旋轉整組配對。
9. 完成 DenDen 外觀或明確略過後，先試跑一般通知，再實際傳送。響鈴與停止另行取得明確同意；只回報 FCM 是否接受，由使用者確認手機結果。
10. 提供可略過的 DenDen 外觀管理離線加密備份。初次摘要已選定日常技能動作，不再另外要求核准。

## 自訂 DenDen 外觀

- 正式安裝只使用「DenDen」或「DenDen 外觀」稱呼，不向使用者顯示抽象的「品牌」選擇。固定詢問：「請選擇 DenDen 外觀：1. 使用內建 DenDen；2. 生成新的 DenDen 圖片；3. 依你提供的條件生成 DenDen 圖片；4. 匯入現有透明 PNG；5. 稍後設定 DenDen 外觀。」
- 選項 2、3、重生成或測試生成時，必須先完整讀取 `references/denden-generation.md`，並把它作為唯一生成契約；不得只讀摘要、自行改寫或把規則重複維護在本檔。
- 使用外部圖片服務前，先說明服務名稱、唯一上傳的遮罩資產與資料範圍並取得同意。沒有圖片工具時，不得直接取消生成選項；完整執行 `references/denden-generation.md` 的「無圖片工具時的手動生成交接」，提供定稿提示詞、遮罩路徑及成品交回位置。使用者拒絕手動使用外部圖片服務或上傳遮罩時，才提供內建 DenDen、匯入現有透明 PNG 或稍後設定，不阻擋配對。
- 先讓使用者查看最終候選；只有使用者明確接受後才可執行外觀分片傳送。使用者只是比較生成方向時，先用乾淨淺色單色背景產生概念預覽，圖片服務每次呼叫只對應一張可見圖；選定方向後才沿用同一份定稿製作透明正式候選。使用者要求查看 `N` 張時，本輪最多呼叫圖片服務 `N` 次，不因修正或去背再多顯示圖片。圖片生成完成不代表手機已套用，App 收齊後仍需再次預覽並確認。預設沿用 App 內建品牌色，角色背景跟隨 App 亮／暗主題的內建表面色，不詢問純黑或純白背景。只有使用者要求時才另設 `#RRGGBB` 品牌色或固定背景色；品牌色控制 App 強調色、DenDen 外框、通知色及捷徑預設底色，背景色只覆寫角色容器與捷徑底色。
- 執行 `setup brand apply --image <png> [--brand-color <#RRGGBB>] [--background-color <#RRGGBB>]`；兩個顏色參數都可省略。工具會轉成 512×512 並在必要時自動量化到 64 KiB 傳輸上限內，不要求使用者另行壓縮圖片。
- 中斷、部分接受，或 FCM 全數接受但手機未套用時，立即執行 `setup brand resume`，沿用並重送同一批密文與世代；不得建立新世代重試。
- 恢復內建 DenDen 執行 `setup brand reset`。外觀控制使用獨立金鑰、普通優先級與 `ttl=0`；App 可在背景或程序未執行但未 force-stop 時接收，完成後會在下次前景顯示候選。
- 配對後才新增自訂 DenDen 不需重掃 QR。App 收齊並驗證所有分片後只保存候選；使用者必須在 App 內預覽並接受，拒絕或尚未確認時都繼續顯示原本的 DenDen。各手機需逐支確認，沒有中央補傳或完成回報。
- 一般日常設定不含品牌金鑰，不能產生合法品牌控制訊息。

## 新手機、旋轉與其他發送電腦

- 新手機使用 `setup qr` 取得同組短效 DenDen 配對碼；在支援本機圖片的對話介面直接顯示受保護 PNG，否則顯示路徑並開啟本機檢視器，原手機不需重配。
- 外洩或遺失時，先確認沒有待續傳品牌，執行 `setup rotate-plan`，取得同意後執行 `setup rotate --approved-digest <摘要>`。所有保留手機重掃。
- 其他電腦只匯入密碼加密的一般發送設定包；私鑰不在設定包內。新電腦依序執行 `management-auth`、`sender-auth-plan`、經核准的 `sender-auth`、`sender-verify` 與經核准的 `management-revoke`，建立自己的逐機服務帳戶。不要複製 Google 憑證、服務帳戶金鑰或 gcloud 目錄。
- 發送電腦退役時先重新執行 `management-auth`，再執行 `sender-revoke-plan`；核准後執行 `sender-revoke`，刪除該電腦的遠端服務帳戶與本機私鑰，最後撤銷管理登入。通知或品牌秘密疑似外洩時仍須旋轉整組配對。
- 匯出／匯入密碼只能由使用者在自己的互動終端關閉回顯輸入。Agent 無法提供私密輸入時停止自動操作，不得改用命令列參數或環境變數。

## 日常技能安裝

初次設定把安裝位置與通知偏好納入唯一摘要，由 `setup direct` 執行。`setup skill-plan`／`setup skill-install` 只供初次設定後的獨立補裝，沿用相同通知選擇題與參數；目的地已有不同內容時停止，不覆寫。選定政策寫入受保護的使用者設定；日常 CLI 仍會向上讀取 `.denden.json` 的 Channel／專案設定，但專案設定不得授權 `ring`。只安裝低權限 `denden` 技能及其 `scripts/denden.mjs`，不依賴持久 CLI，也不帶入本技能的 Google、IAM、配對或品牌能力。

## 完成回報

只回報來源提交、正式 APK 驗證、project ID、未連帳務、配對碼圖片路徑、配對、DenDen 外觀、授權驗證、通知測試及技能位置。短效 DenDen 配對碼可依上述規則由本機路徑直接顯示；不得轉錄或輸出配對碼原始內容、主題、金鑰、FCM 權杖、Google token、服務帳戶私鑰、密碼或設定檔內容。
