package ru.nstu.mail.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.nstu.mail.data.EmailHtmlSanitizer
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val MAX_REMOTE_IMAGE_BYTES = 4 * 1024 * 1024

private val REMOTE_IMAGE_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

private val PLAIN_URL_REGEX = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

internal sealed interface EmailBlock {
    data class RichText(
        val text: AnnotatedString,
        val fontSize: TextUnit = 15.sp,
        val lineHeight: TextUnit = 22.sp,
        val fontFamily: FontFamily = FontFamily.SansSerif,
        val bgColor: Color? = null,
        val indentLevel: Int = 0
    ) : EmailBlock

    data class Quote(val blocks: List<EmailBlock>) : EmailBlock
    data class BulletItem(val prefix: String, val blocks: List<EmailBlock>, val indent: Int) : EmailBlock
    data class ImageBlock(val src: String, val alt: String) : EmailBlock
    data class TableBlock(val rows: List<List<TableCell>>) : EmailBlock
    data object Divider : EmailBlock
}

internal data class TableCell(
    val blocks: List<EmailBlock>,
    val isHeader: Boolean,
    val bgColor: Color?
)

@Composable
internal fun DesktopEmailBodyViewer(
    messageKey: String,
    body: String,
    isHtml: Boolean,
    darkMode: Boolean,
    bgColor: Color,
    fgColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgHex = remember(bgColor) { colorToHex(bgColor) }
    val fgHex = remember(fgColor) { colorToHex(fgColor) }
    val linkHex = remember(linkColor) { colorToHex(linkColor) }

    val blocks = remember(messageKey, body, isHtml, darkMode, bgHex, fgHex, linkHex) {
        if (!isHtml) {
            listOf(
                EmailBlock.RichText(
                    text = linkifyPlainText(body, linkColor, onOpenUrl),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            )
        } else {
            val sanitizedDoc = EmailHtmlSanitizer.renderEmailDocument(
                body = body,
                isHtml = true,
                darkMode = darkMode,
                bgHex = bgHex,
                textHex = fgHex,
                linkHex = linkHex
            )
            parseEmailHtmlToBlocks(sanitizedDoc, fgColor, linkColor, onOpenUrl)
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(messageKey) {
        scrollState.scrollTo(0)
    }

    Box(modifier = modifier.background(bgColor)) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmailBlocksColumn(
                    blocks = blocks,
                    fgColor = fgColor,
                    linkColor = linkColor,
                    darkMode = darkMode
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
        )
    }
}

@Composable
private fun EmailBlocksColumn(
    blocks: List<EmailBlock>,
    fgColor: Color,
    linkColor: Color,
    darkMode: Boolean
) {
    blocks.forEach { block ->
        when (block) {
            is EmailBlock.RichText -> {
                if (block.text.isNotEmpty()) {
                    val padStart = (block.indentLevel * 14).dp
                    val baseMod = if (block.bgColor != null) {
                        Modifier
                            .fillMaxWidth()
                            .padding(start = padStart)
                            .clip(RoundedCornerShape(6.dp))
                            .background(block.bgColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(start = padStart)
                    }
                    Text(
                        text = block.text,
                        color = fgColor,
                        style = TextStyle(
                            fontSize = block.fontSize,
                            lineHeight = block.lineHeight,
                            fontFamily = block.fontFamily
                        ),
                        modifier = baseMod
                    )
                }
            }

            is EmailBlock.Quote -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(linkColor)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EmailBlocksColumn(block.blocks, fgColor, linkColor, darkMode)
                    }
                }
            }

            is EmailBlock.BulletItem -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.indent * 16).dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = block.prefix,
                        color = fgColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EmailBlocksColumn(block.blocks, fgColor, linkColor, darkMode)
                    }
                }
            }

            is EmailBlock.ImageBlock -> {
                EmailImageView(src = block.src, alt = block.alt, fgColor = fgColor)
            }

            is EmailBlock.TableBlock -> {
                EmailTableView(
                    table = block,
                    fgColor = fgColor,
                    linkColor = linkColor,
                    darkMode = darkMode
                )
            }

            EmailBlock.Divider -> {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = fgColor.copy(alpha = if (darkMode) 0.2f else 0.15f)
                )
            }
        }
    }
}

