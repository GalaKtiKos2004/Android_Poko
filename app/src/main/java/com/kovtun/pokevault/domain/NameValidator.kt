package com.kovtun.pokevault.domain

/** Validation errors for user-created names (profiles, collections, presets). */
sealed class NameError(message: String) : IllegalArgumentException(message) {
    data object Blank : NameError("Название не может быть пустым")
    data object TooLong : NameError("Название длиннее $MAX_NAME_LENGTH символов")
    data object Duplicate : NameError("Такое название уже есть")
}

const val MAX_NAME_LENGTH = 40

object NameValidator {
    /**
     * Returns the normalised name or throws [NameError].
     * Duplicates are compared case-insensitively.
     */
    fun validate(raw: String, existing: Collection<String>): String {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) throw NameError.Blank
        if (name.length > MAX_NAME_LENGTH) throw NameError.TooLong
        if (existing.any { it.equals(name, ignoreCase = true) }) throw NameError.Duplicate
        return name
    }
}
