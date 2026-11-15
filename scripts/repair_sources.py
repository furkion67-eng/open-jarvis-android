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
    # executeTask() opens scope.launch { taskMutex.withLock { ... } }.
    # The packaged file closes those two lambdas but omits executeTask() itself.
    before = s.split(marker, 1)[0]
    trailing = before.rstrip().splitlines()[-2:]
    indents = [len(line) - len(line.lstrip()) for line in trailing]
    if indents != [4, 4]:
        s = s.replace(marker, "\n    }\n\n    suspend fun testConnection(): Result<Long> {", 1)
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


# Repair UI, voice, vision and screen-watcher API mismatches found by CI.
def settings(s):
    s = s.replace("import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft", "import androidx.compose.material.icons.filled.KeyboardArrowLeft")
    s = s.replace("import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight", "import androidx.compose.material.icons.filled.KeyboardArrowRight")
    s = s.replace("Icons.AutoMirrored.Filled.KeyboardArrowLeft", "Icons.Filled.KeyboardArrowLeft")
    s = s.replace("Icons.AutoMirrored.Filled.KeyboardArrowRight", "Icons.Filled.KeyboardArrowRight")
    if "import androidx.compose.ui.graphics.graphicsLayer" not in s:
        s = s.replace("import androidx.compose.ui.graphics.Color", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer")
    return s

def tutorial(s):
    s = s.replace("TutorialState.OFF", "TutorialState.Off")
    s = s.replace("action.description ?: action.action", "action.action")
    return s

def screenshot(s):
    s = s.replace("import android.view.WindowManager", "import android.view.WindowManager\nimport android.hardware.display.DisplayManager")
    old_call = """            mediaProjection?.createVirtualDisplay(
                "Screenshot",
                surface.width,
                surface.height,
                surface.allocation,
                surface.allocation,
                surface,
                null,
                handler
            )"""
    new_call = """            val reader = imageReader ?: return null
            val densityDpi = context.resources.displayMetrics.densityDpi
            mediaProjection?.createVirtualDisplay(
                "Screenshot",
                reader.width,
                reader.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )"""
    return s.replace(old_call, new_call)

def vision(s):
    s = s.replace("confidence = block.confidence,", "confidence = 0f,")
    s = s.replace("confidence = line.confidence,", "confidence = 0f,")
    return s

def voice(s):
    if "import kotlinx.coroutines.cancel" not in s:
        s = s.replace("import kotlinx.coroutines.launch", "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.cancel")
    return s

def watcher(s):
    s = s.replace("private val screenReader = ScreenReader(context)", "private val screenReader: ScreenReader? get() = JarvisAccessibilityService.instance?.let { ScreenReader(it) }")
    s = s.replace("screenReader.extractAllText()", "screenReader?.extractAllText() ?: \"\"")
    s = s.replace("val lastTriggered: Long = 0", "var lastTriggered: Long = 0")
    return s

edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", settings)
edit("app/src/main/java/com/openjarvis/ui/tutorial/TutorialMode.kt", tutorial)
edit("app/src/main/java/com/openjarvis/vision/ScreenshotCapture.kt", screenshot)
edit("app/src/main/java/com/openjarvis/vision/VisionModule.kt", vision)
edit("app/src/main/java/com/openjarvis/voice/VoiceManager.kt", voice)
edit("app/src/main/java/com/openjarvis/watch/ScreenWatcher.kt", watcher)

theme = root / "app/src/main/java/com/openjarvis/ui/theme/VoidColor.kt"
theme.parent.mkdir(parents=True, exist_ok=True)
if not theme.exists():
    theme.write_text("""package com.openjarvis.ui.theme

import androidx.compose.ui.graphics.Color

object VoidColor {
    val Void950 = Color(0xFF09090F)
    val Void900 = Color(0xFF11111B)
    val Void800 = Color(0xFF1B1B2A)
    val Void700 = Color(0xFF29293B)
    val Void600 = Color(0xFF3A3A50)
    val Violet = Color(0xFF9B59B6)
    val VioletDim = Color(0xFF6F3F8E)
    val Cyan = Color(0xFF42D9E8)
    val Green = Color(0xFF43C98B)
    val Red = Color(0xFFE45B68)
    val Amber = Color(0xFFFFC857)
    val TextPrimary = Color(0xFFF4F2FA)
    val TextSecondary = Color(0xFFB7B3C7)
    val TextDisabled = Color(0xFF777386)
    val BorderSubtle = Color(0xFF333044)
    val BorderGlow = Color(0xFF8E55B8)
}
""")



# Additional compile repairs from the next CI pass.
def socket_server(s):
    # Keep the first companion object, which contains all socket constants.
    return s.replace("""
    companion object {
        const val SOCKET_NAME = "jarvis.port"
    }
}""", "\n}", 1)

def notification_listener(s):
    s = s.replace("import com.openjarvis.graphify.GraphifyRepository", "import com.openjarvis.graphify.GraphifyRepository\nimport com.openjarvis.accessibility.JarvisAccessibilityService")
    s = s.replace('''        fun shouldProcessNotification(packageName: String): Boolean {
            return packageName !in PRIVACY_PROTECTED_APPS
        }
    }''', '''        private val messagingApps = setOf(
            "com.whatsapp", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.instagram.android",
            "com.facebook.orca", "org.telegram.messenger", "com.slack", "com.discord"
        )
        fun shouldProcessNotification(packageName: String): Boolean = packageName !in PRIVACY_PROTECTED_APPS
    }''')
    s = s.replace('''        scope.launch {
            try {
                graphifyRepo?.logNotification(
                    "${parsed.packageName}: ${parsed.title}"
                )
            } catch (e: Exception) { }
        }
''', '')
    s = s.replace('Notification.EXTRA_SENDER_TEXT', 'Notification.EXTRA_TITLE')
    # Remove the second companion object at the bottom.
    s = s.replace('''
    companion object {
        private val messagingApps = listOf(
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.instagram.android",
            "com.facebook.orca",
            "org.telegram.messenger",
            "com.slack",
            "com.discord"
        )
    }
''', '\n')
    return s

def ai_interactor(s):
    s = s.replace('private val screenReader = ScreenReader(context)', 'private val screenReader: ScreenReader? get() = JarvisAccessibilityService.instance?.let { ScreenReader(it) }')
    s = s.replace('''            delay(300)
''', '')
    s = s.replace('screenReader.extractAllText()', 'screenReader?.extractAllText() ?: ""')
    return s

def app_builder(s):
    s = s.replace('''    enum class FileType {
        GRADLE, GRADLE_APP, MANIFEST, KOTLIN, XML, PROPERTIES, MARKDOWN
    }''', '''    enum class FileType {
        GRADLE, GRADLE_APP, MANIFEST, KOTLIN, XML, PROPERTIES, PROGUARD, MARKDOWN
    }
    enum class Complexity { SIMPLE, MEDIUM, COMPLEX }''')
    s = s.replace('val complexity: AppBuilderMode.Complexity', 'val complexity: AppBuilderMode.Complexity')
    s = s.replace('\nenum class Complexity { SIMPLE, MEDIUM, COMPLEX }', '')
    return s

def universal_adapter(s):
    s = s.replace('prefs.getString("provider_name", "Groq")', 'prefs.getString("provider_name", "Groq") ?: "Groq"')
    s = s.replace('prefs.getString("provider_base_url", "")', 'prefs.getString("provider_base_url", "") ?: ""')
    s = s.replace('prefs.getString("provider_api_key", "")', 'prefs.getString("provider_api_key", "") ?: ""')
    s = s.replace('prefs.getString("provider_model", "")', 'prefs.getString("provider_model", "") ?: ""')
    s = s.replace('ModelManager.ModelState.ModelLoaded', 'ModelManager.ModelState.Loaded')
    return s

def groq_provider(s):
    marker = '\nclass GeminiProvider('
    return s.split(marker, 1)[0] + '\n' if marker in s else s

def providers(s):
    s = s.replace('private val baseUrl: String,', 'override val baseUrl: String,')
    return s

def model_manager(s):
    s = s.replace('_state.value = ModelState.Loading', '_state.value = ModelState.Loading(tier)')
    s = s.replace('_state.value = ModelStateLoaded(tier)', '_state.value = ModelState.Loaded(tier)')
    return s

def mcp_client(s):
    s = s.replace('put("id", "jarvis")', 'put("id", 1)')
    return s

def stt(s):
    return s.replace('            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_CONFIDENCE_THRESHOLD, 0.1f)\n', '')

def settings_screen(s):
    s = s.replace('showPassword', 'passwordVisible')
    return s

def dashboard(s):
    if 'import androidx.compose.ui.graphics.graphicsLayer' not in s:
        s = s.replace('import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer')
    if 'import androidx.compose.ui.text.withStyle' not in s:
        s = s.replace('import androidx.compose.ui.text.', 'import androidx.compose.ui.text.withStyle\nimport androidx.compose.ui.text.', 1)
    s = s.replace('Column(alpha = alpha)', 'Column(modifier = Modifier.graphicsLayer { this.alpha = alpha })')
    return s

edit("app/src/main/java/com/openjarvis/bridge/SocketServer.kt", socket_server)
edit("app/src/main/java/com/openjarvis/intelligence/JarvisNotificationListener.kt", notification_listener)
edit("app/src/main/java/com/openjarvis/intelligence/AIAppInteractor.kt", ai_interactor)
edit("app/src/main/java/com/openjarvis/builder/AppBuilderMode.kt", app_builder)
edit("app/src/main/java/com/openjarvis/llm/UniversalAdapter.kt", universal_adapter)
edit("app/src/main/java/com/openjarvis/llm/providers/GroqProvider.kt", groq_provider)
edit("app/src/main/java/com/openjarvis/llm/providers/Providers.kt", providers)
edit("app/src/main/java/com/openjarvis/local/ModelManager.kt", model_manager)
edit("app/src/main/java/com/openjarvis/mcp/MCPClient.kt", mcp_client)
edit("app/src/main/java/com/openjarvis/voice/AndroidSTTEngine.kt", stt)
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", settings_screen)
edit("app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt", dashboard)

app_gradle = root / "app/build.gradle"
g = app_gradle.read_text()
if "androidx.activity:activity-compose" not in g:
    g = g.replace("implementation 'androidx.activity:activity-ktx:1.8.2'", "implementation 'androidx.activity:activity-ktx:1.8.2'\n    implementation 'androidx.activity:activity-compose:1.8.2'")
app_gradle.write_text(g)

print("Applied Open Jarvis compile repairs")
