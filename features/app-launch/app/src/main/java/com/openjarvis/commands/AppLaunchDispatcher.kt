package com.openjarvis.commands

/** A deliberately small set of applications that JARVIS is allowed to launch. */
data class AppLaunchTarget(
    val displayName: String,
    val aliases: Set<String>,
    val packageNames: List<String>
) {
    init {
        require(displayName.isNotBlank())
        require(aliases.isNotEmpty())
        require(packageNames.isNotEmpty())
        require(packageNames.all { it.isNotBlank() })
    }
}

object AppLaunchRegistry {
    val targets: List<AppLaunchTarget> = listOf(
        AppLaunchTarget("WhatsApp", setOf("whatsapp", "whats app", "vatsap"), listOf("com.whatsapp")),
        AppLaunchTarget("YouTube", setOf("youtube", "you tube", "yutub"), listOf("com.google.android.youtube")),
        AppLaunchTarget("Chrome", setOf("chrome", "google chrome", "krom"), listOf("com.android.chrome")),
        AppLaunchTarget("Gmail", setOf("gmail", "google mail", "e posta"), listOf("com.google.android.gm")),
        AppLaunchTarget("Haritalar", setOf("haritalar", "google maps", "maps"), listOf("com.google.android.apps.maps")),
        AppLaunchTarget("Ayarlar", setOf("ayarlar", "settings"), listOf("com.android.settings")),
        AppLaunchTarget("Telegram", setOf("telegram"), listOf("org.telegram.messenger")),
        AppLaunchTarget("Instagram", setOf("instagram", "insta"), listOf("com.instagram.android")),
        AppLaunchTarget("Spotify", setOf("spotify"), listOf("com.spotify.music")),
        AppLaunchTarget("TikTok", setOf("tiktok", "tik tok"), listOf("com.zhiliaoapp.musically")),
        AppLaunchTarget("Play Store", setOf("play store", "google play", "uygulama mağazası"), listOf("com.android.vending")),
        AppLaunchTarget("Google Fotoğraflar", setOf("fotoğraflar", "google fotoğraflar", "photos"), listOf("com.google.android.apps.photos"))
    )

    fun find(requestedName: String, candidates: List<AppLaunchTarget> = targets): AppLaunchTarget? {
        val key = normalize(requestedName)
        if (key.isEmpty()) return null
        return candidates.firstOrNull { target -> target.aliases.any { normalize(it) == key } }
    }

    internal fun normalize(value: String): String =
        ContactMatcher.normalize(value).filter(Char::isLetterOrDigit)
}

/** Result returned by the Android adapter for one registered package. */
sealed class AppLaunchAttempt {
    object Started : AppLaunchAttempt()
    object NotInstalled : AppLaunchAttempt()
    object NoLaunchActivity : AppLaunchAttempt()
    object IntentNotResolvable : AppLaunchAttempt()
    object BackgroundRestricted : AppLaunchAttempt()
    object Failed : AppLaunchAttempt()
}

fun interface AppLaunchPlatform {
    fun launch(packageName: String): AppLaunchAttempt
}

/** Public result; never reports an app as open merely because a package name matched. */
sealed class AppLaunchResult {
    data class LaunchRequested(val target: AppLaunchTarget, val packageName: String) : AppLaunchResult()
    data class Unsupported(val requestedName: String) : AppLaunchResult()
    data class NotInstalled(val target: AppLaunchTarget) : AppLaunchResult()
    data class NoLaunchActivity(val target: AppLaunchTarget) : AppLaunchResult()
    data class IntentNotResolvable(val target: AppLaunchTarget) : AppLaunchResult()
    data class BackgroundRestricted(val target: AppLaunchTarget) : AppLaunchResult()
    data class LaunchFailed(val target: AppLaunchTarget) : AppLaunchResult()
}

class AppLaunchDispatcher(
    private val platform: AppLaunchPlatform,
    private val targets: List<AppLaunchTarget> = AppLaunchRegistry.targets
) {
    fun dispatch(requestedName: String): AppLaunchResult {
        val target = AppLaunchRegistry.find(requestedName, targets)
            ?: return AppLaunchResult.Unsupported(requestedName.trim())

        var sawNoLaunchActivity = false
        var sawUnresolvableIntent = false
        var sawFailure = false

        for (packageName in target.packageNames) {
            when (platform.launch(packageName)) {
                AppLaunchAttempt.Started -> return AppLaunchResult.LaunchRequested(target, packageName)
                AppLaunchAttempt.NotInstalled -> Unit
                AppLaunchAttempt.NoLaunchActivity -> sawNoLaunchActivity = true
                AppLaunchAttempt.IntentNotResolvable -> sawUnresolvableIntent = true
                AppLaunchAttempt.BackgroundRestricted -> return AppLaunchResult.BackgroundRestricted(target)
                AppLaunchAttempt.Failed -> sawFailure = true
            }
        }

        return when {
            sawNoLaunchActivity -> AppLaunchResult.NoLaunchActivity(target)
            sawUnresolvableIntent -> AppLaunchResult.IntentNotResolvable(target)
            sawFailure -> AppLaunchResult.LaunchFailed(target)
            else -> AppLaunchResult.NotInstalled(target)
        }
    }
}

object AppLaunchMessages {
    fun forResult(result: AppLaunchResult): String = when (result) {
        is AppLaunchResult.LaunchRequested -> result.target.displayName + " açma isteği Android'e iletildi."
        is AppLaunchResult.Unsupported -> (result.requestedName.ifBlank { "Bu uygulama" }) +
            " için izinli uygulama açma kısayolu yok. Desteklenenler: " +
            AppLaunchRegistry.targets.joinToString(", ") { it.displayName } + "."
        is AppLaunchResult.NotInstalled -> result.target.displayName + " cihazda yüklü görünmüyor."
        is AppLaunchResult.NoLaunchActivity -> result.target.displayName + " yüklü, ancak açılabilir bir ekranı bulunmuyor."
        is AppLaunchResult.IntentNotResolvable -> result.target.displayName + " için Android açılış hedefi çözümlenemedi."
        is AppLaunchResult.BackgroundRestricted -> "Android, JARVIS arka plandayken uygulama açmayı engelledi. JARVIS'i ekranda açıp komutu yeniden ver veya üstte görünme iznini kontrol et."
        is AppLaunchResult.LaunchFailed -> result.target.displayName + " açılamadı. Android başlatma isteğini reddetti."
    }
}
