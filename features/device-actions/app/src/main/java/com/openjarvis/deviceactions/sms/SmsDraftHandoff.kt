package com.openjarvis.deviceactions.sms

import android.content.Intent
import android.net.Uri
import com.openjarvis.tasks.DeviceActionOutcomeCode

/**
 * Creates a user-owned SMS draft handoff. This helper never invokes SmsManager and its
 * journal outcome is always DRAFT; the external messaging app owns any later send action.
 */
internal object SmsDraftHandoff {
    val outcomeCode: DeviceActionOutcomeCode = DeviceActionOutcomeCode.DRAFT

    fun createIntent(phoneNumber: String, exactMessage: String): Intent {
        val number = phoneNumber.trim()
        require(number.isNotBlank()) { "Taslak alıcı numarası boş olamaz." }
        require(exactMessage.isNotBlank()) { "Taslak mesajı boş olamaz." }
        return Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:" + Uri.encode(number))
        ).putExtra("sms_body", exactMessage)
    }
}
