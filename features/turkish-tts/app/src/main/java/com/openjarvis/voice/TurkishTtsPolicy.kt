package com.openjarvis.voice

import java.util.Locale

/** A small, platform-independent description of an installed Android TTS voice. */
data class TurkishTtsVoiceCandidate(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val latency: Int,
    val requiresNetwork: Boolean
)

sealed class TurkishTtsStatus {
    object Initializing : TurkishTtsStatus()
    data class Ready(val voiceLocale: String, val offline: Boolean) : TurkishTtsStatus()
    object TurkishVoiceMissing : TurkishTtsStatus()
    object InitializationFailed : TurkishTtsStatus()
    object Released : TurkishTtsStatus()
}

/** Deterministic Turkish-first voice selection and user-facing status text. */
object TurkishTtsPolicy {
    const val LANGUAGE_AVAILABLE = 0
    const val COUNTRY_AVAILABLE = 1
    const val COUNTRY_VARIANT_AVAILABLE = 2

    const val DEFAULT_SPEECH_RATE = 0.96f
    const val DEFAULT_PITCH = 1.0f

    fun isTurkishLocaleTag(localeTag: String): Boolean {
        return Locale.forLanguageTag(localeTag).language.equals("tr", ignoreCase = true)
    }

    fun isExactTurkishLocale(localeTag: String): Boolean {
        val locale = Locale.forLanguageTag(localeTag)
        return locale.language.equals("tr", ignoreCase = true) &&
            locale.country.equals("TR", ignoreCase = true)
    }

    /** Android TextToSpeech language availability values 0, 1 and 2 are supported. */
    fun isLanguageAvailable(result: Int): Boolean = result in LANGUAGE_AVAILABLE..COUNTRY_VARIANT_AVAILABLE

    fun requiresNetwork(features: Set<String>): Boolean = features.any {
        it.equals("network", ignoreCase = true) || it.equals("networkTts", ignoreCase = true)
    }

    /**
     * Prefer exact tr-TR, then offline synthesis, higher quality, lower latency,
     * and finally a stable name tie-breaker. Non-Turkish voices are never eligible.
     */
    fun selectVoice(candidates: Iterable<TurkishTtsVoiceCandidate>): TurkishTtsVoiceCandidate? {
        return candidates
            .filter { isTurkishLocaleTag(it.localeTag) }
            .sortedWith(
                compareByDescending<TurkishTtsVoiceCandidate> { isExactTurkishLocale(it.localeTag) }
                    .thenBy { it.requiresNetwork }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .firstOrNull()
    }

    fun messageFor(status: TurkishTtsStatus): String = when (status) {
        TurkishTtsStatus.Initializing -> "Türkçe ses hazırlanıyor."
        is TurkishTtsStatus.Ready -> "Türkçe ses hazır (${status.voiceLocale})."
        TurkishTtsStatus.TurkishVoiceMissing ->
            "Bu cihazda kullanılabilir Türkçe ses bulunamadı. Android ses ayarlarından Türkçe ses verisini yükleyebilirsin."
        TurkishTtsStatus.InitializationFailed ->
            "Android metin okuma motoru başlatılamadı. Metin okuma ayarlarını kontrol edip tekrar dene."
        TurkishTtsStatus.Released -> "Türkçe ses motoru kapatıldı."
    }
}
