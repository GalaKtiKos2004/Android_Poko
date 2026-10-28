package com.kovtun.pokevault.domain

/**
 * Single normalisation of the search box text, used both for the Room query and for the
 * server lookup, so "#025", "025" and "25" all mean the same pokemon.
 */
object SearchQuery {

    fun normalize(raw: String): String {
        val text = raw.trim().lowercase().removePrefix("#").trim()
        if (text.isEmpty()) return ""
        if (text.all { it.isDigit() }) {
            // "000" stays "0" — a valid query that simply finds nothing.
            return text.trimStart('0').ifEmpty { "0" }
        }
        // API names use dashes: "mr mime" -> "mr-mime"
        return text.replace(Regex("\\s+"), "-")
    }

    fun isNumber(normalized: String): Boolean = normalized.isNotEmpty() && normalized.all { it.isDigit() }

    /** Names shorter than this are too ambiguous to look up on the server; numbers are always fine. */
    const val MIN_REMOTE_NAME_LENGTH = 3

    fun isRemoteLookupAllowed(normalized: String): Boolean =
        (isNumber(normalized) && normalized != "0") || normalized.length >= MIN_REMOTE_NAME_LENGTH
}
