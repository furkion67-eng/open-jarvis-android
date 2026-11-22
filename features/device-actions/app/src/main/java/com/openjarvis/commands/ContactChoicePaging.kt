package com.openjarvis.commands

import java.util.Locale

/**
 * Paginates ambiguous ContactsContract matches without silently dropping candidates.
 * Option ordinals stay global so a spoken number always maps to the number shown in the prompt.
 */
internal data class ContactChoicePage<T>(
    val pageIndex: Int,
    val pageCount: Int,
    val startIndex: Int,
    val items: List<T>,
    val hasPrevious: Boolean,
    val hasNext: Boolean
) {
    val startOrdinal: Int
        get() = if (items.isEmpty()) 0 else startIndex + 1
}

internal object ContactChoicePaging {
    const val PAGE_SIZE = 5

    fun <T> page(
        items: List<T>,
        requestedPageIndex: Int,
        pageSize: Int = PAGE_SIZE
    ): ContactChoicePage<T> {
        require(pageSize > 0) { "pageSize must be positive" }
        val pageCount = if (items.isEmpty()) 0 else (items.size - 1) / pageSize + 1
        val pageIndex = if (pageCount == 0) 0 else requestedPageIndex.coerceIn(0, pageCount - 1)
        val startIndex = pageIndex * pageSize
        val endIndex = minOf(startIndex + pageSize, items.size)
        return ContactChoicePage(
            pageIndex = pageIndex,
            pageCount = pageCount,
            startIndex = startIndex,
            items = items.subList(startIndex, endIndex).toList(),
            hasPrevious = pageIndex > 0,
            hasNext = pageIndex + 1 < pageCount
        )
    }

    /**
     * Returns a valid page index only for explicit navigation phrases.
     * Numeric selection is left to ContactChoiceSelector and is never mistaken for paging.
     */
    fun navigate(
        command: String,
        currentPageIndex: Int,
        totalItems: Int,
        pageSize: Int = PAGE_SIZE
    ): Int? {
        require(pageSize > 0) { "pageSize must be positive" }
        if (totalItems <= 0) return null
        val pageCount = (totalItems - 1) / pageSize + 1
        val current = currentPageIndex.coerceIn(0, pageCount - 1)
        return when (normalize(command)) {
            "sonraki", "sonraki sayfa", "daha fazla", "devam", "ileri" ->
                (current + 1).takeIf { it < pageCount }
            "önceki", "önceki sayfa", "geri" ->
                (current - 1).takeIf { it >= 0 }
            else -> null
        }
    }

    private fun normalize(command: String): String =
        command.trim()
            .lowercase(Locale.forLanguageTag("tr-TR"))
            .replace('\t', ' ')
            .replace('\n', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trimEnd('.', ',', '!', '?')
}
