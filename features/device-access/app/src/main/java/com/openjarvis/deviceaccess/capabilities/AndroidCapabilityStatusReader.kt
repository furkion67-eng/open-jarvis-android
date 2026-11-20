package com.openjarvis.deviceaccess.capabilities

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent

/** Read-only status reader. It never requests permissions or opens privileged settings automatically. */
class AndroidCapabilityStatusReader(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("jarvis_capability_history", Context.MODE_PRIVATE)

    fun recordPermissionRequestResult(id: String, granted: Boolean) {
        preferences.edit()
            .putBoolean("requested." + id, true)
            .putBoolean(
                "grantedBefore." + id,
                granted || preferences.getBoolean("grantedBefore." + id, false)
            )
            .commit()
    }

    fun recordStoragePickerResult(readGranted: Boolean, writeGranted: Boolean) {
        recordPermissionRequestResult("shared-storage-read", readGranted)
        recordPermissionRequestResult("shared-storage-write", writeGranted)
    }

    fun snapshot(): List<CapabilityStatusItem> = buildList {
        add(runtime("contacts", "Kişiler", Manifest.permission.READ_CONTACTS))
        add(runtime("sms", "SMS gönderme", Manifest.permission.SEND_SMS))
        add(cameraStatus())
        add(microphoneStatus())
        add(notificationStatus())
        add(mediaStatus())
        addAll(storageStatuses())
        add(overlayStatus())
        add(accessibilityStatus())
        add(batteryStatus())
        add(appLaunchStatus())
    }

    private fun runtime(id: String, title: String, permission: String): CapabilityStatusItem {
        val granted = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val requested = preferences.getBoolean("requested." + id, false)
        val grantedBefore = preferences.getBoolean("grantedBefore." + id, false)
        if (granted) {
            preferences.edit().putBoolean("grantedBefore." + id, true).commit()
        }
        val state = CapabilityStatusPolicy.runtimePermission(granted, requested, grantedBefore)
        val detail = when (state) {
            CapabilityState.ENABLED -> "Android izni şu anda etkin."
            CapabilityState.NOT_REQUESTED -> "Henüz istenmedi. İzin yalnızca ilgili özelliği kullandığında istenir."
            CapabilityState.DENIED -> "İzin reddedildi. Özellik çalıştırılmayacak."
            CapabilityState.REVOKED -> "Daha önce verilen izin artık yok; Android ayarlarını veya izin isteğini kontrol et."
            else -> "Android izin durumu okunamadı."
        }
        return CapabilityStatusItem(id, title, state, detail)
    }

    /** Camera capture is delegated to the user's camera app; this app does not silently open a camera. */
    private fun cameraStatus(): CapabilityStatusItem {
        val available = runCatching {
            appContext.packageManager.resolveActivity(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        }.getOrDefault(false)
        return CapabilityStatusItem(
            "camera",
            "Kamera ile fotoğraf çekme",
            CapabilityStatusPolicy.delegatedAction(available),
            if (available) {
                "Android kamera uygulaması kullanılabilir. Fotoğrafı yalnızca sen başlattığında açar; JARVIS arka planda kamera çalıştırmaz ve CAMERA izni istemez."
            } else {
                "Bu cihazda Android'in başlatılabilir kamera uygulaması bulunamadı veya paket görünürlüğü nedeniyle doğrulanamadı."
            }
        )
    }

    /** Speech recognition is user-initiated and delegated to Android's recognition UI, not background recording. */
    private fun microphoneStatus(): CapabilityStatusItem {
        val available = runCatching {
            appContext.packageManager.resolveActivity(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        }.getOrDefault(false)
        return CapabilityStatusItem(
            "microphone",
            "Mikrofon / konuşma tanıma",
            CapabilityStatusPolicy.delegatedAction(available),
            if (available) {
                "Türkçe konuşma tanıma Android'in kullanıcı tarafından açılan sistem arayüzüne devredilir. Bu özellik arka planda ham ses kaydetmez."
            } else {
                "Bu cihazda kullanılabilir Android konuşma tanıma arayüzü bulunamadı."
            }
        )
    }

    private fun notificationStatus(): CapabilityStatusItem {
        val notificationsEnabled = appContext
            .getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() == true
        if (Build.VERSION.SDK_INT >= 33) {
            val permissionState = runtime(
                "notifications",
                "Bildirimler",
                Manifest.permission.POST_NOTIFICATIONS
            )
            val state = if (
                permissionState.state == CapabilityState.ENABLED && !notificationsEnabled
            ) CapabilityState.REQUIRES_SETTINGS else permissionState.state
            val detail = when (state) {
                CapabilityState.ENABLED -> "Bildirim izni açık. Kullanıcı başlatırsa JARVIS yerel bildirim gösterebilir."
                CapabilityState.REQUIRES_SETTINGS -> "İzin verilmiş olsa da uygulama bildirimleri sistem ayarlarında kapalı."
                CapabilityState.NOT_REQUESTED -> "Bildirim izni henüz istenmedi. Test bildirimi düğmesi Android'in izin penceresini açar."
                CapabilityState.DENIED -> "Bildirim izni reddedildi; JARVIS bildirim gönderemez."
                CapabilityState.REVOKED -> "Daha önce verilen bildirim izni geri alındı."
                else -> "Bildirim durumu doğrulanamadı."
            }
            return CapabilityStatusItem("notifications", "Bildirimler", state, detail)
        }
        return CapabilityStatusItem(
            "notifications",
            "Bildirimler",
            if (notificationsEnabled) CapabilityState.ENABLED else CapabilityState.REQUIRES_SETTINGS,
            if (notificationsEnabled) {
                "Android uygulama bildirimleri açık. Kullanıcı başlatırsa JARVIS yerel bildirim gösterebilir."
            } else {
                "Bildirimler Android uygulama ayarlarından açılmalı."
            }
        )
    }

    private fun mediaStatus(): CapabilityStatusItem {
        if (Build.VERSION.SDK_INT < 33) {
            return CapabilityStatusItem(
                "media",
                "Fotoğraf ve medya",
                CapabilityState.NOT_AVAILABLE,
                "Bu sürümde genel READ_MEDIA izinleri yok. JARVIS tekil dosya ve klasörler için Android dosya seçicisini kullanır."
            )
        }
        val permissions = buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        val granted = permissions.filter {
            appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val wasRequested = preferences.getBoolean("requested.media", false)
        val wasGrantedBefore = preferences.getBoolean("grantedBefore.media", false)
        if (granted.isNotEmpty()) {
            preferences.edit().putBoolean("grantedBefore.media", true).commit()
        }
        val state = CapabilityStatusPolicy.permissionSet(
            grantedCount = granted.size,
            wasRequested = wasRequested,
            wasGrantedBefore = wasGrantedBefore
        )
        val detail = when (state) {
            CapabilityState.ENABLED -> {
                val selected = granted.map { permission ->
                    when (permission) {
                        Manifest.permission.READ_MEDIA_IMAGES -> "fotoğraf"
                        Manifest.permission.READ_MEDIA_VIDEO -> "video"
                        Manifest.permission.READ_MEDIA_AUDIO -> "ses"
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "kullanıcının seçtiği görsel/video"
                        else -> permission
                    }
                }
                "Yalnızca şu medya kapsamları verilmiş: " + selected.joinToString() +
                    ". Android 14 seçili medya erişimi tüm galeriye erişim anlamına gelmez."
            }
            CapabilityState.NOT_REQUESTED -> "Geniş medya izni henüz istenmedi. Tekil dosyalar için Android dosya seçicisi kullanılabilir."
            CapabilityState.DENIED -> "Medya izni istendi ancak verilmedi. Seçilen dosyalar için SAF kullanılabilir."
            CapabilityState.REVOKED -> "Daha önce verilen medya izni artık yok. Android uygulama ayarlarını kontrol et."
            else -> "Medya erişimi doğrulanamadı."
        }
        return CapabilityStatusItem("media", "Fotoğraf ve medya", state, detail)
    }

    private fun storageStatuses(): List<CapabilityStatusItem> {
        val grants = appContext.contentResolver.persistedUriPermissions
        val readCount = grants.count { it.isReadPermission }
        val writeCount = grants.count { it.isWritePermission }
        val requestedRead = preferences.getBoolean("requested.shared-storage-read", false)
        val requestedWrite = preferences.getBoolean("requested.shared-storage-write", false)
        val grantedReadBefore = preferences.getBoolean("grantedBefore.shared-storage-read", false)
        val grantedWriteBefore = preferences.getBoolean("grantedBefore.shared-storage-write", false)
        if (readCount > 0 || writeCount > 0) {
            preferences.edit()
                .putBoolean("grantedBefore.shared-storage-read", readCount > 0 || grantedReadBefore)
                .putBoolean("grantedBefore.shared-storage-write", writeCount > 0 || grantedWriteBefore)
                .commit()
        }
        val readState = CapabilityStatusPolicy.runtimePermission(
            readCount > 0, requestedRead, grantedReadBefore
        )
        val writeState = CapabilityStatusPolicy.runtimePermission(
            writeCount > 0, requestedWrite, grantedWriteBefore
        )
        return listOf(
            CapabilityStatusItem(
                "shared-storage-read",
                "Seçilen klasörleri okuma",
                readState,
                when (readState) {
                    CapabilityState.ENABLED -> "$readCount kalıcı kullanıcı seçimi okuma izni etkin."
                    CapabilityState.NOT_REQUESTED -> "Henüz klasör seçilmedi. Dosya erişimi panelinden klasör seçebilirsin."
                    CapabilityState.DENIED -> "Klasör seçildi ancak Android kalıcı okuma izni vermedi."
                    CapabilityState.REVOKED -> "Daha önce verilen klasör okuma izni geri alındı."
                    else -> "Klasör okuma izni doğrulanamadı."
                }
            ),
            CapabilityStatusItem(
                "shared-storage-write",
                "Seçilen klasörlere yazma",
                writeState,
                when (writeState) {
                    CapabilityState.ENABLED -> "$writeCount kalıcı kullanıcı seçimi yazma izni etkin."
                    CapabilityState.NOT_REQUESTED -> "Henüz yazma izni istenmedi. Yazma erişimi için dosya seçicisinde izin vermelisin."
                    CapabilityState.DENIED -> "Android seçilen klasör için yazma izni vermedi; dosya değiştirme işlemleri kapalı."
                    CapabilityState.REVOKED -> "Daha önce verilen klasör yazma izni geri alındı; değişiklik yapılamaz."
                    else -> "Klasör yazma izni doğrulanamadı."
                }
            )
        )
    }

    private fun overlayStatus(): CapabilityStatusItem {
        val granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(appContext)
        return CapabilityStatusItem(
            "overlay",
            "Diğer uygulamaların üzerinde görünme",
            if (granted) CapabilityState.ENABLED else CapabilityState.REQUIRES_SETTINGS,
            if (granted) "Üstte görünme izni açık." else "Bu özel izin Android ayarlarından kullanıcı tarafından açılmalı."
        )
    }

    private fun accessibilityStatus(): CapabilityStatusItem {
        val declaredServices = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentServices(
                Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(appContext.packageName),
                PackageManager.GET_META_DATA
            ).mapNotNull { it.serviceInfo }.filter {
                it.packageName == appContext.packageName &&
                    it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            }
        }.getOrDefault(emptyList())
        val enabledComponents = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }
        val declared = declaredServices.isNotEmpty()
        val enabled = declaredServices.any { service ->
            ComponentName(service.packageName, service.name) in enabledComponents
        }
        val state = CapabilityStatusPolicy.accessibilityService(declared, enabled)
        val detail = when (state) {
            CapabilityState.ENABLED ->
                "JARVIS erişilebilirlik servisi Android ayarlarında kullanıcı tarafından etkinleştirilmiş."
            CapabilityState.REQUIRES_SETTINGS ->
                "JARVIS erişilebilirlik servisi uygulamada tanımlı; kullanmak için Android Erişilebilirlik ayarlarında kendin etkinleştirmelisin."
            else ->
                "Son host manifestinde JARVIS erişilebilirlik servisi tanımlı değil."
        }
        return CapabilityStatusItem(
            "accessibility",
            "Erişilebilirlik ile ekranda işlem",
            state,
            detail
        )
    }

    private fun appLaunchStatus(): CapabilityStatusItem {
        val visibleLaunchableApps = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_DEFAULT_ONLY
            ).filter { it.activityInfo?.packageName != appContext.packageName }
        }.getOrDefault(emptyList())
        return CapabilityStatusItem(
            "app-launch",
            "Uygulama açma",
            if (visibleLaunchableApps.isNotEmpty()) CapabilityState.ENABLED else CapabilityState.NOT_AVAILABLE,
            if (visibleLaunchableApps.isNotEmpty()) {
                visibleLaunchableApps.size.toString() +
                    " Android tarafından görünür başlatılabilir uygulama bulundu; diğer paketler görünür olmayabilir."
            } else {
                "Android bu uygulama için başka başlatılabilir uygulama göstermiyor; paket görünürlüğü veya launcher etkinliği mevcut değil."
            }
        )
    }

    private fun batteryStatus(): CapabilityStatusItem {
        val power = appContext.getSystemService(PowerManager::class.java)
        val exempt = Build.VERSION.SDK_INT < 23 || power?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        return CapabilityStatusItem(
            "background",
            "Arka plan ve pil kısıtlamaları",
            if (exempt) CapabilityState.ENABLED else CapabilityState.REQUIRES_SETTINGS,
            if (exempt) {
                "Pil optimizasyonu kısıtlaması bu uygulama için devre dışı."
            } else {
                "Android arka planı sınırlayabilir. Pil ayarları kullanıcı tarafından yönetilmelidir; uygulama kendiliğinden muafiyet alamaz."
            }
        )
    }
}
