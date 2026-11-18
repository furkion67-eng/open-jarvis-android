package com.openjarvis.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TurkishTtsEngineInstrumentedTest {
    @Test
    fun engineNeverReportsReadyWithANonTurkishVoiceAndCompletesHandoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Context
        lateinit var engine: AndroidTTSEngine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine = AndroidTTSEngine(context)
        }

        val status = runBlocking {
            withTimeout(15_000) {
                engine.status.first { it !is TurkishTtsStatus.Initializing }
            }
        }
        when (status) {
            is TurkishTtsStatus.Ready -> assertTrue(
                "Ready status must always identify a Turkish voice",
                TurkishTtsPolicy.isTurkishLocaleTag(status.voiceLocale)
            )
            TurkishTtsStatus.TurkishVoiceMissing,
            TurkishTtsStatus.InitializationFailed -> Unit // Honest, supported fallback on emulator images without Turkish voice data.
            else -> throw AssertionError("Unexpected TTS state: $status")
        }

        val handoff = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.speak("Merhaba, ben JARVIS.") { handoff.countDown() }
        }
        assertTrue("Speech completion/error must release the STT handoff", handoff.await(15, TimeUnit.SECONDS))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
    }
    @Test
    fun releaseBeforeInitializationDoesNotLeaveSpeechHandoffPending() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Context
        lateinit var engine: AndroidTTSEngine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine = AndroidTTSEngine(context)
            engine.release()
        }

        val handoff = CountDownLatch(1)
        engine.speak("Bu metin okunmayacak.") { handoff.countDown() }
        assertTrue("A released engine must complete the caller handoff", handoff.await(1, TimeUnit.SECONDS))
        assertTrue(engine.status.value == TurkishTtsStatus.Released)
    }

    @Test
    fun repeatedRepliesCompleteTheLatestHandoffOnlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Context
        lateinit var engine: AndroidTTSEngine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine = AndroidTTSEngine(context)
        }
        runBlocking {
            withTimeout(15_000) {
                engine.status.first { it !is TurkishTtsStatus.Initializing }
            }
        }

        val latest = CountDownLatch(1)
        val latestCallbacks = AtomicInteger(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.speak("Önceki yanıt") { }
            engine.speak("Son yanıt") {
                latestCallbacks.incrementAndGet()
                latest.countDown()
            }
        }
        assertTrue("The latest utterance must release the STT handoff", latest.await(15, TimeUnit.SECONDS))
        assertTrue("The latest callback must be delivered once", latestCallbacks.get() == 1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
    }

}
