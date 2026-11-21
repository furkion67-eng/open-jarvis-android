package com.openjarvis.commands
import com.openjarvis.agent.AgentState
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.deviceactions.sms.SmsConfirmationGate
import com.openjarvis.deviceactions.sms.SmsReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class DeviceActionCoordinator(
    private val context: android.content.Context,
    private val graphifyRepo: GraphifyRepository,
    private val scope: CoroutineScope
) {
    private val executor = AndroidActionExecutor(context)
    private val smsConfirmationGate = SmsConfirmationGate()
    private val appLaunchDispatcher = AppLaunchDispatcher(AndroidAppLaunchPlatform(context))
    private sealed class PendingDeviceAction {
        data class SmsAwaitingBody(val contactName: String?, val number: String) : PendingDeviceAction()
        data class SmsConfirmation(val review: SmsReview) : PendingDeviceAction()
        data class CallConfirmation(val contactName: String?, val number: String) : PendingDeviceAction()
        data class WhatsAppAwaitingBody(val contactName: String?, val number: String) : PendingDeviceAction()
        data class WhatsAppConfirmation(val contactName: String?, val number: String, val body: String?) : PendingDeviceAction()
        data class ContactAwaitingChoice(
            val query: String,
            val kind: PendingKind,
            val candidates: List<ContactMatcher.Match>,
            val body: String?
        ) : PendingDeviceAction()
    }
    private enum class PendingKind { Sms, Call, WhatsApp }
    private data class ResolvedRecipient(val displayName: String, val number: String)
    private var pending: PendingDeviceAction? = null
    fun hasPending(): Boolean = pending != null
    private fun awaitingBodyInput(): Boolean =
        pending is PendingDeviceAction.SmsAwaitingBody ||
            pending is PendingDeviceAction.WhatsAppAwaitingBody
    suspend fun handleWhilePending(command: String, setState: (AgentState) -> Unit): Boolean {
        if (pending == null) return false
        val choice = pending as? PendingDeviceAction.ContactAwaitingChoice
        if (choice != null) {
            return handleChoice(command, choice, setState)
        }
        return when (VoiceDecision.parse(command)) {
            VoiceDecision.Decision.Confirm -> {
                if (awaitingBodyInput()) {
                    setState(AgentState.Error("Lütfen önce göndermek istediğiniz mesajı yazın."))
                } else {
                    confirmPending(setState)
                }
                true
            }
            VoiceDecision.Decision.Cancel -> {
                cancelPending(setState)
                true
            }
            VoiceDecision.Decision.None -> when (pending) {
                is PendingDeviceAction.SmsAwaitingBody, is PendingDeviceAction.WhatsAppAwaitingBody -> {
                    submitInput(command, setState)
                    true
                }
                else -> false
            }
        }
    }
    fun clearPending() {
        (pending as? PendingDeviceAction.SmsConfirmation)?.review?.let {
            smsConfirmationGate.cancel(it.reviewId)
        }
        pending = null
    }
    private suspend fun handleChoice(
        command: String,
        choice: PendingDeviceAction.ContactAwaitingChoice,
        setState: (AgentState) -> Unit
    ): Boolean {
        if (VoiceDecision.parse(command) == VoiceDecision.Decision.Cancel) {
            cancelPending(setState)
            return true
        }
        when (val result = ContactChoiceSelector.select(command, choice.candidates)) {
            is ContactChoiceSelector.Result.Selected -> {
                val match = result.match
                when (choice.kind) {
                    PendingKind.Sms ->
                        if (choice.body.isNullOrBlank()) {
                            pending = PendingDeviceAction.SmsAwaitingBody(match.label, match.value)
                            setState(AgentState.NeedsInput("${match.label} için göndermek istediğiniz mesajı yazın."))
                        } else {
                            requestSmsConfirmation(match.label, match.value, choice.body, "sms", setState)
                        }
                    PendingKind.Call -> {
                        pending = PendingDeviceAction.CallConfirmation(match.label, match.value)
                        setState(
                            AgentState.AwaitingConfirmation(
                                title = "Arama onayı",
                                detail = "${match.label} numarası aranacak: ${match.value}",
                                confirmLabel = "Ara",
                                cancelLabel = "İptal",
                                actionTag = "make_call"
                            )
                        )
                    }
                    PendingKind.WhatsApp ->
                        if (choice.body.isNullOrBlank()) {
                            pending = PendingDeviceAction.WhatsAppAwaitingBody(match.label, match.value)
                            setState(AgentState.NeedsInput("${match.label} için WhatsApp mesajını yazın."))
                        } else {
                            pending = PendingDeviceAction.WhatsAppConfirmation(match.label, match.value, choice.body)
                            setState(
                                AgentState.AwaitingConfirmation(
                                    title = "WhatsApp onayı",
                                    detail = "${match.label} ile sohbet açılacak. Mesaj: \"${choice.body}\"",
                                    confirmLabel = "Sohbeti aç",
                                    cancelLabel = "İptal",
                                    actionTag = "whatsapp"
                                )
                            )
                        }
                }
            }
            ContactChoiceSelector.Result.Unmatched ->
                setState(choicePrompt(choice))
        }
        return true
    }
    private fun choicePrompt(choice: PendingDeviceAction.ContactAwaitingChoice): AgentState.NeedsInput {
        val options = choice.candidates
            .take(5)
            .mapIndexed { i, c -> "${i + 1}. ${c.label} (${c.value})" }
            .joinToString(", ")
        return AgentState.NeedsInput(
            "\"${choice.query}\" için birden fazla eşleşme var: $options. " +
                "Hangisi? Numarasını söyleyin ya da tam adıyla seçin."
        )
    }
    suspend fun handle(routed: RoutedCommand, command: String, setState: (AgentState) -> Unit) {
        clearPending()
        when (routed) {
            is RoutedCommand.OpenApp -> handleOpenApp(routed, command, setState)
            is RoutedCommand.SendSms -> handleSms(routed, command, setState)
            is RoutedCommand.MakeCall -> handleCall(routed, command, setState)
            is RoutedCommand.WhatsAppMessage -> handleWhatsApp(routed, command, setState)
        }
    }
    private suspend fun handleOpenApp(routed: RoutedCommand.OpenApp, command: String, setState: (AgentState) -> Unit) {
        openApp(routed.appName, command, setState)
    }
    suspend fun openApp(appName: String, command: String, setState: (AgentState) -> Unit) {
        setState(AgentState.Running(appName + " için açılış hedefi kontrol ediliyor..."))
        val result = appLaunchDispatcher.dispatch(appName)
        when (result) {
            is AppLaunchResult.LaunchRequested -> {
                setState(AgentState.Done(AppLaunchMessages.forResult(result)))
                graphifyRepo.logTask(command, "app launch requested: " + result.packageName, "local-action", 0)
            }
            else -> {
                setState(AgentState.Error(AppLaunchMessages.forResult(result)))
                logFailure(command, "app launch rejected")
            }
        }
    }
    private suspend fun handleSms(routed: RoutedCommand.SendSms, command: String, setState: (AgentState) -> Unit) {
        setState(AgentState.Running("Alıcı çözümleniyor..."))
        val recipient = resolveRecipient(routed.contactName, routed.phoneNumber, routed, command, setState)
        if (recipient == null) {
            setState(AgentState.Error("Alıcı belirsiz; hem kişi hem numara bulunamadı."))
            logFailure(command, "sms: no recipient")
            return
        }
        val body = routed.messageBody
        if (body.isNullOrBlank()) {
            pending = PendingDeviceAction.SmsAwaitingBody(recipient.displayName, recipient.number)
            setState(
                AgentState.NeedsInput(
                    "${recipient.displayName} için göndermek istediğiniz mesajı yazın."
                )
            )
            return
        }
        requestSmsConfirmation(recipient.displayName, recipient.number, body, command, setState)
    }
    private suspend fun handleCall(routed: RoutedCommand.MakeCall, command: String, setState: (AgentState) -> Unit) {
        setState(AgentState.Running("Alıcı çözümleniyor..."))
        val recipient = resolveRecipient(routed.contactName, routed.phoneNumber, routed, command, setState)
        if (recipient == null) {
            setState(AgentState.Error("Aranacak numara bulunamadı."))
            logFailure(command, "call: no recipient")
            return
        }
        pending = PendingDeviceAction.CallConfirmation(recipient.displayName, recipient.number)
        setState(
            AgentState.AwaitingConfirmation(
                title = "Arama onayı",
                detail = "${recipient.displayName} numarası aranacak: ${recipient.number}",
                confirmLabel = "Ara",
                cancelLabel = "İptal",
                actionTag = "make_call"
            )
        )
    }
    private suspend fun handleWhatsApp(routed: RoutedCommand.WhatsAppMessage, command: String, setState: (AgentState) -> Unit) {
        setState(AgentState.Running("Alıcı çözümleniyor..."))
        val recipient = resolveRecipient(routed.contactName, routed.phoneNumber, routed, command, setState)
        if (recipient == null) {
            setState(AgentState.Error("WhatsApp alıcısı bulunamadı."))
            logFailure(command, "whatsapp: no recipient")
            return
        }
        if (routed.messageBody.isNullOrBlank()) {
            pending = PendingDeviceAction.WhatsAppAwaitingBody(recipient.displayName, recipient.number)
            setState(AgentState.NeedsInput("${recipient.displayName} için WhatsApp mesajını yazın."))
            return
        }
        pending = PendingDeviceAction.WhatsAppConfirmation(recipient.displayName, recipient.number, routed.messageBody)
        setState(
            AgentState.AwaitingConfirmation(
                title = "WhatsApp onayı",
                detail = "${recipient.displayName} ile sohbet açılacak. Mesaj: \"${routed.messageBody}\"",
                confirmLabel = "Sohbeti aç",
                cancelLabel = "İptal",
                actionTag = "whatsapp"
            )
        )
    }
    private suspend fun resolveRecipient(
        contactName: String?,
        phoneNumber: String?,
        routed: RoutedCommand,
        command: String,
        setState: (AgentState) -> Unit
    ): ResolvedRecipient? = when (val source = RecipientSourcePolicy.choose(contactName, phoneNumber)) {
        is RecipientSource.ContactName ->
            resolveContactNumber(source.value, routed, command, setState)
        is RecipientSource.ExplicitNumber ->
            ResolvedRecipient(source.value, source.value)
        RecipientSource.Missing -> null
    }

    private suspend fun resolveContactNumber(
        name: String,
        routed: RoutedCommand,
        command: String,
        setState: (AgentState) -> Unit
    ): ResolvedRecipient? {
        if (!executor.canReadContacts()) {
            setState(AgentState.Error("Rehber okuma izni verilmedi. Android uygulama ayarlarından Kişiler iznini açıp tekrar deneyin."))
            logFailure(command, "contacts permission missing")
            return null
        }
        val kind = when (routed) {
            is RoutedCommand.SendSms -> PendingKind.Sms
            is RoutedCommand.MakeCall -> PendingKind.Call
            is RoutedCommand.WhatsAppMessage -> PendingKind.WhatsApp
            is RoutedCommand.OpenApp -> {
                setState(AgentState.Error("Rehber araması yalnızca mesaj ve arama komutları için kullanılabilir."))
                logFailure(command, "contacts lookup for unsupported command")
                return null
            }
        }
        return when (val resolution = executor.resolveContactNumber(name)) {
            is AndroidActionExecutor.ContactResolution.Exact ->
                ResolvedRecipient(resolution.displayName, resolution.number)
            is AndroidActionExecutor.ContactResolution.Ambiguous -> {
                pending = PendingDeviceAction.ContactAwaitingChoice(
                    query = name,
                    kind = kind,
                    candidates = resolution.candidates,
                    body = when (routed) {
                        is RoutedCommand.SendSms -> routed.messageBody
                        is RoutedCommand.WhatsAppMessage -> routed.messageBody
                        else -> null
                    }
                )
                setState(choicePrompt(pending as PendingDeviceAction.ContactAwaitingChoice))
                graphifyRepo.logTask(command, "ambiguous contact, voice choice requested: $name", "local-action", 0)
                null
            }
            AndroidActionExecutor.ContactResolution.NotFound -> {
                setState(AgentState.Error("Rehberde \"$name\" bulunamadı."))
                logFailure(command, "contact not found: $name")
                null
            }
            AndroidActionExecutor.ContactResolution.PermissionMissing -> {
                setState(AgentState.Error("Rehber okuma izni verilmedi. Android uygulama ayarlarından Kişiler iznini açıp tekrar deneyin."))
                logFailure(command, "contacts permission missing")
                null
            }
            is AndroidActionExecutor.ContactResolution.Unavailable -> {
                setState(AgentState.Error("Android Rehberi okunamadı: ${resolution.reason}"))
                logFailure(command, "contacts provider unavailable")
                null
            }
        }
    }
    private suspend fun requestSmsConfirmation(
        contactName: String?,
        number: String,
        body: String,
        command: String,
        setState: (AgentState) -> Unit
    ) {
        (pending as? PendingDeviceAction.SmsConfirmation)?.review?.let {
            smsConfirmationGate.cancel(it.reviewId)
        }
        pending = null
        val review = try {
            smsConfirmationGate.prepare(labelOf(contactName, number), number, body)
        } catch (error: Exception) {
            setState(AgentState.Error("SMS önizlemesi oluşturulamadı: ${error.message ?: "geçersiz alıcı veya mesaj"}"))
            logFailure(command, "sms: invalid review")
            return
        }
        pending = PendingDeviceAction.SmsConfirmation(review)
        setState(
            AgentState.AwaitingConfirmation(
                title = "SMS gönderme onayı",
                detail = "Alıcı: ${review.recipientName} (${review.phoneNumber})\nMesaj: ${review.exactMessage}",
                confirmLabel = "Gönder",
                cancelLabel = "İptal",
                actionTag = "send_sms"
            )
        )
    }
    suspend fun confirmPending(setState: (AgentState) -> Unit) {
        val action = pending
        if (action == null) {
            setState(AgentState.Idle)
            return
        }
        pending = null
        when (action) {
            is PendingDeviceAction.SmsConfirmation -> confirmSms(action.review, setState)
            is PendingDeviceAction.CallConfirmation -> {
                val direct = executor.canCallDirectly()
                val started = if (executor.placeCall(action.number, direct)) {
                    AgentState.Done(
                        if (direct) "Arama başlatıldı: ${labelOf(action.contactName, action.number)}."
                        else "Arama ekranı açıldı; aramayı oradan onaylayın."
                    )
                } else {
                    AgentState.Error("Arama başlatılamadı. (Numara: ${action.number})")
                }
                setState(started)
                graphifyRepo.logTask(
                    if (started is AgentState.Done) "call ok: ${action.contactName ?: action.number}" else "call failed",
                    if (started is AgentState.Done) "success" else "failed",
                    "local-action", 0
                )
            }
            is PendingDeviceAction.WhatsAppConfirmation -> {
                val opened = executor.openWhatsAppChat(action.number, action.body)
                setState(
                    if (opened) AgentState.Done("WhatsApp sohbeti açıldı; göndermek için oradaki gönder düğmesine dokunun.")
                    else AgentState.Error("WhatsApp sohbeti açılamadı (uygulama kurulu olmayabilir).")
                )
                graphifyRepo.logTask("whatsapp chat ${action.contactName ?: action.number}", if (opened) "success" else "failed", "local-action", 0)
            }
            else -> setState(AgentState.Idle)
        }
    }
    private suspend fun confirmSms(review: SmsReview, setState: (AgentState) -> Unit) {
        if (!executor.canSendSmsDirectly()) {
            smsConfirmationGate.cancel(review.reviewId)
            val opened = executor.openSmsApp(review.phoneNumber, review.exactMessage)
            setState(
                if (opened) AgentState.Done("SMS uygulaması açıldı; mesaj taslak olarak hazır. Göndermek için oradaki gönder düğmesine dokunun.")
                else AgentState.Error("SMS uygulaması açılamadı.")
            )
            graphifyRepo.logTask(
                "sms to ${review.recipientName}",
                if (opened) "draft opened (no SEND_SMS permission)" else "failed to open SMS draft",
                "local-action", 0
            )
            return
        }
        val confirmation = smsConfirmationGate.confirm(review.reviewId)
        if (confirmation == null) {
            setState(AgentState.Error("SMS onayı geçersiz veya daha önce kullanılmış. Yeni önizleme oluştur."))
            return
        }
        setState(AgentState.Running("Android SMS gönderim callback'i bekleniyor..."))
        executor.dispatchConfirmedSms(confirmation, smsConfirmationGate) { result ->
            scope.launch(Dispatchers.IO) {
                result.fold(
                    onSuccess = {
                        setState(AgentState.Done(
                            "Android gönderim callback'i başarılı; ${review.recipientName} için teslimat teyit edilmedi."
                        ))
                        graphifyRepo.logTask(
                            "sms to ${review.recipientName}",
                            "platform accepted all SMS parts; delivery not confirmed",
                            "local-action", 0
                        )
                    },
                    onFailure = { error ->
                        setState(AgentState.Error("SMS gönderim sonucu: ${error.message ?: "bilinmeyen hata"}"))
                        graphifyRepo.logTask(
                            "sms to ${review.recipientName}",
                            "failed or unknown: ${error.message}",
                            "local-action", 0
                        )
                    }
                )
            }
        }
    }
    suspend fun cancelPending(setState: (AgentState) -> Unit) {
        (pending as? PendingDeviceAction.SmsConfirmation)?.review?.let {
            smsConfirmationGate.cancel(it.reviewId)
        }
        pending = null
        setState(AgentState.Done("İptal edildi."))
    }
    suspend fun submitInput(text: String, setState: (AgentState) -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) {
            setState(AgentState.Error("Boş mesaj gönderilemez."))
            return
        }
        when (val action = pending) {
            is PendingDeviceAction.SmsAwaitingBody -> requestSmsConfirmation(action.contactName, action.number, body, "sms", setState)
            is PendingDeviceAction.WhatsAppAwaitingBody -> {
                pending = PendingDeviceAction.WhatsAppConfirmation(action.contactName, action.number, body)
                setState(
                    AgentState.AwaitingConfirmation(
                        title = "WhatsApp onayı",
                        detail = "${labelOf(action.contactName, action.number)} ile sohbet açılacak. Mesaj: \"$body\"",
                        confirmLabel = "Sohbeti aç",
                        cancelLabel = "İptal",
                        actionTag = "whatsapp"
                    )
                )
            }
            is PendingDeviceAction.SmsConfirmation -> requestSmsConfirmation(
                action.review.recipientName, action.review.phoneNumber, body, "sms", setState
            )
            else -> setState(AgentState.Error("Şu anda bekleyen bir mesaj yok. Önce alıcıyı belirtin."))
        }
    }
    private fun labelOf(contactName: String?, number: String): String = contactName ?: number
    private suspend fun logFailure(command: String, reason: String) {
        graphifyRepo.logTask(command, "failed: $reason", "local-action", 0)
    }
}
