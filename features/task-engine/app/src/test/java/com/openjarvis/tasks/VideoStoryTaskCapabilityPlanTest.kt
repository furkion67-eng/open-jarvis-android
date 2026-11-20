package com.openjarvis.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoryTaskCapabilityPlanTest {
    private val capability = VideoStoryTaskCapability()

    @Test
    fun supportedResearchedStoryPackIsValidatedBeforePreview() = runBlocking {
        val plan = capability.buildPlan("İstanbul'un Fethi hakkında video oluştur")

        assertTrue(plan.requiresNetwork)
        assertTrue(plan.summary.contains("İstanbul'un Fethi"))
        assertTrue(plan.steps.any { it.contains("Wikimedia Commons") })
        assertTrue(plan.steps.any { it.contains("SHA-256") })
        assertFalse(plan.steps.any { it.contains("prosedürel", ignoreCase = true) })
    }

    @Test
    fun unsupportedTopicIsRejectedBeforeUserCanAcceptPlan() = runBlocking {
        val error = runCatching {
            capability.buildPlan("Mars'ta yaşam hakkında video oluştur")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun commandWithoutTopicIsRejectedBeforePreview() = runBlocking {
        val error = runCatching {
            capability.buildPlan("video oluştur")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
