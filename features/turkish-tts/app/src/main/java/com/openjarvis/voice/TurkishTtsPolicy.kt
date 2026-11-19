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

    private const val EXACT_TURKISH_LOCALE_TAG = "tr-TR"

    fun isTurkishLocaleTag(localeTag: String): Boolean {
        return Locale.forLanguageTag(localeTag).language.equals("tr", ignoreCase = true)
    }

    /**
     * Accept only the exact tr-TR tag, allowing case differences only.
     *
     * Do not parse and re-canonicalize arbitrary input here: Locale.forLanguageTag is
     * deliberately tolerant and may silently truncate malformed trailing subtags
     * (for example, "tr-TR-" or "tr-TR-foo!"), turning invalid input into "tr-TR".
     * Exact equality after case normalization rejects variants, extensions, malformed
     * tags, underscores and surrounding whitespace before they can be selected or
     * reported as the actual engine voice.
     */
    fun isExactTurkishLocale(localeTag: String): Boolean {
        return localeTag.equals(EXACT_TURKISH_LOCALE_TAG, ignoreCase = true)
    }

    /** Returns a canonical locale only when the actual engine reports exact tr-TR; null fails closed. */
    fun exactActualEngineLocale(localeTag: String?): String? {
        if (localeTag.isNullOrBlank() || !isExactTurkishLocale(localeTag)) return null
        return EXACT_TURKISH_LOCALE_TAG
    }

    /** Android TextToSpeech language availability values 0, 1 and 2 are supported. */
    fun isLanguageAvailable(result: Int): Boolean = result in LANGUAGE_AVAILABLE..COUNTRY_VARIANT_AVAILABLE

    fun requiresNetwork(features: Set<String>): Boolean = features.any {
        it.equals("network", ignoreCase = true) || it.equals("networkTts", ignoreCase = true)
    }

    /**
     * Prefer exact tr-TR, then offline synthesis, higher quality, lower latency,
     * and finally a stable name tie-breaker. Non-exact Turkish voices are never eligible.
     */
    fun selectVoice(candidates: Iterable<TurkishTtsVoiceCandidate>): TurkishTtsVoiceCandidate? {
        return candidates
            .filter { isExactTurkishLocale(it.localeTag) }
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
