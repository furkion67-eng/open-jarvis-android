package com.openjarvis.deviceactions.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openjarvis.deviceaccess.capabilities.AndroidCapabilityStatusReader
import com.openjarvis.deviceactions.contacts.AndroidContactsResolver
import com.openjarvis.deviceactions.contacts.ContactCandidate
import com.openjarvis.deviceactions.contacts.ContactLookupResult
import com.openjarvis.deviceactions.contacts.ContactPhoneOption
import com.openjarvis.deviceactions.sms.AndroidSmsSender
import com.openjarvis.deviceactions.sms.ConfirmedSmsDispatcher
import com.openjarvis.deviceactions.sms.SmsConfirmationGate
import com.openjarvis.deviceactions.sms.SmsPermission
import com.openjarvis.deviceactions.sms.SmsSendOutcome

/** Contact-first SMS flow. No phone-number field is shown for a unique contact match. */
@Composable
fun SmsComposePanel(
    modifier: Modifier = Modifier,
    onSmsOutcome: (SmsSendOutcome) -> Unit = {}
) {
    val context = LocalContext.current
    val contacts = remember(context) { AndroidContactsResolver(context) }
    val capabilityStatus = remember(context) { AndroidCapabilityStatusReader(context) }
    val sender = remember(context) { AndroidSmsSender(context) }
    val gate = remember { SmsConfirmationGate() }
    val dispatcher = remember(context, gate, sender) {
        ConfirmedSmsDispatcher(
            permission = SmsPermission {
                context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            },
            gate = gate,
            transport = sender
        )
    }

    var nameQuery by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<ContactLookupResult?>(null) }
    var selectedContact by remember { mutableStateOf<ContactCandidate?>(null) }
    var selectedPhone by remember { mutableStateOf<ContactPhoneOption?>(null) }
    var status by remember { mutableStateOf("SMS için önce rehberden bir kişi bul.") }
    var showConfirmation by remember { mutableStateOf(false) }

    fun applyLookup(result: ContactLookupResult) {
        lookupResult = result
        selectedContact = null
        selectedPhone = null
        when (result) {
            is ContactLookupResult.Unique -> {
                selectedContact = result.contact
                selectedPhone = result.phone
            }
            is ContactLookupResult.ChooseNumber -> selectedContact = result.contact
            else -> Unit
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        capabilityStatus.recordPermissionRequestResult("contacts", granted)
        if (granted) {
            applyLookup(contacts.resolve(nameQuery))
            status = "Rehber izni verildi."
        } else {
            applyLookup(ContactLookupResult.PermissionDenied)
            status = "Rehber izni verilmedi. Kişi adı okunmadı."
        }
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        capabilityStatus.recordPermissionRequestResult("sms", granted)
        status = if (granted) {
            "SMS izni verildi. Göndermek için tekrar Gönder'e dokun ve onay ekranını onayla."
        } else {
            "SMS gönderme izni verilmedi. Hiçbir mesaj gönderilmedi."
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Kişiye SMS gönder", style = MaterialTheme.typography.titleMedium)
            Text(
                "JARVIS önce Android Rehberinde arar. Tek bir kişi ve tek bir numara bulunursa numarayı elle yazman istenmez.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = nameQuery,
                onValueChange = { nameQuery = it },
                label = { Text("Rehberde kişi adı") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = nameQuery.isNotBlank(),
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        applyLookup(contacts.resolve(nameQuery))
                    } else {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
            ) { Text("Rehberde ara") }

            when (val result = lookupResult) {
                is ContactLookupResult.PermissionDenied -> Text(
                    "Kişiler izni yok. Android iznini vererek yeniden deneyebilirsin.",
                    color = MaterialTheme.colorScheme.error
                )
                is ContactLookupResult.InvalidQuery -> Text("Bir kişi adı yaz.")
                is ContactLookupResult.NoMatch -> Text(
                    "“" + result.query + "” adına uygun telefon numaralı kişi bulunamadı. İsmi kontrol et veya kişiyi Android Rehberine ekle."
                )
                is ContactLookupResult.Unavailable -> Text(
                    "Rehber okunamadı: " + result.reason,
                    color = MaterialTheme.colorScheme.error
                )
                is ContactLookupResult.ChooseContact -> {
                    Text("Birden fazla kişi eşleşti. Alıcıyı seç:")
                    result.candidates.forEach { candidate ->
                        Button(
                            onClick = {
                                selectedContact = candidate
                                if (candidate.phoneNumbers.size == 1) {
                                    val onlyPhone = candidate.phoneNumbers.single()
                                    selectedPhone = onlyPhone
                                    lookupResult = ContactLookupResult.Unique(candidate, onlyPhone)
                                } else {
                                    selectedPhone = null
                                    lookupResult = ContactLookupResult.ChooseNumber(candidate)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(candidate.displayName + " · " + candidate.phoneNumbers.size + " numara") }
                    }
                }
                is ContactLookupResult.ChooseNumber -> {
                    Text(result.contact.displayName + " için numara seç:")
                    result.contact.phoneNumbers.forEach { phone ->
                        TextButton(onClick = { selectedPhone = phone }) {
                            Text((phone.label ?: "Telefon") + " · " + phone.number)
                        }
                    }
                }
                is ContactLookupResult.Unique -> Text(
                    "Tek eşleşme bulundu: " + result.contact.displayName + " · " + result.phone.number
                )
                null -> Unit
            }

            val recipient = selectedContact
            val phone = selectedPhone
            if (recipient != null && phone != null) {
                Text("Alıcı: " + recipient.displayName, style = MaterialTheme.typography.titleSmall)
                Text("Numara: " + phone.number, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Mesajın") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Button(
                    enabled = message.isNotBlank(),
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            showConfirmation = true
                        } else {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Gönderme önizlemesi ve onay") }

                TextButton(
                    enabled = message.isNotBlank(),
                    onClick = {
                        val handoff = Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("smsto:" + Uri.encode(phone.number))
                        ).putExtra("sms_body", message)
                        try {
                            context.startActivity(handoff)
                            status = "SMS uygulamasında taslak açıldı. JARVIS bunu gönderildi olarak işaretlemez; son gönderme işlemi SMS uygulamasında sana aittir."
                        } catch (_: Exception) {
                            status = "Varsayılan SMS uygulaması açılamadı."
                        }
                    }
                ) { Text("SMS uygulamasında taslak aç (gönderilmez)") }
            }

            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showConfirmation && selectedContact != null && selectedPhone != null) {
        val recipient = selectedContact!!
        val phone = selectedPhone!!
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("SMS gönderilsin mi?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alıcı: " + recipient.displayName)
                    Text("Numara: " + phone.number)
                    Text("Gönderilecek mesaj:")
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Bu onay yalnızca bu alıcıya ve yukarıdaki tam mesaja aittir.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val review = gate.prepare(recipient.displayName, phone.number, message)
                    val confirmation = gate.confirm(review.reviewId)
                    if (confirmation == null) {
                        status = "Gönderim onayı oluşturulamadı."
                    } else {
                        status = "SMS gönderim sonucu bekleniyor…"
                        dispatcher.dispatch(confirmation) { outcome ->
                            status = when (outcome) {
                                SmsSendOutcome.Sent ->
                                    "Android gönderim callback'i başarılı. Bu, mesajın alıcıya teslim edildiğini kanıtlamaz."
                                SmsSendOutcome.PermissionDenied ->
                                    "SMS izni yok veya geri alındı. SENT kaydedilmedi."
                                SmsSendOutcome.Unsupported ->
                                    "Bu cihaz SMS gönderimini desteklemiyor."
                                SmsSendOutcome.Rejected ->
                                    "Onay geçersiz veya daha önce kullanılmış. Mesaj gönderilmedi."
                                is SmsSendOutcome.Failed ->
                                    "SMS gönderilemedi: " + outcome.reason
                                is SmsSendOutcome.Unknown ->
                                    "Gönderim sonucu doğrulanamadı: " + outcome.reason
                            }
                            onSmsOutcome(outcome)
                        }
                    }
                }) { Text("Onayla ve SMS gönder") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("İptal") }
            }
        )
    }
}
