package com.openjarvis.commands

/**
 * Prefer a spoken contact name over a simultaneously parsed raw number.
 * When a name is present, the ContactsContract resolver must decide the recipient;
 * an explicit number is used only when no contact name was supplied.
 */
internal sealed interface RecipientSource {
    data class ContactName(val value: String) : RecipientSource
    data class ExplicitNumber(val value: String) : RecipientSource
    object Missing : RecipientSource
}

internal object RecipientSourcePolicy {
    fun choose(contactName: String?, phoneNumber: String?): RecipientSource {
        val name = contactName?.trim()?.takeIf(String::isNotEmpty)
        if (name != null) return RecipientSource.ContactName(name)

        val number = phoneNumber?.trim()?.takeIf(String::isNotEmpty)
        if (number != null) return RecipientSource.ExplicitNumber(number)

        return RecipientSource.Missing
    }
}
