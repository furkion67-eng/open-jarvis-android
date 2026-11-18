package com.openjarvis.video

import kotlin.math.abs

data class VideoScene(val heading: String, val caption: String)

data class VideoRenderSpec(
    val title: String,
    val scenes: List<VideoScene>,
    val width: Int = 360,
    val height: Int = 640,
    val framesPerSecond: Int = 24,
    val secondsPerScene: Int = 2,
    val bitRate: Int = 900_000
) {
    val durationSeconds: Int get() = scenes.size * secondsPerScene
    val frameCount: Int get() = durationSeconds * framesPerSecond
    val estimatedOutputBytes: Long get() = bitRate.toLong() * durationSeconds / 8L * 2L

    fun validate() {
        require(title.isNotBlank()) { "Video başlığı boş olamaz." }
        require(title.length <= MAX_TITLE_LENGTH) { "Video başlığı çok uzun." }
        require(scenes.size in MIN_SCENES..MAX_SCENES) { "Video 1–6 sahne içermeli." }
        require(scenes.all { it.heading.isNotBlank() && it.heading.length <= MAX_HEADING_LENGTH }) {
            "Her sahnenin başlığı 1–80 karakter olmalı."
        }
        require(scenes.all { it.caption.length <= MAX_CAPTION_LENGTH }) {
            "Sahne açıklamaları 180 karakteri aşamaz."
        }
        require(width in MIN_WIDTH..MAX_WIDTH && height in MIN_HEIGHT..MAX_HEIGHT) {
            "Video çözünürlüğü desteklenen sınırların dışında."
        }
        require(width % 2 == 0 && height % 2 == 0 && width < height) {
            "Dikey video için genişlik ve yükseklik çift sayı olmalı."
        }
        require(abs(width.toFloat() / height.toFloat() - PORTRAIT_RATIO) <= MAX_RATIO_DELTA) {
            "İlk sürüm yalnızca 9:16 dikey video üretir."
        }
        require(framesPerSecond in MIN_FPS..MAX_FPS) { "Kare hızı 15–30 FPS aralığında olmalı." }
        require(secondsPerScene in 1..MAX_SECONDS_PER_SCENE) { "Sahne süresi 1–5 saniye olmalı." }
        require(durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            "İlk prototip 3–20 saniye arasında video üretir."
        }
        require(frameCount <= MAX_FRAMES) { "Video kare sayısı cihaz sınırını aşıyor." }
        require(bitRate in MIN_BIT_RATE..MAX_BIT_RATE) { "Video bit hızı desteklenen sınırların dışında." }
        require(estimatedOutputBytes <= MAX_OUTPUT_BYTES) { "Tahmini video boyutu 24 MB sınırını aşıyor." }
    }

    companion object {
        const val MAX_TITLE_LENGTH = 100
        const val MAX_HEADING_LENGTH = 80
        const val MAX_CAPTION_LENGTH = 180
        const val MIN_SCENES = 1
        const val MAX_SCENES = 6
        const val MIN_WIDTH = 240
        const val MAX_WIDTH = 720
        const val MIN_HEIGHT = 426
        const val MAX_HEIGHT = 1280
        const val MIN_FPS = 15
        const val MAX_FPS = 30
        const val MIN_DURATION_SECONDS = 3
        const val MAX_DURATION_SECONDS = 20
        const val MAX_SECONDS_PER_SCENE = 5
        const val MAX_FRAMES = 600
        const val MIN_BIT_RATE = 300_000
        const val MAX_BIT_RATE = 3_000_000
        const val MAX_OUTPUT_BYTES = 24L * 1024L * 1024L
        const val MIN_FREE_SPACE_MARGIN_BYTES = 2L * 1024L * 1024L
        private const val PORTRAIT_RATIO = 9f / 16f
        private const val MAX_RATIO_DELTA = 0.015f

        fun sample(topic: String): VideoRenderSpec {
            val cleanTopic = topic.trim().take(MAX_TITLE_LENGTH)
            return VideoRenderSpec(
                title = cleanTopic,
                scenes = listOf(
                    VideoScene("Konu", cleanTopic),
                    VideoScene("Yerel video taslağı", "Bu sahne cihaz üzerinde oluşturuldu."),
                    VideoScene("Sonraki aşama", "Ses, görseller ve altyazı henüz eklenmedi.")
                )
            )
        }
    }
}
