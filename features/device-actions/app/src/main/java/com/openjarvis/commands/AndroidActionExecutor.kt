package com.openjarvis.commands

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.openjarvis.deviceactions.contacts.AndroidContactsResolver
import com.openjarvis.deviceactions.contacts.ContactCandidate
import com.openjarvis.deviceactions.contacts.ContactLookupResult
import com.openjarvis.deviceactions.sms.SmsConfirmationGate
import com.openjarvis.deviceactions.sms.SmsSendOutcome
import com.openjarvis.deviceactions.sms.createAndroidSmsDispatcher
import com.openjarvis.deviceactions.sms.toDeviceActionOutcomeCode
import com.openjarvis.tasks.DeviceActionOutcomeCode
import com.openjarvis.tasks.DeviceActionOutcomeJournal
import com.openjarvis.tasks.DeviceActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Permission-aware execution of local Android actions.
 *
 * Direct platform SMS transport is deliberately absent from this class: every direct send is delegated to the
 * feature-owned ConfirmedSmsDispatcher and its private AndroidSmsSender. The voice coordinator
 * calls sendSms only after its AwaitingConfirmation has been explicitly accepted. The Compose
 * panel uses the same dispatcher factory, one-time confirmation gate and persisted attempt journal.
 *
 * A successful callback means Android accepted every SMS part (RESULT_OK); it does not prove
 * delivery or reading by the recipient. A missing callback remains UNKNOWN and is never replayed.
 */
class AndroidActionExecutor(private val context: Context) {
    private val outcomeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun persistOutcome(code: DeviceActionOutcomeCode) {
        runCatching {
            DeviceActionOutcomeJournal.record(
                context.applicationContext,
                DeviceActionType.SMS,
                code
            )
        }
    }

    private fun persistOutcomeAsync(code: DeviceActionOutcomeCode) {
        outcomeScope.launch { persistOutcome(code) }
    }

    sealed class ContactResolution {
        data class Exact(val number: String, val displayName: String) : ContactResolution()
        data class Ambiguous(val candidates: List<ContactMatcher.Match>) : ContactResolution()
        object NotFound : ContactResolution()
        object PermissionMissing : ContactResolution()
    }

    fun canSendSmsDirectly(): Boolean = hasPermission(Manifest.permission.SEND_SMS)
    fun canCallDirectly(): Boolean = hasPermission(Manifest.permission.CALL_PHONE)
    fun canReadContacts(): Boolean = hasPermission(Manifest.permission.READ_CONTACTS)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Uses the same ContactsContract-backed resolver as the compose panel. A single contact with
     * one number is returned immediately; duplicate contacts or multiple numbers are presented as
     * explicit choices, never silently reduced to the first row.
     */
    fun resolveContactNumber(name: String): ContactResolution {
        val result = AndroidContactsResolver(context).resolve(name)
        return when (result) {
            is ContactLookupResult.Unique ->
                ContactResolution.Exact(result.phone.number, result.contact.displayName)
            is ContactLookupResult.ChooseNumber ->
                ambiguousNumbers(listOf(result.contact))
            is ContactLookupResult.ChooseContact ->
                ambiguousNumbers(result.candidates)
            ContactLookupResult.PermissionDenied ->
                ContactResolution.PermissionMissing
            else ->
                ContactResolution.NotFound
        }
    }

    private fun ambiguousNumbers(contacts: List<ContactCandidate>): ContactResolution {
        val matches = contacts.flatMap { contact ->
            contact.phoneNumbers.map { phone ->
                val label = if (phone.label.isNullOrBlank()) {
                    contact.displayName
                } else {
                    contact.displayName + " (" + phone.label + ")"
                }
                ContactMatcher.Match(label, phone.number, 100)
            }
        }
        return when (matches.size) {
            0 -> ContactResolution.NotFound
            1 -> ContactResolution.Exact(matches.single().value, matches.single().label)
            else -> ContactResolution.Ambiguous(matches)
        }
    }

