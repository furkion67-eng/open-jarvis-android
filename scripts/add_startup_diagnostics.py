from pathlib import Path
import sys

project = Path(sys.argv[1])
main = project / "app/src/main/java/com/openjarvis/ui/MainActivity.kt"
s = main.read_text()

if "openjarvis_startup_crash.txt" not in s:
    s = s.replace(
        "import android.widget.Toast\n",
        "import android.widget.Toast\nimport java.io.File\nimport kotlin.system.exitProcess\n"
    )
    s = s.replace(
        "        super.onCreate(savedInstanceState)\n",
        """        super.onCreate(savedInstanceState)

        // Persist fatal startup exceptions so the next launch can show the exact cause.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "openjarvis_startup_crash.txt").writeText(
                    "Thread: ${thread.name}\\n" + android.util.Log.getStackTraceString(error)
                )
            }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
"""
    )
    s = s.replace(
        "        setContent {\n",
        """        val startupCrashFile = File(filesDir, "openjarvis_startup_crash.txt")
        val previousCrash = runCatching {
            if (startupCrashFile.exists()) startupCrashFile.readText() else null
        }.getOrNull()

        setContent {
"""
    )
    s = s.replace(
        "            OpenJarvisTheme {\n",
        """            OpenJarvisTheme {
                if (previousCrash != null) {
                    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            androidx.compose.material3.Text(
                                "Open Jarvis açılış hatası yakalandı",
                                style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            androidx.compose.foundation.rememberScrollState().let { scroll ->
                                androidx.compose.foundation.verticalScroll(scroll)
                            }.let { scrollModifier ->
                                androidx.compose.material3.Text(previousCrash, modifier = scrollModifier)
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                            androidx.compose.material3.Button(onClick = {
                                startupCrashFile.delete()
                                recreate()
                            }) {
                                androidx.compose.material3.Text("Hata kaydını temizle ve yeniden dene")
                            }
                        }
                    }
                } else {
"""
    )
    # Close the diagnostic else branch immediately before setContent's closing braces.
    marker = """            }
        }
    }

    private fun isAccessibilityServiceEnabled"""
    replacement = """                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled"""
    if marker not in s:
        raise SystemExit("Could not locate MainActivity Compose closing block; refusing unsafe patch")
    s = s.replace(marker, replacement, 1)
    main.write_text(s)
print("Startup crash diagnostics installed; Android permissions unchanged.")
