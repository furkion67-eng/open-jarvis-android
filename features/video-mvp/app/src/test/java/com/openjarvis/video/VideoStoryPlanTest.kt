package com.openjarvis.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoryPlanTest {

    @Test
    fun istanbulConquestCreatesFiveSourceBackedScenesAndFortyFiveSecondDraft() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        assertEquals(5, plan.scenes.size)
        assertEquals(45, plan.renderSpec.durationSeconds)
        assertEquals(720, plan.renderSpec.width)
        assertEquals(1_280, plan.renderSpec.height)
        assertEquals(1_500_000, plan.renderSpec.bitRate)
        assertEquals(6, plan.captions.size)
        assertEquals(0L, plan.captions.first().startMs)
        assertEquals(2_000L, plan.captions.first().endMs)
        assertEquals(plan.hookText, plan.captions.first().text)
        assertTrue(plan.scenes.first().heading != plan.hookText)
        assertTrue(plan.scenes.first().heading.contains("zincir", ignoreCase = true))
        assertTrue(plan.narrationText.startsWith(plan.hookText))
        assertEquals(plan.scenes.first().caption, plan.captions[1].text)
        assertEquals(45_000L, plan.captions.last().endMs)
        assertTrue(plan.narrationText.contains("29 Mayıs 1453"))
        assertEquals(5, plan.claimSources.size)
        assertTrue(plan.claimSources.all { it.sourceUrl.startsWith("https://") })
        plan.renderSpec.copy(requireLicensedVisualAssets = false).validate()
    }

    @Test
    fun mimarSinanCreatesFiveSourceBackedScenesAndFortyFiveSecondDraft() {
        val plan = VideoStoryPlanBuilder.build("Mimar Sinan")
        assertEquals(5, plan.scenes.size)
        assertEquals(45, plan.renderSpec.durationSeconds)
        assertEquals(720, plan.renderSpec.width)
        assertEquals(1_280, plan.renderSpec.height)
        assertEquals(6, plan.captions.size)
        assertEquals("Süleymaniye yedi yılda!", plan.hookText)
        assertEquals("sinan-suleymaniye", plan.scenes.first().sceneId)
        assertEquals("commons-sinan-suleymaniye-exterior", plan.scenes.first().visualAssetId)
        assertEquals(0L, plan.captions.first().startMs)
        assertEquals(2_000L, plan.captions.first().endMs)
        assertEquals(plan.hookText, plan.captions.first().text)
        assertTrue(plan.narrationText.startsWith(plan.hookText))
        assertTrue(plan.narrationText.contains("1548"))
        assertTrue(plan.narrationText.contains("15 Ekim 1557"))
        assertTrue(plan.narrationText.contains("1588"))
        assertEquals(5, plan.claimSources.size)
        val suleymaniyeClaim = plan.claimSources.single { it.claimId == "sinan-journeyman" }
        assertEquals(
            "https://islamansiklopedisi.org.tr/suleymaniye-camii-ve-kulliyesi",
            suleymaniyeClaim.sourceUrl
        )
        assertTrue(suleymaniyeClaim.supportedClaim.contains("15 Ekim 1557"))
        assertTrue(plan.claimSources.filterNot { it.claimId == "sinan-journeyman" }.all {
            it.sourceUrl == "https://islamansiklopedisi.org.tr/sinan" &&
                it.publisher == "TDV İslâm Ansiklopedisi"
        })
        assertEquals(5, plan.scenes.map { it.visualAssetId }.distinct().size)
        assertEquals(5, plan.scenes.map { it.sceneId }.distinct().size)
        assertTrue(plan.scenes.all { it.claimIds.isNotEmpty() && it.visualAssetId != null })
        assertTrue(plan.sourceLabel.contains("CC BY-SA 4.0"))
        plan.renderSpec.copy(requireLicensedVisualAssets = false).validate()
    }

    @Test
    fun mimarSinanAliasesResolveTheSameCuratedStory() {
        val canonical = VideoStoryPlanBuilder.build("Mimar Sinan")
        listOf("Şehzade Camii", "Süleymaniye", "Selimiye").forEach { alias ->
            val plan = VideoStoryPlanBuilder.build(alias)
            assertEquals(canonical.title, plan.title)
            assertEquals(canonical.scenes.map { it.sceneId }, plan.scenes.map { it.sceneId })
        }
    }

    @Test
    fun storyQualityRejectsAFirstSceneHeadingThatRepeatsTheSeparateHook() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        val repeatedHeading = plan.hookText.removeSuffix("!")
        val invalid = plan.copy(
            scenes = plan.scenes.toMutableList().also { scenes ->
                scenes[0] = scenes[0].copy(heading = repeatedHeading)
            }
        )
        assertTrue(runCatching { VideoStoryQualityPolicy.validate(invalid) }.isFailure)
    }

    @Test
    fun storyQualityRejectsUntrustedResearchHostPublisherAndInvalidCheckDate() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        val first = plan.claimSources.first()
        val invalidSources = listOf(
            first.copy(sourceUrl = "https://islamansiklopedisi.org.tr.evil.example/halic"),
            first.copy(publisher = "Unverified publisher"),
            first.copy(checkedOn = "2026-02-31"),
            first.copy(checkedOn = "2099-01-01")
        )
        invalidSources.forEach { invalid ->
            val invalidPlan = plan.copy(
                claimSources = plan.claimSources.map {
                    if (it.claimId == first.claimId) invalid else it
                }
            )
            assertTrue(runCatching { VideoStoryQualityPolicy.validate(invalidPlan) }.isFailure)
        }
    }

    @Test
    fun storyQualityRejectsTheOpeningHookRepeatedByAnyLaterSceneHeading() {
        val plan = VideoStoryPlanBuilder.build("1453")
        val invalid = plan.copy(
            scenes = plan.scenes.toMutableList().also { scenes ->
                scenes[4] = scenes[4].copy(heading = plan.hookText.removeSuffix("!"))
            }
        )
        assertTrue(runCatching { VideoStoryQualityPolicy.validate(invalid) }.isFailure)
    }

    @Test
    fun storyQualityRejectsSceneHeadingsRepeatedWithDifferentPunctuation() {
        val plan = VideoStoryPlanBuilder.build("1453")
        val invalid = plan.copy(
            scenes = plan.scenes.toMutableList().also { scenes ->
                scenes[1] = scenes[1].copy(heading = scenes[0].heading + "!!!")
            }
        )
        assertTrue(runCatching { VideoStoryQualityPolicy.validate(invalid) }.isFailure)
    }

    @Test
    fun storyQualityRejectsSceneCaptionsRepeatedWithDifferentPunctuation() {
        val plan = VideoStoryPlanBuilder.build("1453")
        val invalid = plan.copy(
            scenes = plan.scenes.toMutableList().also { scenes ->
                scenes[1] = scenes[1].copy(caption = scenes[0].caption + "!!!")
            }
        )
        assertTrue(runCatching { VideoStoryQualityPolicy.validate(invalid) }.isFailure)
    }

    @Test
    fun storyQualityRejectsResearchClaimsNotBoundToAnyScene() {
        val plan = VideoStoryPlanBuilder.build("1453")
        val orphan = plan.claimSources.first().copy(
            claimId = "orphan-research-claim",
            supportedClaim = "Bu kaynak hiçbir sahnede kullanılmamalı."
        )
        assertTrue(
            runCatching {
                VideoStoryQualityPolicy.validate(plan.copy(claimSources = plan.claimSources + orphan))
            }.isFailure
        )
    }

    @Test
    fun everySceneHasDistinctSourceBackedVisualAndClaim() {
        val plan = VideoStoryPlanBuilder.build("1453")
        assertEquals(5, plan.scenes.map { it.visualAssetId }.distinct().size)
        assertEquals(5, plan.scenes.map { it.sceneId }.distinct().size)
        assertTrue(plan.scenes.all { it.visualAssetId != null && it.claimIds.isNotEmpty() })
        assertTrue(plan.scenes.any { it.caption.contains("farklılaşıyor") })
        assertTrue(plan.sourceLabel.contains("Wikimedia Commons"))
    }

    @Test
    fun productionRenderSpecFailsClosedUntilEverySceneHasAResolvedLicensedImage() {
        val unresolved = VideoStoryPlanBuilder.build("İstanbul'un Fethi").renderSpec
        assertTrue(runCatching { unresolved.validate() }.isFailure)
    }

    @Test
    fun firstHookIsShortAndMustFitTheTwoSecondWindow() {
        val plan = VideoStoryPlanBuilder.build("İstanbul'un Fethi")
        assertTrue(plan.hookText.split(Regex("\\s+")).size <= 4)
        VideoStoryQualityPolicy.validateHookDuration(1_850L)
        assertTrue(runCatching { VideoStoryQualityPolicy.validateHookDuration(2_001L) }.isFailure)
    }

    @Test
    fun unsupportedTopicFailsClosedInsteadOfUsingGenericFiller() {
        val error = runCatching { VideoStoryPlanBuilder.build("Kedilerin bakımı") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("lisanslı") == true)
    }

    @Test
    fun captionsAreMonotonicAndSrtUsesTurkishText() {
        val plan = VideoStoryPlanBuilder.build("1453")
        plan.captions.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMs, right.startMs)
        }
        val srt = plan.toSrt()
        assertTrue(srt.contains("00:00:00,000 --> 00:00:02,000"))
        assertTrue(srt.contains("Gemiler karadan Haliç'e!"))
        assertTrue(srt.contains("29 Mayıs 1453"))
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
