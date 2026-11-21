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
        val executorPath = sourceRoot.resolve("com/openjarvis/commands/AndroidActionExecutor.kt")
        val coordinatorPath = sourceRoot.resolve("com/openjarvis/commands/DeviceActionCoordinator.kt")
        val senderSource = kotlinCode(readUtf8(senderPath))
        val contractSource = kotlinCode(readUtf8(contractPath))
        val panelSource = kotlinCode(readUtf8(panelPath))
        val executorSource = kotlinCode(readUtf8(executorPath))
        val coordinatorSource = kotlinCode(readUtf8(coordinatorPath))

        assertTrue(senderSource.contains("internal fun createAndroidSmsDispatcher("))
        assertTrue(senderSource.contains("private class AndroidSmsSender("))
        assertTrue(senderSource.contains("val sender = AndroidSmsSender(appContext)"))
        assertTrue(senderSource.contains("transport = sender"))
        assertTrue(contractSource.contains("internal fun interface SmsTransport"))
        assertTrue(contractSource.contains("internal fun interface SmsPermission"))
        assertTrue(contractSource.contains("internal class ConfirmedSmsDispatcher("))
        assertTrue(panelSource.contains("createAndroidSmsDispatcher(context, gate)"))
        assertTrue(panelSource.contains("gate.prepare(recipient.displayName, phone.number, message)"))
        assertTrue(panelSource.contains("gate.confirm(review.reviewId)"))
        assertTrue(panelSource.contains("dispatcher.dispatch(confirmation)"))
        assertFalse(panelSource.contains("AndroidSmsSender"))

        // Voice and compose must share a one-time token created from the exact visible review.
        assertFalse(Regex("""\bfun\s+sendSms\s*\(""").containsMatchIn(executorSource))
        assertFalse(executorSource.contains("SmsConfirmationGate()"))
        assertFalse(executorSource.contains("gate.prepare("))
        assertFalse(executorSource.contains("gate.confirm("))
        assertTrue(executorSource.contains("internal fun dispatchConfirmedSms("))
        assertTrue(executorSource.contains("createAndroidSmsDispatcher(context, gate)"))
        assertTrue(executorSource.contains("dispatcher.dispatch(confirmation)"))
        assertTrue(coordinatorSource.contains("private val smsConfirmationGate = SmsConfirmationGate()"))
        assertTrue(coordinatorSource.contains("private suspend fun requestSmsConfirmation("))
        assertTrue(coordinatorSource.contains("smsConfirmationGate.prepare(labelOf(contactName, number), number, body)"))
        assertTrue(coordinatorSource.contains("AgentState.AwaitingConfirmation("))
        assertTrue(coordinatorSource.contains("detail = \"Alıcı: \${review.recipientName} (\${review.phoneNumber})\\nMesaj: \${review.exactMessage}\""))
        assertTrue(coordinatorSource.contains("private suspend fun confirmSms(review: SmsReview"))
        assertTrue(coordinatorSource.contains("smsConfirmationGate.confirm(review.reviewId)"))
        assertTrue(coordinatorSource.contains("executor.dispatchConfirmedSms(confirmation, smsConfirmationGate)"))
        assertTrue(coordinatorSource.contains("ResolvedRecipient(resolution.displayName, resolution.number)"))
        assertTrue(coordinatorSource.contains("RecipientSourcePolicy.choose(contactName, phoneNumber)"))
        assertEquals(3, Regex("""resolveRecipient\\(routed\\.contactName, routed\\.phoneNumber, routed, command, setState\\)""").findAll(coordinatorSource).count())
        assertFalse(coordinatorSource.contains("routed.phoneNumber != null -> ResolvedRecipient"))
        assertTrue(executorSource.contains("is ContactLookupResult.Unavailable -> ContactResolution.Unavailable(result.reason)"))
        assertTrue(coordinatorSource.contains("is AndroidActionExecutor.ContactResolution.Unavailable ->"))
        assertTrue(coordinatorSource.contains("Android gönderim callback'i başarılı;"))
        assertFalse(executorSource.contains("SmsManager"))
        assertFalse(executorSource.contains("sendTextMessage"))
        assertFalse(executorSource.contains("sendMultipartTextMessage"))
        assertTrue(executorSource.contains("DeviceActionOutcomeJournal.record"))
        assertTrue(executorSource.contains("outcome.toDeviceActionOutcomeCode()"))
        assertTrue(executorSource.contains("DeviceActionOutcomeCode.UNKNOWN"))
        assertTrue(executorSource.contains("DeviceActionOutcomeCode.DRAFT"))
        assertTrue(panelSource.contains("DeviceActionOutcomeJournal.record"))
        assertTrue(panelSource.contains("outcome.toDeviceActionOutcomeCode()"))
        assertTrue(panelSource.contains("DeviceActionOutcomeCode.DRAFT"))
        assertFalse(coordinatorSource.contains("SmsManager"))

        val kotlinFiles: List<Path> = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
        val sourceByPath: Map<Path, String> = kotlinFiles.associateWith { path: Path ->
            kotlinCode(readUtf8(path))
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

        val confirmedDispatchCallSites = sourceByPath.filterValues { source: String ->
            Regex("""\bexecutor\s*\.\s*dispatchConfirmedSms\s*\(""").containsMatchIn(source)
        }.keys
        assertEquals("Only the coordinator's explicit confirmation handler may dispatch", setOf(coordinatorPath), confirmedDispatchCallSites)

        val rawSendSmsCallSites = sourceByPath.filterValues { source: String ->
            Regex("""\bexecutor\s*\.\s*sendSms\s*\(""").containsMatchIn(source)
        }.keys
        assertTrue("Raw number/body SMS entry points are forbidden", rawSendSmsCallSites.isEmpty())
        assertEquals(1, Regex("""smsConfirmationGate\.confirm\(""").findAll(coordinatorSource).count())

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

    /**
     * Removes comments/KDoc while preserving literals. String templates can contain executable
     * Kotlin expressions, so literals are not discarded by the transport security scan.
     */
    private fun kotlinCode(source: String): String {
        val output = StringBuilder(source.length)
        var index = 0
        var state = 0 // code, line comment, block comment, string, raw string, char
        var blockDepth = 0

        while (index < source.length) {
            when (state) {
                0 -> when {
                    source.startsWith("//", index) -> {
                        output.append("  ")
                        index += 2
                        state = 1
                    }
                    source.startsWith("/*", index) -> {
                        output.append("  ")
                        index += 2
                        blockDepth = 1
                        state = 2
                    }
                    source.startsWith("\"\"\"", index) -> {
                        output.append("\"\"\"")
                        index += 3
                        state = 4
                    }
                    source[index] == '"' -> {
                        output.append('"')
                        index++
                        state = 3
                    }
                    source[index] == '\'' -> {
                        output.append('\'')
                        index++
                        state = 5
                    }
                    else -> {
                        output.append(source[index])
                        index++
                    }
                }
                1 -> {
                    if (source[index] == '\n') {
                        output.append('\n')
                        state = 0
                    } else output.append(' ')
                    index++
                }
                2 -> when {
                    source.startsWith("/*", index) -> {
                        output.append("  ")
                        index += 2
                        blockDepth++
                    }
                    source.startsWith("*/", index) -> {
                        output.append("  ")
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = 0
                    }
                    else -> {
                        output.append(if (source[index] == '\n') '\n' else ' ')
                        index++
                    }
                }
                3, 5 -> {
                    val quote = if (state == 3) '"' else '\''
                    val current = source[index]
                    output.append(current)
                    index++
                    when {
                        current == '\\' && index < source.length -> {
                            output.append(source[index])
                            index++
                        }
                        current == quote -> state = 0
                    }
                }
                4 -> {
                    if (source.startsWith("\"\"\"", index)) {
                        output.append("\"\"\"")
                        index += 3
                        state = 0
                    } else {
                        output.append(source[index])
                        index++
                    }
                }
            }
        }
        return output.toString()
    }

    @Test
    fun scannerIgnoresCommentsButPreservesLiteralAndExecutableTokens() {
        val source = "val note = \"SmsManager\" // SmsManager\\n/* SmsManager */ SmsManager.getDefault()"
        val scanned = kotlinCode(source)
        assertTrue(scanned.contains("\"SmsManager\""))
        assertFalse(scanned.contains("// SmsManager"))
        assertTrue(scanned.contains("SmsManager.getDefault()"))
    }

    private fun readUtf8(path: Path): String =
        Files.newBufferedReader(path).use { reader -> reader.readText() }
}
