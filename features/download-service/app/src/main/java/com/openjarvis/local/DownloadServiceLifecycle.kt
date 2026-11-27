package com.openjarvis.local

internal sealed interface DownloadServiceCommand {
    data class Download(val tier: ModelManager.ModelTier) : DownloadServiceCommand
    data object Cancel : DownloadServiceCommand
    data class Reject(val reason: RejectReason) : DownloadServiceCommand
}

internal enum class RejectReason(val turkishMessage: String) {
    MISSING_TIER("Model indirme isteğinde seviye bilgisi eksik."),
    INVALID_TIER("İstenen model seviyesi desteklenmiyor."),
    UNKNOWN_ACTION("Bilinmeyen indirme servisi işlemi reddedildi.")
}

internal object DownloadServiceCommandParser {
    fun parse(action: String?, tierName: String?): DownloadServiceCommand = when (action) {
        DownloadService.ACTION_DOWNLOAD -> {
            if (tierName.isNullOrBlank()) {
                DownloadServiceCommand.Reject(RejectReason.MISSING_TIER)
            } else {
                val tier = ModelManager.ModelTier.fromName(tierName)
                if (tier == null) {
                    DownloadServiceCommand.Reject(RejectReason.INVALID_TIER)
                } else {
                    DownloadServiceCommand.Download(tier)
                }
            }
        }
        DownloadService.ACTION_CANCEL -> DownloadServiceCommand.Cancel
        else -> DownloadServiceCommand.Reject(RejectReason.UNKNOWN_ACTION)
    }
}

/**
 * Owns one service start at a time. A late completion/cancellation from an older start
 * cannot clean up a newer foreground download.
 */
internal class DownloadServiceRunGuard {
    private var generation: Long = 0L
    private var activeStartId: Int = 0

    @Synchronized
    fun begin(startId: Int): Long {
        generation += 1
        activeStartId = startId
        return generation
    }

    @Synchronized
    fun cancel(startId: Int): Long {
        generation += 1
        activeStartId = startId
        return generation
    }

    @Synchronized
    fun invalidate(): Long {
        generation += 1
        activeStartId = 0
        return generation
    }

    @Synchronized
    fun owns(token: Long, startId: Int): Boolean =
        token == generation && startId == activeStartId
}
