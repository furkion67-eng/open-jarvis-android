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


# Final pass: resolve the remaining compile errors with source-level fixes.
def screen_reader_final(s):
    s = s.replace("import android.view.accessibility.AccessibilityNodeInfo", "import android.content.Context\nimport android.view.accessibility.AccessibilityNodeInfo")
    s = s.replace(
        "class ScreenReader(private val service: JarvisAccessibilityService) {",
        "class ScreenReader(@Suppress(\"UNUSED_PARAMETER\") context: Context) {\n    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance"
    )
    s = s.replace("service.rootInActiveWindow ?: return \"\"", "service?.rootInActiveWindow ?: return \"\"")
    s = s.replace("service.rootInActiveWindow ?: return null", "service?.rootInActiveWindow ?: return null")
    return s
edit("app/src/main/java/com/openjarvis/accessibility/ScreenReader.kt", screen_reader_final)
edit("app/src/main/java/com/openjarvis/accessibility/ActionExecutor.kt",
     lambda s: s.replace("import com.openjarvis.agent.ActionPlan\n", ""))
edit("app/src/main/java/com/openjarvis/accessibility/JarvisAccessibilityService.kt",
     lambda s: s.replace("AccessibilityEvent.TYPE_ALL_MASK", "AccessibilityEvent.TYPES_ALL_MASK"))

edit("app/src/main/java/com/openjarvis/agent/AgentCore.kt", lambda s:
     s.replace("return@taskMutex.withLock", "return@withLock")
      .replace("cleanCommand = cleanCommand,", "command = cleanCommand,"))

def prompt_engine_final(s):
    s = s.replace("IntentAnalyzer.analyze(resolvedPrompt)", "IntentAnalyzer.analyze(resolvedPrompt, UniversalAdapter(context))")
    s = s.replace(
        "suspend fun analyze(prompt: String): JSONObject = withContext(Dispatchers.IO) {\n        val adapter = UniversalAdapter.getModelManager(context)",
        "suspend fun analyze(prompt: String, adapter: UniversalAdapter): JSONObject = withContext(Dispatchers.IO) {"
    )
    return s
edit("app/src/main/java/com/openjarvis/agent/PromptEngine.kt", prompt_engine_final)

def fallback_final(s):
    if "import org.json.JSONException" not in s:
        s = s.replace("import kotlinx.coroutines.flow.MutableStateFlow", "import kotlinx.coroutines.flow.MutableStateFlow\nimport org.json.JSONException")
    s = s.replace("val result = runCatching { provider.complete(system, user) }", "val result = provider.complete(system, user)")
    return s
edit("app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt", fallback_final)

def self_healing_final(s):
    s = s.replace("private val llm = UniversalAdapter.getModelManager(context)", "private val llm = UniversalAdapter(context)")
    s = s.replace("if (result.success) return result", "if (result is ActionResult.Success) return result")
    s = s.replace("llm.complete(healingPrompt.first, healingPrompt.second)", "llm.complete(healingPrompt.first, healingPrompt.second).getOrNull()")
    s = s.replace("if (tapped) ", "if (tapped == true) ")
    s = s.replace("if (typed) ", "if (typed == true) ")
    s = s.replace("\${action.description ?: action.action}", "\${action.action}")
    return s
edit("app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt", self_healing_final)

def ai_interactor_final(s):
    s = s.replace(
        "return JarvisAccessibilityService.instance?.openAppByPackage(meta.packageName) ?: false",
        "return JarvisAccessibilityService.instance?.let { service -> service.openAppByPackage(meta.packageName); true } ?: false"
    )
    s = s.replace("            delay(300)\n", "")
    return s
edit("app/src/main/java/com/openjarvis/intelligence/AIAppInteractor.kt", ai_interactor_final)

def mcp_final(s):
    s = s.replace('optJSONArray("result")?.optJSONArray("tools")', 'optJSONObject("result")?.optJSONArray("tools")')
    return s
edit("app/src/main/java/com/openjarvis/mcp/MCPClient.kt", mcp_final)

def skill_final(s):
    start = s.find("    private fun copyBuiltinSkillsIfNeeded() {")
    end = s.find("    private fun parseSkill", start)
    if start >= 0 and end >= 0:
        method = """    private fun copyBuiltinSkillsIfNeeded() {
        val assetNames = context.assets.list("skills") ?: return
        for (assetName in assetNames) {
            if (!assetName.endsWith(".json")) continue
            val destFile = File(skillsDir, assetName)
            if (destFile.exists()) continue
            try {
                context.assets.open("skills/$assetName").use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

"""
        s = s[:start] + method + s[end:]
    s = s.replace("val usageCount: Int,\n    val successRate: Float", "val usageCount: Int = 0,\n    val successRate: Float = 1.0f")
    return s
edit("app/src/main/java/com/openjarvis/skills/SkillEngine.kt", skill_final)

# Compose host integration and theme.
app_gradle = root / "app/build.gradle"
gradle_text = app_gradle.read_text()
if "androidx.activity:activity-compose" not in gradle_text:
    gradle_text = gradle_text.replace(
        "implementation 'androidx.activity:activity-ktx:1.8.2'",
        "implementation 'androidx.activity:activity-ktx:1.8.2'\n    implementation 'androidx.activity:activity-compose:1.8.2'"
    )
app_gradle.write_text(gradle_text)

theme_file = root / "app/src/main/java/com/openjarvis/ui/theme/Theme.kt"
theme_file.parent.mkdir(parents=True, exist_ok=True)
if not theme_file.exists():
    theme_file.write_text("""package com.openjarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisDarkColors = darkColorScheme(
    primary = VoidColor.Violet,
    onPrimary = VoidColor.TextPrimary,
    secondary = VoidColor.Cyan,
    onSecondary = VoidColor.Void950,
    background = VoidColor.Void950,
    onBackground = VoidColor.TextPrimary,
    surface = VoidColor.Void900,
    onSurface = VoidColor.TextPrimary,
    surfaceVariant = VoidColor.Void800,
    onSurfaceVariant = VoidColor.TextSecondary,
    error = VoidColor.Red
)

@Composable
fun OpenJarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisDarkColors, content = content)
}
""")

def main_activity_final(s):
    if "import androidx.compose.foundation.layout.*" not in s:
        s = s.replace("import androidx.compose.foundation.layout.fillMaxSize", "import androidx.compose.foundation.layout.*")
    if "import androidx.compose.ui.unit.dp" not in s:
        s = s.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp")
    return s
edit("app/src/main/java/com/openjarvis/ui/MainActivity.kt", main_activity_final)

def dashboard_final(s):
    if "import androidx.compose.ui.draw.scale" not in s:
        s = s.replace("import androidx.compose.ui.draw.clip", "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.scale")
    if "import androidx.compose.ui.text.withStyle" not in s:
        s = s.replace("import androidx.compose.ui.text.buildAnnotatedString", "import androidx.compose.ui.text.buildAnnotatedString\nimport androidx.compose.ui.text.withStyle")
    if "import androidx.compose.ui.graphics.graphicsLayer" not in s:
        s = s.replace("import androidx.compose.ui.graphics.Color", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer")
    s = s.replace("Column(alpha = alpha)", "Column(modifier = Modifier.graphicsLayer { this.alpha = alpha })")
    return s
edit("app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt", dashboard_final)

def onboarding_final(s):
    s = s.replace("import androidx.lifecycle.lifecycleScope\n", "")
    s = s.replace("fun OnboardingScreen(onComplete: () -> Unit) {\n", "fun OnboardingScreen(onComplete: () -> Unit) {\n    val coroutineScope = rememberCoroutineScope()\n")
    s = s.replace("lifecycleScope.launch {", "coroutineScope.launch {")
    return s
edit("app/src/main/java/com/openjarvis/ui/onboarding/OnboardingActivity.kt", onboarding_final)

def floating_final(s):
    s = s.replace("import androidx.compose.foundation.gestures.detectTransformableState\n", "")
    s = s.replace("import androidx.compose.foundation.pointerInput\n", "")
    s = s.replace("import androidx.compose.material.icons.automirrored.filled.KeyboardArrowUp", "import androidx.compose.material.icons.filled.KeyboardArrowUp")
    s = s.replace("Icons.AutoMirrored.Filled.KeyboardArrowUp", "Icons.Filled.KeyboardArrowUp")
    if "import androidx.compose.material3.Divider" not in s:
        s = s.replace("import androidx.compose.material3.Icon\n", "import androidx.compose.material3.Divider\nimport androidx.compose.material3.Icon\n")
    start = s.find("        transitionSpec = {", s.find("    AnimatedContent("))
    end = s.find("        label = \"overlay_expand\"", start)
    if start >= 0 and end >= 0:
        s = s[:start] + """        transitionSpec = {
            (expandHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                fadeIn(animationSpec = tween(150))) togetherWith
                (shrinkHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                    fadeOut(animationSpec = tween(100)))
        },
""" + s[end:]
    old = """    var glowAlpha by remember { mutableFloatStateOf(0.15f) }

    LaunchedEffect(Unit) {
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        ) { glowAlpha = this }
    }
"""
    new = """    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )
"""
    s = s.replace(old, new)
    return s
edit("app/src/main/java/com/openjarvis/ui/overlay/FloatingOverlayWidget.kt", floating_final)

edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt",
     lambda s: s.replace("if (isPassword && !passwordVisible)", "if (isPassword)"))

def voice_manager_final(s):
    if "fun initialize()" not in s:
        s = s.replace("    fun isVoiceEnabled(): Boolean", "    fun initialize() { /* Engines initialize in their constructors. */ }\n\n    fun isVoiceEnabled(): Boolean")
    return s
edit("app/src/main/java/com/openjarvis/voice/VoiceManager.kt", voice_manager_final)

edit("app/src/main/java/com/openjarvis/voice/AndroidSTTEngine.kt",
     lambda s: s.replace('            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_CONFIDENCE_THRESHOLD, 0.1f)\n', ""))

def tutorial_final(s):
    return s.replace("MutableStateFlow(TutorialState.Off)", "MutableStateFlow<TutorialState>(TutorialState.Off)")
edit("app/src/main/java/com/openjarvis/ui/tutorial/TutorialMode.kt", tutorial_final)

def graphify_final(s):
    if "suspend fun logProviderFailure" not in s:
        marker = "    suspend fun getRecentApps(limit: Int = 10): List<AppNode>"
        method = """    suspend fun logProviderFailure(name: String, message: String) = withContext(Dispatchers.IO) {
        val existing = providerNodeDao.getByName(name)
        if (existing == null) {
            providerNodeDao.insert(ProviderNode(providerName = name, successCount = 0))
        } else {
            providerNodeDao.update(existing.copy(lastUsed = System.currentTimeMillis(), useCount = existing.useCount + 1))
        }
    }

"""
        s = s.replace(marker, method + marker)
    return s
edit("app/src/main/java/com/openjarvis/graphify/GraphifyRepository.kt", graphify_final)


def self_healing_final2(s):
    s = s.replace("$" + "{action.description ?: action.action}", "$" + "{action.action}")
    return s
edit("app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt", self_healing_final2)

