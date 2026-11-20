package com.openjarvis.tasks

/** Closed set of device-action categories persisted without caller-provided text. */
enum class DeviceActionType(val storageValue: String) {
    SMS("sms"),
    CALL("call"),
    STORAGE("storage")
}

/**
 * Persistable result vocabulary with fixed, privacy-safe summaries.
 *
 * Callers cannot pass contact names, phone numbers, message bodies or arbitrary summaries.
 */
enum class DeviceActionOutcomeCode(
    val storageValue: String,
    val safeSummary: String
) {
    SENT(
        "SENT",
        "Android gönderim callback'leri başarılı; teslimat doğrulanmadı."
    ),
    FAILED(
        "FAILED",
        "Android işlem callback'i başarısız oldu."
    ),
    UNKNOWN(
        "UNKNOWN",
        "İşlem sonucu doğrulanamadı; otomatik tekrar yapılmadı."
    ),
    NOT_EXECUTED(
        "NOT_EXECUTED",
        "İşlem başlatılmadı veya gerekli izin/önkoşul sağlanmadı."
    ),
    DRAFT(
        "DRAFT",
        "ACTION_SENDTO taslağı açıldı; JARVIS mesajı göndermedi."
    )
}
