package com.openjarvis.video

import java.util.Locale

data class TimedVideoCaption(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class VideoStoryPlan(
    val title: String,
    val scenes: List<VideoScene>,
    val narrationText: String,
    val captions: List<TimedVideoCaption>,
    val sourceLabel: String
) {
    val renderSpec: VideoRenderSpec
        get() = VideoRenderSpec(
            title = title,
            scenes = scenes,
            secondsPerScene = SECONDS_PER_SCENE
        )

    fun toSrt(): String = captions.mapIndexed { index, cue ->
        buildString {
            append(index + 1).append('\n')
            append(formatSrtTime(cue.startMs))
                .append(" --> ")
                .append(formatSrtTime(cue.endMs))
                .append('\n')
            append(cue.text).append('\n')
        }
    }.joinToString("\n")

    private fun formatSrtTime(valueMs: Long): String {
        val hours = valueMs / 3_600_000L
        val minutes = (valueMs % 3_600_000L) / 60_000L
        val seconds = (valueMs % 60_000L) / 1_000L
        val millis = valueMs % 1_000L
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            hours,
            minutes,
            seconds,
            millis
        )
    }

    companion object {
        const val SECONDS_PER_SCENE = 4
    }
}

/**
 * Offline storyboard templates. The Istanbul conquest template uses a small curated set of
 * historical milestones; other topics get an explicitly generic structure rather than invented
 * topic-specific facts. Narration text is prepared for the later TTS stage but is not audio.
 */
object VideoStoryPlanBuilder {
    private val istanbulConquestAliases = listOf(
        "istanbul'un fethi",
        "istanbulun fethi",
        "konstantiniyye",
        "1453"
    )

    fun build(prompt: String): VideoStoryPlan {
        val clean = prompt.trim().take(VideoRenderSpec.MAX_TITLE_LENGTH)
        require(clean.isNotBlank()) { "Video konusu boş olamaz." }

        val normalized = clean.lowercase(Locale.forLanguageTag("tr-TR"))
        val scenes = if (istanbulConquestAliases.any(normalized::contains)) {
            istanbulConquestScenes()
        } else {
            genericScenes(clean)
        }

        val captions = scenes.mapIndexed { index, scene ->
            TimedVideoCaption(
                startMs = index * VideoStoryPlan.SECONDS_PER_SCENE * 1_000L,
                endMs = (index + 1) * VideoStoryPlan.SECONDS_PER_SCENE * 1_000L,
                text = scene.caption
            )
        }

        val plan = VideoStoryPlan(
            title = clean,
            scenes = scenes,
            narrationText = scenes.joinToString(" ") { scene ->
                "${scene.heading}. ${scene.caption}"
            },
            captions = captions,
            sourceLabel = if (scenes === ISTANBUL_CONQUEST_SCENES) {
                "Yerel tarih şablonu"
            } else {
                "Genel yerel şablon"
            }
        )
        plan.renderSpec.validate()
        return plan
    }

    private val ISTANBUL_CONQUEST_SCENES = listOf(
        VideoScene(
            "1453: Hedef Konstantiniyye",
            "Fatih Sultan Mehmet, Bizans'ın başkentini almak için hazırlıklarını tamamladı."
        ),
        VideoScene(
            "Kuşatma başladı",
            "6 Nisan 1453'te Osmanlı ordusu surları kuşattı. Şahi topları surlarda gedikler açtı."
        ),
        VideoScene(
            "Haliç'te beklenmedik hamle",
            "Osmanlı gemileri karadan yürütülerek Haliç'e indirildi ve savunma hattı zorlandı."
        ),
        VideoScene(
            "29 Mayıs sabahı",
            "Haftalar süren kuşatmanın ardından 29 Mayıs 1453 sabahı genel hücum başladı."
        ),
        VideoScene(
            "Tarihi sonuç",
            "Şehir Osmanlı hakimiyetine girdi ve imparatorluğun önemli merkezlerinden biri oldu."
        )
    )

    private fun istanbulConquestScenes(): List<VideoScene> = ISTANBUL_CONQUEST_SCENES

    private fun genericScenes(topic: String): List<VideoScene> = listOf(
        VideoScene("Konuya giriş", "$topic konusunu kısa ve anlaşılır biçimde ele alıyoruz."),
        VideoScene("Arka plan", "Konunun ortaya çıktığı koşulları ve temel kavramları tanıyoruz."),
        VideoScene("Öne çıkan gelişmeler", "Önemli gelişmeleri ve kararları sırasıyla inceliyoruz."),
        VideoScene("Dönüm noktası", "Sürecin yönünü değiştiren temel noktaya odaklanıyoruz."),
        VideoScene("Sonuç ve etki", "Başlıca sonuçları ve bugüne uzanan etkileri özetliyoruz.")
    )
}
