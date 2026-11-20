package com.openjarvis.deviceaccess.capabilities

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager

/** Read-only status reader. It never requests permissions or opens privileged settings automatically. */
class AndroidCapabilityStatusReader(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("jarvis_capability_history", Context.MODE_PRIVATE)

    fun recordPermissionRequestResult(id: String, granted: Boolean) {
        preferences.edit()
            .putBoolean("requested." + id, true)
            .putBoolean("grantedBefore." + id, granted || preferences.getBoolean("grantedBefore." + id, false))
            .apply()
    }

    fun snapshot(): List<CapabilityStatusItem> = buildList {
        add(runtime("contacts", "Kişiler", Manifest.permission.READ_CONTACTS))
        add(runtime("sms", "SMS gönderme", Manifest.permission.SEND_SMS))
        add(runtime("camera", "Kamera", Manifest.permission.CAMERA))
        add(runtime("microphone", "Mikrofon", Manifest.permission.RECORD_AUDIO))
        add(notificationStatus())
        add(mediaStatus())
        add(storageStatus())
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
            preferences.edit().putBoolean("grantedBefore." + id, true).apply()
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

    private fun notificationStatus(): CapabilityStatusItem {
        if (Build.VERSION.SDK_INT >= 33) {
            return runtime("notifications", "Bildirimler", Manifest.permission.POST_NOTIFICATIONS)
        }
        val enabled = appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        return CapabilityStatusItem(
            "notifications",
            "Bildirimler",
            if (enabled) CapabilityState.ENABLED else CapabilityState.REQUIRES_SETTINGS,
            if (enabled) "Uygulama bildirimleri açık." else "Bildirimler Android uygulama ayarlarından açılmalı."
        )
    }

    private fun mediaStatus(): CapabilityStatusItem {
        if (Build.VERSION.SDK_INT < 33) {
            return CapabilityStatusItem(
                "media",
                "Fotoğraf ve medya",
                CapabilityState.NOT_AVAILABLE,
                "Genel medya izni yerine Android dosya seçicisi ve kullanıcı tarafından seçilen URI'ler kullanılmalı."
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
        return CapabilityStatusItem(
            "media",
            "Fotoğraf ve medya",
            if (granted.isNotEmpty()) CapabilityState.ENABLED else CapabilityState.NOT_REQUESTED,
            if (granted.isNotEmpty()) {
                "Yalnızca izin verilen medya kategorileri veya Android 14'te kullanıcı tarafından seçilen öğeler erişilebilir."
            } else {
                "Henüz medya izni yok. Tekil dosyalar için Android dosya seçicisi kullanılabilir."
            }
        )
    }

    private fun storageStatus(): CapabilityStatusItem {
        val grants = appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
        return CapabilityStatusItem(
            "shared-storage",
            "Seçilen ortak klasörler",
            if (grants.isNotEmpty()) CapabilityState.ENABLED else CapabilityState.NOT_REQUESTED,
            if (grants.isNotEmpty()) {
                grants.size.toString() + " kalıcı, kullanıcı tarafından seçilmiş klasör izni mevcut."
            } else {
                "Henüz klasör seçilmedi. Android dosya seçicisinden klasör seçerek erişim verebilirsin."
            }
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
