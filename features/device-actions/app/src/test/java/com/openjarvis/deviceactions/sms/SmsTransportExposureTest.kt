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
        val senderSource = Files.readString(senderPath)
        val contractSource = Files.readString(contractPath)
        val panelSource = Files.readString(panelPath)

        assertTrue(senderSource.contains("internal fun createAndroidSmsDispatcher("))
        assertTrue(senderSource.contains("private class AndroidSmsSender("))
        assertTrue(senderSource.contains("transport = AndroidSmsSender(appContext)"))
        assertTrue(contractSource.contains("internal fun interface SmsTransport"))
        assertTrue(contractSource.contains("internal fun interface SmsPermission"))
        assertTrue(contractSource.contains("internal class ConfirmedSmsDispatcher("))
        assertTrue(panelSource.contains("createAndroidSmsDispatcher(context, gate)"))
        assertFalse(panelSource.contains("AndroidSmsSender"))

        val kotlinFiles: List<Path> = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
        val sourceByPath = kotlinFiles.associateWith { Files.readString(it) }

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

        val directSenderCalls = sourceByPath.filterValues {
            Regex("""\b(?:sender|smsSender)\s*\.\s*send\s*\(""")
                .containsMatchIn(it)
        }
        assertTrue("No caller may invoke the concrete sender directly", directSenderCalls.isEmpty())

        val transportDispatchSites = sourceByPath.filterValues {
            Regex("""\btransport\s*\.\s*send\s*\(\s*draft\s*,\s*callback\s*\)""")
                .containsMatchIn(it)
        }.keys
        assertEquals(setOf(contractPath), transportDispatchSites)
    }
}
