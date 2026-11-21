package com.openjarvis.deviceactions.sms

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTransportExposureTest {
    @Test
    fun onlyConfirmedDispatcherCanReachTheAndroidSmsTransport() {
        val sourceRoot = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("features/device-actions/app/src/main/java")
        ).firstOrNull { Files.isDirectory(it) }
            ?: throw AssertionError("Cannot locate the Android main Kotlin source root")

        val senderPath = sourceRoot.resolve(
            "com/openjarvis/deviceactions/sms/AndroidSmsSender.kt"
        )
        val contractPath = sourceRoot.resolve(
            "com/openjarvis/deviceactions/sms/SmsActionContract.kt"
        )
        val panelPath = sourceRoot.resolve(
            "com/openjarvis/deviceactions/ui/SmsComposePanel.kt"
        )
        val senderSource = readUtf8(senderPath)
        val contractSource = readUtf8(contractPath)
        val panelSource = readUtf8(panelPath)

        assertTrue(senderSource.contains("internal fun createAndroidSmsDispatcher("))
        assertTrue(senderSource.contains("private class AndroidSmsSender("))
        assertTrue(senderSource.contains("val sender = AndroidSmsSender(appContext)"))
        assertTrue(senderSource.contains("transport = sender"))
        assertTrue(contractSource.contains("internal fun interface SmsTransport"))
        assertTrue(contractSource.contains("internal fun interface SmsPermission"))
        assertTrue(contractSource.contains("internal class ConfirmedSmsDispatcher("))
        assertTrue(panelSource.contains("createAndroidSmsDispatcher(context, gate)"))
        assertFalse(panelSource.contains("AndroidSmsSender"))

        val kotlinFiles: List<Path> = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
        val sourceByPath: Map<Path, String> = kotlinFiles.associateWith { path: Path ->
            readUtf8(path)
        }

        val smsManagerFiles = sourceByPath.filterValues { it.contains("SmsManager") }
            .keys.map { sourceRoot.relativize(it).toString().replace('\\', '/') }
            .sorted()
        assertEquals(
            listOf("com/openjarvis/deviceactions/sms/AndroidSmsSender.kt"),
            smsManagerFiles
        )

        val concreteSenderConstructionSites = sourceByPath.filterValues {
            it.contains("AndroidSmsSender(appContext)")
        }.keys
        assertEquals(setOf(senderPath), concreteSenderConstructionSites)

        val directSenderCalls = sourceByPath.filterValues { source: String ->
            Regex("""\b(?:sender|smsSender)\s*\.\s*send\s*\(""")
                .containsMatchIn(source)
        }.keys
        assertTrue("No caller may invoke the concrete sender directly", directSenderCalls.isEmpty())

        val transportDispatchSites = sourceByPath.filterValues { source: String ->
            Regex("""\btransport\s*\.\s*send\s*\(\s*draft\s*,\s*callback\s*\)""")
                .containsMatchIn(source)
        }.keys
        assertEquals(setOf(contractPath), transportDispatchSites)
    }

    private fun readUtf8(path: Path): String =
        Files.newBufferedReader(path).use { reader -> reader.readText() }
}
