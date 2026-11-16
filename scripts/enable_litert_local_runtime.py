#!/usr/bin/env python3
"""Enable a real on-device LiteRT-LM runtime in the extracted Open Jarvis source."""
from pathlib import Path
import sys

project = Path(sys.argv[1]).resolve()
gradle = project / "app/build.gradle"
gradle_text = gradle.read_text()
dependency = "implementation 'com.google.ai.edge.litertlm:litertlm-android:0.14.0'"
if dependency not in gradle_text:
    marker = "dependencies {"
    if marker not in gradle_text:
        raise SystemExit("Could not find dependencies block in app/build.gradle")
    gradle_text = gradle_text.replace(marker, marker + "\n    " + dependency, 1)
    gradle.write_text(gradle_text)

runtime = project / "app/src/main/java/com/openjarvis/local/LiteRtLmRuntime.kt"
runtime.parent.mkdir(parents=True, exist_ok=True)
runtime.write_text("""package com.openjarvis.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the on-device LiteRT-LM engine. A model is reported as loaded only
 * after the native runtime has successfully initialized it.
 */
object LiteRtLmRuntime {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var activeModelPath: String? = null

    private fun initializeLocked(modelPath: String) {
        if (engine != null && activeModelPath == modelPath) return

        engine?.close()
        engine = null
        activeModelPath = null

        val candidate = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
        )
        try {
            candidate.initialize()
            engine = candidate
            activeModelPath = modelPath
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked(modelPath) }
    }

    suspend fun generate(
        modelPath: String,
        systemPrompt: String,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (engine == null || activeModelPath != modelPath) {
                initializeLocked(modelPath)
            }

            val activeEngine = engine ?: error("Local model engine did not initialize")
            val conversation = activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    maxOutputToken = 1024
                )
            )
            try {
                val output = StringBuilder()
                conversation.sendMessageAsync(userMessage).collect { chunk ->
                    output.append(chunk)
                }
                output.toString().trim().also {
                    check(it.isNotEmpty()) { "Local model returned an empty response" }
                }
            } finally {
                conversation.close()
            }
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine?.close()
            engine = null
            activeModelPath = null
        }
    }

    suspend fun isInitializedFor(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { engine != null && activeModelPath == modelPath }
    }
}
""")