@Composable
private fun EmailTableView(
    table: EmailBlock.TableBlock,
    fgColor: Color,
    linkColor: Color,
    darkMode: Boolean
) {
    val maxCols = table.rows.maxOfOrNull { it.size } ?: 0
    if (maxCols <= 0) return

    val borderColor = fgColor.copy(alpha = if (darkMode) 0.25f else 0.2f)
    val hScroll = rememberScrollState()
    val wideTable = maxCols > 3

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (wideTable) Modifier.horizontalScroll(hScroll) else Modifier)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        table.rows.forEachIndexed { rIdx, row ->
            if (rIdx > 0) HorizontalDivider(color = borderColor)
            Row(modifier = Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
                for (cIdx in 0 until maxCols) {
                    if (cIdx > 0) {
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(borderColor))
                    }
                    val cell = row.getOrNull(cIdx)
                    val cellMod = (if (wideTable) Modifier.widthIn(min = 120.dp, max = 320.dp) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .then(if (cell?.bgColor != null) Modifier.background(cell.bgColor) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                    Column(
                        modifier = cellMod,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (cell != null) {
                            EmailBlocksColumn(cell.blocks, fgColor, linkColor, darkMode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailImageView(src: String, alt: String, fgColor: Color) {
    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(decodeDataUriImage(src)) }

    LaunchedEffect(src) {
        if (bitmap == null && src.startsWith("https://", ignoreCase = true)) {
            bitmap = loadRemoteImageBitmap(src)
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .widthIn(max = 760.dp)
                .heightIn(max = 560.dp)
        )
    } else if (alt.isNotBlank()) {
        Text(
            text = "[Изображение: $alt]",
            color = fgColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

private fun decodeDataUriImage(src: String): ImageBitmap? {
    if (!src.startsWith("data:", ignoreCase = true)) return null
    val commaIdx = src.indexOf(',')
    if (commaIdx <= 0) return null
    val meta = src.substring(0, commaIdx)
    if (!meta.contains("base64", ignoreCase = true)) return null
    val payload = src.substring(commaIdx + 1).replace(Regex("\\s+"), "")
    return runCatching {
        val bytes = Base64.getDecoder().decode(payload)
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

private suspend fun loadRemoteImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder().url(url).build()
        REMOTE_IMAGE_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body ?: return@withContext null
            if (body.contentLength() > MAX_REMOTE_IMAGE_BYTES) return@withContext null
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > MAX_REMOTE_IMAGE_BYTES) return@withContext null
                    baos.write(buf, 0, read)
                }
            }
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return@withContext null
            val img = Image.makeFromEncoded(bytes)
            // Skip 1x1 tracking pixels
            if (img.width <= 1 && img.height <= 1) return@withContext null
            img.toComposeImageBitmap()
        }
    }.getOrNull()
}

private fun colorToHex(color: Color): String =
    String.format("#%06X", color.toArgb() and 0xFFFFFF)

internal fun linkifyPlainText(
    text: String,
    linkColor: Color,
    onOpenUrl: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in PLAIN_URL_REGEX.findAll(text)) {
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
        val url = m.value
        pushLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = { onOpenUrl(url) }
            )
        )
        append(url)
        pop()
        cursor = m.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

internal fun parseEmailHtmlToBlocks(
    sanitizedHtmlDoc: String,
    defaultTextColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit
): List<EmailBlock> {
    val body = Jsoup.parse(sanitizedHtmlDoc).body()
    val out = mutableListOf<EmailBlock>()
    collectContainerBlocks(body, out, defaultTextColor, linkColor, onOpenUrl, listDepth = 0)
    return out
}

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "main", "figure", "figcaption",
    "address", "center", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre",
    "ul", "ol", "li", "table", "hr"
)

private fun collectContainerBlocks(
    container: Element,
    out: MutableList<EmailBlock>,
    defaultTextColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    listDepth: Int
) {
    val inlineBuffer = mutableListOf<Node>()

    fun flushInline() {
        if (inlineBuffer.isEmpty()) return
        val annotated = buildInlineAnnotatedString(inlineBuffer, defaultTextColor, linkColor, onOpenUrl)
        inlineBuffer.clear()
        if (annotated.text.isNotBlank()) {
            out += EmailBlock.RichText(text = annotated)
        }
    }

    for (child in container.childNodes()) {
        if (child is TextNode) {
            inlineBuffer += child
            continue
        }
        if (child !is Element) continue
        if (isHiddenByStyle(child)) continue

        val tag = child.tagName().lowercase()
        when {
            tag == "hr" -> {
                flushInline()
                out += EmailBlock.Divider
            }
            tag == "img" -> {
                flushInline()
                val src = child.attr("src").trim()
                if (src.isNotEmpty() && !isTinyTrackingImg(child)) {
                    out += EmailBlock.ImageBlock(src = src, alt = child.attr("alt").trim())
                }
            }
            tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> {
                flushInline()
                val level = tag[1].digitToInt()
                val size = when (level) {
                    1 -> 22.sp
                    2 -> 19.sp
                    3 -> 17.sp
                    else -> 16.sp
                }
                val ann = buildInlineAnnotatedString(
                    child.childNodes(),
                    defaultTextColor,
                    linkColor,
                    onOpenUrl,
                    baseSpan = SpanStyle(fontWeight = FontWeight.Bold)
                )
                if (ann.text.isNotBlank()) {
                    out += EmailBlock.RichText(text = ann, fontSize = size, lineHeight = (size.value + 7f).sp)
                }
            }
            tag == "pre" -> {
                flushInline()
                val preBg = parseCssBackground(child)
                val ann = buildInlineAnnotatedString(child.childNodes(), defaultTextColor, linkColor, onOpenUrl, preserveWhitespace = true)
                if (ann.text.isNotBlank()) {
                    out += EmailBlock.RichText(
                        text = ann,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        bgColor = preBg ?: defaultTextColor.copy(alpha = 0.06f)
                    )
                }
            }
            tag == "blockquote" -> {
                flushInline()
                val nested = mutableListOf<EmailBlock>()
                collectContainerBlocks(child, nested, defaultTextColor, linkColor, onOpenUrl, listDepth)
                if (nested.isNotEmpty()) out += EmailBlock.Quote(nested)
            }
            tag == "ul" || tag == "ol" -> {
                flushInline()
                var idx = 1
                for (li in child.children()) {
                    if (li.tagName().equals("li", ignoreCase = true)) {
                        val prefix = if (tag == "ol") "${idx++}." else "•"
                        val liBlocks = mutableListOf<EmailBlock>()
                        collectContainerBlocks(li, liBlocks, defaultTextColor, linkColor, onOpenUrl, listDepth + 1)
                        if (liBlocks.isNotEmpty()) {
                            out += EmailBlock.BulletItem(prefix = prefix, blocks = liBlocks, indent = listDepth)
                        }
                    }
                }
            }
            tag == "table" -> {
                flushInline()
                emitTable(child, out, defaultTextColor, linkColor, onOpenUrl, listDepth)
            }
            tag in BLOCK_TAGS -> {
                flushInline()
                val hasBlockChildren = child.children().any { it.tagName().lowercase() in BLOCK_TAGS }
                val blockBg = parseCssBackground(child)
                if (hasBlockChildren && blockBg == null) {
                    collectContainerBlocks(child, out, defaultTextColor, linkColor, onOpenUrl, listDepth)
                } else {
                    val baseStyle = parseSpanStyleFromElement(child)
                    val nested = mutableListOf<EmailBlock>()
                    if (hasBlockChildren) {
                        collectContainerBlocks(child, nested, defaultTextColor, linkColor, onOpenUrl, listDepth)
                        out += nested
                    } else {
                        emitMixedBlock(child.childNodes(), baseStyle, blockBg, out, defaultTextColor, linkColor, onOpenUrl)
                    }
                }
            }
            else -> {
                inlineBuffer += child
            }
        }
    }
    flushInline()
}

private fun emitMixedBlock(
    nodes: List<Node>,
    baseSpan: SpanStyle,
    blockBg: Color?,
    out: MutableList<EmailBlock>,
    defaultTextColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit
) {
    // Split inline text around standalone <img> tags inside <p> or <div>
    val run = mutableListOf<Node>()
    fun flush() {
        if (run.isEmpty()) return
        val ann = buildInlineAnnotatedString(run, defaultTextColor, linkColor, onOpenUrl, baseSpan = baseSpan)
        run.clear()
        if (ann.text.isNotBlank()) {
            out += EmailBlock.RichText(text = ann, bgColor = blockBg)
        }
    }
    for (n in nodes) {
        if (n is Element && n.tagName().equals("img", ignoreCase = true)) {
            flush()
            val src = n.attr("src").trim()
            if (src.isNotEmpty() && !isTinyTrackingImg(n)) {
                out += EmailBlock.ImageBlock(src = src, alt = n.attr("alt").trim())
            }
        } else {
            run += n
        }
    }
    flush()
}

private fun emitTable(
    table: Element,
    out: MutableList<EmailBlock>,
    defaultTextColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    listDepth: Int
) {
    val rowEls = table.select("> tr, > tbody > tr, > thead > tr, > tfoot > tr")
    if (rowEls.isEmpty()) return

    val maxCols = rowEls.maxOfOrNull { tr ->
        tr.children().count { it.tagName().lowercase() in setOf("td", "th") }
    } ?: 0

    val hasNestedTable = rowEls.any { tr ->
        tr.children().any { cell -> cell.selectFirst("table") != null }
    }

    // Unwrap 1-column email layout wrapper tables or tables wrapping other tables so text never squishes
    if (maxCols <= 1 || hasNestedTable) {
        for (tr in rowEls) {
            for (cell in tr.children()) {
                if (cell.tagName().lowercase() in setOf("td", "th")) {
                    collectContainerBlocks(cell, out, defaultTextColor, linkColor, onOpenUrl, listDepth)
                }
            }
        }
        return
    }

    val parsedRows = rowEls.mapNotNull { tr ->
        val cells = tr.children().filter { it.tagName().lowercase() in setOf("td", "th") }.map { cell ->
            val cellBlocks = mutableListOf<EmailBlock>()
            collectContainerBlocks(cell, cellBlocks, defaultTextColor, linkColor, onOpenUrl, listDepth)
            TableCell(
                blocks = cellBlocks,
                isHeader = cell.tagName().equals("th", ignoreCase = true),
                bgColor = parseCssBackground(cell) ?: parseCssBackground(tr)
            )
        }
        if (cells.all { it.blocks.isEmpty() }) null else cells
    }
    if (parsedRows.isNotEmpty()) {
        out += EmailBlock.TableBlock(parsedRows)
    }
}

private fun buildInlineAnnotatedString(
    nodes: List<Node>,
    defaultTextColor: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    baseSpan: SpanStyle = SpanStyle(),
    preserveWhitespace: Boolean = false
): AnnotatedString = buildAnnotatedString {
    if (baseSpan != SpanStyle()) pushStyle(baseSpan)
    val state = StringBuilder()

    fun appendCollapsedText(raw: String) {
        if (preserveWhitespace) {
            append(raw)
            state.append(raw)
            return
        }
        val normalized = raw.replace(Regex("[\\r\\n\\t ]+"), " ")
        val prevIsSpaceOrEmpty = state.isEmpty() || state.last().isWhitespace()
        val trimmedStart = if (prevIsSpaceOrEmpty) normalized.trimStart(' ') else normalized
        if (trimmedStart.isNotEmpty()) {
            append(trimmedStart)
            state.append(trimmedStart)
        }
    }

    fun walk(n: Node, currentUrl: String?) {
        when (n) {
            is TextNode -> {
                val txt = n.wholeText
                if (currentUrl == null) {
                    var cursor = 0
                    for (m in PLAIN_URL_REGEX.findAll(txt)) {
                        if (m.range.first > cursor) appendCollapsedText(txt.substring(cursor, m.range.first))
                        val u = m.value
                        pushLink(
                            LinkAnnotation.Url(
                                url = u,
                                styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                linkInteractionListener = { onOpenUrl(u) }
                            )
                        )
                        append(u)
                        state.append(u)
                        pop()
                        cursor = m.range.last + 1
                    }
                    if (cursor < txt.length) appendCollapsedText(txt.substring(cursor))
                } else {
                    appendCollapsedText(txt)
                }
            }
            is Element -> {
                if (isHiddenByStyle(n)) return
                val tag = n.tagName().lowercase()
                when (tag) {
                    "br" -> {
                        append('\n')
                        state.append('\n')
                    }
                    "img" -> {
                        val alt = n.attr("alt").trim()
                        if (alt.isNotEmpty() && !isTinyTrackingImg(n)) {
                            appendCollapsedText("[$alt]")
                        }
                    }
                    "a" -> {
                        val href = n.attr("href").trim()
                        val safeHref = href.takeIf {
                            it.startsWith("http://", ignoreCase = true) ||
                                it.startsWith("https://", ignoreCase = true) ||
                                it.startsWith("mailto:", ignoreCase = true)
                        }
                        if (safeHref != null) {
                            pushLink(
                                LinkAnnotation.Url(
                                    url = safeHref,
                                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                    linkInteractionListener = { onOpenUrl(safeHref) }
                                )
                            )
                            val span = parseSpanStyleFromElement(n)
                            if (span != SpanStyle()) pushStyle(span)
                            n.childNodes().forEach { walk(it, safeHref) }
                            if (span != SpanStyle()) pop()
                            pop()
                        } else {
                            n.childNodes().forEach { walk(it, currentUrl) }
                        }
                    }
                    else -> {
                        val span = parseSpanStyleFromElement(n)
                        if (span != SpanStyle()) pushStyle(span)
                        n.childNodes().forEach { walk(it, currentUrl) }
                        if (span != SpanStyle()) pop()
                        if (tag in setOf("p", "div", "tr")) {
                            if (state.isNotEmpty() && state.last() != '\n') {
                                append('\n')
                                state.append('\n')
                            }
                        }
                    }
                }
            }
        }
    }

    nodes.forEach { walk(it, null) }
    if (baseSpan != SpanStyle()) pop()
}

private fun isHiddenByStyle(el: Element): Boolean {
    val style = el.attr("style").lowercase()
    return Regex("""display\s*:\s*none""").containsMatchIn(style)
}

private fun isTinyTrackingImg(el: Element): Boolean {
    val w = el.attr("width").trim().toIntOrNull()
    val h = el.attr("height").trim().toIntOrNull()
    return (w != null && w <= 1) && (h != null && h <= 1)
}

private fun parseSpanStyleFromElement(el: Element): SpanStyle {
    val tag = el.tagName().lowercase()
    var weight: FontWeight? = when (tag) {
        "b", "strong", "th", "h1", "h2", "h3", "h4", "h5", "h6" -> FontWeight.Bold
        else -> null
    }
    var style: FontStyle? = if (tag == "i" || tag == "em") FontStyle.Italic else null
    var decoration: TextDecoration? = when (tag) {
        "u", "ins" -> TextDecoration.Underline
        "s", "del", "strike" -> TextDecoration.LineThrough
        else -> null
    }
    val family: FontFamily? = if (tag == "code" || tag == "tt" || tag == "kbd" || tag == "pre") {
        FontFamily.Monospace
    } else null

    var color = el.attr("color").takeIf { it.isNotBlank() }?.let(::parseCssColor) ?: Color.Unspecified
    var bgColor = Color.Unspecified

    val rawStyle = el.attr("style")
    if (rawStyle.isNotBlank()) {
        for (decl in rawStyle.split(';')) {
            val idx = decl.indexOf(':')
            if (idx <= 0) continue
            val prop = decl.substring(0, idx).trim().lowercase()
            val valStr = decl.substring(idx + 1).trim()
            when (prop) {
                "color" -> parseCssColor(valStr)?.let { color = it }
                "background", "background-color" -> parseCssColor(valStr)?.let { bgColor = it }
                "font-weight" -> {
                    val lv = valStr.lowercase()
                    val num = lv.toIntOrNull()
                    if (lv == "bold" || lv == "bolder" || (num != null && num >= 600)) {
                        weight = FontWeight.Bold
                    } else if (lv == "normal") {
                        weight = FontWeight.Normal
                    }
                }
                "font-style" -> {
                    if (valStr.equals("italic", true) || valStr.equals("oblique", true)) {
                        style = FontStyle.Italic
                    }
                }
                "text-decoration" -> {
                    val lv = valStr.lowercase()
                    decoration = when {
                        "underline" in lv -> TextDecoration.Underline
                        "line-through" in lv -> TextDecoration.LineThrough
                        "none" in lv -> TextDecoration.None
                        else -> decoration
                    }
                }
            }
        }
    }
    return SpanStyle(
        color = color,
        background = bgColor,
        fontWeight = weight,
        fontStyle = style,
        textDecoration = decoration,
        fontFamily = family
    )
}

private fun parseCssBackground(el: Element): Color? {
    val rawStyle = el.attr("style")
    if (rawStyle.isNotBlank()) {
        for (decl in rawStyle.split(';')) {
            val idx = decl.indexOf(':')
            if (idx <= 0) continue
            val prop = decl.substring(0, idx).trim().lowercase()
            if (prop == "background-color" || prop == "background") {
                parseCssColor(decl.substring(idx + 1).trim())?.let { return it }
            }
        }
    }
    return el.attr("bgcolor").takeIf { it.isNotBlank() }?.let(::parseCssColor)
}

private val NAMED_CSS_COLORS = mapOf(
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "blue" to Color(0xFF0000FF),
    "navy" to Color(0xFF000080),
    "darkblue" to Color(0xFF00008B),
    "maroon" to Color(0xFF800000),
    "darkred" to Color(0xFF8B0000),
    "darkgreen" to Color(0xFF006400),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "silver" to Color(0xFFC0C0C0),
    "dimgray" to Color(0xFF696969),
    "dimgrey" to Color(0xFF696969),
    "lightgray" to Color(0xFFD3D3D3),
    "lightgrey" to Color(0xFFD3D3D3),
    "orange" to Color(0xFFFFA500),
    "yellow" to Color(0xFFFFFF00),
    "purple" to Color(0xFF800080),
    "teal" to Color(0xFF008080)
)

internal fun parseCssColor(raw: String): Color? {
    val token = raw.trim().lowercase().substringBefore('!').trim()
    if (token.isEmpty() || token == "transparent" || token == "inherit" || token == "initial") return null
    NAMED_CSS_COLORS[token]?.let { return it }
    if (token.startsWith("rgb")) {
        val inner = token.substringAfter('(', "").substringBefore(')')
        val parts = inner.split(',').map { it.trim().toDoubleOrNull() ?: return null }
        if (parts.size < 3) return null
        val r = parts[0].toInt().coerceIn(0, 255)
        val g = parts[1].toInt().coerceIn(0, 255)
        val b = parts[2].toInt().coerceIn(0, 255)
        val a = if (parts.size >= 4) parts[3].toFloat().coerceIn(0f, 1f) else 1f
        return Color(r / 255f, g / 255f, b / 255f, a)
    }
    val hex = if (token.startsWith("#")) token else Regex("#[0-9a-f]{3,6}").find(token)?.value ?: return null
    val h = hex.removePrefix("#")
    return when (h.length) {
        3 -> {
            val r = h[0].digitToIntOrNull(16)?.times(17) ?: return null
            val g = h[1].digitToIntOrNull(16)?.times(17) ?: return null
            val b = h[2].digitToIntOrNull(16)?.times(17) ?: return null
            Color(r, g, b)
        }
        6 -> {
            val v = h.toLongOrNull(16) ?: return null
            Color(0xFF000000L or v)
        }
        else -> null
    }
}
