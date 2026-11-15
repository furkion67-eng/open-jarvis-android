from pathlib import Path
import sys

project = Path(sys.argv[1])
main = project / "app/src/main/java/com/openjarvis/ui/MainActivity.kt"
s = main.read_text()

if "openjarvis_startup_crash.txt" not in s:
    import_line = "import android.widget.Toast\n"
    if import_line not in s:
        raise SystemExit("Toast import anchor not found")
    s = s.replace(import_line, import_line + "import java.io.File\n", 1)

    anchor = "        super.onCreate(savedInstanceState)\n"
    if anchor not in s:
        raise SystemExit("onCreate anchor not found")
    diagnostic = """        super.onCreate(savedInstanceState)

        // Save fatal exceptions; show the full report on the next app launch.
        val crashReport = File(filesDir, "openjarvis_startup_crash.txt")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashReport.writeText(
                    "Thread: ${thread.name}\\n" + android.util.Log.getStackTraceString(error)
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
    s = s.replace(anchor, diagnostic, 1)
    main.write_text(s)

print("Installed next-launch crash report dialog; Android permissions unchanged.")
