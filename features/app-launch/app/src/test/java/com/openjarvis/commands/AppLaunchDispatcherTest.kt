package com.openjarvis.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchDispatcherTest {
    private val target = AppLaunchRegistry.find("whatsapp")!!

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
    fun ambiguousNormalizedAliasesFailClosedBeforePlatformAccess() {
        val candidates = listOf(
            AppLaunchTarget(
                "Birinci uygulama",
                setOf("ortak uygulama"),
                listOf("com.example.first")
            ),
            AppLaunchTarget(
                "İkinci uygulama",
                setOf("ortak-uygulama"),
                listOf("com.example.second")
            )
        )
        val fake = FakePlatform(
            mapOf(
                "com.example.first" to AppLaunchAttempt.Started,
                "com.example.second" to AppLaunchAttempt.Started
            )
        )

        val result = AppLaunchDispatcher(fake, candidates).dispatch("ortak uygulama")

        assertTrue(result is AppLaunchResult.Unsupported)
        assertTrue("Ambiguous aliases must not reach PackageManager", fake.calls.isEmpty())
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
    fun typedAndRecognizedSpeechEntryAdaptersUseTheSameAllowlistedDispatcher() {
        val fake = FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.Started))
        val dispatcher = AppLaunchDispatcher(fake)
        val input = AppLaunchInputAdapter(AppLaunchCommandEntryPoint(dispatcher))

        val typed = input.fromTypedInput("WhatsApp'ı aç")
        val spoken = input.fromRecognizedSpeech("WhatsApp'ı aç")

        assertTrue(typed is AppLaunchResult.LaunchRequested)
        assertTrue(spoken is AppLaunchResult.LaunchRequested)
        assertEquals(listOf("com.whatsapp", "com.whatsapp"), fake.calls)
    }

    @Test
    fun turkishInflectedOpenCommandsResolveToCanonicalAllowlistedTargets() {
        val cases = listOf(
            "WhatsApp'ı aç" to "WhatsApp",
            "YouTube'u çalıştır" to "YouTube"
        )
        for ((utterance, expectedName) in cases) {
            val routed = TurkishCommandRouter.route(utterance)
            assertEquals(RoutedCommand.OpenApp(expectedName), routed)
            assertEquals(expectedName, AppLaunchRegistry.find((routed as RoutedCommand.OpenApp).appName)?.displayName)
        }
    }

    @Test
    fun rejectsUnknownAndRawPackageIdentifiersBeforePlatformAccess() {
        val fake = FakePlatform(mapOf(
            "com.whatsapp" to AppLaunchAttempt.Started,
            "com.attacker.app" to AppLaunchAttempt.Started
        ))
        val dispatcher = AppLaunchDispatcher(fake)
        for (raw in listOf("com.whatsapp", "com.attacker.app", "rastgele uygulama")) {
            assertTrue(dispatcher.dispatch(raw) is AppLaunchResult.Unsupported)
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun nonAppExternalCommandsDoNotEnterTheAppLaunchDispatcher() {
        val fake = FakePlatform(mapOf("com.whatsapp" to AppLaunchAttempt.Started))
        val entry = AppLaunchCommandEntryPoint(AppLaunchDispatcher(fake))
        assertEquals(null, entry.dispatch("Sıla'ya mesaj gönder"))
        assertEquals(null, entry.dispatch("annemi ara"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun launchResultMessagesAreTurkishAndDoNotClaimVerifiedForeground() {
        val message = AppLaunchMessages.forResult(AppLaunchResult.LaunchRequested(target, "com.whatsapp"))
        assertTrue(message.contains("açma isteği"))
        assertFalse(message.contains("açıldı"))
        val backgroundMessage = AppLaunchMessages.forResult(AppLaunchResult.BackgroundRestricted(target))
        assertTrue(backgroundMessage.contains("arka planda"))
        assertTrue(backgroundMessage.contains("ön plana"))
        assertFalse(backgroundMessage.contains("üstte görünme"))
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
