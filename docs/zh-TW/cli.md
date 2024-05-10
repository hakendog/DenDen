# DenDen CLI

DenDen 沒有網站 API 或 REST API。需要讓程式或 AI 助理操作 DenDen 時，請使用 CLI。本頁保留命令、設定格式與程式整合需要的技術細節。

CLI 支援 Windows、macOS 與 Linux，需要 Git、Node.js 22 與 Google Cloud CLI。

## 執行方式與輸出

以下範例使用 `denden`。正式整合應改用安裝器回報的啟動程式絕對路徑。

成功時，CLI 會把一個 JSON 值寫入 stdout。失敗時會把 JSON 錯誤寫入 stderr，並回傳非零結束碼。呼叫端必須同時檢查結束碼與 JSON，不能只比對文字。

```sh
denden capabilities
```

相容版本會回傳：

```json
{
  "schemaVersion": 1,
  "runtimeProtocol": "direct-fcm-v2",
  "requiresAutomationToken": false
}
```

`runtimeProtocol` 是 CLI 的內部相容識別碼，不是 DenDen 配對碼的產品版本名稱。

CLI 不得輸出私鑰、權杖、完整 DenDen 配對碼、私人通知頻道、密碼或未遮蔽的設定內容。

## 日常命令

```text
denden notify [--channel-id ID] [--title TEXT] [--message TEXT]
  [--event-id ID] [--duration SECONDS] [--mode normal|quiet]
  [--tag TEXT ...] [--dry-run]

denden ring [--channel-id ID] [--title TEXT] [--message TEXT]
  [--event-id ID] [--duration SECONDS] [--tag TEXT ...] [--dry-run]

denden stop --event-id ID [--dry-run]

denden report --event EVENT [--duration SECONDS] [--duration-reliable]
  [--action off|quiet|notify|ring] [--title TEXT] [--message TEXT]
  [--channel-id ID] [--dry-run]

denden policy inspect --event EVENT [--duration SECONDS]
  [--duration-reliable] [--action off|quiet|notify|ring]
  [--channel-id ID]
```

`EVENT` 可用值：`completed`、`failed`、`partial`、`blocked`、`needs-reply`、`manual`。

`--duration` 必須是非負數。`--tag` 可以重複，整理後最多 20 個，每個最多 100 字。

`--dry-run` 不會呼叫 FCM，只回傳預計採取的動作與遮蔽後的資料。`stop` 只解除指定事件的手機警報。

`--device` 與 `devices` 一律拒絕，因為 DenDen 沒有遠端裝置名冊，也不使用手機裝置權杖發送。

## 訊息頻道

```text
denden channel init --channel-id ID [--name NAME]
denden channel list
denden channel add --channel-id ID --name NAME
denden channel use --channel-id ID
denden channel remove --channel-id ID
```

`init` 會在目前目錄建立 `.denden.json`，檔案已存在時拒絕。CLI 不允許移除目前的預設訊息頻道。

## 通知預設值

```sh
denden presets
```

回傳 `important`、`balanced` 與 `all-completed` 的 JSON 定義。

## 設定與管理

```sh
denden setup --help
```

公開子命令：

- 安裝：`install`、`rollback`、`doctor`
- 初次設定：`management-auth`、`plan`、`direct`
- 發送身分：`sender-auth-plan`、`sender-auth`、`sender-verify`、`sender-revoke-plan`、`sender-revoke`
- 配對：`status`、`qr`、`qr-remove`、`rotate-plan`、`rotate`
- 轉移與備份：`export sender|brand`、`import-plan sender`、`import sender|brand`
- 外觀：`brand apply|resume|reset`
- 日常技能：`skill-plan`、`skill-install`
- 管理登入：`management-revoke-plan`、`management-revoke`

一般發送轉移包未加密，包含共用 Google 發送私鑰；取得檔案即取得完整發送權限。匯入前必須執行 `import-plan sender`，再以 `--approved-digest <digest>` 執行 `import sender`。不同的現有設定預設拒絕覆寫；只有在共用發送身分已核准更換後，才加上 `--replace-existing true`。`brand` 備份仍使用密碼加密。

會修改 Google、配對、憑證、技能或外觀的命令，都必須先由對應的規劃命令產生摘要，再使用相同的 `--approved-digest` 執行。不要自行產生摘要值。

安全的唯讀範例：

```sh
denden setup status
```

其他設定命令應交給 `denden-setup`。AI 助理會從[安裝與設定入口](../agent-install.md)載入完整規則。

## 設定檔

專案資料夾可以使用 `.denden.json` 指定預設訊息頻道與各頻道的通知政策。這個檔案不能包含秘密，也不能授權響鈴。

```json
{
  "defaultChannelId": "docs",
  "channels": {
    "docs": {
      "channelId": "docs",
      "channelName": "文件",
      "policy": {
        "preset": "balanced"
      }
    }
  }
}
```

含有私人通知頻道、加密金鑰與發送憑證位置的使用者設定會存放在受保護目錄。不要手動複製、輸出或提交這個檔案。

完整讀取順序、發送金鑰的驗證方式與實際傳送流程，見 [DenDen 如何讀取設定與發送金鑰](settings-and-sender-key.md)。

常用環境變數：

| 變數 | 用途 |
|---|---|
| `DENDEN_CONFIG_PATH` | 覆寫本次程序使用的設定檔 |
| `DENDEN_CHANNEL_ID` | 覆寫本次使用的訊息頻道 |
| `DENDEN_INSTALL_ROOT` | 覆寫持久工具安裝目錄 |
| `XDG_CONFIG_HOME` | 設定根目錄 |
| `XDG_DATA_HOME` | 資料根目錄 |

不存在合法的 `DENDEN_AUTOMATION_TOKEN` 設定。要求這個權杖代表執行到舊版工具。

## 通知政策格式

```json
{
  "preset": "all-completed",
  "events": {
    "failed": "notify"
  },
  "minCompletedDurationSeconds": 60,
  "quietHours": {
    "start": "22:00",
    "end": "07:00",
    "timeZone": "Asia/Taipei",
    "mode": "downgrade"
  }
}
```

動作可用 `off`、`quiet`、`notify`、`ring`。只有受保護的使用者設定可以授權 `ring`。

## AI 助理整合

```sh
denden integration hook
```

這個命令從 stdin 讀取 JSON，只接受回應中的標準事件註記：

```html
<!-- denden:event=completed;durationSeconds=95;durationReliable=true -->
```

找不到精確註記、輸入損壞或無法讀取回應時，CLI 會回傳 `notified: false`，不會猜測事件或發送通知。

Codex 使用低權限的 `skills/denden/`。Claude Code 與 Gemini CLI 的範例位於 `integrations/`。沒有原生整合的工具可以直接呼叫 `denden report`。

## DenDen 配對碼與傳送方式

DenDen 配對碼的原始文字以 `DDC.` 開頭。配對碼含有私人通知頻道與加密金鑰，必須整份視為秘密。外部工具不應自行建立、解析、記錄或上傳配對碼，應交給 `denden setup` 產生與移除。

手機會自行向 FCM 註冊並訂閱私人通知頻道。CLI 只向 `message.topic` 發送，不取得、保存或使用手機的 FCM 裝置權杖。

通知與外觀內容使用 AES-256-GCM 加密。FCM 接受要求只代表 Google 收到發送請求，不代表手機已收到或寫入 DenDen 收件匣。
