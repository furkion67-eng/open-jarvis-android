#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])

def edit(rel, fn):
    p = root / rel
    s = p.read_text()
    p.write_text(fn(s))

def agent(s):
    marker = "\n    suspend fun testConnection(): Result<Long> {"
    if marker not in s:
        raise SystemExit("AgentCore.kt: testConnection marker not found")
    # The packaged source has one extra closing brace at EOF because executeTask()
    # is left open. Move that brace to the correct point before testConnection().
    tail = s.rstrip()
    if tail.endswith("}\n}"):
        s = tail[:-3] + "}"
        s = s.replace(marker, "\n    }\n\n    suspend fun testConnection(): Result<Long> {", 1)
    elif not tail.endswith("}"):
        raise SystemExit("AgentCore.kt: unexpected file ending")
    return s

def mcp(s):
    s = s.replace("import okhttp3.Request\n", "import okhttp3.Request\nimport okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.RequestBody.Companion.toRequestBody\n")
    old = """okhttp3.RequestBody.create(
                    "application/json".toByteArray().to okhttp3.MediaType.get("application/json"),
                    requestBody
                )"""
    return s.replace(old, 'requestBody.toRequestBody("application/json".toMediaType())')

def taskdao(s):
    old = '@Query("SELECT * FROM task_nodes ORDER BY timestamp DESC LIMIT :limit")\n    suspend fun getTasksByCommand(command: String, limit: Int = 10): List<TaskNode>'
    new = '@Query("SELECT * FROM task_nodes WHERE command LIKE \'%\' || :command || \'%\' ORDER BY timestamp DESC LIMIT :limit")\n    suspend fun getTasksByCommand(command: String, limit: Int = 10): List<TaskNode>'
    return s.replace(old, new)

edit("app/src/main/java/com/openjarvis/agent/AgentCore.kt", agent)
edit("app/src/main/java/com/openjarvis/mcp/MCPClient.kt", mcp)
edit("app/src/main/java/com/openjarvis/graphify/nodes/TaskNodeDao.kt", taskdao)

db = """package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val scheduleAtMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Update
    suspend fun update(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

fun AutomationManager.Automation.toEntity(): AutomationEntity {
    val type: String
    var hour = 0
    var minute = 0
    var day = 0
    var interval = 0L
    var at = 0L
    when (val s = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> { type = "daily"; hour = s.hour; minute = s.minute }
        is AutomationManager.AutomationSchedule.Weekly -> { type = "weekly"; day = s.dayOfWeek; hour = s.hour; minute = s.minute }
        is AutomationManager.AutomationSchedule.Interval -> { type = "interval"; interval = s.intervalMs }
        is AutomationManager.AutomationSchedule.Once -> { type = "once"; at = s.atMs }
    }
    return AutomationEntity(id, name, command, type, hour, minute, day, interval, at, enabled, lastRun, lastResult, runCount)
}

fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val schedule = when (scheduleType.lowercase()) {
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleAtMs)
        else -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
    }
    return AutomationManager.Automation(id, name, command, schedule, enabled, lastRun, lastResult, runCount)
}

@Database(entities = [AutomationEntity::class], version = 2, exportSchema = false)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null
        fun getInstance(context: Context): AutomationDB = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AutomationDB::class.java, "automations.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()
        return try {
            val dao = AutomationDB.getInstance(applicationContext).automationDao()
            val automation = dao.getById(id) ?: return Result.failure()
            dao.update(automation.copy(lastRun = System.currentTimeMillis(), lastResult = "success", runCount = automation.runCount + 1))
            Result.success()
        } catch (_: Exception) { Result.failure() }
    }
}
"""
(root / "app/src/main/java/com/openjarvis/automation/AutomationDB.kt").write_text(db)

manager = root / "app/src/main/java/com/openjarvis/automation/AutomationManager.kt"
s = manager.read_text()
s = s.replace("_automationsFlow.value = dao.getAll()", "_automationsFlow.value = dao.getAll().map { it.toAutomation() }")
s = s.replace("dao.insert(automation)", "dao.insert(automation.toEntity())")
s = s.replace("dao.update(automation)", "dao.update(automation.toEntity())")
s = s.replace("val automation = dao.getById(id) ?: return@withContext", "val automation = dao.getById(id)?.toAutomation() ?: return@withContext")
s = s.replace("dao.update(updated)", "dao.update(updated.toEntity())")
s = s.replace(
    """        WorkManager.getInstance(context)
            .enqueueUniqueWork(automation.id, ExistingWorkPolicy.REPLACE, request)""",
    """        val workManager = WorkManager.getInstance(context)
        when (request) {
            is PeriodicWorkRequest -> workManager.enqueueUniquePeriodicWork(
                automation.id, ExistingPeriodicWorkPolicy.REPLACE, request
            )
            is OneTimeWorkRequest -> workManager.enqueueUniqueWork(
                automation.id, ExistingWorkPolicy.REPLACE, request
            )
        }"""
)
manager.write_text(s)

# The XML theme parent and attributes in res/values/themes.xml are provided by
# Material Components for Android; Compose Material3 alone does not provide them.
app_gradle = root / "app/build.gradle"
if app_gradle.exists():
    gradle_text = app_gradle.read_text()
    if "com.google.android.material:material" not in gradle_text:
        if "dependencies {" not in gradle_text:
            raise SystemExit("app/build.gradle: dependencies block not found")
        gradle_text = gradle_text.replace(
            "dependencies {",
            "dependencies {\n    implementation 'com.google.android.material:material:1.12.0'",
            1
        )
        app_gradle.write_text(gradle_text)
else:
    app_gradle_kts = root / "app/build.gradle.kts"
    if not app_gradle_kts.exists():
        raise SystemExit("app Gradle build file not found")
    gradle_text = app_gradle_kts.read_text()
    if "com.google.android.material:material" not in gradle_text:
        if "dependencies {" not in gradle_text:
            raise SystemExit("app/build.gradle.kts: dependencies block not found")
        gradle_text = gradle_text.replace(
            "dependencies {",
            'dependencies {\n    implementation("com.google.android.material:material:1.12.0")',
            1
        )
        app_gradle_kts.write_text(gradle_text)

print("Applied Open Jarvis Kotlin and Android resource configuration repairs")
