---
name: denden
description: 使用技能內低權限 DenDen CLI 與直接 FCM 回報 Agent 完成、失敗、部分完成、受阻、需回覆或手動通知，並在明確授權時響鈴或停止手機警報；不要用於設定、配對、Firebase 管理或外觀。
---

# DenDen 日常回報

- `<denden>` 固定代表 `node <本技能目錄>/scripts/denden.mjs`；不得改用 `PATH`、持久 launcher、npm 或自行實作傳送。
- 傳送前執行 `<denden> capabilities`，只接受 `{"schemaVersion":1,"runtimeProtocol":"direct-fcm-v2","requiresAutomationToken":false}`；缺少或不符即停止並回報 skill 不完整，也不尋找舊 credential。
- 單純問答或查狀態不回報。其餘映射為 `completed`、`failed`、`partial`、`blocked`、`needs-reply` 或 `manual`，執行 `<denden> report --event <event> --title <短標題> --message <安全摘要>`；僅可靠量測時加入 `--duration <秒> --duration-reliable`。
- 預設 `completed` 安靜回報；`failed`、`partial`、`blocked`、`needs-reply` 一般通知；`ring` 關閉。
- 只有使用者明確要求，或受保護使用者設定有精確規則時才可 `ring`。程式庫政策只能選 `off`、`quiet`、`notify`，永遠不能授權 `ring`。
- `<denden> stop --event-id <id>` 只解除手機警報，不代表停止來源工作。
- 不執行 `denden setup`、Firebase／IAM、配對、匯出或外觀操作；改用 `denden-setup`。不得輸出或傳入任何憑證、權杖、私有主題、密鑰或 QR Code。
- Channel 依序取 `--channel-id`、`DENDEN_CHANNEL_ID`、`.denden.json.defaultChannelId`；設定無效即停止。唯一使用者設定為 `~/.config/denden/config.json`，不得讀取舊 `~/.config/agent-skills/denden.json`。
- FCM 接受不代表手機收到；DenDen 傳送失敗也不改變原工作結果。
