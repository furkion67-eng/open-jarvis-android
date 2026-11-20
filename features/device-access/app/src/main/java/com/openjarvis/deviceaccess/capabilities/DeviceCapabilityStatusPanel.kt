package com.openjarvis.deviceaccess.capabilities

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DeviceCapabilityStatusPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reader = remember(context) { AndroidCapabilityStatusReader(context) }
    var items by remember { mutableStateOf(reader.snapshot()) }
    var actionStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var recognizedSpeech by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (uri == null) {
            actionStatus = "Kamera sonucu için kayıtlı hedef bulunamadı."
        } else if (success) {
            val finalized = runCatching { finalizePendingCameraCapture(context, uri) }
            if (finalized.isSuccess) {
                actionStatus = "Fotoğraf JARVIS klasörüne kaydedildi: Pictures/JARVIS"
            } else {
                context.contentResolver.delete(uri, null, null)
                actionStatus = "Fotoğraf doğrulanamadı ve geçici kayıt temizlendi: " +
                    (finalized.exceptionOrNull()?.localizedMessage ?: "bilinmeyen hata")
            }
        } else {
            context.contentResolver.delete(uri, null, null)
            actionStatus = "Fotoğraf çekimi iptal edildi; boş medya kaydı temizlendi."
        }
        items = reader.snapshot()
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        recognizedSpeech = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        actionStatus = if (recognizedSpeech.isNullOrBlank()) {
            "Konuşma tanıma sonucu alınmadı veya işlem iptal edildi."
        } else {
            "Konuşma tanıma tamamlandı. Metin herhangi bir hassas işlemi kendiliğinden başlatmadı."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        reader.recordPermissionRequestResult("notifications", granted)
        items = reader.snapshot()
        actionStatus = if (granted) {
            if (postCapabilityTestNotification(context)) {
                "Test bildirimi Android tarafından gönderildi."
            } else {
                "Bildirim izni verildi ancak Android bildirimleri kapalı veya bildirim gönderilemedi."
            }
        } else {
            "Bildirim izni verilmedi; bildirim gönderilmedi."
        }
    }

    LaunchedEffect(Unit) { items = reader.snapshot() }

    val cameraAvailable = items.firstOrNull { it.id == "camera" }?.state == CapabilityState.ENABLED
    val microphoneAvailable = items.firstOrNull { it.id == "microphone" }?.state == CapabilityState.ENABLED

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("JARVIS izin ve yetenek durumu", style = MaterialTheme.typography.titleMedium)
            Text(
                "JARVIS yalnızca Android'in verdiği izinler ve senin seçtiğin dosya/klasörler içinde işlem yapabilir. İzinleri Android ayarlarından istediğin zaman geri alabilirsin.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { items = reader.snapshot() }) { Text("Durumu yenile") }

            Text("Kullanıcı başlatmalı cihaz işlemleri", style = MaterialTheme.typography.titleSmall)
            Button(
                enabled = cameraAvailable,
                onClick = {
                    val uri = createPendingCameraCapture(context)
                    if (uri == null) {
                        actionStatus = "Bu Android sürümünde güvenli MediaStore kamera hedefi oluşturulamadı."
                    } else {
                        pendingCameraUri = uri.toString()
                        runCatching { cameraLauncher.launch(uri) }.onFailure { error ->
                            context.contentResolver.delete(uri, null, null)
                            pendingCameraUri = null
                            actionStatus = "Kamera uygulaması açılamadı: " +
                                (error.localizedMessage ?: "bilinmeyen hata")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Android kamera uygulamasıyla fotoğraf çek") }

            Button(
                enabled = microphoneAvailable,
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS için Türkçe konuş")
                    }
                    runCatching { speechLauncher.launch(intent) }.onFailure { error ->
                        actionStatus = "Konuşma tanıma açılamadı: " +
                            (error.localizedMessage ?: "bilinmeyen hata")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Android ile Türkçe konuşma tanı") }

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (context.getSystemService(NotificationManager::class.java)
                            ?.areNotificationsEnabled() != true
                    ) {
                        openNotificationSettings(context)
                    } else {
                        actionStatus = if (postCapabilityTestNotification(context)) {
                            "Test bildirimi Android tarafından gönderildi."
                        } else {
                            "Bildirim gönderilemedi; Android izin ve kanal ayarlarını kontrol et."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("JARVIS test bildirimi gönder") }

            actionStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            recognizedSpeech?.let {
                Text("Tanımlanan konuşma: $it", style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(stateLabel(item.state), color = stateColor(item.state))
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            if (item.state != CapabilityState.ENABLED &&
                                item.state != CapabilityState.NOT_AVAILABLE &&
                                !item.id.startsWith("shared-storage")
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = { openCapabilitySettings(context, item.id) }
                                ) { Text("İlgili Android ayarını aç") }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Android uygulama izinlerini aç") }
            Text(
                "Kamera ve konuşma tanıma Android'in kullanıcı tarafından başlatılan uygulamalarına devredilir. JARVIS arka planda kamera veya ham mikrofon kaydı yapmaz. Erişilebilirlik, üstte görünme ve pil ayarları ayrı Android ekranlarıdır; JARVIS bu onayları atlayamaz ve diğer uygulamaların özel dosyalarına erişemez.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun createPendingCameraCapture(context: Context): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/JARVIS"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}

private fun finalizePendingCameraCapture(context: Context, uri: Uri) {
    require(Build.VERSION.SDK_INT >= 29) { "MediaStore pending capture requires Android 10 or newer." }
    val size = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Images.Media.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)) else 0L
    } ?: 0L
    require(size > 0L) { "Camera provider returned an empty image." }
    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    require(context.contentResolver.update(uri, values, null, null) > 0) {
        "MediaStore could not finalize the captured image."
    }
}

private fun postCapabilityTestNotification(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val channelId = "jarvis_capability_test"
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "JARVIS test bildirimleri",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
    val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
    val builder = if (Build.VERSION.SDK_INT >= 26) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }
    val notification = builder
        .setSmallIcon(icon)
        .setContentTitle("JARVIS")
        .setContentText("Kullanıcı tarafından başlatılan test bildirimi.")
        .setAutoCancel(true)
        .build()
    manager.notify(7301, notification)
    true
}.getOrDefault(false)

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

private fun stateLabel(state: CapabilityState): String = when (state) {
    CapabilityState.ENABLED -> "Etkin / kullanılabilir"
    CapabilityState.NOT_REQUESTED -> "Henüz istenmedi"
    CapabilityState.DENIED -> "Reddedildi"
    CapabilityState.REVOKED -> "Geri alındı"
    CapabilityState.REQUIRES_SETTINGS -> "Android ayarları gerekiyor"
    CapabilityState.NOT_AVAILABLE -> "Kullanılamıyor"
}

@Composable
private fun stateColor(state: CapabilityState) = when (state) {
    CapabilityState.ENABLED -> MaterialTheme.colorScheme.primary
    CapabilityState.NOT_REQUESTED -> MaterialTheme.colorScheme.onSurfaceVariant
    CapabilityState.DENIED, CapabilityState.REVOKED -> MaterialTheme.colorScheme.error
    CapabilityState.REQUIRES_SETTINGS, CapabilityState.NOT_AVAILABLE -> MaterialTheme.colorScheme.tertiary
}

private fun openCapabilitySettings(context: Context, id: String) {
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName)
    )
    val intent = when (id) {
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "background" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> appDetails
    }
    runCatching { context.startActivity(intent) }
}
