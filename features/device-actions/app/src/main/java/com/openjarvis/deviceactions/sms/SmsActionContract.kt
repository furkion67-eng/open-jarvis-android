package com.openjarvis.deviceactions.sms

import java.util.UUID
import com.openjarvis.tasks.DeviceActionOutcomeCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs suspend journal work without translating coroutine cancellation into an ordinary
 * transport or persistence result. Callers may classify only operational exceptions.
 */
internal suspend fun <T> runSmsJournalOperation(operation: suspend () -> T): Result<T> =
    try {
        Result.success(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

internal data class SmsReview(
    val reviewId: String,
    val recipientName: String,
    val phoneNumber: String,
    val exactMessage: String
)

internal data class SmsDraft(
    val recipientName: String,
    val phoneNumber: String,
    val exactMessage: String
)

internal data class ConfirmedSmsAction(
    val reviewId: String,
    internal val oneTimeToken: String
)

internal class SmsConfirmationGate {
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
        val expectedToken = tokens[confirmation.reviewId] ?: return null
        if (expectedToken != confirmation.oneTimeToken) return null
        tokens.remove(confirmation.reviewId)
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
        if (trimmed.any { !it.isDigit() && it !in "+ ()-./" }) return false
        return trimmed.count(Char::isDigit) in 5..20
    }
}

enum class SmsPartResult { PENDING, SENT, FAILED }
enum class SmsSendState { PENDING, SENT, FAILED, UNKNOWN }

/**
 * A multipart send is terminal only after every SMS_SENT callback has arrived.
 * An early failed part must remain PENDING while other callbacks are outstanding;
 * process loss or timeout then correctly resolves the incomplete attempt as UNKNOWN.
 */
private fun multipartState(parts: List<SmsPartResult>): SmsSendState {
    require(parts.isNotEmpty())
    return when {
        parts.all { it == SmsPartResult.SENT } -> SmsSendState.SENT
        parts.all { it != SmsPartResult.PENDING } -> SmsSendState.FAILED
        else -> SmsSendState.PENDING
    }
}

internal object SmsPlatformResultMapper {
    fun toPartResult(resultCode: Int, resultOk: Int): SmsPartResult =
        if (resultCode == resultOk) SmsPartResult.SENT else SmsPartResult.FAILED
}

internal object SmsSelectionInvalidationPolicy {
    fun queryChanged(previous: String, next: String): Boolean = previous != next

    fun recipientChanged(
        previousName: String?,
        previousNumber: String?,
        nextName: String?,
        nextNumber: String?
    ): Boolean = previousName != nextName || previousNumber != nextNumber
}

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
    fun recordPlatformResult(index: Int, resultCode: Int, resultOk: Int): SmsSendState =
        recordPart(index, SmsPlatformResultMapper.toPartResult(resultCode, resultOk))

    @Synchronized
    fun markUnknown(): SmsSendState {
        if (state() == SmsSendState.PENDING) terminalState = SmsSendState.UNKNOWN
        return state()
    }

    @Synchronized
    fun state(): SmsSendState = terminalState ?: multipartState(parts)
}

