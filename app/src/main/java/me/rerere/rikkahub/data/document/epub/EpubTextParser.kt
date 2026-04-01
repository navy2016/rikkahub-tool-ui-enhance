package me.rerere.rikkahub.data.document.epub

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.document.DocumentExtractOptions
import me.rerere.rikkahub.data.document.DocumentTextBlock
import me.rerere.rikkahub.data.document.DocumentTextExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

internal object EpubTextParser {
    fun stream(
        file: File,
        options: DocumentExtractOptions,
        sink: (DocumentTextBlock) -> Boolean,
    ) {
        ZipFile(file).use { zip ->
            val opfPath = findPackagePath(zip)
                ?: error("Invalid EPUB: package document not found")
            val opfEntry = zip.getEntry(opfPath)
                ?: error("Invalid EPUB: package entry missing")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfDoc = zip.getInputStream(opfEntry).use {
                Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
            }

            val manifest = readManifest(opfDoc)
            val spineIds = readSpineIds(opfDoc)
            val coverHref = findCoverImageHref(opfDoc, manifest, opfDir)
            val coverKey = coverHref?.let(::normalizeHrefForCompare)
            val imageItems = manifest.values.filter { item ->
                item.mediaType.startsWith("image/") &&
                    !item.properties.containsEpubProperty("cover-image") &&
                    normalizeHrefForCompare(item.href) != coverKey
            }

            val total = spineIds.size + if (options.enableImageOcr) imageItems.size else 0
            var progress = 0

            spineIds.forEach { idref ->
                val item = manifest[idref] ?: return@forEach
                if (item.properties.containsEpubProperty("nav")) return@forEach

                val htmlPath = EpubPathResolver.resolve(opfDir, item.href)
                val html = readZipText(zip, htmlPath) ?: return@forEach
                val extracted = extractReadableHtmlText(html)
                progress += 1
                if (!emit(extracted, progress, total, "章节", sink)) return
            }

            if (!options.enableImageOcr) return

            imageItems.forEachIndexed { index, item ->
                val imagePath = EpubPathResolver.resolve(opfDir, item.href)
                val imageEntry = zip.getEntry(imagePath) ?: return@forEachIndexed
                val temp = File.createTempFile("epub_image_", ".tmp")
                try {
                    zip.getInputStream(imageEntry).use { input ->
                        temp.outputStream().use(input::copyTo)
                    }
                    val ocrText = performImageOcr(temp, imagePath)
                    if (ocrText.isNotBlank()) {
                        progress += 1
                        if (!emit("[EPUB图片 ${index + 1}]\n$ocrText", progress, total, "图片", sink)) return
                    }
                } finally {
                    temp.delete()
                }
            }
        }
    }

    private fun readManifest(opfDoc: Document): Map<String, EpubManifestItem> {
        val manifestElement = opfDoc.getAllElements().firstOrNull { it.hasTagLocalName("manifest") }
            ?: return emptyMap()
        return manifestElement.children()
            .filter { it.hasTagLocalName("item") }
            .map { item ->
                EpubManifestItem(
                    id = item.attr("id"),
                    href = item.attr("href"),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties"),
                )
            }
            .filter { it.id.isNotBlank() && it.href.isNotBlank() }
            .associateBy { it.id }
    }

    private fun readSpineIds(opfDoc: Document): List<String> {
        val spineElement = opfDoc.getAllElements().firstOrNull { it.hasTagLocalName("spine") }
            ?: return emptyList()
        return spineElement.children()
            .filter { it.hasTagLocalName("itemref") }
            .mapNotNull { it.attr("idref").takeIf(String::isNotBlank) }
    }

    private fun extractReadableHtmlText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script,style,noscript,svg,math,nav,aside[epub|type=footnote],aside[role=doc-footnote]")
            .remove()
        doc.select("[hidden],[aria-hidden=true]").remove()

        val text = buildString {
            val body = doc.body() ?: doc
            body.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre").forEach { e ->
                val line = e.text().trim()
                if (line.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(line)
                }
            }
            if (isBlank()) append(doc.text())
        }
        return DocumentTextExtractor.normalizeExtractedText(text)
    }

    private fun findCoverImageHref(
        opfDoc: Document,
        manifest: Map<String, EpubManifestItem>,
        opfDir: String,
    ): String? {
        val metadata = opfDoc.getAllElements().firstOrNull { it.hasTagLocalName("metadata") }
        val coverMetaId = metadata
            ?.children()
            ?.firstOrNull { it.hasTagLocalName("meta") && it.attr("name").equals("cover", ignoreCase = true) }
            ?.attr("content")
            ?.takeIf(String::isNotBlank)
        if (coverMetaId != null) {
            manifest[coverMetaId]?.href?.let { return EpubPathResolver.resolve(opfDir, it) }
        }

        manifest.values.firstOrNull { it.properties.containsEpubProperty("cover-image") }?.href?.let {
            return EpubPathResolver.resolve(opfDir, it)
        }

        val guide = opfDoc.getAllElements().firstOrNull { it.hasTagLocalName("guide") }
        val coverRef = guide
            ?.children()
            ?.firstOrNull {
                it.hasTagLocalName("reference") && it.attr("type").equals("cover", ignoreCase = true)
            }
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
        return coverRef?.let { EpubPathResolver.resolve(opfDir, it) }
    }

    private fun findPackagePath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = zip.getInputStream(container).use {
            Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
        }
        return doc.getAllElements()
            .firstOrNull { it.hasTagLocalName("rootfile") }
            ?.attr("full-path")
            ?.takeIf(String::isNotBlank)
    }

    private fun readZipText(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use(InputStream::readBytes).toString(Charsets.UTF_8)
    }

    private fun performImageOcr(file: File, imagePath: String): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return ""
        if (width * height < 6_400) return ""

        val raw = runBlocking {
            OcrTransformer.performOcr(UIMessagePart.Image(file.toUri().toString()))
        }
        return raw.substringAfter("<image_file_ocr>", raw)
            .substringBefore("</image_file_ocr>")
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith("[ERROR") }
            ?.let { "来源: $imagePath\n$it" }
            .orEmpty()
    }

    private fun emit(
        text: String,
        current: Int,
        total: Int,
        label: String,
        sink: (DocumentTextBlock) -> Boolean,
    ): Boolean {
        val normalized = DocumentTextExtractor.normalizeExtractedText(text)
        if (normalized.isBlank()) return true
        return sink(
            DocumentTextBlock(
                text = normalized,
                progressCurrent = current,
                progressTotal = total,
                progressLabel = label,
            )
        )
    }

    private fun normalizeHrefForCompare(href: String): String {
        return href.substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')
            .trimStart('.')
            .trimStart('/')
            .replace(Regex("/{2,}"), "/")
            .lowercase()
    }
}

private fun Element.hasTagLocalName(localName: String): Boolean {
    return tagName().substringAfter(':').equals(localName, ignoreCase = true)
}
