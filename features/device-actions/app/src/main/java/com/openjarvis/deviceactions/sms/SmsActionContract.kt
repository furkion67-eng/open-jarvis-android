package com.openjarvis.deviceactions.sms

import java.util.UUID

data class SmsReview(
    val reviewId: String,
    val recipientName: String,
    val phoneNumber: String,
    val exactMessage: String
)

data class SmsDraft(
    val recipientName: String,
    val phoneNumber: String,
    val exactMessage: String
)

data class ConfirmedSmsAction internal constructor(
    val reviewId: String,
    internal val oneTimeToken: String
)

class SmsConfirmationGate {
    private val reviews = mutableMapOf<String, SmsReview>()
    private val tokens = mutableMapOf<String, String>()

    @Synchronized
    fun prepare(recipientName: String, phoneNumber: String, exactMessage: String): SmsReview {
        require(recipientName.isNotBlank()) { "Alıcı adı boş olamaz." }
        require(isPlausiblePhoneNumber(phoneNumber)) { "Telefon numarası geçersiz." }
        require(exactMessage.isNotBlank()) { "Mesaj boş olamaz." }
        val review = SmsReview(
            UUID.randomUUID().toString(),
            recipientName.trim(),
            phoneNumber.trim(),
            exactMessage
        )
        reviews[review.reviewId] = review
        return review
    }

    @Synchronized
    fun confirm(reviewId: String): ConfirmedSmsAction? {
        if (!reviews.containsKey(reviewId) || tokens.containsKey(reviewId)) return null
        val token = UUID.randomUUID().toString()
        tokens[reviewId] = token
        return ConfirmedSmsAction(reviewId, token)
    }

    @Synchronized
    fun consume(confirmation: ConfirmedSmsAction): SmsDraft? {
        val expectedToken = tokens.remove(confirmation.reviewId) ?: return null
        if (expectedToken != confirmation.oneTimeToken) return null
        val review = reviews.remove(confirmation.reviewId) ?: return null
        return SmsDraft(review.recipientName, review.phoneNumber, review.exactMessage)
    }

    @Synchronized
    fun cancel(reviewId: String) {
        tokens.remove(reviewId)
        reviews.remove(reviewId)
    }

    private fun isPlausiblePhoneNumber(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.count { it == '+' } > 1) return false
        if (trimmed.contains('+') && !trimmed.startsWith('+')) return false
        return trimmed.count(Char::isDigit) in 5..20
    }
}

enum class SmsPartResult { PENDING, SENT, FAILED }
enum class SmsSendState { PENDING, SENT, FAILED, UNKNOWN }

class SmsSendTracker(partCount: Int) {
    private val parts: MutableList<SmsPartResult>
    private var terminalState: SmsSendState? = null

    init {
        require(partCount > 0)
        parts = MutableList(partCount) { SmsPartResult.PENDING }
    }

    @Synchronized
    fun recordPart(index: Int, result: SmsPartResult): SmsSendState {
        if (index !in parts.indices || result == SmsPartResult.PENDING) return state()
        if (state() != SmsSendState.PENDING || parts[index] != SmsPartResult.PENDING) return state()
        parts[index] = result
        return state()
    }

    @Synchronized
    fun markUnknown(): SmsSendState {
        if (state() == SmsSendState.PENDING) terminalState = SmsSendState.UNKNOWN
        return state()
    }

    @Synchronized
    fun state(): SmsSendState = terminalState ?: when {
        parts.any { it == SmsPartResult.FAILED } -> SmsSendState.FAILED
        parts.all { it == SmsPartResult.SENT } -> SmsSendState.SENT
        else -> SmsSendState.PENDING
    }
}

sealed interface SmsSendOutcome {
    object Sent : SmsSendOutcome
    object PermissionDenied : SmsSendOutcome
    object Unsupported : SmsSendOutcome
    object Rejected : SmsSendOutcome
    data class Failed(val reason: String) : SmsSendOutcome
    data class Unknown(val reason: String) : SmsSendOutcome
}

fun interface SmsPermission { fun isGranted(): Boolean }
fun interface SmsTransport { fun send(draft: SmsDraft, callback: (SmsSendOutcome) -> Unit) }

class ConfirmedSmsDispatcher(
    private val permission: SmsPermission,
    private val gate: SmsConfirmationGate,
    private val transport: SmsTransport
) {
    fun dispatch(confirmation: ConfirmedSmsAction, callback: (SmsSendOutcome) -> Unit) {
        if (!runCatching(permission::isGranted).getOrDefault(false)) {
            gate.cancel(confirmation.reviewId)
            callback(SmsSendOutcome.PermissionDenied)
            return
        }
        val draft = gate.consume(confirmation)
        if (draft == null) {
            callback(SmsSendOutcome.Rejected)
            return
        }
        transport.send(draft, callback)
    }
}
