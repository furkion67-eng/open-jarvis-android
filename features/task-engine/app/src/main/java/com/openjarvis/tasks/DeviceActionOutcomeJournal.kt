package com.openjarvis.tasks

import android.content.Context

/**
 * Awaitable bridge from a confirmed device action to the durable Room journal.
 *
 * The call returns only after Room has committed the event. Persistence errors propagate to
 * the caller so the UI can report them separately without changing or replaying the action.
 */
object DeviceActionOutcomeJournal {
    suspend fun record(
        context: Context,
        actionType: DeviceActionType,
        outcomeCode: DeviceActionOutcomeCode
    ): String {
        val repository = JarvisTaskRepository.getInstanceOnIo(context.applicationContext)
        return record(repository, actionType, outcomeCode)
    }

    /** Injectable repository overload keeps the commit-before-return contract instrumentable. */
    internal suspend fun record(
        repository: JarvisTaskRepository,
        actionType: DeviceActionType,
        outcomeCode: DeviceActionOutcomeCode
    ): String = repository.recordDeviceActionOutcome(actionType, outcomeCode)
}
