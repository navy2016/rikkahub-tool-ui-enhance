package me.rerere.rikkahub.data.document.epub

internal data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String,
)

internal fun String.containsEpubProperty(value: String): Boolean {
    return split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .any { it.equals(value, ignoreCase = true) }
}
