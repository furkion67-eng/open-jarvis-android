package com.openjarvis.tasks

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application-lifetime bridge from confirmed device actions to the durable Room journal. */
object DeviceActionOutcomeJournal {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(context: Context, actionType: String, outcomeCode: String, safeSummary: String) {
        ioScope.launch {
            runCatching {
                JarvisTaskRepository.getInstance(context.applicationContext)
                    .recordDeviceActionOutcome(actionType, outcomeCode, safeSummary)
            }
        }
    }
}
