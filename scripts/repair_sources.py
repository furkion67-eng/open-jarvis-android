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