    /**
     * Called only after DeviceActionCoordinator has received the user's explicit voice/UI
     * confirmation. This method binds the exact number and message to a fresh one-time token,
     * then dispatches through the same confirmed transport used by SmsComposePanel.
     *
     * The dispatcher rechecks SEND_SMS immediately before transport and maps missing callbacks
     * to UNKNOWN in the durable opaque attempt journal; no retry is scheduled.
     */
    fun sendSms(number: String, body: String, onResult: (Result<Unit>) -> Unit) {
        val gate = SmsConfirmationGate()
        val review = try {
            gate.prepare(number, number, body)
        } catch (error: Exception) {
            onResult(Result.failure<Unit>(error))
            return
        }
        val confirmation = gate.confirm(review.reviewId)
        if (confirmation == null) {
            onResult(Result.failure<Unit>(IllegalStateException("SMS için tek kullanımlık onay oluşturulamadı.")))
            return
        }

        val dispatcher = createAndroidSmsDispatcher(context, gate)
        outcomeScope.launch {
            // A process-reopened pending attempt is UNKNOWN, never replayed as a new send.
            repeat(dispatcher.recoveredUnknownCount) {
                persistOutcome(DeviceActionOutcomeCode.UNKNOWN)
            }
            dispatcher.dispatch(confirmation) { outcome ->
                val result = when (outcome) {
                    SmsSendOutcome.Sent -> Result.success(Unit)
                    SmsSendOutcome.PermissionDenied ->
                        Result.failure<Unit>(SecurityException("SMS izni yok veya gönderim öncesinde geri alındı."))
                    SmsSendOutcome.Unsupported ->
                        Result.failure<Unit>(UnsupportedOperationException("Bu cihaz SMS gönderimini desteklemiyor."))
                    SmsSendOutcome.Rejected ->
                        Result.failure<Unit>(IllegalStateException("SMS onayı geçersiz veya daha önce kullanılmış."))
                    is SmsSendOutcome.Failed ->
                        Result.failure<Unit>(IllegalStateException(outcome.reason))
                    is SmsSendOutcome.Unknown ->
                        Result.failure<Unit>(IllegalStateException(
                            "Gönderim sonucu UNKNOWN; Android callback'i doğrulanamadı. Otomatik tekrar yapılmadı."
                        ))
                }
                outcomeScope.launch {
                    persistOutcome(outcome.toDeviceActionOutcomeCode())
                    onResult(result)
                }
            }
        }
    }

    /** Opens the default SMS app with recipient and body prefilled (no SEND_SMS permission needed). */
    fun openSmsApp(number: String, body: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        persistOutcomeAsync(DeviceActionOutcomeCode.DRAFT)
        true
    } catch (_: ActivityNotFoundException) {
        persistOutcomeAsync(DeviceActionOutcomeCode.NOT_EXECUTED)
        false
    } catch (_: Exception) {
        persistOutcomeAsync(DeviceActionOutcomeCode.NOT_EXECUTED)
        false
    }

    /** Places the call directly (CALL_PHONE) or opens the dialer for the user. */
    fun placeCall(number: String, directIfPermitted: Boolean): Boolean = try {
        val direct = directIfPermitted && canCallDirectly()
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Opens a WhatsApp chat; text is prefilled but never auto-sent. */
    fun openWhatsAppChat(number: String?, body: String?): Boolean = try {
        val uri = if (number.isNullOrBlank()) {
            "https://wa.me/"
        } else {
            val digits = number.filter { it.isDigit() }
            val international = when {
                digits.startsWith("90") -> digits
                digits.startsWith("0") -> "90" + digits.drop(1)
                else -> "90$digits"
            }
            "https://wa.me/$international"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage("com.whatsapp")
            if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            intent.setPackage(null)
            context.startActivity(intent)
        }
        true
    } catch (_: Exception) {
        false
    }
}
