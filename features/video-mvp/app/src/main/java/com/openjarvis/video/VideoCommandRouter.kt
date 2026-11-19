package com.openjarvis.video

enum class VideoCommandOrigin {
    TYPED,
    RECOGNIZED_SPEECH,
    TASK
}

data class VideoCommandIntent(
    val topic: String?,
    val origin: VideoCommandOrigin
)

/** Deterministic Turkish CREATE_VIDEO intent parser shared by typed, speech and task replay paths. */
object VideoCommandRouter {
    private val videoFirstPattern = Regex(
        """(?iu)\b(?:bir\s+)?(?:video\w*|shorts?\w*|reels\w*)\s+(?:oluştur|olustur|hazırla|hazirla|yap|üret|uret)(?:alım|elim|yalım|yelim|un|ın|in|yın|yin|abilir|ebilir|yabilir|yebilir|abilirim|ar|ır|ir|ur|ür|acak|ecek|mak|mek|r)?(?:\s+(?:musun|mısın|misin|mısınız|misiniz|lütfen))?\b"""
    )
    private val actionFirstPattern = Regex(
        """(?iu)\b(?:oluştur|olustur|hazırla|hazirla|yap|üret|uret)(?:alım|elim|yalım|yelim|un|ın|in|yın|yin|abilir|ebilir|yabilir|yebilir|abilirim|ar|ır|ir|ur|ür|acak|ecek|mak|mek|r)?\s+(?:bir\s+)?(?:video\w*|shorts?\w*|reels\w*)\b"""
    )
    private val leadingFiller = Regex("""(?iu)^\s*(?:(?:lütfen|bana)\s+)+""")
    private val trailingFiller = Regex("""(?iu)\s+(?:hakkında|konulu|konusunda|için|lütfen|musun|mısın|misin|mısınız|misiniz)\s*$""")
    private val leadingPunctuation = Regex("""^[\s:;,.!?…]+""")
    private val trailingPunctuation = Regex("""[\s:;,.!?…]+$""")
    private val whitespace = Regex("""\s+""")

    fun parse(text: String, origin: VideoCommandOrigin): VideoCommandIntent? {
        val input = text.trim()
        if (input.isEmpty()) return null
        if (!videoFirstPattern.containsMatchIn(input) &&
            !actionFirstPattern.containsMatchIn(input)
        ) return null

        var topic = input
        topic = videoFirstPattern.replace(topic, " ")
        topic = actionFirstPattern.replace(topic, " ")
        topic = leadingFiller.replace(topic, "")
        topic = leadingPunctuation.replace(topic, "")
        topic = trailingPunctuation.replace(topic, "")
        topic = trailingFiller.replace(topic, "")
        topic = leadingPunctuation.replace(topic, "")
        topic = trailingPunctuation.replace(topic, "")
        topic = whitespace.replace(topic, " ").trim()
        return VideoCommandIntent(
            topic = topic.takeIf { it.isNotBlank() }?.take(120),
            origin = origin
        )
    }
}
