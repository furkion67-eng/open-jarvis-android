package com.openjarvis.ui.player

import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoArtifactArchiveSelectionTest {
    @Test
    fun firstVisibleVerifiedArtifactIsSelectedWhenNothingWasPreviouslySelected() {
        val newer = artifact("newer", updatedAt = 200L)
        val older = artifact("older", updatedAt = 100L)

        assertEquals(
            newer.id,
            resolveVideoArchiveSelection(selectedId = null, visiblePage = listOf(newer, older))
        )
    }

    @Test
    fun olderSelectionIsRetainedWhenTheArchiveMovesToAnotherPage() {
        val newerPage = listOf(artifact("newer", updatedAt = 200L))
        val selectedOlderId = "older"

        assertEquals(
            selectedOlderId,
            resolveVideoArchiveSelection(selectedId = selectedOlderId, visiblePage = newerPage)
        )
    }

    @Test
    fun emptyArchiveDoesNotInventASelection() {
        assertNull(resolveVideoArchiveSelection(selectedId = null, visiblePage = emptyList()))
    }

    private fun artifact(id: String, updatedAt: Long) = JarvisTaskEntity(
        id = id,
        goal = "Video " + id,
        status = JarvisTaskStatus.SUCCEEDED.storageValue,
        updatedAt = updatedAt,
        idempotencyKey = "archive-key-" + id,
        videoUri = "content://media/external/video/media/" + id,
        captionsUri = "content://media/external/file/" + id,
        videoArtifactMetadataJson = "{\"artifact\":\"" + id + "\"}"
    )
}