model_manager = project / "app/src/main/java/com/openjarvis/local/ModelManager.kt"
model_manager.write_text("""package com.openjarvis.local

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var unloadJob: Job? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val state: StateFlow<ModelState> = _state

    private val modelDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun getModelPath(tier: ModelTier): String =
        File(modelDir, tier.fileName).absolutePath

    fun isModelDownloaded(tier: ModelTier): Boolean {
        val file = File(modelDir, tier.fileName)
        return file.exists() && file.length() >= tier.minSize
    }

    fun getDownloadedTier(): ModelTier? =
        ModelTier.entries.find { isModelDownloaded(it) }

    fun canLoadModel(tier: ModelTier): Boolean {
        if (!isModelDownloaded(tier)) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMB = memInfo.availMem / (1024 * 1024)
        return availableMB > tier.ramRequiredMB + 500
    }

    suspend fun loadModel(tier: ModelTier): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isModelDownloaded(tier)) {
                error("Local model is not downloaded yet.")
            }
            if (!canLoadModel(tier)) {
                error("Not enough free RAM. Need about ${tier.ramRequiredMB}MB plus system headroom.")
            }

            _state.value = ModelState.Loading(tier)
            // This starts the native inference engine; never mark Loaded before it succeeds.
            LiteRtLmRuntime.initialize(getModelPath(tier))
            _state.value = ModelState.Loaded(tier)
            resetUnloadTimer()
        }.onFailure {
            _state.value = ModelState.Unloaded
        }
    }

    fun unloadModel() {
        unloadJob?.cancel()
        scope.launch {
            _state.value = ModelState.Unloading
            runCatching { LiteRtLmRuntime.unload() }
            _state.value = ModelState.Unloaded
        }
    }

    private fun resetUnloadTimer() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(UNLOAD_TIMEOUT_MS)
            unloadModel()
        }
    }

    suspend fun downloadModel(
        tier: ModelTier,
        onProgress: (Float) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            if (downloadJob?.isActive == true) error("Download already in progress")

            _state.value = ModelState.Downloading(tier, 0f)
            val file = File(modelDir, tier.fileName)
            val existingSize = if (file.exists()) file.length() else 0L

            val request = okhttp3.Request.Builder()
                .url(tier.downloadUrl)
                .apply {
                    if (existingSize > 0L) addHeader("Range", "bytes=$existingSize-")
                }
                .build()

            val client = okhttp3.OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty model download response")

                // A server may ignore Range and return the complete file (200).
                val append = existingSize > 0L && response.code == 206
                if (!append) file.writeBytes(byteArrayOf())

                val responseLength = body.contentLength().takeIf { it > 0L } ?: tier.minSize
                val totalSize = if (append) existingSize + responseLength else responseLength

                body.byteStream().use { input ->
                    FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = if (append) existingSize else 0L
                        var lastProgress = 0f
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val progress = (downloaded.toFloat() / totalSize.coerceAtLeast(1L)).coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.01f) {
                                _state.value = ModelState.Downloading(tier, progress)
                                onProgress(progress)
                                lastProgress = progress
                            }
                        }
                    }
                }
            }

            if (!isModelDownloaded(tier)) {
                file.delete()
                error("Downloaded model file is incomplete or invalid.")
            }
            _state.value = ModelState.Downloaded(tier)
            file.length()
        }.onFailure {
            _state.value = ModelState.Unloaded
        }
    }

    enum class ModelTier(
        val displayName: String,
        val fileName: String,
        val downloadUrl: String,
        val minSize: Long,
        val ramRequiredMB: Int,
        val description: String
    ) {
        MINIMUM(
            "Qwen2.5 0.5B (Local)",
            "qwen2.5-0.5b-int4.litertlm",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/refs%2Fpr%2F1/Qwen2.5-0.5B-Instruct_wi4b64_ekv1280.litertlm",
            250_000_000L,
            1800,
            "Small quantized on-device model; about 265MB download"
        );

        companion object {
            fun fromName(name: String): ModelTier? =
                if (name.equals("Qwen2.5 0.5B (Local)", true) ||
                    name.equals("Gemma 2B", true) ||
                    name.equals("Phi-3 Mini", true) ||
                    name.equals("Llama 3 8B", true)
                ) MINIMUM else null
        }
    }

    sealed class ModelState {
        object Unloaded : ModelState()
        data class Loading(val tier: ModelTier) : ModelState()
        data class Loaded(val tier: ModelTier) : ModelState()
        object Unloading : ModelState()
        data class Downloading(val tier: ModelTier, val progress: Float) : ModelState()
        data class Downloaded(val tier: ModelTier) : ModelState()
    }

    companion object {
        private const val UNLOAD_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
""")

provider = project / "app/src/main/java/com/openjarvis/llm/providers/LlamaProvider.kt"
provider.write_text("""package com.openjarvis.llm.providers

import com.openjarvis.llm.LLMProvider
import com.openjarvis.local.LiteRtLmRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local, offline inference backed by Google's LiteRT-LM Android runtime.
 * The model file is stored in app-private storage and never sent to a server.
 */
class LlamaProvider(
    private val modelPath: String,
    @Suppress("UNUSED_PARAMETER") private val modelName: String = "Qwen2.5-0.5B"
) : LLMProvider {

    override val name: String = "Local LiteRT-LM"
    override val baseUrl: String = "local://litert-lm"

    override suspend fun complete(
        systemPrompt: String,
        userMessage: String
    ): Result<String> = runCatching {
        val model = File(modelPath)
        check(model.exists() && model.length() >= 250_000_000L) {
            "Local model is missing or incomplete. Download the Qwen2.5 0.5B model in Settings first."
        }
        LiteRtLmRuntime.generate(modelPath, systemPrompt, userMessage)
    }

    override suspend fun testConnection(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.currentTimeMillis()
            val model = File(modelPath)
            check(model.exists() && model.length() >= 250_000_000L) {
                "Local model is not downloaded."
            }
            LiteRtLmRuntime.initialize(modelPath)
            System.currentTimeMillis() - start
        }
    }
}
""")


