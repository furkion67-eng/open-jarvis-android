package com.openjarvis.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoryPlanTest {

    @Test
    fun istanbulConquestCreatesFiveScenesAndFortyFiveSecondShortsDraft() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        assertEquals(5, plan.scenes.size)
        assertEquals(45, plan.renderSpec.durationSeconds)
        assertEquals(5, plan.captions.size)
        assertEquals(0L, plan.captions.first().startMs)
        assertEquals(45_000L, plan.captions.last().endMs)
        assertTrue(plan.narrationText.contains("29 Mayıs 1453"))
        plan.renderSpec.validate()
    }

    @Test
    fun istanbulScenesUseDistinctLocalProceduralVisuals() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        assertEquals(
            listOf(
                VideoVisualStyle.CITY_WALLS,
                VideoVisualStyle.CANNON_SIEGE,
                VideoVisualStyle.SHIPS,
                VideoVisualStyle.SUNRISE,
                VideoVisualStyle.CITY_MAP
            ),
            plan.scenes.map { it.visualStyle }
        )
        assertTrue(plan.sourceLabel.contains("prosedürel çizimler"))
    }

    @Test
    fun captionsAreMonotonicAndSrtUsesTurkishText() {
        val plan = VideoStoryPlanBuilder.build("1453")
        plan.captions.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMs, right.startMs)
        }
        val srt = plan.toSrt()
        assertTrue(srt.contains("00:00:00,000 --> 00:00:09,000"))
        assertTrue(srt.contains("Fatih Sultan Mehmet"))
        assertTrue(srt.contains("00:00:36,000 --> 00:00:45,000"))
    }

    @Test
    fun genericTopicUsesStructureWithoutInventingSpecificFacts() {
        val plan = VideoStoryPlanBuilder.build("Kedilerin bakımı")
        assertTrue(plan.sourceLabel.startsWith("Genel yerel şablon"))
        assertTrue(plan.scenes.first().caption.contains("Kedilerin bakımı"))
        assertTrue(plan.scenes.none { it.caption.contains("1453") })
        assertTrue(plan.scenes.all { it.visualStyle == VideoVisualStyle.GENERIC })
    }


    @Test
    fun speechFrameBoundariesSelectTheMatchingVisualScene() {
        val spec = VideoRenderSpec(
            title = "Konuşma zaman çizelgesi",
            scenes = listOf(
                VideoScene("Bir", "İlk sahne"),
                VideoScene("İki", "İkinci sahne"),
                VideoScene("Üç", "Üçüncü sahne")
            ),
            framesPerSecond = 24,
            sceneFrameDurations = listOf(164, 135, 180)
        )

        assertEquals(479, spec.frameCount)
        assertEquals(20, spec.durationSeconds)
        assertEquals(0, spec.sceneIndexForFrame(163))
        assertEquals(1, spec.sceneIndexForFrame(164))
        assertEquals(1, spec.sceneIndexForFrame(298))
        assertEquals(2, spec.sceneIndexForFrame(299))
        assertEquals(2, spec.sceneIndexForFrame(478))
        spec.validate()
    }

    @Test
    fun blankPromptIsRejected() {
        val error = runCatching { VideoStoryPlanBuilder.build("   ") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
