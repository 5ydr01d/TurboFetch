package com.fiveydr.turbofetch

internal fun sanitizeFileName(input: String): String {
    val cleaned = input.map { character ->
        if (
            character == '/' || character == '\\' || character == ':' ||
            character == '*' || character == '?' || character == '"' ||
            character == '<' || character == '>' || character == '|' ||
            character.isISOControl()
        ) '_' else character
    }.joinToString("").trim().take(150)
    return cleaned.ifBlank { "download.bin" }
}

internal fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