# Add a visible local-model download/load control to the existing provider settings screen.
settings = project / "app/src/main/java/com/openjarvis/ui/settings/SettingsScreen.kt"
settings_text = settings.read_text()
imports = [
    "import androidx.compose.ui.platform.LocalContext",
    "import com.openjarvis.local.DownloadService",
    "import com.openjarvis.local.ModelManager",
    "import kotlinx.coroutines.launch",
]
package_line = "package com.openjarvis.ui.settings\\n"
for item in imports:
    if item not in settings_text:
        settings_text = settings_text.replace(package_line, package_line + item + "\\n", 1)

function_anchor = ") {\\n    var selectedProvider by remember"
function_replacement = """) {
    val context = LocalContext.current
    val localModelManager = remember(context) { UniversalAdapter.getModelManager(context) }
    val localModelState by localModelManager.state.collectAsState()
    val localModelScope = rememberCoroutineScope()
    var localModelMessage by remember { mutableStateOf<String?>(null) }

    var selectedProvider by remember"""
if function_anchor not in settings_text:
    raise SystemExit("Could not locate SettingsScreen function body for local model state")
settings_text = settings_text.replace(function_anchor, function_replacement, 1)

voice_anchor = """                Spacer(modifier = Modifier.height(32.dp))

                SectionLabel("VOICE")"""
local_model_card = """                if (selectedProvider == "Local (LlamaCpp)") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = VoidColor.Void900,
                        border = BorderStroke(1.dp, VoidColor.BorderSubtle)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("ON-DEVICE AI MODEL", color = VoidColor.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Qwen2.5 0.5B · yaklaşık 277 MB · İnternet olmadan cihazında çalışır",
                                color = VoidColor.TextDisabled,
                                fontSize = 12.sp
                            )
                            when (val state = localModelState) {
                                is ModelManager.ModelState.Downloading ->
                                    Text("İndiriliyor: ${(state.progress * 100).toInt()}%", color = VoidColor.TextPrimary)
                                is ModelManager.ModelState.Loading ->
                                    Text("Model başlatılıyor…", color = VoidColor.TextPrimary)
                                is ModelManager.ModelState.Loaded ->
                                    Text("Yerel model çalışır durumda", color = VoidColor.TextPrimary)
                                is ModelManager.ModelState.Downloaded ->
                                    Text("Model indirildi; başlatılmaya hazır", color = VoidColor.TextPrimary)
                                else ->
                                    Text("Model henüz indirilmedi", color = VoidColor.TextDisabled)
                            }
                            Button(
                                onClick = {
                                    DownloadService.startDownload(
                                        context,
                                        ModelManager.ModelTier.MINIMUM.displayName
                                    )
                                    localModelMessage = "Model indirme işlemi başlatıldı."
                                },
                                enabled = localModelState !is ModelManager.ModelState.Downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Ücretsiz yerel modeli indir") }
                            Button(
                                onClick = {
                                    localModelScope.launch {
                                        val result = localModelManager.loadModel(ModelManager.ModelTier.MINIMUM)
                                        localModelMessage = result.exceptionOrNull()?.message
                                            ?: "Yerel model başarıyla başlatıldı."
                                    }
                                },
                                enabled = localModelManager.isModelDownloaded(ModelManager.ModelTier.MINIMUM),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Yerel modeli başlat") }
                            localModelMessage?.let {
                                Text(it, color = VoidColor.TextDisabled, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(32.dp))

                SectionLabel("VOICE")"""
if voice_anchor not in settings_text:
    raise SystemExit("Could not locate Voice section anchor in SettingsScreen")
settings_text = settings_text.replace(voice_anchor, local_model_card, 1)
settings.write_text(settings_text)

# Android 14 requires this non-runtime permission for the already-declared dataSync download service.
manifest = project / "app/src/main/AndroidManifest.xml"
manifest_text = manifest.read_text()
permission = '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />'
if permission not in manifest_text:
    manifest_text = manifest_text.replace(
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\\n    ' + permission,
        1
    )
    manifest.write_text(manifest_text)

print("LiteRT-LM patch written.")
