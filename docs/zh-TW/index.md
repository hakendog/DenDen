# DenDen 說明文件

[English](../en/index.md) · 繁體中文

DenDen 是一套 Android 通知系統，讓電腦、AI 助理與手機自動化在重要事項發生時通知你。你可以選擇安靜通知、一般通知或響鈴，已送達的內容會保存在 DenDen 收件匣。

這份文件包含第一次安裝、Bixby 與 Tasker、日常裝置管理、疑難排解、安全限制與技術整合。第一次使用時先閱讀本頁；需要完整操作步驟時，再前往對應文件。

## 如何開始

先依用途選擇一條路徑：

### 只使用手機自動化

安裝 DenDen 後，在第一次開啟時選擇「只使用本機自動化」。接著即可用 Samsung Bixby 日常行程或 Tasker 建立安靜訊息、一般通知與警報，不需要電腦、Firebase、DenDen 配對碼或網路。

[查看 Bixby 與 Tasker 設定](local-automation.md)

### 接收電腦或 AI 助理通知

先在 Android 手機安裝 DenDen 並允許通知，再把官方安裝引導交給能操作電腦的 AI 助理。確認變更摘要後，用手機掃描 DenDen 配對碼，最後確認測試通知與收件紀錄都有出現。

[查看安裝與首次配對步驟](setup.md)

## 日常使用

- [查看收件匣、通知與響鈴](usage.md)
- [新增發送裝置（電腦）](device-management.md#新增或停用電腦)
- [新增接收裝置（手機）](device-management.md#新增手機)
- [更換 DenDen 圖片](preferences-and-backup.md#更換-denden-圖片)
- [調整通知方式](usage.md#通知方式)
- [匯出或匯入設定](preferences-and-backup.md#匯出或匯入設定)
- [更新 DenDen](preferences-and-backup.md#更新-denden)

## 遇到問題

- 通知沒有出現、無法配對或更新失敗時，先看[疑難排解](troubleshooting.md)。
- 想了解離線、送達、資料保存與使用範圍時，閱讀[安全與限制](security-and-limitations.md)。

## 技術文件

只有在自行整合或檢查電腦端設定時才需要閱讀：

- [DenDen 如何讀取設定與發送金鑰](settings-and-sender-key.md)
- [CLI 技術文件](cli.md)
