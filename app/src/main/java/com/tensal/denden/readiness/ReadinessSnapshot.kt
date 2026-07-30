package com.tensal.denden.readiness

data class ReadinessEvidence(
    val key: String,
    val label: String,
    val satisfied: Boolean?,
    val detail: String
)

data class ReadinessSnapshot(
    val isReady: Boolean,
    val blockingReasons: List<String>,
    val evidence: List<ReadinessEvidence>,
    val lastDegradedReason: String? = null,
    val isLoading: Boolean = false
) {
    companion object {
        val Loading = ReadinessSnapshot(
            isReady = false,
            blockingReasons = emptyList(),
            evidence = listOf(
                "fcm_pairing" to "FCM 配對",
                "notification_permission" to "通知權限",
                "alarm_channel" to "警報 Channel",
                "last_fcm" to "最近有效訊息",
                "last_test" to "最近本機測試"
            ).map { ReadinessEvidence(it.first, it.second, null, "檢查中") },
            isLoading = true
        )
    }
}

fun buildDirectReadinessSnapshot(
    paired: Boolean,
    notificationPermission: Boolean,
    alarmChannelEnabled: Boolean,
    lastFcmAtMillis: Long,
    lastTestAtMillis: Long,
    lastTestResult: String?,
    lastDegradedReason: String?
): ReadinessSnapshot {
    val required = listOf(
        "fcm_pairing" to paired,
        "notification_permission" to notificationPermission,
        "alarm_channel" to alarmChannelEnabled
    )
    return ReadinessSnapshot(
        isReady = required.all { it.second },
        blockingReasons = required.filterNot { it.second }.map { it.first },
        evidence = listOf(
            ReadinessEvidence("fcm_pairing", "FCM 配對", paired, if (paired) "已訂閱" else "尚未配對"),
            ReadinessEvidence("notification_permission", "通知權限", notificationPermission, if (notificationPermission) "已授權" else "未授權"),
            ReadinessEvidence("alarm_channel", "警報 Channel", alarmChannelEnabled, if (alarmChannelEnabled) "可用" else "停用或不存在"),
            historyEvidence("last_fcm", "最近有效訊息", lastFcmAtMillis),
            ReadinessEvidence(
                "last_test",
                "最近本機測試",
                null,
                if (lastTestAtMillis > 0) "${lastTestResult ?: "unknown"} · $lastTestAtMillis" else "尚無紀錄"
            )
        ),
        lastDegradedReason = lastDegradedReason
    )
}

private fun historyEvidence(key: String, label: String, millis: Long): ReadinessEvidence =
    ReadinessEvidence(key, label, null, if (millis > 0) millis.toString() else "尚無紀錄")
