package com.openjarvis.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
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
    fun speakAfterReleaseDoesNotRestartStt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Context
        lateinit var engine: AndroidTTSEngine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine = AndroidTTSEngine(context)
            engine.release()
        }

        val handoff = CountDownLatch(1)
        engine.speak("Bu metin okunmayacak.") { handoff.countDown() }
        assertFalse("A released engine must not restart STT from a stale callback", handoff.await(300, TimeUnit.MILLISECONDS))
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


    @Test
    fun releaseDuringCurrentUtteranceDoesNotRestartSttAfterTeardown() {
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
        Assume.assumeTrue("A Turkish voice is required to exercise an in-flight utterance", status is TurkishTtsStatus.Ready)

        val callbacks = AtomicInteger(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.speak("Bu uzun Türkçe cümle, etkinlik kapanırken bekleyen konuşma callback'inin dinlemeyi yeniden başlatmamasını doğrular.") {
                callbacks.incrementAndGet()
            }
            engine.release()
        }
        Thread.sleep(300)
        assertTrue("Release must not invoke a stale STT handoff", callbacks.get() == 0)
        assertTrue(engine.status.value == TurkishTtsStatus.Released)
    }

    @Test
    fun stopDuringCurrentUtteranceCompletesHandoffExactlyOnce() {
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
        Assume.assumeTrue("A Turkish voice is required to exercise an in-flight utterance", status is TurkishTtsStatus.Ready)

        val handoff = CountDownLatch(1)
        val callbacks = AtomicInteger(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.speak("Dinleme güvenli biçimde yeniden başlayabilsin diye bu konuşma durduruluyor.") {
                callbacks.incrementAndGet()
                handoff.countDown()
            }
            engine.stop()
        }

        assertTrue("Stop must complete the pending STT handoff", handoff.await(2, TimeUnit.SECONDS))
        assertTrue("Stop must deliver the handoff exactly once", callbacks.get() == 1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
    }
}
