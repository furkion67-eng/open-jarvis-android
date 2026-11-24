#!/usr/bin/env python3
from pathlib import Path
import sys


def install_startup_diagnostics(source):
    marker = "openjarvis_startup_crash.txt"
    if marker in source:
        required = (
            "Thread.setDefaultUncaughtExceptionHandler",
            'android.util.Log.e("OpenJarvisCrash"',
            "crashReport.writeText(",
            'android.app.AlertDialog.Builder(this)',
        )
        missing = [item for item in required if item not in source]
        if missing:
            raise SystemExit("MainActivity.kt: partial startup diagnostic installation; missing " + ", ".join(missing))
        return source

    if "import java.io.File" not in source:
        toast_import = "import android.widget.Toast\n"
        if source.count(toast_import) != 1:
            raise SystemExit("MainActivity.kt: Toast import anchor is missing or ambiguous")
        source = source.replace(toast_import, toast_import + "import java.io.File\n", 1)

    anchor = "        super.onCreate(savedInstanceState)\n"
    if source.count(anchor) != 1:
        raise SystemExit("MainActivity.kt: onCreate super-call anchor is missing or ambiguous")

    diagnostic = """        super.onCreate(savedInstanceState)

        // Save fatal exceptions; show the full report on the next app launch.
        val crashReport = File(filesDir, "openjarvis_startup_crash.txt")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Surface the exact stack in logcat too, so adb logcat on a physical
            // device shows "OpenJarvisCrash" entries for this runtime blocker.
            android.util.Log.e("OpenJarvisCrash", "Fatal exception on thread " + thread.name, error)
            runCatching {
                crashReport.writeText(
                    "Thread: " + thread.name + "\\n" + android.util.Log.getStackTraceString(error)
                )
            }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        if (crashReport.exists()) {
            val report = runCatching { crashReport.readText() }.getOrDefault("Crash report could not be read")
            android.app.AlertDialog.Builder(this)
                .setTitle("Open Jarvis açılış hatası")
                .setMessage(report)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Kaydı temizle") { _, _ -> crashReport.delete() }
                .show()
        }
"""
    return source.replace(anchor, diagnostic, 1)


def _startup_diagnostics_regression_test():
    fixture = """package com.openjarvis.ui
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { }
    }
}
"""
    once = install_startup_diagnostics(fixture)
    twice = install_startup_diagnostics(once)
    if once != twice:
        raise SystemExit("MainActivity.kt: startup diagnostics installation is not idempotent")
    if once.count("openjarvis_startup_crash.txt") != 1:
        raise SystemExit("MainActivity.kt: startup diagnostics marker count is unexpected")
    if once.count("import java.io.File") != 1:
        raise SystemExit("MainActivity.kt: startup diagnostics duplicated the File import")
    if once.count("Thread.setDefaultUncaughtExceptionHandler") != 1:
        raise SystemExit("MainActivity.kt: startup diagnostics handler was duplicated")

    existing_file_import = fixture.replace(
        "import android.widget.Toast\n",
        "import android.widget.Toast\nimport java.io.File\n",
        1
    )
    if install_startup_diagnostics(existing_file_import).count("import java.io.File") != 1:
        raise SystemExit("MainActivity.kt: existing File import was duplicated")

    try:
        install_startup_diagnostics(fixture.replace("super.onCreate(savedInstanceState)", ""))
    except SystemExit:
        pass
    else:
        raise SystemExit("MainActivity.kt: missing onCreate anchor was not rejected")


_startup_diagnostics_regression_test()

project = Path(sys.argv[1])
main = project / "app/src/main/java/com/openjarvis/ui/MainActivity.kt"
if not main.is_file():
    raise SystemExit("Host MainActivity.kt not found: " + str(main))
original = main.read_text(encoding="utf-8")
repaired = install_startup_diagnostics(original)
if repaired != original:
    main.write_text(repaired, encoding="utf-8")

print("Installed next-launch crash report dialog; Android permissions unchanged.")
