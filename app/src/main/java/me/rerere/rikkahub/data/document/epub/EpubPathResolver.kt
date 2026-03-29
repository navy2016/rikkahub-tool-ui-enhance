package me.rerere.rikkahub.data.document.epub

internal object EpubPathResolver {
    fun resolve(baseDir: String, relativePath: String): String {
        if (relativePath.isBlank()) return relativePath

        val normalized = relativePath
            .substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')

        val isAbsolute = normalized.startsWith('/')
        val clean = normalized.trimStart('/').replace(Regex("/{2,}"), "/")
        if (clean.isBlank()) return clean

        val parts = buildList {
            if (!isAbsolute && baseDir.isNotBlank()) {
                addAll(baseDir.split('/').filter { it.isNotBlank() })
            }
            clean.split('/').forEach { seg ->
                when (seg.trim()) {
                    "", "." -> Unit
                    ".." -> if (isNotEmpty()) removeAt(size - 1)
                    else -> add(seg)
                }
            }
        }
        return parts.joinToString("/")
    }
}
