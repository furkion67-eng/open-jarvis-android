package com.openjarvis.ui.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

/**
 * Debug-build-only host used by the embedded-player instrumentation test.
 *
 * The repair script declares this Activity only in the extracted host's src/debug manifest.
 * It is not declared by the production manifest and cannot bypass the user's normal device gates.
 */
class DashboardPlayerTestActivity : ComponentActivity() {
    companion object {
        @Volatile
        var current: DashboardPlayerTestActivity? = null
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContent {
            MaterialTheme {
                VideoArtifactPlayerPanel()
            }
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