data class SmsAttemptRecord(
    val requestId: String,
    val parts: List<SmsPartResult>,
    val state: SmsSendState,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

internal interface SmsAttemptStore {
    fun all(): List<SmsAttemptRecord>
    fun get(requestId: String): SmsAttemptRecord?
    fun put(record: SmsAttemptRecord)
    fun remove(requestId: String)
}

/** Durable callback journal. It stores no phone number, contact name or message body. */
internal class SmsAttemptJournal(private val store: SmsAttemptStore) {
    @Synchronized
    fun begin(requestId: String, partCount: Int) {
        require(requestId.isNotBlank() && partCount > 0)
        store.put(SmsAttemptRecord(requestId, List(partCount) { SmsPartResult.PENDING }, SmsSendState.PENDING))
        pruneTerminalHistory()
    }

    @Synchronized
    fun recordPlatformResult(requestId: String, partIndex: Int, resultCode: Int, resultOk: Int): SmsSendState {
        val current = store.get(requestId) ?: return SmsSendState.UNKNOWN
        if (current.state != SmsSendState.PENDING || partIndex !in current.parts.indices ||
            current.parts[partIndex] != SmsPartResult.PENDING
        ) return current.state
        val parts = current.parts.toMutableList()
        parts[partIndex] = SmsPlatformResultMapper.toPartResult(resultCode, resultOk)
        val state = multipartState(parts)
        store.put(current.copy(parts = parts, state = state, updatedAtMillis = System.currentTimeMillis()))
        pruneTerminalHistory()
        return state
    }

    @Synchronized
    fun markUnknown(requestId: String): SmsSendState = transitionPending(requestId, SmsSendState.UNKNOWN)
    @Synchronized
    fun markFailed(requestId: String): SmsSendState = transitionPending(requestId, SmsSendState.FAILED)

    /** Called once when the SMS integration is first opened in a new app process. */
    @Synchronized
    fun recoverPendingAsUnknown(): List<SmsAttemptRecord> {
        val pending = store.all().filter { it.state == SmsSendState.PENDING }
        pending.forEach { store.put(it.copy(state = SmsSendState.UNKNOWN, updatedAtMillis = System.currentTimeMillis())) }
        pruneTerminalHistory()
        return pending.map { it.copy(state = SmsSendState.UNKNOWN) }
    }

    private fun transitionPending(requestId: String, target: SmsSendState): SmsSendState {
        val current = store.get(requestId) ?: return SmsSendState.UNKNOWN
        if (current.state != SmsSendState.PENDING) return current.state
        store.put(current.copy(state = target, updatedAtMillis = System.currentTimeMillis()))
        pruneTerminalHistory()
        return target
    }

    private fun pruneTerminalHistory() {
        store.all().filter { it.state != SmsSendState.PENDING }
            .sortedByDescending { it.updatedAtMillis }
            .drop(MAX_TERMINAL_HISTORY)
            .forEach { store.remove(it.requestId) }
    }

    private companion object { const val MAX_TERMINAL_HISTORY = 32 }
}

internal class SmsAttemptJournalIo(
    private val journal: SmsAttemptJournal,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun begin(requestId: String, partCount: Int) = withContext(ioDispatcher) { journal.begin(requestId, partCount) }
    suspend fun markUnknown(requestId: String): SmsSendState = withContext(ioDispatcher) { journal.markUnknown(requestId) }
    suspend fun markFailed(requestId: String): SmsSendState = withContext(ioDispatcher) { journal.markFailed(requestId) }
    suspend fun recoverPendingAsUnknown(): List<SmsAttemptRecord> = withContext(ioDispatcher) { journal.recoverPendingAsUnknown() }
}

/**
 * Shared callback seam used by the Android BroadcastReceiver and tests. Durable journal state
 * is authoritative: a late callback after timeout/process recovery can never promote UNKNOWN to SENT.
 */
internal class SmsPlatformCallbackRecorder(
    private val requestId: String,
    private val journal: SmsAttemptJournal,
    private val resultOk: Int
) {
    @Synchronized
    fun record(partIndex: Int, resultCode: Int): SmsSendState = journal.recordPlatformResult(requestId, partIndex, resultCode, resultOk)
    @Synchronized
    fun markUnknown(): SmsSendState = journal.markUnknown(requestId)
}

internal class SmsPlatformCallbackRecorderIo(
    private val recorder: SmsPlatformCallbackRecorder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun record(partIndex: Int, resultCode: Int): SmsSendState = withContext(ioDispatcher) { recorder.record(partIndex, resultCode) }
    suspend fun markUnknown(): SmsSendState = withContext(ioDispatcher) { recorder.markUnknown() }
}

sealed interface SmsSendOutcome {
    object Sent : SmsSendOutcome
    object PermissionDenied : SmsSendOutcome
    object Unsupported : SmsSendOutcome
    object Rejected : SmsSendOutcome
    data class Failed(val reason: String) : SmsSendOutcome
    data class Unknown(val reason: String) : SmsSendOutcome
}

internal fun interface SmsPermission { fun isGranted(): Boolean }
internal fun interface SmsTransport {
    fun send(draft: SmsDraft, callback: (SmsSendOutcome) -> Unit)
    suspend fun recoverPendingAttempts(): Int = 0
}

internal class ConfirmedSmsDispatcher(
    private val permission: SmsPermission,
    private val gate: SmsConfirmationGate,
    private val transport: SmsTransport
) {
    suspend fun recoverPendingAttempts(): Int = transport.recoverPendingAttempts()
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

/**
 * Recovery failures block a new send, while coroutine cancellation remains cancellation and
 * must propagate to the owning scope instead of being reported as an ordinary transport error.
 */
internal suspend fun recoverPendingSmsAttemptsOrReport(
    dispatcher: ConfirmedSmsDispatcher,
    onFailure: (Exception) -> Unit
): Int? = try {
    dispatcher.recoverPendingAttempts()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
    null
}

internal class SmsDispatchIoRunner(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun recoverPendingAttempts(dispatcher: ConfirmedSmsDispatcher): Int = withContext(ioDispatcher) { dispatcher.recoverPendingAttempts() }
    suspend fun dispatch(dispatcher: ConfirmedSmsDispatcher, confirmation: ConfirmedSmsAction, callback: (SmsSendOutcome) -> Unit) {
        withContext(ioDispatcher) { dispatcher.dispatch(confirmation, callback) }
    }
}

/** One privacy-safe outcome mapping shared by voice and compose SMS entry points. */
fun SmsSendOutcome.toDeviceActionOutcomeCode(): DeviceActionOutcomeCode = when (this) {
    SmsSendOutcome.Sent -> DeviceActionOutcomeCode.SENT
    SmsSendOutcome.PermissionDenied,
    SmsSendOutcome.Unsupported,
    SmsSendOutcome.Rejected -> DeviceActionOutcomeCode.NOT_EXECUTED
    is SmsSendOutcome.Failed -> DeviceActionOutcomeCode.FAILED
    is SmsSendOutcome.Unknown -> DeviceActionOutcomeCode.UNKNOWN
}
