package com.openjarvis.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchDispatcherTest {
    private class FakePlatform(private val outcomes: Map<String, AppLaunchAttempt>) : AppLaunchPlatform {
        val calls = mutableListOf<String>()
        override fun launch(packageName: String): AppLaunchAttempt {
            calls += packageName
            return outcomes[packageName] ?: AppLaunchAttempt.NotInstalled
        }
    }

    @Test
    fun resolvesOnlyRegisteredAliases() {
        assertEquals("WhatsApp", AppLaunchRegistry.find("Whats App")?.displayName)
        assertEquals("YouTube", AppLaunchRegistry.find("yutub")?.displayName)
        assertEquals("Ayarlar", AppLaunchRegistry.find("AYARLAR")?.displayName)
        assertEquals(null, AppLaunchRegistry.find("com.whatsapp"))
        assertEquals(null, AppLaunchRegistry.find("rastgele uygulama"))
    }

    @Test
    fun launchesOnlyTheRegisteredPackage() {
        val fake = FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.Started))
        val result = AppLaunchDispatcher(fake).dispatch("WhatsApp")
        assertTrue(result is AppLaunchResult.LaunchRequested)
        assertEquals(listOf("com.whatsapp"), fake.calls)
    }

    @Test
    fun reportsUnsupportedPackageNameInsteadOfLaunchingIt() {
        val fake = FakePlatform(mapOf("com.attacker.app" to AppLaunchAttempt.Started))
        val result = AppLaunchDispatcher(fake).dispatch("com.attacker.app")
        assertTrue(result is AppLaunchResult.Unsupported)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun distinguishesNotInstalledFromMissingLauncherActivity() {
        val missing = AppLaunchDispatcher(FakePlatform(emptyMap())).dispatch("WhatsApp")
        val noActivity = AppLaunchDispatcher(
            FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.NoLaunchActivity))
        ).dispatch("WhatsApp")
        assertTrue(missing is AppLaunchResult.NotInstalled)
        assertTrue(noActivity is AppLaunchResult.NoLaunchActivity)
    }

    @Test
    fun distinguishesUnresolvableAndBackgroundRestrictedLaunches() {
        val unresolved = AppLaunchDispatcher(
            FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.IntentNotResolvable))
        ).dispatch("WhatsApp")
        val background = AppLaunchDispatcher(
            FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.BackgroundRestricted))
        ).dispatch("WhatsApp")
        assertTrue(unresolved is AppLaunchResult.IntentNotResolvable)
        assertTrue(background is AppLaunchResult.BackgroundRestricted)
    }

    @Test
    fun knownTurkishLaunchVerbRoutesToTheSameAllowlistedApp() {
        val routed = TurkishCommandRouter.route("YouTube'u çalıştır")
        assertEquals(RoutedCommand.OpenApp("YouTube"), routed)
        assertEquals("YouTube", AppLaunchRegistry.find((routed as RoutedCommand.OpenApp).appName)?.displayName)
    }

    @Test
    fun typedAndRecognizedSpeechUseTheSameTurkishOpenCommandRoute() {
        val typed = TurkishCommandRouter.route("WhatsApp'ı aç")
        val recognizedSpeech = TurkishCommandRouter.route("WhatsApp'ı aç")
        assertEquals(RoutedCommand.OpenApp("WhatsApp"), typed)
        assertEquals(typed, recognizedSpeech)
    }

    @Test
    fun launchResultMessagesAreTurkishAndDoNotClaimVerifiedForeground() {
        val message = AppLaunchMessages.forResult(AppLaunchResult.LaunchRequested(target, "com.whatsapp"))
        assertTrue(message.contains("açma isteği"))
        assertTrue(!message.contains("açıldı"))
        assertTrue(AppLaunchMessages.forResult(AppLaunchResult.BackgroundRestricted(target)).contains("arka planda"))
    }
    @Test
    fun reportsGenericAndroidLaunchFailureInTurkish() {
        val target = AppLaunchRegistry.find("whatsapp")!!
        val result = AppLaunchDispatcher(
            FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.Failed))
        ).dispatch("WhatsApp")
        assertTrue(result is AppLaunchResult.LaunchFailed)
        assertTrue(AppLaunchMessages.forResult(result).contains("açılamadı"))
    }

}
