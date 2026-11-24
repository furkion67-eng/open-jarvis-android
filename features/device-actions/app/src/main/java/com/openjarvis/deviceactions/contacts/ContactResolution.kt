package com.openjarvis.deviceactions.contacts

import java.text.Normalizer
import java.util.Locale

data class ContactPhoneRecord(
    val contactId: Long,
    val displayName: String,
    val number: String,
    val label: String? = null
)

data class ContactPhoneOption(val number: String, val label: String? = null)

data class ContactCandidate(
    val contactId: Long,
    val displayName: String,
    val phoneNumbers: List<ContactPhoneOption>
)


sealed interface ContactLookupResult {
    object PermissionDenied : ContactLookupResult
    object InvalidQuery : ContactLookupResult
    data class NoMatch(val query: String) : ContactLookupResult
    data class Unique(val contact: ContactCandidate, val phone: ContactPhoneOption) : ContactLookupResult
    data class ChooseNumber(val contact: ContactCandidate) : ContactLookupResult
    data class ChooseContact(val candidates: List<ContactCandidate>) : ContactLookupResult
    data class Unavailable(val reason: String) : ContactLookupResult
}

object ContactNameNormalizer {
    fun normalize(value: String): String {
        val turkishLower = value.trim().lowercase(Locale.forLanguageTag("tr-TR"))
        val decomposed = Normalizer.normalize(turkishLower, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .replace('ı', 'i')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}

object ContactResolver {
    fun resolve(query: String, rows: List<ContactPhoneRecord>): ContactLookupResult {
        val normalizedQuery = ContactNameNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) return ContactLookupResult.InvalidQuery

        val ranked = rows
            .filter { it.contactId >= 0 && it.displayName.isNotBlank() && it.number.isNotBlank() }
            .groupBy { it.contactId }
            .mapNotNull { (contactId, contactRows) ->
                val displayName = contactRows.first().displayName.trim()
                val score = matchScore(normalizedQuery, ContactNameNormalizer.normalize(displayName))
                if (score == 0) return@mapNotNull null
                val numbers = contactRows
                    .map { ContactPhoneOption(it.number.trim(), it.label?.trim()?.takeIf(String::isNotBlank)) }
                    .distinctBy { normalizePhone(it.number) }
                    .filter { normalizePhone(it.number).isNotBlank() }
                    .sortedWith(
                        compareBy<ContactPhoneOption> { it.label.orEmpty().lowercase(Locale.forLanguageTag("tr-TR")) }
                            .thenBy { normalizePhone(it.number) }
                            .thenBy { it.number }
                    )
                if (numbers.isEmpty()) null else score to ContactCandidate(contactId, displayName, numbers)
            }

        if (ranked.isEmpty()) return ContactLookupResult.NoMatch(query)
        val bestScore = ranked.maxOf { it.first }
        val best = ranked.filter { it.first == bestScore }.map { it.second }
            .sortedWith(
                compareBy<ContactCandidate> { ContactNameNormalizer.normalize(it.displayName) }
                    .thenBy { it.contactId }
            )
        if (best.size > 1) return ContactLookupResult.ChooseContact(best)
        val candidate = best.single()
        return if (candidate.phoneNumbers.size == 1) {
            ContactLookupResult.Unique(candidate, candidate.phoneNumbers.single())
        } else {
            ContactLookupResult.ChooseNumber(candidate)
        }
    }

    private fun matchScore(query: String, name: String): Int = when {
        name == query -> 4
        name.startsWith("$query ") -> 3
        name.split(' ').any { it == query } -> 2
        // Contact names are used for consequential actions (SMS/calls). Do not auto-resolve
        // arbitrary interior substrings such as "ali" inside "Kalina" to a unique recipient.
        else -> 0
    }

    private fun normalizePhone(number: String): String =
        number.filter { it.isDigit() || it == '+' }
}

class ContactLookupUseCase(
    private val hasReadContactsPermission: () -> Boolean,
    private val loadPhoneRows: () -> List<ContactPhoneRecord>
) {
    fun resolve(query: String): ContactLookupResult {
        if (ContactNameNormalizer.normalize(query).isBlank()) return ContactLookupResult.InvalidQuery
        if (!runCatching(hasReadContactsPermission).getOrDefault(false)) {
            return ContactLookupResult.PermissionDenied
        }
        return try {
            ContactResolver.resolve(query, loadPhoneRows())
        } catch (_: SecurityException) {
            ContactLookupResult.PermissionDenied
        } catch (error: Exception) {
            ContactLookupResult.Unavailable(error.message ?: "Rehber okunamadı.")
        }
    }
}