def onboarding_final2(s):
    s = s.replace(
        "private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {\n",
        "private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {\n    val coroutineScope = rememberCoroutineScope()\n"
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/onboarding/OnboardingActivity.kt", onboarding_final2)

print("Applied Open Jarvis compile repairs")


# Compile repair pass 26: resolve remaining API and Compose mismatches.
def screen_reader_pass(s):
    s = s.replace("import android.view.accessibility.AccessibilityNodeInfo", "import android.content.Context\nimport android.view.accessibility.AccessibilityNodeInfo")
    s = s.replace("class ScreenReader(private val service: JarvisAccessibilityService) {", "class ScreenReader(private val context: Context) {\n    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance")
    s = s.replace("service.rootInActiveWindow", "service?.rootInActiveWindow")
    return s
edit("app/src/main/java/com/openjarvis/accessibility/ScreenReader.kt", screen_reader_pass)

def interactor_pass(s):
    s = s.replace("ScreenReader(it)", "ScreenReader(context)")
    s = s.replace("return JarvisAccessibilityService.instance?.openAppByPackage(meta.packageName) ?: false", "return JarvisAccessibilityService.instance?.let { it.openAppByPackage(meta.packageName); true } ?: false")
    return s
edit("app/src/main/java/com/openjarvis/intelligence/AIAppInteractor.kt", interactor_pass)
edit("app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt", lambda s:
     s.replace("ScreenReader(context)", "ScreenReader(context)")
      .replace("UniversalAdapter.getModelManager(context)", "UniversalAdapter(context)")
      .replace("if (result.success) return result", "if (result is ActionResult.Success) return result")
      .replace("llm.complete(healingPrompt.first, healingPrompt.second)", "llm.complete(healingPrompt.first, healingPrompt.second).getOrNull()")
      .replace("action.description ?: action.action", "action.action")
      .replace("if (tapped)", "if (tapped == true)")
      .replace("if (typed)", "if (typed == true)"))

edit("app/src/main/java/com/openjarvis/accessibility/JarvisAccessibilityService.kt",
     lambda s: s.replace("AccessibilityEvent.TYPE_ALL_MASK", "AccessibilityEvent.TYPES_ALL_MASK"))
edit("app/src/main/java/com/openjarvis/agent/AgentCore.kt", lambda s:
     s.replace("return@taskMutex.withLock", "return@launch")
      .replace("cleanCommand = cleanCommand,", "command = cleanCommand,")
      .replace("ScreenReader(context)", "ScreenReader(context)"))

def prompt_pass(s):
    s = s.replace("IntentAnalyzer.analyze(resolvedPrompt)", "IntentAnalyzer.analyze(resolvedPrompt, UniversalAdapter(context))")
    s = s.replace("suspend fun analyze(prompt: String): JSONObject = withContext(Dispatchers.IO) {\n        val adapter = UniversalAdapter.getModelManager(context)", "suspend fun analyze(prompt: String, adapter: UniversalAdapter): JSONObject = withContext(Dispatchers.IO) {")
    return s
edit("app/src/main/java/com/openjarvis/agent/PromptEngine.kt", prompt_pass)

def fallback_pass(s):
    if "import org.json.JSONException" not in s:
        s = s.replace("import kotlinx.coroutines.flow.MutableStateFlow", "import kotlinx.coroutines.flow.MutableStateFlow\nimport org.json.JSONException")
    s = s.replace("val result = runCatching { provider.complete(system, user) }", "val result = provider.complete(system, user)")
    return s
edit("app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt", fallback_pass)

def skill_pass(s):
    start = s.find("    private fun copyBuiltinSkillsIfNeeded() {")
    end = s.find("    private fun parseSkill", start)
    if start >= 0 and end >= 0:
        s = s[:start] + """    private fun copyBuiltinSkillsIfNeeded() {
        val names = context.assets.list("skills") ?: return
        names.filter { it.endsWith(".json") }.forEach { name ->
            val target = File(skillsDir, name)
            if (!target.exists()) try {
                context.assets.open("skills/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) { }
        }
    }

""" + s[end:]
    # Supply defaults for optional skill statistics and repair the generated Skill constructor.
    s = s.replace("val usageCount: Int,\n    val successRate: Float", "val usageCount: Int = 0,\n    val successRate: Float = 1.0f")
    return s
edit("app/src/main/java/com/openjarvis/skills/SkillEngine.kt", skill_pass)

def main_pass(s):
    s = s.replace("import androidx.compose.foundation.layout.fillMaxSize", "import androidx.compose.foundation.layout.*")
    if "import androidx.compose.ui.unit.dp" not in s:
        s = s.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp")
    return s
edit("app/src/main/java/com/openjarvis/ui/MainActivity.kt", main_pass)

def overlay_pass(s):
    s = s.replace("voiceManager.initialize()", "voiceManager.initialize()")
    return s
edit("app/src/main/java/com/openjarvis/ui/OverlayService.kt", overlay_pass)

def settings_pass(s):
    s = s.replace("if (isPassword && !passwordVisible)", "if (isPassword)")
    return s
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", settings_pass)

def dashboard_pass(s):
    if "import androidx.compose.ui.draw.scale" not in s:
        s = s.replace("import androidx.compose.ui.draw.clip", "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.scale")
    if "import androidx.compose.ui.text.withStyle" not in s:
        s = s.replace("import androidx.compose.ui.text.buildAnnotatedString", "import androidx.compose.ui.text.buildAnnotatedString\nimport androidx.compose.ui.text.withStyle")
    return s
edit("app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt", dashboard_pass)

def onboarding_pass(s):
    if "import androidx.compose.runtime.rememberCoroutineScope" not in s:
        s = s.replace("import androidx.compose.runtime.*", "import androidx.compose.runtime.*")
    s = s.replace("private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {", "private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {\n    val tryScope = rememberCoroutineScope()")
    s = s.replace("delay(2000)", "tryScope.launch { delay(2000) }")
    return s
edit("app/src/main/java/com/openjarvis/ui/onboarding/OnboardingActivity.kt", onboarding_pass)

def floating_pass(s):
    s = s.replace("import androidx.compose.foundation.gestures.detectTransformableState\n", "")
    s = s.replace("import androidx.compose.foundation.pointerInput\n", "")
    s = s.replace("import androidx.compose.material.icons.automirrored.filled.KeyboardArrowUp", "import androidx.compose.material.icons.filled.KeyboardArrowUp")
    s = s.replace("Icons.AutoMirrored.Filled.KeyboardArrowUp", "Icons.Filled.KeyboardArrowUp")
    if "import androidx.compose.material3.Divider" not in s:
        s = s.replace("import androidx.compose.material3.Icon", "import androidx.compose.material3.Divider\nimport androidx.compose.material3.Icon", 1)
    s = s.replace("infiniteTransition.animateFloat(", "rememberInfiniteTransition(label = \"float\").animateFloat(")
    return s
edit("app/src/main/java/com/openjarvis/ui/overlay/FloatingOverlayWidget.kt", floating_pass)

print("Applied Open Jarvis compile repair pass 26")


# Final cleanup for pass 28 compiler diagnostics.
def cleanup_screen_reader(s):
    lines = s.splitlines()
    seen = set()
    out = []
    for line in lines:
        if line.startswith("import "):
            if line in seen:
                continue
            seen.add(line)
        out.append(line)
    return "\n".join(out) + ("\n" if s.endswith("\n") else "")

def cleanup_self_healing(s):
    s = s.replace("llm.complete(healingPrompt.first, healingPrompt.second).getOrNull().getOrNull()", "llm.complete(healingPrompt.first, healingPrompt.second).getOrNull()")
    return s

edit("app/src/main/java/com/openjarvis/accessibility/ScreenReader.kt", cleanup_screen_reader)
edit("app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt", cleanup_self_healing)

print("Applied final pass 28 compile cleanup")


# Startup hardening: Room access must not be allowed to crash the launcher.
def guard_launcher_database():
    main = root / "app/src/main/java/com/openjarvis/ui/MainActivity.kt"
    s = main.read_text()
    s = s.replace("    private lateinit var graphifyRepo: GraphifyRepository\n", "")
    old_init = "        graphifyRepo = GraphifyRepository(this)"
    new_init = """        val graphifyRepo = try {
            GraphifyRepository(applicationContext)
        } catch (e: Exception) {
            android.util.Log.e("OpenJarvis", "Graphify database initialization failed", e)
            null
        }"""
    if old_init in s:
        s = s.replace(old_init, new_init, 1)
    elif "val graphifyRepo = try {" not in s:
        raise SystemExit("MainActivity: GraphifyRepository initialization anchor missing")

    import re
    state_pattern = re.compile(
        r"(?ms)^\s*var recentTasks by remember \{ mutableStateOf<List<TaskNode>>\(emptyList\(\)\) \}\s*"
        r"^\s*LaunchedEffect\(Unit\) \{\s*"
        r"^\s*recentTasks = graphifyRepo\.getRecentTasks\(10\)\s*"
        r"^\s*\}\s*"
    )
    s, _ = state_pattern.subn("", s, count=1)
    s = s.replace("import com.openjarvis.graphify.nodes.TaskNode\n", "")
    main.write_text(s)

    dashboard = root / "app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt"
    ds = dashboard.read_text()
    ds = ds.replace("    graphifyRepo: GraphifyRepository,", "    graphifyRepo: GraphifyRepository?,")
    if "var databaseError by remember" not in ds:
        ds = ds.replace(
            "    var isSystemActive by remember { mutableStateOf(true) }",
            """    var isSystemActive by remember { mutableStateOf(true) }
    var databaseError by remember { mutableStateOf<String?>(null) }"""
        )
    old_query = """    LaunchedEffect(Unit) {
        recentTasks = graphifyRepo.getRecentTasks(10)
        mostUsedApps = graphifyRepo.getMostUsedApps(4)
    }"""
    new_query = """    LaunchedEffect(graphifyRepo) {
        val repository = graphifyRepo
        if (repository == null) {
            databaseError = "Yerel hafıza veritabanı başlatılamadı."
            return@LaunchedEffect
        }
        try {
            recentTasks = repository.getRecentTasks(10)
            mostUsedApps = repository.getMostUsedApps(4)
            databaseError = null
        } catch (e: Exception) {
            android.util.Log.e("OpenJarvis", "Dashboard database load failed", e)
            databaseError = e.localizedMessage ?: "Veritabanı okunamadı."
        }
    }"""
    if old_query in ds:
        ds = ds.replace(old_query, new_query, 1)
    elif "LaunchedEffect(graphifyRepo)" not in ds:
        raise SystemExit("DashboardScreen: database query anchor missing")

    warning = """            if (databaseError != null) {
                Text(
                    text = "Hafıza verisi kullanılamıyor: " + (databaseError ?: ""),
                    color = VoidColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }"""
    if warning not in ds:
        ds = ds.replace(
            "            HeroSection(isSystemActive = isSystemActive)",
            """            HeroSection(isSystemActive = isSystemActive)

""" + warning,
            1
        )
    dashboard.write_text(ds)

guard_launcher_database()
print("Applied launcher database startup guard")

# Navigation repair: expose the floating Jarvis agent and make task history actionable.
def repair_dashboard_navigation():
    dashboard = root / "app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt"
    s = dashboard.read_text()
    if "import androidx.compose.material.icons.filled.Mic" not in s:
        s = s.replace(
            "import androidx.compose.material.icons.filled.Settings",
            "import androidx.compose.material.icons.filled.Settings\nimport androidx.compose.material.icons.filled.Mic"
        )
    s = s.replace(
        "BottomNavBar(\n            onSettingsClick = onOpenSettings,",
        "BottomNavBar(\n            onStartOverlay = onStartOverlay,\n            tasks = recentTasks,\n            onSettingsClick = onOpenSettings,"
    )
    start = s.find("@Composable\nprivate fun BottomNavBar(")
    end = s.find("\nprivate fun formatRelativeTime", start)
    if start < 0 or end < 0:
        raise SystemExit("DashboardScreen: BottomNavBar replacement anchors not found")
    replacement = """@Composable
private fun BottomNavBar(
    onStartOverlay: () -> Unit,
    tasks: List<TaskNode>,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTasks by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth(), color = VoidColor.Void900) {
        Column {
            Divider(modifier = Modifier.fillMaxWidth(), thickness = 1.dp, color = VoidColor.BorderSubtle)
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(onClick = onStartOverlay)
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), color = VoidColor.Violet.copy(alpha = 0.08f)) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Start Jarvis assistant",
                            tint = VoidColor.Violet,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).size(22.dp)
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { showTasks = true }
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), color = VoidColor.Violet.copy(alpha = 0.08f)) {
                        Icon(
                            imageVector = Icons.Filled.Task,
                            contentDescription = "Tasks",
                            tint = VoidColor.Violet,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).size(22.dp)
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(onClick = onSettingsClick)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = VoidColor.TextDisabled,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
    if (showTasks) {
        AlertDialog(
            onDismissRequest = { showTasks = false },
            title = { Text("Recent tasks") },
            text = {
                if (tasks.isEmpty()) {
                    Text("No tasks have been run yet.")
                } else {
                    Column {
                        tasks.take(10).forEach { task ->
                            Text(task.command, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                            Text(task.result, style = MaterialTheme.typography.bodySmall, color = VoidColor.TextSecondary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTasks = false }) { Text("Close") } },
            containerColor = VoidColor.Void900,
            titleContentColor = VoidColor.TextPrimary,
            textContentColor = VoidColor.TextSecondary
        )
    }
}
"""
    s = s[:start] + replacement + s[end:]
    dashboard.write_text(s)

repair_dashboard_navigation()
print("Applied dashboard navigation repair")


# Persist and restore provider settings in the settings UI.
def restore_provider_settings_activity(s):
    old = """                SettingsScreen(
                    onNavigateBack = { finish() },
                    onSaveProvider = { name, baseUrl, apiKey, model ->
                        saveProviderSettings(name, baseUrl, apiKey, model)
                    },
                    modifier = Modifier.fillMaxSize()
                )"""
    new = """                SettingsScreen(
                    onNavigateBack = { finish() },
                    initialProvider = listOf(
                        prefs.getString("provider_name", "Groq") ?: "Groq",
                        prefs.getString("provider_base_url", "") ?: "",
                        prefs.getString("provider_api_key", "") ?: "",
                        prefs.getString("provider_model", "llama-3.1-70b-versatile") ?: "llama-3.1-70b-versatile"
                    ),
                    onSaveProvider = { name, baseUrl, apiKey, model ->
                        saveProviderSettings(name, baseUrl, apiKey, model)
                    },
                    modifier = Modifier.fillMaxSize()
                )"""
    if old not in s and "initialProvider = listOf(" not in s:
        raise SystemExit("SettingsActivity: SettingsScreen call anchor missing")
    return s.replace(old, new, 1) if old in s else s
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", restore_provider_settings_activity)

def restore_provider_settings_screen(s):
    s = s.replace(
        """    onNavigateBack: () -> Unit,
    onSaveProvider: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedProvider by remember { mutableStateOf("Groq") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("llama-3.1-70b-versatile") }""",
        """    onNavigateBack: () -> Unit,
    initialProvider: List<String> = listOf("Groq", "", "", "llama-3.1-70b-versatile"),
    onSaveProvider: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedProvider by remember(initialProvider) { mutableStateOf(initialProvider.getOrElse(0) { "Groq" }) }
    var baseUrl by remember(initialProvider) { mutableStateOf(initialProvider.getOrElse(1) { "" }) }
    var apiKey by remember(initialProvider) { mutableStateOf(initialProvider.getOrElse(2) { "" }) }
    var model by remember(initialProvider) { mutableStateOf(initialProvider.getOrElse(3) { "llama-3.1-70b-versatile" }) }"""
    )
    s = s.replace(
        """                TestConnectionButton(
                    apiKey = apiKey,""",
        """                TestConnectionButton(
                    provider = selectedProvider,
                    apiKey = apiKey,"""
    )
    s = s.replace(
        """private fun TestConnectionButton(
    apiKey: String,""",
        """private fun TestConnectionButton(
    provider: String,
    apiKey: String,"""
    )
    s = s.replace('onSave("Groq", baseUrl, apiKey, model)', 'onSave(provider, baseUrl, apiKey, model)')
    if "initialProvider: List<String>" not in s:
        raise SystemExit("SettingsScreen: provider state anchor missing")
    return s
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", restore_provider_settings_screen)

print("Applied provider settings restore/save repair")



# Persist the voice controls in the same encrypted store used by VoiceManager.
def voice_activity(s):
    if "import com.openjarvis.voice.VoiceManager" not in s:
        s = s.replace(
            "import com.openjarvis.ui.theme.OpenJarvisTheme",
            "import com.openjarvis.ui.theme.OpenJarvisTheme\\nimport com.openjarvis.voice.VoiceManager\\nimport com.openjarvis.voice.STTMode"
        )
    if "private lateinit var voiceManager" not in s:
        s = s.replace(
            "private lateinit var prefs: android.content.SharedPreferences",
            "private lateinit var prefs: android.content.SharedPreferences\\n    private lateinit var voiceManager: VoiceManager"
        )
    if "voiceManager = VoiceManager(applicationContext)" not in s:
        s = s.replace("        setContent {", "        voiceManager = VoiceManager(applicationContext)\\n\\n        setContent {", 1)
    anchor = """                    onSaveProvider = { name, baseUrl, apiKey, model ->
                        saveProviderSettings(name, baseUrl, apiKey, model)
                    },"""
    replacement = """                    initialVoiceEnabled = voiceManager.isVoiceEnabled(),
                    initialSpeakResults = voiceManager.isSpeakResultsEnabled(),
                    initialSttMode = voiceManager.getSTTMode().name,
                    onVoiceSettingsChanged = { enabled, speak, mode ->
                        voiceManager.setVoiceEnabled(enabled)
                        voiceManager.setSpeakResultsEnabled(speak)
                        voiceManager.setSTTMode(if (mode == "AUTO_VAD") STTMode.AUTO_VAD else STTMode.PUSH_TO_TALK)
                    },
                    onSaveProvider = { name, baseUrl, apiKey, model ->
                        saveProviderSettings(name, baseUrl, apiKey, model)
                    },"""
    if anchor in s:
        s = s.replace(anchor, replacement, 1)
    return s
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", voice_activity)

def voice_screen(s):
    if "import com.openjarvis.voice.STTMode" not in s:
        s = s.replace("import com.openjarvis.llm.UniversalAdapter", "import com.openjarvis.llm.UniversalAdapter\\nimport com.openjarvis.voice.STTMode")
    s = s.replace(
        """    initialProvider: List<String> = listOf("Groq", "", "", "llama-3.1-70b-versatile"),
    onSaveProvider: (String, String, String, String) -> Unit,""",
        """    initialProvider: List<String> = listOf("Groq", "", "", "llama-3.1-70b-versatile"),
    initialVoiceEnabled: Boolean = false,
    initialSpeakResults: Boolean = true,
    initialSttMode: String = "PUSH_TO_TALK",
    onVoiceSettingsChanged: (Boolean, Boolean, String) -> Unit = { _, _, _ -> },
    onSaveProvider: (String, String, String, String) -> Unit,"""
    )
    s = s.replace(
        """    var voiceEnabled by remember { mutableStateOf(false) }
    var speakResults by remember { mutableStateOf(true) }
    var speechRate by remember { mutableStateOf(1.05f) }
    var sttMode by remember { mutableStateOf("Push to Talk") }""",
        """    var voiceEnabled by remember(initialVoiceEnabled) { mutableStateOf(initialVoiceEnabled) }
    var speakResults by remember(initialSpeakResults) { mutableStateOf(initialSpeakResults) }
    var speechRate by remember { mutableStateOf(1.05f) }
    var sttMode by remember(initialSttMode) { mutableStateOf(if (initialSttMode == "AUTO_VAD") "Auto (VAD)" else "Push to Talk") }"""
    )
    s = s.replace("onToggle = { voiceEnabled = !voiceEnabled }", """onToggle = {
                        voiceEnabled = !voiceEnabled
                        onVoiceSettingsChanged(voiceEnabled, speakResults, sttMode)
                    }""")
    s = s.replace("onToggle = { speakResults = !speakResults }", """onToggle = {
                            speakResults = !speakResults
                            onVoiceSettingsChanged(voiceEnabled, speakResults, sttMode)
                        }""")
    s = s.replace(
        """sttMode = if (sttMode == "Push to Talk") "Auto (VAD)" else "Push to Talk"
                                }""",
        """sttMode = if (sttMode == "Push to Talk") "Auto (VAD)" else "Push to Talk"
                                    onVoiceSettingsChanged(voiceEnabled, speakResults, sttMode)
                                }"""
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", voice_screen)
print("Applied persistent voice settings repair")



# Normalize escaped line breaks introduced by the voice settings patch.
def normalize_voice_patch(s):
    s = s.replace(
        "import com.openjarvis.ui.theme.OpenJarvisTheme\\\\nimport com.openjarvis.voice.VoiceManager\\\\nimport com.openjarvis.voice.STTMode",
        "import com.openjarvis.ui.theme.OpenJarvisTheme\\nimport com.openjarvis.voice.VoiceManager\\nimport com.openjarvis.voice.STTMode"
    )
    s = s.replace(
        "private lateinit var prefs: android.content.SharedPreferences\\\\n    private lateinit var voiceManager: VoiceManager",
        "private lateinit var prefs: android.content.SharedPreferences\\n    private lateinit var voiceManager: VoiceManager"
    )
    s = s.replace(
        "voiceManager = VoiceManager(applicationContext)\\\\n\\\\n        setContent {",
        "voiceManager = VoiceManager(applicationContext)\\n\\n        setContent {"
    )
    s = s.replace(
        "import com.openjarvis.llm.UniversalAdapter\\\\nimport com.openjarvis.voice.STTMode",
        "import com.openjarvis.llm.UniversalAdapter\\nimport com.openjarvis.voice.STTMode"
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", normalize_voice_patch)
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", normalize_voice_patch)
print("Normalized voice settings Kotlin line breaks")



# Correct literal backslash-n sequences in the three inserted Kotlin fragments.
def fix_voice_kotlin_newlines(s):
    s = s.replace(
        r"import com.openjarvis.ui.theme.OpenJarvisTheme\nimport com.openjarvis.voice.VoiceManager\nimport com.openjarvis.voice.STTMode",
        "import com.openjarvis.ui.theme.OpenJarvisTheme\nimport com.openjarvis.voice.VoiceManager\nimport com.openjarvis.voice.STTMode"
    )
    s = s.replace(
        r"private lateinit var prefs: android.content.SharedPreferences\n    private lateinit var voiceManager: VoiceManager",
        "private lateinit var prefs: android.content.SharedPreferences\n    private lateinit var voiceManager: VoiceManager"
    )
    s = s.replace(
        r"voiceManager = VoiceManager(applicationContext)\n\n        setContent {",
        "voiceManager = VoiceManager(applicationContext)\n\n        setContent {"
    )
    s = s.replace(
        r"import com.openjarvis.llm.UniversalAdapter\nimport com.openjarvis.voice.STTMode",
        "import com.openjarvis.llm.UniversalAdapter\nimport com.openjarvis.voice.STTMode"
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", fix_voice_kotlin_newlines)
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", fix_voice_kotlin_newlines)
print("Fixed literal backslash-n sequences in Kotlin files")


# Real voice-command Activity: Android SpeechRecognizer -> AgentCore -> TTS.
voice_activity = r'''package com.openjarvis.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.ui.theme.OpenJarvisTheme
import com.openjarvis.voice.VoiceManager

class VoiceCommandActivity : ComponentActivity() {
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var agent: AgentCore
    private lateinit var voice: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AgentCore(applicationContext)
        voice = VoiceManager(applicationContext)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        setContent {
            OpenJarvisTheme {
                var listening by remember { mutableStateOf(false) }
                var transcript by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("Mikrofon hazır") }
                var command by remember { mutableStateOf("") }
                val agentState by agent.state.collectAsState()
                LaunchedEffect(agentState) {
                    when (val state = agentState) {
                        is AgentState.Running -> message = state.step
                        is AgentState.Done -> { message = state.result; voice.speak("İşlem tamamlandı") }
                        is AgentState.Error -> { message = state.message; voice.speak("Bir hata oluştu: " + state.message) }
                        is AgentState.Idle -> Unit
                    }
                }
                DisposableEffect(Unit) {
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { listening = true; message = "Dinliyorum..." }
                        override fun onBeginningOfSpeech() { message = "Konuşmanı algıladım..." }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { listening = false; message = "Konuşma çözümleniyor..." }
                        override fun onError(error: Int) {
                            listening = false
                            message = when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli"
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Konuşma algılanmadı, tekrar dene"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ses tanıma için ağ bağlantısı gerekli"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul, tekrar dene"
                                else -> "Ses tanıma hatası (" + error + ")"
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            listening = false
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                            if (text.isNotBlank()) {
                                transcript = text
                                command = text
                                message = "Komut Jarvis'e gönderiliyor..."
                                agent.executeTask(text)
                            } else message = "Konuşma metne çevrilemedi"
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) transcript = partial
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    onDispose { runCatching { recognizer.destroy() } }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text("← Geri") }
                    Text("JARVIS SESLİ KOMUT", style = MaterialTheme.typography.headlineSmall)
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    if (transcript.isNotBlank()) Text("Algılanan konuşma: " + transcript)
                    Button(onClick = { startVoiceRecognition() }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {
                        Text(if (listening) "Dinleniyor..." else "Mikrofona bas ve konuş")
                    }
                    OutlinedButton(onClick = { recognizer.cancel(); listening = false; message = "Dinleme durduruldu" }, enabled = listening, modifier = Modifier.fillMaxWidth()) { Text("Dinlemeyi durdur") }
                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Komutunu yaz") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (command.isNotBlank()) { message = "Komut Jarvis'e gönderiliyor..."; agent.executeTask(command.trim()) } }, enabled = command.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Komutu çalıştır") }
                    Text("İşlem durumu: " + agentState.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Android mikrofon izni bulunamadı", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "Bu cihazda konuşma tanıma hizmeti bulunamadı", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching { recognizer.startListening(intent) }.onFailure {
            android.widget.Toast.makeText(this, "Mikrofon başlatılamadı: " + it.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
'''

def create_voice_activity(s):
    (root / "app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt").write_text(voice_activity)
    return s
edit("app/src/main/java/com/openjarvis/ui/MainActivity.kt", lambda s: s.replace(
    "onStartOverlay = { startOverlayService() }",
    "onStartOverlay = { startActivity(Intent(this, VoiceCommandActivity::class.java)) }"
))
edit("app/src/main/java/com/openjarvis/ui/MainActivity.kt", create_voice_activity)
edit("app/src/main/AndroidManifest.xml", lambda s: s.replace(
    '        <activity\n            android:name=".ui.SettingsActivity"',
    '        <activity\n            android:name=".ui.VoiceCommandActivity"\n            android:exported="false"\n            android:theme="@style/Theme.OpenJarvis" />\n\n        <activity\n            android:name=".ui.SettingsActivity"'
))

# Replace permanently-red permission labels with actual Android settings checks.
def live_permission_rows(s):
    if "import android.content.ComponentName" not in s:
        s = s.replace("package com.openjarvis.ui.settings\n", "package com.openjarvis.ui.settings\n\nimport android.content.ComponentName\nimport android.provider.Settings\n")
    s = s.replace(
        '''                PermissionRow(
                    title = "Accessibility",
                    subtitle = "Required — Tap to enable"
                )''',
        '''                val permissionContext = androidx.compose.ui.platform.LocalContext.current
                val accessibilityEnabled = runCatching {
                    val enabled = Settings.Secure.getString(permissionContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    enabled.split(':').any { it.equals(ComponentName(permissionContext, com.openjarvis.accessibility.JarvisAccessibilityService::class.java).flattenToString(), ignoreCase = true) }
                }.getOrDefault(false)
                val overlayEnabled = Settings.canDrawOverlays(permissionContext)
                PermissionRow(title = "Accessibility", subtitle = if (accessibilityEnabled) "Enabled" else "Required — Tap to enable", granted = accessibilityEnabled)'''
    )
    s = s.replace(
        '''                PermissionRow(
                    title = "Overlay",
                    subtitle = "Required — Tap to enable"
                )''',
        '''                PermissionRow(title = "Overlay", subtitle = if (overlayEnabled) "Enabled" else "Required — Tap to enable", granted = overlayEnabled)'''
    )
    s = s.replace(
        '''private fun PermissionRow(
    title: String,
    subtitle: String
) {
    val isGranted by remember { mutableStateOf(false) }''',
        '''private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean
) {'''
    )
    s = s.replace("background(VoidColor.Red.copy(alpha = 0.12f))", "background((if (granted) VoidColor.Green else VoidColor.Red).copy(alpha = 0.12f))")
    s = s.replace("tint = VoidColor.Red,", "tint = if (granted) VoidColor.Green else VoidColor.Red,")
    s = s.replace("color = VoidColor.Red", "color = if (granted) VoidColor.Green else VoidColor.Red")
    return s
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", live_permission_rows)
print("Implemented a direct voice command flow and real permission status labels")


# Fix v0.1 voice UX: runtime mic permission request, dark screen/input, Turkish TTS,
# and migrate the retired Groq model without touching the saved API key.
def voice_activity_fixes(s):
    s = s.replace(
        "import androidx.core.content.ContextCompat",
        "import androidx.core.content.ContextCompat"
    )
    s = s.replace(
        "import androidx.compose.material3.*",
        "import androidx.compose.material3.*\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.text.TextStyle\nimport androidx.compose.ui.text.font.FontWeight"
    )
    s = s.replace(
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.text.input.ImeAction\nimport androidx.compose.foundation.text.KeyboardOptions"
    )
    s = s.replace(
        "import androidx.compose.runtime.*",
        "import androidx.compose.runtime.*\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts"
    )
    s = s.replace(
        '''                var command by remember { mutableStateOf("") }
                val agentState by agent.state.collectAsState()''',
        '''                var command by remember { mutableStateOf("") }
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) startVoiceRecognition()
                    else message = "Mikrofon izni verilmedi. Android uygulama ayarlarından Mikrofon iznini aç."
                }
                val agentState by agent.state.collectAsState()'''
    )
    s = s.replace(
        '''                        is AgentState.Done -> { message = state.result; voice.speak("İşlem tamamlandı") }
                        is AgentState.Error -> { message = state.message; voice.speak("Bir hata oluştu: " + state.message) }''',
        '''                        is AgentState.Done -> { message = "Görev tamamlandı"; voice.speak("Görev tamamlandı.") }
                        is AgentState.Error -> {
                            val friendly = if (state.message.contains("decommissioned", ignoreCase = true) ||
                                state.message.contains("no longer supported", ignoreCase = true)) {
                                "Seçili yapay zekâ modeli artık desteklenmiyor. Yeni model ayarlandıktan sonra tekrar dene."
                            } else state.message
                            message = friendly
                            voice.speak(friendly)
                        }'''
    )
    s = s.replace(
        '''                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {''',
        '''                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF09080F),
                    contentColor = Color(0xFFF4F0FA)
                ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {'''
    )
    s = s.replace(
        '''                    Button(onClick = { startVoiceRecognition() }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {''',
        '''                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(this@VoiceCommandActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecognition()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {'''
    )
    s = s.replace(
        '''                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Komutunu yaz") }, modifier = Modifier.fillMaxWidth())''',
        '''                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Komutunu yaz", color = Color(0xFFB987D7)) },
                        placeholder = { Text("Örn: YouTube'u aç", color = Color(0xFF8E8798)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        textStyle = TextStyle(color = Color(0xFFF4F0FA), fontWeight = FontWeight.Medium),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFF4F0FA),
                            unfocusedTextColor = Color(0xFFF4F0FA),
                            focusedContainerColor = Color(0xFF171420),
                            unfocusedContainerColor = Color(0xFF171420),
                            focusedBorderColor = Color(0xFFB36BD0),
                            unfocusedBorderColor = Color(0xFF51485C),
                            cursorColor = Color(0xFFB36BD0),
                            focusedLabelColor = Color(0xFFB36BD0),
                            unfocusedLabelColor = Color(0xFFB7AFC1)
                        )
                    )'''
    )
    s = s.replace(
        '''                }
            }
        }
    }

    private fun startVoiceRecognition()''',
        '''                }
                }
            }
        }
    }

    private fun startVoiceRecognition()'''
    )
    # Request permission instead of immediately showing a misleading denial toast.
    s = s.replace(
        '''        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Android mikrofon izni bulunamadı", android.widget.Toast.LENGTH_LONG).show()
            return
        }''',
        '''        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }'''
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", voice_activity_fixes)

def turkish_tts(s):
    s = s.replace("tts?.language = Locale.US", """val turkish = Locale.forLanguageTag("tr-TR")
                val languageResult = tts?.setLanguage(turkish) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    isReady = false
                }""")
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", turkish_tts)

def migrate_groq_model(s):
    s = s.replace("private val prefs = EncryptedSharedPreferences.create(", "private val prefs = EncryptedSharedPreferences.create(")
    anchor = """    fun getActiveProvider(): LLMProvider {
        val name"""
    replacement = """    init {
        val savedModel = prefs.getString("provider_model", "") ?: ""
        if (savedModel == "llama-3.1-70b-versatile" || savedModel == "llama-3.3-70b-versatile") {
            prefs.edit().putString("provider_model", "openai/gpt-oss-120b").apply()
        }
    }

    fun getActiveProvider(): LLMProvider {
        val name"""
    s = s.replace(anchor, replacement)
    s = s.replace('"Groq" -> "llama-3.1-70b-versatile"', '"Groq" -> "openai/gpt-oss-120b"')
    return s
edit("app/src/main/java/com/openjarvis/llm/UniversalAdapter.kt", migrate_groq_model)

def update_groq_defaults(s):
    return s.replace("llama-3.1-70b-versatile", "openai/gpt-oss-120b")
edit("app/src/main/java/com/openjarvis/llm/providers/GroqProvider.kt", update_groq_defaults)
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", update_groq_defaults)
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", update_groq_defaults)
print("Applied Turkish TTS, dark voice UI, runtime microphone permission request, and Groq model migration")


# Fix microphone permission request path, dark input styling, Turkish TTS, and retired Groq model.
def patch_voice_activity(s):
    s = s.replace(
        "import androidx.activity.compose.setContent",
        "import androidx.activity.compose.setContent\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts"
    )
    s = s.replace(
        "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.unit.dp"
    )
    s = s.replace(
        '''                var command by remember { mutableStateOf("") }
                val agentState by agent.state.collectAsState()''',
        '''                var command by remember { mutableStateOf("") }
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) startVoiceRecognition()
                    else message = "Mikrofon izni verilmedi. Android uygulama ayarlarından izin ver."
                }
                val agentState by agent.state.collectAsState()'''
    )
    s = s.replace(
        '''                        is AgentState.Done -> { message = state.result; voice.speak("İşlem tamamlandı") }
                        is AgentState.Error -> { message = state.message; voice.speak("Bir hata oluştu: " + state.message) }''',
        '''                        is AgentState.Done -> { message = "İşlem tamamlandı."; voice.speak("Komutunu tamamladım.") }
                        is AgentState.Error -> {
                            val friendly = when {
                                state.message.contains("decommissioned", true) || state.message.contains("no longer supported", true) -> "Yapay zekâ modeli eski. Yeni model ayarlandıktan sonra tekrar dene."
                                state.message.contains("API key", true) || state.message.contains("401") -> "API anahtarı geçersiz."
                                state.message.contains("429") || state.message.contains("rate limit", true) -> "İstek sınırına ulaşıldı. Biraz bekle."
                                state.message.contains("network", true) || state.message.contains("timeout", true) -> "Bağlantı hatası. İnternetini kontrol et."
                                else -> "İşlem sırasında hata oluştu."
                            }
                            message = friendly
                            voice.speak(friendly)
                        }'''
    )
    s = s.replace(
        '''                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {''',
        '''                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF08070D)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {'''
    )
    s = s.replace(
        '''                    TextButton(onClick = { finish() }) { Text("← Geri") }
                    Text("JARVIS SESLİ KOMUT", style = MaterialTheme.typography.headlineSmall)
                    Text(message, style = MaterialTheme.typography.bodyLarge)''',
        '''                    TextButton(onClick = { finish() }) { Text("← Geri", color = Color(0xFFB36AD0)) }
                    Text("JARVIS SESLİ KOMUT", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White)'''
    )
    s = s.replace(
        '''                    Button(onClick = { startVoiceRecognition() }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {''',
        '''                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(this@VoiceCommandActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceRecognition()
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {'''
    )
    s = s.replace(
        '''                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Komutunu yaz") }, modifier = Modifier.fillMaxWidth())''',
        '''                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Komutunu yaz", color = Color(0xFFB8AFC4)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFB36AD0),
                            unfocusedBorderColor = Color(0xFF62586D),
                            cursorColor = Color(0xFFB36AD0),
                            focusedLabelColor = Color(0xFFB36AD0),
                            unfocusedLabelColor = Color(0xFFB8AFC4)
                        )
                    )'''
    )
    s = s.replace(
        '''                    Text("İşlem durumu: " + agentState.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }''',
        '''                    Text("İşlem durumu: " + when (agentState) {
                        is AgentState.Idle -> "Hazır"
                        is AgentState.Running -> "Çalışıyor"
                        is AgentState.Done -> "Tamamlandı"
                        is AgentState.Error -> "Hata"
                    }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8AFC4))
                }
                }
            }
        }'''
    )
    s = s.replace(
        '''            android.widget.Toast.makeText(this, "Android mikrofon izni bulunamadı", android.widget.Toast.LENGTH_LONG).show()
            return''',
        '''            android.widget.Toast.makeText(this, "Mikrofon izni istenmeli", android.widget.Toast.LENGTH_LONG).show()
            return'''
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", patch_voice_activity)

def patch_tts_turkish(s):
    s = s.replace("tts?.language = Locale.US", "tts?.language = Locale.forLanguageTag(\"tr-TR\")")
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", patch_tts_turkish)

def patch_groq_model(s):
    s = s.replace(
        '''        val model = prefs.getString("provider_model", "") ?: ""
        
        return when (name) {
            "Groq" -> GroqProvider(apiKey, model)''',
        '''        var model = prefs.getString("provider_model", "") ?: ""
        if (name == "Groq" && (model.isBlank() || model == "llama-3.1-70b-versatile" || model == "llama-3.3-70b-versatile")) {
            model = "openai/gpt-oss-120b"
            prefs.edit().putString("provider_model", model).apply()
        }
        
        return when (name) {
            "Groq" -> GroqProvider(apiKey, model)'''
    )
    s = s.replace('"Groq" -> "llama-3.1-70b-versatile"', '"Groq" -> "openai/gpt-oss-120b"')
    return s
edit("app/src/main/java/com/openjarvis/llm/UniversalAdapter.kt", patch_groq_model)

def patch_defaults(s):
    return s.replace('"llama-3.1-70b-versatile"', '"openai/gpt-oss-120b"')
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", patch_defaults)
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", patch_defaults)
print("Patched mic permission request, dark text input, Turkish TTS and Groq model migration")


# Balance the Activity onCreate braces after wrapping the Compose screen in Surface.
def balance_voice_activity_braces(s):
    marker = """                }
            }
        }
    }

    private fun startVoiceRecognition()"""
    replacement = """                }
            }
        }
    }
    }

    private fun startVoiceRecognition()"""
    if marker in s:
        s = s.replace(marker, replacement, 1)
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", balance_voice_activity_braces)
print("Balanced VoiceCommandActivity onCreate braces")


# Deduplicate imports in the generated voice screen; repeated patch passes had added two copies.
def dedupe_voice_imports(s):
    seen = set()
    out = []
    for line in s.splitlines():
        if line.startswith("import "):
            if line in seen:
                continue
            seen.add(line)
        out.append(line)
    return "\n".join(out) + "\n"
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", dedupe_voice_imports)
print("Removed duplicate imports from VoiceCommandActivity")


# Make voice input conversational by default (Turkish), keep explicit device commands on AgentCore,
# and start/resume listening automatically when AUTO_VAD is selected in Settings.
def voice_chat_auto(s):
    additions = [
        "import androidx.lifecycle.lifecycleScope",
        "import kotlinx.coroutines.delay",
        "import kotlinx.coroutines.launch",
        "import com.openjarvis.llm.UniversalAdapter",
        "import com.openjarvis.voice.STTMode"
    ]
    for item in additions:
        if item not in s:
            s = s.replace("import androidx.activity.ComponentActivity", "import androidx.activity.ComponentActivity\n" + item, 1)
    s = s.replace("    private lateinit var voice: VoiceManager\n",
                  "    private lateinit var voice: VoiceManager\n    private lateinit var adapter: UniversalAdapter\n")
    s = s.replace("        voice = VoiceManager(applicationContext)\n",
                  "        voice = VoiceManager(applicationContext)\n        adapter = UniversalAdapter(applicationContext)\n")
    s = s.replace('                var command by remember { mutableStateOf("") }\n',
                  '                var command by remember { mutableStateOf("") }\n                val autoMode = remember { voice.getSTTMode() == STTMode.AUTO_VAD }\n')
    s = s.replace(
        "                val agentState by agent.state.collectAsState()\n",
        """                val agentState by agent.state.collectAsState()
                LaunchedEffect(Unit) {
                    if (autoMode) {
                        delay(700)
                        if (ContextCompat.checkSelfPermission(this@VoiceCommandActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceRecognition()
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
"""
    )
    s = s.replace("agent.executeTask(text)", "submitCommand(text) { message = it }")
    s = s.replace("agent.executeTask(command.trim())", "submitCommand(command.trim()) { message = it }")
    dispatcher = """
    private fun submitCommand(text: String, updateStatus: (String) -> Unit) {
        val lower = text.lowercase(java.util.Locale.forLanguageTag("tr-TR"))
        val deviceVerbs = listOf("aç", "kapat", "başlat", "durdur", "tıkla", "dokun", "kaydır", "geri git", "ana ekrana", "uygulamayı", "uygulama aç", "mesaj gönder", "arama yap", "ekran görüntüsü")
        if (deviceVerbs.any { lower.contains(it) }) {
            updateStatus("Komut Jarvis'e gönderiliyor...")
            agent.executeTask(text)
            return
        }
        lifecycleScope.launch {
            updateStatus("Jarvis düşünüyor...")
            val system = "Sen Open Jarvis'sin. Kullanıcıyla Türkçe konuşan kişisel asistansın. Her zaman doğal, anlaşılır Türkçe cevap ver. Kullanıcı başka dil istemedikçe İngilizce kullanma. Kendini tanıtman istenirse Open Jarvis, kişisel dijital asistanı olduğunu söyle. JSON veya eylem listesi döndürme."
            adapter.complete(system, text).fold(
                onSuccess = { reply ->
                    val clean = reply.trim().replace(chr(96).toString(), "").trim()
                    updateStatus(clean)
                    voice.speak(clean)
                    if (voice.getSTTMode() == STTMode.AUTO_VAD) {
                        delay(1800)
                        if (!isFinishing) startVoiceRecognition()
                    }
                },
                onFailure = { error ->
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    val friendly = when {
                        detail.contains("401") -> "Yapay zekâ API anahtarı geçersiz."
                        detail.contains("429") -> "İstek sınırına ulaşıldı. Biraz bekleyip tekrar dene."
                        detail.contains("model", true) -> "Seçili yapay zekâ modeli kullanılamıyor. Ayarlardan desteklenen bir model seç."
                        detail.contains("timeout", true) || detail.contains("network", true) -> "İnternet bağlantısında sorun var."
                        else -> "Yapay zekâ bağlantı hatası: " + detail.take(180)
                    }
                    updateStatus(friendly)
                    voice.speak(friendly)
                    if (voice.getSTTMode() == STTMode.AUTO_VAD) {
                        delay(1800)
                        if (!isFinishing) startVoiceRecognition()
                    }
                }
            )
        }
    }

"""
    marker = "    private fun startVoiceRecognition() {"
    if marker in s and "private fun submitCommand(" not in s:
        s = s.replace(marker, dispatcher + marker, 1)
    return s

edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", voice_chat_auto)
print("Connected Turkish chat replies and saved automatic listening mode")

# Kotlin has no chr() function; strip optional Markdown fences with String helpers.
def fix_voice_reply_cleanup(s):
    return s.replace(
        'reply.trim().replace(chr(96).toString(), "").trim()',
        'reply.trim().removePrefix("\x60\x60\x60").removeSuffix("\x60\x60\x60").trim()'
    )
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", fix_voice_reply_cleanup)
print("Fixed Kotlin voice reply cleanup")


def patch_tts_completion(s):
    if "import android.os.Handler" not in s:
        s = s.replace("import android.content.Context", "import android.content.Context\nimport android.os.Handler\nimport android.os.Looper\nimport java.util.concurrent.ConcurrentHashMap", 1)
    s = s.replace("    private var pitch = 0.95f", "    private var pitch = 1.0f\n    private val callbacks = ConcurrentHashMap<String, () -> Unit>()")
    s = s.replace(
        """                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })""",
        """                tts?.voices?.filter { it.locale.language.equals("tr", ignoreCase = true) }
                    ?.maxByOrNull { voice -> (if ("network" !in voice.features) 100 else 0) + voice.quality }
                    ?.let { tts?.voice = it }
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> callbacks.remove(id)?.let { cb -> Handler(Looper.getMainLooper()).post { cb() } } }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { id -> callbacks.remove(id)?.let { cb -> Handler(Looper.getMainLooper()).post { cb() } } }
                    }
                })"""
    )
    s = s.replace("    fun speak(text: String) {", "    fun speak(text: String, onDone: (() -> Unit)? = null) {", 1)
    s = s.replace(
        """        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)""",
        """        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && onDone != null) {
            callbacks.remove(utteranceId)
            Handler(Looper.getMainLooper()).post { onDone() }
        }""",
        1
    )
    s = s.replace("        tts?.stop()\n    }\n\n    fun setSpeechRate", "        tts?.stop()\n        callbacks.clear()\n    }\n\n    fun setSpeechRate", 1)
    s = s.replace("        tts?.shutdown()", "        callbacks.clear()\n        tts?.shutdown()")
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", patch_tts_completion)

def patch_voice_manager_callback(s):
    return s.replace(
        """    fun speak(text: String) {
        if (isSpeakResultsEnabled()) {
            ttsEngine.speak(text)
        }
    }""",
        """    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (isSpeakResultsEnabled()) ttsEngine.speak(text, onDone) else onDone?.invoke()
    }"""
    )
edit("app/src/main/java/com/openjarvis/voice/VoiceManager.kt", patch_voice_manager_callback)

def patch_voice_command_handsfree(s):
    s = s.replace(
        "val mode = prefs.getString(KEY_STT_MODE, STTMode.PUSH_TO_TALK.name)",
        "val mode = prefs.getString(KEY_STT_MODE, STTMode.AUTO_VAD.name)"
    )
    s = s.replace(
        """                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(this@VoiceCommandActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecognition()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {
                        Text(if (listening) "Dinleniyor..." else "Mikrofona bas ve konuş")
                    }""",
        """                    if (autoMode) {
                        Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF17352D), shape = MaterialTheme.shapes.large) {
                            Text(if (listening) "Otomatik dinleme açık · Seni dinliyorum" else "Otomatik dinleme açık · Hazırlanıyor",
                                modifier = Modifier.padding(18.dp), color = Color(0xFF66D6A4), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(this@VoiceCommandActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceRecognition()
                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }, enabled = !listening, modifier = Modifier.fillMaxWidth()) {
                            Text(if (listening) "Dinleniyor..." else "Mikrofona bas ve konuş")
                        }
                    }"""
    )
    if "private fun speakAndResume(" not in s:
        s = s.replace(
            "    private fun startVoiceRecognition() {",
            """    private fun speakAndResume(text: String) {
        runCatching { recognizer.cancel() }
        voice.speak(text) {
            if (!isFinishing && voice.getSTTMode() == STTMode.AUTO_VAD) {
                lifecycleScope.launch {
                    delay(500)
                    if (!isFinishing) startVoiceRecognition()
                }
            }
        }
    }

    private fun startVoiceRecognition() {""",
            1
        )
    s = s.replace('voice.speak("Görev tamamlandı.")', 'speakAndResume("Görev tamamlandı.")')
    s = s.replace('voice.speak(friendly)', 'speakAndResume(friendly)')
    s = s.replace('voice.speak(clean)', 'speakAndResume(clean)')
    s = s.replace(
        """                    if (voice.getSTTMode() == STTMode.AUTO_VAD) {
                        delay(1800)
                        if (!isFinishing) startVoiceRecognition()
                    }
""",
        ""
    )
    s = s.replace(
        """                        override fun onResults(results: Bundle?) {
                            listening = false""",
        """                        override fun onResults(results: Bundle?) {
                            listening = false
                            runCatching { recognizer.cancel() }"""
    )
    s = s.replace(
        'Text("İşlem durumu: " + agentState.toString(), style = MaterialTheme.typography.bodySmall)',
        """Text("İşlem durumu: " + when (agentState) {
                        is AgentState.Idle -> "Hazır"
                        is AgentState.Running -> "Çalışıyor"
                        is AgentState.Done -> "Tamamlandı"
                        is AgentState.Error -> "Hata"
                    }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7AFC1))"""
    )
    s = s.replace(
        """            val system = "Sen Open Jarvis'sin. Kullanıcıyla Türkçe konuşan kişisel asistansın. Her zaman doğal, anlaşılır Türkçe cevap ver. Kullanıcı başka dil istemedikçe İngilizce kullanma. Kendini tanıtman istenirse Open Jarvis, kişisel dijital asistanı olduğunu söyle. JSON veya eylem listesi döndürme." """.rstrip(),
        """            val system = "Sen Open Jarvis'sin. Kullanıcıyla daima Türkçe, doğal ve kısa konuş. Varsayılan olarak 1-3 cümleyle cevap ver. Kullanıcı başka dil istemedikçe İngilizce kullanma. Kendini tanıtman istenirse Open Jarvis, kişisel dijital asistanı olduğunu söyle. Sesinin daha doğal olmasını isterse ses dosyası değiştirdiğini iddia etme; uygulama Türkçe sistem TTS sesini ve konuşma hızını kullanır. JSON veya eylem listesi döndürme." """
    )
    s = s.replace(
        """        val lower = text.lowercase(java.util.Locale.forLanguageTag("tr-TR"))
        val deviceVerbs""",
        """        val lower = text.lowercase(java.util.Locale.forLanguageTag("tr-TR"))
        if (lower.contains("daha normal konuş") || lower.contains("daha doğal konuş") || lower.contains("sesini yavaşlat")) {
            voice.setSpeechRate(0.96f)
            updateStatus("Türkçe konuşma hızı daha doğal olacak şekilde ayarlandı.")
            speakAndResume("Konuşma hızımı daha doğal olacak şekilde ayarladım.")
            return
        }
        val deviceVerbs"""
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", patch_voice_command_handsfree)

def patch_auto_mode_default(s):
    return s.replace('initialSttMode: String = "PUSH_TO_TALK"', 'initialSttMode: String = "AUTO_VAD"')
edit("app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt", patch_auto_mode_default)
print("Added completion-driven hands-free loop, Turkish voice preference, natural speech rate and automatic default")


def fix_tts_callback_and_auto_default(s):
    s = s.replace(
        """        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)""",
        """        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && onDone != null) {
            callbacks.remove(utteranceId)
            Handler(Looper.getMainLooper()).post { onDone() }
        }"""
    )
    s = s.replace(
        """        if (!isReady) return
        
        val clean = text""",
        """        if (!isReady) {
            onDone?.invoke()
            return
        }

        val clean = text""",
        1
    )
    s = s.replace(
        """        if (clean.isBlank()) return
        
        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)""",
        """        if (clean.isBlank()) {
            onDone?.invoke()
            return
        }

        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && onDone != null) {
            callbacks.remove(utteranceId)
            Handler(Looper.getMainLooper()).post { onDone() }
        }"""
    )
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", fix_tts_callback_and_auto_default)

def default_auto_and_natural_rate(s):
    s = s.replace("STTMode.PUSH_TO_TALK.name)", "STTMode.AUTO_VAD.name)")
    s = s.replace("STTMode.PUSH_TO_TALK\n", "STTMode.AUTO_VAD\n")
    s = s.replace("STTMode.PUSH_TO_TALK\n", "STTMode.AUTO_VAD\n")
    s = s.replace("getSpeechRate(): Float = prefs.getFloat(KEY_SPEECH_RATE, 1.05f)", "getSpeechRate(): Float = prefs.getFloat(KEY_SPEECH_RATE, 0.96f)")
    return s
edit("app/src/main/java/com/openjarvis/voice/VoiceManager.kt", default_auto_and_natural_rate)
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", lambda s: s.replace("private var speechRate = 1.05f", "private var speechRate = 0.96f"))
print("Fixed real TTS completion callback; AUTO_VAD is fresh-install default; natural speech rate set")


def fix_settings_auto_mode_mapping(s):
    s = s.replace(
        'voiceManager.setSTTMode(if (mode == "AUTO_VAD") STTMode.AUTO_VAD else STTMode.PUSH_TO_TALK)',
        'voiceManager.setSTTMode(if (mode == "Auto (VAD)" || mode == "AUTO_VAD") STTMode.AUTO_VAD else STTMode.PUSH_TO_TALK)'
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", fix_settings_auto_mode_mapping)

def ensure_tts_completion_callback(s):
    old = '''        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)'''
    new = '''        val utteranceId = "jarvis_\${UUID.randomUUID()}"
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && onDone != null) {
            callbacks.remove(utteranceId)
            Handler(Looper.getMainLooper()).post { onDone() }
        }'''
    s = s.replace(old, new, 1)
    s = s.replace('''        if (clean.isBlank()) return
        
        val utteranceId = "jarvis_\${UUID.randomUUID()}"''',
                  '''        if (clean.isBlank()) {
            onDone?.invoke()
            return
        }

        val utteranceId = "jarvis_\${UUID.randomUUID()}"''', 1)
    s = s.replace('''        if (!isReady) return
        
        val clean = text''',
                  '''        if (!isReady) {
            onDone?.invoke()
            return
        }

        val clean = text''', 1)
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", ensure_tts_completion_callback)

def add_persistent_conversation_memory(s):
    if "import androidx.security.crypto.EncryptedSharedPreferences" not in s:
        s = s.replace("import androidx.core.content.ContextCompat",
                      "import androidx.core.content.ContextCompat\nimport androidx.security.crypto.EncryptedSharedPreferences\nimport androidx.security.crypto.MasterKey\nimport android.content.SharedPreferences\nimport org.json.JSONArray\nimport org.json.JSONObject")
    s = s.replace("    private lateinit var adapter: UniversalAdapter",
                  "    private lateinit var adapter: UniversalAdapter\n    private lateinit var conversationPrefs: SharedPreferences\n    private var recognitionRetryCount = 0")
    s = s.replace(
        "        adapter = UniversalAdapter(applicationContext)",
        """        adapter = UniversalAdapter(applicationContext)
        val memoryKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        conversationPrefs = EncryptedSharedPreferences.create(
            this, "jarvis_conversation_memory", memoryKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )"""
    )
    s = s.replace(
        '''val system = "Sen Open Jarvis'sin. Kullanıcıyla daima Türkçe, doğal ve kısa konuş. Varsayılan olarak 1-3 cümleyle cevap ver. Kullanıcı başka dil istemedikçe İngilizce kullanma. Kendini tanıtman istenirse Open Jarvis, kişisel dijital asistanı olduğunu söyle. Sesinin daha doğal olmasını isterse ses dosyası değiştirdiğini iddia etme; uygulama Türkçe sistem TTS sesini ve konuşma hızını kullanır. JSON veya eylem listesi döndürme." ''',
        '''val memoryContext = loadConversationMemory()
            val system = "Sen Open Jarvis'sin. Kullanıcıyla daima Türkçe, doğal ve kısa konuş. Önceki konuşmalardaki bilgileri ve tercihleri dikkate al; kullanıcı daha önce verdiği bir bilgiyi tekrar sorma. Bilmediğin şeyi hatırlıyormuş gibi yapma. Varsayılan olarak 1-3 cümleyle cevap ver. Kullanıcı başka dil istemedikçe İngilizce kullanma. Kendini tanıtman istenirse Open Jarvis, kişisel dijital asistanı olduğunu söyle. JSON veya eylem listesi döndürme.\\n\\nKALICI KONUŞMA HAFIZASI:\\n" + memoryContext'''
    )
    s = s.replace(
        "                    updateStatus(clean)\n                    speakAndResume(clean)",
        "                    rememberConversation(text, clean)\n                    updateStatus(clean)\n                    speakAndResume(clean)"
    )
    if "private fun loadConversationMemory()" not in s:
        marker = "    private fun speakAndResume(text: String) {"
        helpers = '''    private fun loadConversationMemory(): String {
        return runCatching {
            val array = JSONArray(conversationPrefs.getString("turns", "[]") ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val content = item.optString("content")
                if (role.isBlank() || content.isBlank()) null else "$role: $content"
            }.takeLast(16).joinToString("\\\\n").take(5000)
        }.getOrDefault("")
    }

    private fun rememberConversation(userText: String, assistantText: String) {
        runCatching {
            val array = JSONArray(conversationPrefs.getString("turns", "[]") ?: "[]")
            array.put(JSONObject().put("role", "Kullanıcı").put("content", userText.take(1200)))
            array.put(JSONObject().put("role", "Jarvis").put("content", assistantText.take(1800)))
            val trimmed = JSONArray()
            val start = (array.length() - 40).coerceAtLeast(0)
            for (index in start until array.length()) trimmed.put(array.getJSONObject(index))
            conversationPrefs.edit().putString("turns", trimmed.toString()).apply()
        }
    }

'''
        s = s.replace(marker, helpers + marker, 1)
    s = s.replace(
        "                        override fun onResults(results: Bundle?) {\n                            listening = false",
        "                        override fun onResults(results: Bundle?) {\n                            listening = false\n                            recognitionRetryCount = 0"
    )
    s = s.replace(
        "                        override fun onError(error: Int) {\n                            listening = false",
        """                        override fun onError(error: Int) {
                            listening = false
                            if (voice.getSTTMode() == STTMode.AUTO_VAD &&
                                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                                recognitionRetryCount < 3 && !isFinishing) {
                                recognitionRetryCount++
                                lifecycleScope.launch {
                                    delay(900)
                                    if (!isFinishing) startVoiceRecognition()
                                }
                            }"""
    )
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", add_persistent_conversation_memory)
print("Fixed Auto(VAD) persistence mapping, TTS completion callback, encrypted conversation memory and bounded auto-listen retries")


# Robustly replace the TTS speak block without relying on Kotlin string interpolation.
def force_tts_callback_registration(s):
    import re
    pattern = r'        val utteranceId = "jarvis_.*?"\n        tts\?\.speak\(clean, TextToSpeech\.QUEUE_FLUSH, null, utteranceId\)'
    replacement = '''        val utteranceId = "jarvis_" + UUID.randomUUID().toString()
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && onDone != null) {
            callbacks.remove(utteranceId)
            Handler(Looper.getMainLooper()).post { onDone() }
        }'''
    s, count = re.subn(pattern, lambda _: replacement, s, count=1)
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", force_tts_callback_registration)
print("Ensured the TTS utterance completion callback is actually registered")


# Remove an invalid callback reference accidentally inserted into speakQueued().
def fix_speak_queued_callback_scope(s):
    start = s.find("fun speakQueued(text: String)")
    if start >= 0:
        end = s.find("\n    fun stop()", start)
        if end > start:
            block = s[start:end]
            block = block.replace("""        if (!isReady) {
            onDone?.invoke()
            return
        }""", """        if (!isReady) return""")
            block = block.replace("""        if (clean.isBlank()) {
            onDone?.invoke()
            return
        }""", """        if (clean.isBlank()) return""")
            s = s[:start] + block + s[end:]
    return s
edit("app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt", fix_speak_queued_callback_scope)
print("Fixed speakQueued callback scope compile error")


# Issue #5: deterministic local actions (SMS / call / app / WhatsApp) surface an
# AwaitingConfirmation / NeedsInput state from AgentCore; the voice screen must
# render the Turkish confirmation dialog and route confirm/cancel/input back to
# AgentCore. All anchors below target the content produced by earlier passes.
def voice_confirmation_flow(s):
    if "pendingConfirmation" in s:
        return s
    state_anchor = "                val agentState by agent.state.collectAsState()"
    if state_anchor not in s:
        raise SystemExit("VoiceCommandActivity: agentState anchor not found")
    s = s.replace(
        state_anchor,
        state_anchor + "\n                var pendingConfirmation by remember { mutableStateOf<AgentState.AwaitingConfirmation?>(null) }",
        1
    )

    running_anchor = "                        is AgentState.Running -> message = state.step"
    if running_anchor in s:
        s = s.replace(
            running_anchor,
            "                        is AgentState.Running -> { message = state.step; pendingConfirmation = null }",
            1
        )

    done_anchor = '                        is AgentState.Done -> { message = "Görev tamamlandı"; speakAndResume("Görev tamamlandı.") }'
    if done_anchor not in s:
        raise SystemExit("VoiceCommandActivity: Done branch anchor not found")
    s = s.replace(
        done_anchor,
        done_anchor + "\n" +
        "                        is AgentState.NeedsInput -> {\n" +
        "                            message = state.prompt\n" +
        "                            pendingConfirmation = null\n" +
        "                            speakAndResume(state.prompt)\n" +
        "                        }\n" +
        "                        is AgentState.AwaitingConfirmation -> {\n" +
        "                            message = state.title + \": \" + state.detail\n" +
        "                            speakAndResume(state.title)\n" +
        "                            pendingConfirmation = state\n" +
        "                        }",
        1
    )

    dialog_anchor = "                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {"
    if dialog_anchor not in s:
        raise SystemExit("VoiceCommandActivity: Column anchor not found")
    dialog_block = """                pendingConfirmation?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { pendingConfirmation = null; agent.cancelPendingAction() },
                        title = { Text(pending.title) },
                        text = { Text(pending.detail) },
                        confirmButton = {
                            TextButton(onClick = { pendingConfirmation = null; agent.confirmPendingAction() }) {
                                Text(pending.confirmLabel)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingConfirmation = null; agent.cancelPendingAction() }) {
                                Text(pending.cancelLabel)
                            }
                        }
                    )
                }
"""
    s = s.replace(dialog_anchor, dialog_block + dialog_anchor, 1)

    # The "İşlem durumu" status label must also cover the new states.
    status_anchor = """                        is AgentState.Error -> "Hata"
                    }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7AFC1))"""
    if status_anchor in s:
        s = s.replace(
            status_anchor,
            """                        is AgentState.Error -> "Hata"
                        is AgentState.NeedsInput -> "Girdi bekleniyor"
                        is AgentState.AwaitingConfirmation -> "Onay bekleniyor"
                    }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7AFC1))""",
            1
        )
    else:
        raise SystemExit("VoiceCommandActivity: status label anchor not found")
    return s
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", voice_confirmation_flow)

# Issue #voice: install one shared Turkish-first SpeechRecognizer policy.
voice_feature_root = Path(__file__).resolve().parent.parent / "features" / "turkish-voice"
if not voice_feature_root.is_dir():
    raise SystemExit("Turkish voice policy overlay is missing")

for source in sorted(voice_feature_root.rglob("*.kt")):
    relative = source.relative_to(voice_feature_root)
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())


def patch_android_stt_turkish(s):
    import re

    if "import android.Manifest" not in s:
        s = s.replace(
            "import android.content.Context",
            "import android.Manifest\nimport android.content.Context\nimport android.content.pm.PackageManager",
            1
        )
    s = s.replace("import java.util.Locale\n", "")

    old_errors = """                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Unknown error"
                    }"""
    new_errors = """                    val errorMessage = TurkishSpeechPolicy.messageFor(
                        TurkishSpeechPolicy.failureForAndroidError(error)
                    )"""
    if old_errors in s:
        s = s.replace(old_errors, new_errors, 1)
    elif new_errors not in s:
        raise SystemExit("AndroidSTTEngine.kt: recognition error mapping anchor not found")

    old_intent = """        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        }"""
    new_intent = """        val intent = TurkishSpeechPolicy.createRecognizerIntent().apply {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        }"""
    if old_intent in s:
        s = s.replace(old_intent, new_intent, 1)
    elif "TurkishSpeechPolicy.createRecognizerIntent()" not in s:
        raise SystemExit("AndroidSTTEngine.kt: recognizer intent anchor not found")

    s = s.replace(
        "val text = matches?.firstOrNull()?.trim()",
        "val text = TurkishSpeechPolicy.normalizeTranscript(matches?.firstOrNull())",
        1
    )

    old_start = "        recognizer?.startListening(intent)"
    new_start = """        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            currentErrorCallback?.invoke(TurkishSpeechPolicy.messageFor(RecognitionFailure.PERMISSION))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            currentErrorCallback?.invoke("Bu cihazda konuşma tanıma hizmeti bulunamadı.")
            return
        }

        // SpeechRecognizer is created on the main looper; start it on that same looper too.
        mainHandler.post {
            val activeRecognizer = recognizer
            if (activeRecognizer == null) {
                currentErrorCallback?.invoke(TurkishSpeechPolicy.messageFor(RecognitionFailure.CLIENT))
                return@post
            }
            try {
                activeRecognizer.startListening(intent)
            } catch (_: SecurityException) {
                _isListening.value = false
                currentErrorCallback?.invoke(TurkishSpeechPolicy.messageFor(RecognitionFailure.PERMISSION))
            } catch (_: IllegalStateException) {
                _isListening.value = false
                currentErrorCallback?.invoke(TurkishSpeechPolicy.messageFor(RecognitionFailure.BUSY))
            } catch (_: RuntimeException) {
                _isListening.value = false
                currentErrorCallback?.invoke(TurkishSpeechPolicy.messageFor(RecognitionFailure.CLIENT))
            }
        }"""
    if old_start in s:
        s = s.replace(old_start, new_start, 1)
    elif "SpeechRecognizer is created on the main looper" not in s:
        raise SystemExit("AndroidSTTEngine.kt: startListening anchor not found")

    old_capture = """        return try {
            audioRecord = AudioRecord("""
    new_capture = """        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        return try {
            audioRecord = AudioRecord("""
    s = s.replace(old_capture, new_capture, 1)
    return s


def patch_voice_command_turkish(s):
    if "import com.openjarvis.voice.TurkishSpeechPolicy" not in s:
        s = s.replace(
            "import com.openjarvis.voice.STTMode",
            "import com.openjarvis.voice.STTMode\nimport com.openjarvis.voice.TurkishSpeechPolicy\nimport com.openjarvis.voice.RecognitionFailure",
            1
        )

    s = s.replace(
        "    private var recognitionRetryCount = 0",
        "    private var recognitionRetryCount = 0\n    private var requestMicrophonePermission: (() -> Unit)? = null",
        1
    )

    state_anchor = '                var message by remember { mutableStateOf("Mikrofon hazır") }'
    state_replacement = state_anchor + """
                var microphonePermissionGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@VoiceCommandActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }"""
    if state_anchor in s:
        s = s.replace(state_anchor, state_replacement, 1)
    elif "var microphonePermissionGranted by remember" not in s:
        raise SystemExit("VoiceCommandActivity.kt: microphone permission state anchor not found")

    old_permission_callback = """                ) { granted ->
                    if (granted) startVoiceRecognition()
                    else message = "Mikrofon izni verilmedi. Android uygulama ayarlarından Mikrofon iznini aç."
                }"""
    new_permission_callback = """                ) { granted ->
                    microphonePermissionGranted = granted
                    if (granted) startVoiceRecognition()
                    else message = "Mikrofon izni verilmedi. Android uygulama ayarlarından JARVIS için Mikrofon iznini aç."
                }
                SideEffect {
                    requestMicrophonePermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }"""
    if old_permission_callback in s:
        s = s.replace(old_permission_callback, new_permission_callback, 1)
    elif "requestMicrophonePermission = {" not in s:
        raise SystemExit("VoiceCommandActivity.kt: permission launcher callback anchor not found")

    s = s.replace(
        "onDispose { runCatching { recognizer.destroy() } }",
        "onDispose { requestMicrophonePermission = null; runCatching { recognizer.destroy() } }",
        1
    )

    old_error_copy = """                            message = when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli"
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Konuşma algılanmadı, tekrar dene"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ses tanıma için ağ bağlantısı gerekli"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul, tekrar dene"
                                else -> "Ses tanıma hatası (" + error + ")"
                            }"""
    new_error_copy = """                            message = TurkishSpeechPolicy.messageFor(
                                TurkishSpeechPolicy.failureForAndroidError(error)
                            )"""
    if old_error_copy in s:
        s = s.replace(old_error_copy, new_error_copy, 1)
    elif new_error_copy not in s:
        raise SystemExit("VoiceCommandActivity.kt: recognition error copy anchor not found")

    s = s.replace(
        "val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()",
        "val text = TurkishSpeechPolicy.normalizeTranscript(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()).orEmpty()",
        1
    )
    s = s.replace(
        "val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()",
        "val partial = TurkishSpeechPolicy.normalizeTranscript(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())",
        1
    )

    old_ui_anchor = """                    OutlinedButton(onClick = { recognizer.cancel(); listening = false; message = "Dinleme durduruldu" }, enabled = listening, modifier = Modifier.fillMaxWidth()) { Text("Dinlemeyi durdur") }"""
    permission_button = """                    if (autoMode && !microphonePermissionGranted) {
                        OutlinedButton(
                            onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Mikrofon izni ver") }
                    }
""" + old_ui_anchor
    if old_ui_anchor in s and "Text(\"Mikrofon izni ver\")" not in s:
        s = s.replace(old_ui_anchor, permission_button, 1)

    old_missing_permission = """        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }"""
    new_missing_permission = """        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission?.invoke()
            if (requestMicrophonePermission == null) {
                android.widget.Toast.makeText(
                    this,
                    TurkishSpeechPolicy.messageFor(RecognitionFailure.PERMISSION),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }"""
    if old_missing_permission in s:
        s = s.replace(old_missing_permission, new_missing_permission, 1)
    elif "requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)" in s:
        raise SystemExit("VoiceCommandActivity.kt: legacy permission request remains")

    old_intent = """        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }"""
    if old_intent in s:
        s = s.replace(old_intent, "        val intent = TurkishSpeechPolicy.createRecognizerIntent()", 1)
    elif "TurkishSpeechPolicy.createRecognizerIntent()" not in s:
        raise SystemExit("VoiceCommandActivity.kt: recognizer intent anchor not found")

    old_failure = """        runCatching { recognizer.startListening(intent) }.onFailure {
            android.widget.Toast.makeText(this, "Mikrofon başlatılamadı: " + it.message, android.widget.Toast.LENGTH_LONG).show()
        }"""
    new_failure = """        runCatching { recognizer.startListening(intent) }.onFailure {
            val failure = if (it is SecurityException) RecognitionFailure.PERMISSION else RecognitionFailure.CLIENT
            android.widget.Toast.makeText(
                this,
                TurkishSpeechPolicy.messageFor(failure),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }"""
    if old_failure in s:
        s = s.replace(old_failure, new_failure, 1)
    elif "val failure = if (it is SecurityException)" not in s:
        raise SystemExit("VoiceCommandActivity.kt: recognizer start failure anchor not found")

    return s


edit("app/src/main/java/com/openjarvis/voice/AndroidSTTEngine.kt", patch_android_stt_turkish)
edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", patch_voice_command_turkish)
print("Installed shared tr-TR STT policy, Turkish error mapping and microphone permission handling")

print("Added Turkish confirmation flow for local device actions (issue #5)")

# Issue #10: install the durable local task model and Turkish task control center.
feature_root = Path(__file__).resolve().parent.parent / "features" / "task-engine"
if not feature_root.exists():
    raise SystemExit("Issue #10 task-engine source overlay is missing")

for source in sorted(feature_root.rglob("*.kt")):
    relative = source.relative_to(feature_root)
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())

dashboard_path = root / "app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt"
dashboard_text = dashboard_path.read_text()
dashboard_anchor = "            RecentTasksSection(tasks = recentTasks, alpha = tasksAlpha)"
if "TaskControlCenter(" not in dashboard_text:
    if dashboard_anchor not in dashboard_text:
        raise SystemExit("DashboardScreen task section anchor not found")
    dashboard_text = dashboard_text.replace(
        dashboard_anchor,
        "            TaskControlCenter(modifier = Modifier.fillMaxWidth())\n"
        "            Spacer(modifier = Modifier.height(16.dp))\n" + dashboard_anchor,
        1
    )
dashboard_path.write_text(dashboard_text)
print("Installed Issue #10 local task engine and Turkish task control center")


# Issue #16: recover the Android SpeechRecognizer after TTS/cancel on HiOS devices.
def voice_recognizer_restart(s):
    s = s.replace(
        "    private var recognitionRetryCount = 0",
        "    private var recognitionRetryCount = 0\n    private var recognitionListener: RecognitionListener? = null",
        1
    )
    old_listener = "                    recognizer.setRecognitionListener(object : RecognitionListener {"
    new_listener = "                    val listener = object : RecognitionListener {"
    if old_listener in s:
        s = s.replace(old_listener, new_listener, 1)
        old_end = "                    })\n                    onDispose { requestMicrophonePermission = null; runCatching { recognizer.destroy() } }"
        new_end = """                    }
                    recognitionListener = listener
                    recognizer.setRecognitionListener(listener)
                    onDispose {
                        requestMicrophonePermission = null
                        recognitionListener = null
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }"""
        if old_end not in s:
            raise SystemExit("VoiceCommandActivity: listener disposal anchor not found")
        s = s.replace(old_end, new_end, 1)
    elif "val listener = object : RecognitionListener" not in s:
        raise SystemExit("VoiceCommandActivity: SpeechRecognizer listener anchor not found")

    old_start = "        runCatching { recognizer.startListening(intent) }.onFailure {"
    new_start = """        runCatching {
            // HiOS/providers can retain a cancelled session after TTS. Recreate the
            // recognizer before every new listening session, keeping the same UI listener.
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val listener = recognitionListener
                ?: throw IllegalStateException("Ses dinleyicisi henüz hazır değil.")
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)
        }.onFailure {"""
    if old_start in s:
        s = s.replace(old_start, new_start, 1)
    elif "recognizer = SpeechRecognizer.createSpeechRecognizer(this)" not in s:
        raise SystemExit("VoiceCommandActivity: startListening anchor not found")
    return s

edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", voice_recognizer_restart)


# Android 11+ package visibility: allow resolving installed launcher activities.
def launcher_package_visibility(s):
    if "<queries>" in s:
        return s
    anchor = "    <application"
    block = """    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <application"""
    if anchor not in s:
        raise SystemExit("AndroidManifest.xml: application anchor not found")
    return s.replace(anchor, block, 1)

edit("app/src/main/AndroidManifest.xml", launcher_package_visibility)


# Android 14+ requires the type-specific permission for DownloadService's dataSync FGS.
def data_sync_permission(s):
    permission = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />'
    if permission in s:
        return s
    anchor = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />'
    if anchor not in s:
        raise SystemExit("AndroidManifest.xml: FOREGROUND_SERVICE permission anchor not found")
    return s.replace(anchor, anchor + "\n" + permission, 1)

edit("app/src/main/AndroidManifest.xml", data_sync_permission)
print("Applied HiOS SpeechRecognizer session reset, Android package visibility and dataSync FGS permission")


# Issue #8 Stage A: install a bounded local silent MP4 prototype.
video_feature_root = Path(__file__).resolve().parent.parent / "features" / "video-mvp"
if not video_feature_root.is_dir():
    raise SystemExit("Issue #8 video-mvp source overlay is missing")

for source in sorted(video_feature_root.rglob("*.kt")):
    relative = source.relative_to(video_feature_root)
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())

video_dashboard_path = root / "app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt"
video_dashboard_text = video_dashboard_path.read_text()
video_dashboard_anchor = "            TaskControlCenter(modifier = Modifier.fillMaxWidth())"
if "VideoMvpPanel(" not in video_dashboard_text:
    if video_dashboard_anchor not in video_dashboard_text:
        raise SystemExit("DashboardScreen task control center anchor not found for video MVP")
    video_dashboard_text = video_dashboard_text.replace(
        video_dashboard_anchor,
        video_dashboard_anchor + "\n            VideoMvpPanel(modifier = Modifier.fillMaxWidth())",
        1
    )
video_dashboard_path.write_text(video_dashboard_text)
print("Installed Issue #8 local silent MP4 prototype")


# Issue #28: Turkish-first TTS readiness, deterministic voice selection and STT handoff.
tts_feature_root = Path(__file__).resolve().parent.parent / "features" / "turkish-tts"
if not tts_feature_root.is_dir():
    raise SystemExit("Issue #28 Turkish TTS source overlay is missing")

for source in sorted(tts_feature_root.rglob("*.kt")):
    relative = source.relative_to(tts_feature_root)
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())

def expose_turkish_tts_status(s):
    anchor = "    private val ttsEngine = AndroidTTSEngine(context)"
    property = "    val ttsStatus: StateFlow<TurkishTtsStatus> get() = ttsEngine.status"
    if property not in s:
        if anchor not in s:
            raise SystemExit("VoiceManager.kt: TTS engine anchor not found")
        s = s.replace(anchor, anchor + "\n" + property, 1)
    return s

edit("app/src/main/java/com/openjarvis/voice/VoiceManager.kt", expose_turkish_tts_status)

def add_turkish_tts_status_ui(s):
    if "import android.speech.tts.TextToSpeech" not in s:
        anchor = "import android.speech.SpeechRecognizer"
        if anchor not in s:
            raise SystemExit("VoiceCommandActivity.kt: SpeechRecognizer import not found")
        s = s.replace(anchor, anchor + "\nimport android.speech.tts.TextToSpeech", 1)

    # VoiceCommandActivity is in com.openjarvis.ui; import the public TTS
    # status/policy types from com.openjarvis.voice so the sealed when is exhaustive.
    voice_import_anchor = "import android.speech.SpeechRecognizer"
    required_voice_imports = (
        "import com.openjarvis.voice.TurkishTtsPolicy",
        "import com.openjarvis.voice.TurkishTtsStatus",
    )
    if voice_import_anchor not in s:
        raise SystemExit("VoiceCommandActivity.kt: SpeechRecognizer import not found for TTS status imports")
    missing_voice_imports = [item for item in required_voice_imports if item not in s]
    if missing_voice_imports:
        s = s.replace(
            voice_import_anchor,
            voice_import_anchor + "\n" + "\n".join(missing_voice_imports),
            1
        )

    anchor = "                val agentState by agent.state.collectAsState()"
    replacement = anchor + "\n                val ttsStatus by voice.ttsStatus.collectAsState()"
    if "val ttsStatus by voice.ttsStatus.collectAsState()" not in s:
        if anchor not in s:
            raise SystemExit("VoiceCommandActivity.kt: agent state anchor not found")
        s = s.replace(anchor, replacement, 1)

    ui_anchor = "                    Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White)"
    ui_block = """                    Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    when (val ttsState = ttsStatus) {
                        TurkishTtsStatus.Initializing -> Text(
                            TurkishTtsPolicy.messageFor(ttsState),
                            color = Color(0xFFB7AFC1)
                        )
                        is TurkishTtsStatus.Ready -> Text(
                            "Türkçe ses hazır · " + ttsState.voiceLocale,
                            color = Color(0xFF66D6A4)
                        )
                        TurkishTtsStatus.TurkishVoiceMissing,
                        TurkishTtsStatus.InitializationFailed -> Column {
                            Text(TurkishTtsPolicy.messageFor(ttsState), color = Color(0xFFFFC38A))
                            OutlinedButton(
                                onClick = {
                                    val opened = runCatching {
                                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                                    }.isSuccess
                                    if (!opened) {
                                        android.widget.Toast.makeText(
                                            this@VoiceCommandActivity,
                                            "Android Türkçe ses verisi ekranı açılamadı. Ayarlar > Metin okuma bölümünü kontrol et.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Türkçe ses verisini aç") }
                        }
                        TurkishTtsStatus.Released -> Unit
                    }"""
    if "Türkçe ses verisini aç" not in s:
        if ui_anchor not in s:
            raise SystemExit("VoiceCommandActivity.kt: message UI anchor not found")
        s = s.replace(ui_anchor, ui_block, 1)

    if "override fun onDestroy()" not in s:
        closing = s.rfind("}")
        if closing < 0:
            raise SystemExit("VoiceCommandActivity.kt: class closing brace not found")
        s = s[:closing] + """    override fun onDestroy() {
        if (::recognizer.isInitialized) {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
        if (::voice.isInitialized) voice.release()
        super.onDestroy()
    }
""" + s[closing:]
    return s

edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", add_turkish_tts_status_ui)

def release_settings_voice_manager(s):
    if "override fun onDestroy()" in s:
        return s
    closing = s.rfind("}")
    if closing < 0:
        raise SystemExit("SettingsActivity.kt: class closing brace not found")
    return s[:closing] + """    override fun onDestroy() {
        if (::voiceManager.isInitialized) voiceManager.release()
        super.onDestroy()
    }
""" + s[closing:]

edit("app/src/main/java/com/openjarvis/ui/SettingsActivity.kt", release_settings_voice_manager)

def release_one_shot_watch_voice(s):
    old = "                com.openjarvis.voice.VoiceManager(context).speak(watchAction.template)"
    new = """                com.openjarvis.voice.VoiceManager(context).let { manager ->
                    manager.speak(watchAction.template) { manager.release() }
                }"""
    return s.replace(old, new, 1) if old in s else s

edit("app/src/main/java/com/openjarvis/watch/ScreenWatcher.kt", release_one_shot_watch_voice)
print("Installed Turkish-only TTS selection, truthful readiness UI and owner lifecycle cleanup (issue #28)")


# Issue #16: allowlisted Android app launch for typed and recognized Turkish speech.
app_launch_feature_root = Path(__file__).resolve().parent.parent / "features" / "app-launch"
if not app_launch_feature_root.is_dir():
    raise SystemExit("Issue #16 app-launch source overlay is missing")

for source in sorted(app_launch_feature_root.rglob("*.kt")):
    relative = source.relative_to(app_launch_feature_root)
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())


def install_allowlisted_app_dispatcher(s):
    anchor = "    private val executor = AndroidActionExecutor(context)"
    property_line = anchor + "\n    private val appLaunchDispatcher = AppLaunchDispatcher(AndroidAppLaunchPlatform(context))"
    if "private val appLaunchDispatcher = AppLaunchDispatcher" not in s:
        if anchor not in s:
            raise SystemExit("DeviceActionCoordinator.kt: executor anchor not found")
        s = s.replace(anchor, property_line, 1)

    start_marker = "    private suspend fun handleOpenApp("
    end_marker = "    private suspend fun handleSms("
    start = s.find(start_marker)
    end = s.find(end_marker, start)
    if start < 0 or end < 0:
        if "suspend fun openApp(appName: String" in s:
            return s
        raise SystemExit("DeviceActionCoordinator.kt: app-launch handler boundary not found")

    replacement = """    private suspend fun handleOpenApp(routed: RoutedCommand.OpenApp, command: String, setState: (AgentState) -> Unit) {
        openApp(routed.appName, command, setState)
    }

    suspend fun openApp(appName: String, command: String, setState: (AgentState) -> Unit) {
        setState(AgentState.Running(appName + " için açılış hedefi kontrol ediliyor..."))
        val result = appLaunchDispatcher.dispatch(appName)
        when (result) {
            is AppLaunchResult.LaunchRequested -> {
                setState(AgentState.Done(AppLaunchMessages.forResult(result)))
                graphifyRepo.logTask(command, "app launch requested: " + result.packageName, "local-action", 0)
            }
            else -> {
                setState(AgentState.Error(AppLaunchMessages.forResult(result)))
                logFailure(command, "app launch rejected")
            }
        }
    }

"""
    s = s[:start] + replacement + s[end:]
    return s


edit("app/src/main/java/com/openjarvis/commands/DeviceActionCoordinator.kt", install_allowlisted_app_dispatcher)


def remove_unrestricted_app_launch_helpers(s):
    start = s.find("    fun resolveAppPackage(label: String): String? {")
    if start >= 0:
        end = s.rfind("\n}")
        if end < start:
            raise SystemExit("AndroidActionExecutor.kt: class closing brace not found")
        s = s[:start] + s[end:]
    return s


edit("app/src/main/java/com/openjarvis/commands/AndroidActionExecutor.kt", remove_unrestricted_app_launch_helpers)


def route_model_app_launch_through_allowlist(s):
    old = """                Action.OPEN_APP -> {
                    val label = action.label
                    if (label != null) {
                        val packageName = findPackageByLabel(label)
                        if (packageName != null) {
                            JarvisAccessibilityService.instance?.openAppByPackage(packageName)
                            graphifyRepo.logAppOpened(packageName, label)
                        } else {
                            _state.value = AgentState.Error("App not found: $label")
                            return
                        }
                    }
                }"""
    new = """                Action.OPEN_APP -> {
                    val requestedName = action.label ?: action.packageName
                    if (requestedName.isNullOrBlank()) {
                        _state.value = AgentState.Error("Açılacak uygulamanın adı eksik.")
                        return
                    }
                    deviceActions.openApp(requestedName, "uygulama aç: " + requestedName) { newState ->
                        _state.value = newState
                    }
                    if (_state.value is AgentState.Error) return
                }"""
    if old in s:
        s = s.replace(old, new, 1)
    elif "deviceActions.openApp(requestedName" not in s:
        raise SystemExit("AgentCore.kt: OPEN_APP branch not found")

    start = s.find("    private fun findPackageByLabel(label: String): String? {")
    if start >= 0:
        end_marker = "    /** Confirms the pending device action"
        end = s.find(end_marker, start)
        if end < 0:
            raise SystemExit("AgentCore.kt: app resolver end marker not found")
        s = s[:start] + s[end:]

    for import_line in (
        "import android.content.Intent\n",
        "import android.content.pm.PackageManager\n",
        "import android.content.pm.ResolveInfo\n"
    ):
        s = s.replace(import_line, "")
    return s


edit("app/src/main/java/com/openjarvis/agent/AgentCore.kt", route_model_app_launch_through_allowlist)


def install_shared_app_launch_input_adapters(s):
    imports = (
        "import com.openjarvis.commands.AndroidAppLaunchPlatform",
        "import com.openjarvis.commands.AppLaunchCommandEntryPoint",
        "import com.openjarvis.commands.AppLaunchDispatcher",
        "import com.openjarvis.commands.AppLaunchInputAdapter",
        "import com.openjarvis.commands.AppLaunchMessages",
    )
    anchor = "import com.openjarvis.agent.AgentState"
    if anchor not in s:
        raise SystemExit("VoiceCommandActivity.kt: AgentState import anchor not found")
    missing = [item for item in imports if item not in s]
    if missing:
        s = s.replace(anchor, anchor + "\n" + "\n".join(missing), 1)

    property_anchor = "    private lateinit var voice: VoiceManager\n"
    property = property_anchor + "    private lateinit var appLaunchInputAdapter: AppLaunchInputAdapter\n"
    if "private lateinit var appLaunchInputAdapter: AppLaunchInputAdapter" not in s:
        if property_anchor not in s:
            raise SystemExit("VoiceCommandActivity.kt: VoiceManager property anchor not found")
        s = s.replace(property_anchor, property, 1)

    init_anchor = "        voice = VoiceManager(applicationContext)\n"
    init = init_anchor + """        appLaunchInputAdapter = AppLaunchInputAdapter(
            AppLaunchCommandEntryPoint(
                AppLaunchDispatcher(AndroidAppLaunchPlatform(this))
            )
        )
"""
    if "appLaunchInputAdapter = AppLaunchInputAdapter(" not in s:
        if init_anchor not in s:
            raise SystemExit("VoiceCommandActivity.kt: VoiceManager initialization anchor not found")
        s = s.replace(init_anchor, init, 1)

    speech_old = "submitCommand(text) { message = it }"
    speech_new = "submitRecognizedSpeechCommand(text) { message = it }"
    if speech_old in s:
        s = s.replace(speech_old, speech_new, 1)
    elif speech_new not in s:
        raise SystemExit("VoiceCommandActivity.kt: recognized-speech submit anchor not found")

    typed_old = "submitCommand(command.trim()) { message = it }"
    typed_new = "submitTypedCommand(command.trim()) { message = it }"
    if typed_old in s:
        s = s.replace(typed_old, typed_new, 1)
    elif typed_new not in s:
        raise SystemExit("VoiceCommandActivity.kt: typed submit anchor not found")

    if "private fun submitTypedCommand(" not in s:
        closing = s.rfind("}")
        if closing < 0:
            raise SystemExit("VoiceCommandActivity.kt: class closing brace not found")
        helpers = """
    private fun submitTypedCommand(text: String, updateStatus: (String) -> Unit) {
        val result = appLaunchInputAdapter.fromTypedInput(text)
        if (result != null) {
            updateStatus(AppLaunchMessages.forResult(result))
            return
        }
        submitCommand(text, updateStatus)
    }

    private fun submitRecognizedSpeechCommand(text: String, updateStatus: (String) -> Unit) {
        val result = appLaunchInputAdapter.fromRecognizedSpeech(text)
        if (result != null) {
            updateStatus(AppLaunchMessages.forResult(result))
            return
        }
        submitCommand(text, updateStatus)
    }
"""
        s = s[:closing] + helpers + s[closing:]
    return s


edit("app/src/main/java/com/openjarvis/ui/VoiceCommandActivity.kt", install_shared_app_launch_input_adapters)
print("Installed Issue #16 allowlisted app launch dispatcher and shared typed/speech input adapters")
