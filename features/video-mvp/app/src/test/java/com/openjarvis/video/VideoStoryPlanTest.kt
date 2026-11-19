package com.openjarvis.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoryPlanTest {

    @Test
    fun istanbulConquestCreatesFiveScenesAndTwentySecondsOfCaptions() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        assertEquals(5, plan.scenes.size)
        assertEquals(20, plan.renderSpec.durationSeconds)
        assertEquals(5, plan.captions.size)
        assertEquals(0L, plan.captions.first().startMs)
        assertEquals(20_000L, plan.captions.last().endMs)
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
        assertTrue(srt.contains("00:00:00,000 --> 00:00:04,000"))
        assertTrue(srt.contains("Fatih Sultan Mehmet"))
        assertTrue(srt.contains("00:00:16,000 --> 00:00:20,000"))
    }

    @Test
    fun genericTopicUsesStructureWithoutInventingSpecificFacts() {
        val plan = VideoStoryPlanBuilder.build("Kedilerin bakımı")
        assertEquals("Genel yerel şablon", plan.sourceLabel)
        assertTrue(plan.scenes.first().caption.contains("Kedilerin bakımı"))
        assertTrue(plan.scenes.none { it.caption.contains("1453") })
        assertTrue(plan.scenes.all { it.visualStyle == VideoVisualStyle.GENERIC })
    }

    @Test
    fun blankPromptIsRejected() {
        val error = runCatching { VideoStoryPlanBuilder.build("   ") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
