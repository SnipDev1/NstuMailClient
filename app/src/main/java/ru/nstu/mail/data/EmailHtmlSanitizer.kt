package ru.nstu.mail.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Sanitizes untrusted HTML emails via jsoup Safelist + CSS property allowlisting
 * and adapts colors/contrast for dark mode.
 */
object EmailHtmlSanitizer {

    private val SAFE_DATA_IMAGE_REGEX = Regex(
        "^data:image/(png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=\\s]+$",
        RegexOption.IGNORE_CASE
    )

    private val ALLOWED_CSS_PROPS = setOf(
        "color", "background-color", "background",
        "font-family", "font-size", "font-weight", "font-style",
        "text-decoration", "text-align", "line-height", "letter-spacing",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border", "border-top", "border-right", "border-bottom", "border-left",
        "border-radius", "border-collapse",
        "width", "max-width", "vertical-align",
        "white-space", "word-break", "list-style", "list-style-type", "display"
    )

    private val COLOR_NAMED = mapOf(
        "black" to intArrayOf(0, 0, 0),
        "white" to intArrayOf(255, 255, 255),
        "gray" to intArrayOf(128, 128, 128),
        "grey" to intArrayOf(128, 128, 128),
        "silver" to intArrayOf(192, 192, 192),
        "dimgray" to intArrayOf(105, 105, 105),
        "dimgrey" to intArrayOf(105, 105, 105),
        "darkgray" to intArrayOf(169, 169, 169),
        "lightgray" to intArrayOf(211, 211, 211),
        "lightgrey" to intArrayOf(211, 211, 211),
        "navy" to intArrayOf(0, 0, 128),
        "darkblue" to intArrayOf(0, 0, 139),
        "maroon" to intArrayOf(128, 0, 0),
        "darkred" to intArrayOf(139, 0, 0),
        "darkgreen" to intArrayOf(0, 100, 0),
        "green" to intArrayOf(0, 128, 0),
        "blue" to intArrayOf(0, 0, 255),
        "red" to intArrayOf(255, 0, 0),
        "windowtext" to intArrayOf(0, 0, 0)
    )

    private val EMAIL_SAFELIST: Safelist = Safelist.relaxed()
        .addTags(
            "span", "div", "hr", "font", "center", "s", "del", "ins", "mark",
            "section", "article", "header", "footer", "main", "figure", "figcaption",
            "small", "big", "tt", "kbd", "abbr", "address",
            "colgroup", "col", "caption", "thead", "tbody", "tfoot"
        )
        .addAttributes(":all", "style", "dir", "lang")
        .addAttributes("font", "color", "face", "size")
        .addAttributes("table", "align", "valign", "bgcolor", "width", "height", "border", "cellpadding", "cellspacing")
        .addAttributes("tr", "align", "valign", "bgcolor")
        .addAttributes("td", "align", "valign", "bgcolor", "width", "height", "colspan", "rowspan")
        .addAttributes("th", "align", "valign", "bgcolor", "width", "height", "colspan", "rowspan")
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "https", "data")

    fun renderEmailDocument(
        body: String,
        isHtml: Boolean,
        darkMode: Boolean,
        bgHex: String,
        textHex: String,
        linkHex: String
    ): String {
        val safeContent = if (!isHtml) {
            "<pre class=\"plain-text\">${Entities.escape(body)}</pre>"
        } else {
            val parsed = Jsoup.parse(body)
            val cleanDoc = Cleaner(EMAIL_SAFELIST).clean(parsed)
            sanitizeAndAdaptElements(cleanDoc.body(), darkMode)
            cleanDoc.body().html()
        }

        val colorScheme = if (darkMode) "dark" else "light"
        val hrColor = if (darkMode) "rgba(255,255,255,0.2)" else "rgba(0,0,0,0.15)"

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=4.0"/>
<meta name="color-scheme" content="$colorScheme"/>
<style>
html, body {
  margin: 0;
  padding: 0;
  color: $textHex;
  background: $bgHex;
  font-family: -apple-system, Roboto, sans-serif;
  font-size: 15px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  word-wrap: break-word;
}
body { padding: 16px; }
a { color: $linkHex; }
img { max-width: 100% !important; height: auto !important; }
table { max-width: 100% !important; border-collapse: collapse; }
blockquote { border-left: 3px solid $linkHex; margin: 8px 0 8px 4px; padding-left: 12px; opacity: 0.9; }
pre, code, .plain-text { white-space: pre-wrap; overflow-wrap: anywhere; font-family: -apple-system, Roboto, sans-serif; }
hr { border: 0; border-top: 1px solid $hrColor; }
</style>
</head>
<body>$safeContent</body>
</html>"""
    }

    private fun sanitizeAndAdaptElements(root: org.jsoup.nodes.Element, darkMode: Boolean) {
        val toRemove = mutableListOf<org.jsoup.nodes.Element>()
        for (el in root.allElements) {
            if (el === root) continue

            if (el.tagName().equals("img", ignoreCase = true)) {
                val src = el.attr("src").trim()
                if (src.isEmpty() || (src.startsWith("data:", ignoreCase = true) && !SAFE_DATA_IMAGE_REGEX.matches(src))) {
                    toRemove += el
                    continue
                }
            }

            if (darkMode) {
                if (el.hasAttr("bgcolor")) {
                    val lum = parseColorLuminance(el.attr("bgcolor"))
                    if (lum == null || lum > 0.32) {
                        el.removeAttr("bgcolor")
                    }
                }
                if (el.hasAttr("color")) {
                    val lum = parseColorLuminance(el.attr("color"))
                    if (lum == null || lum < 0.42) {
                        el.removeAttr("color")
                    }
                }
            }

            if (el.hasAttr("style")) {
                val cleanStyle = sanitizeInlineStyle(el.attr("style"), darkMode)
                if (cleanStyle.isBlank()) {
                    el.removeAttr("style")
                } else {
                    el.attr("style", cleanStyle)
                }
            }
        }
        toRemove.forEach { it.remove() }
    }

    private fun sanitizeInlineStyle(rawStyle: String, darkMode: Boolean): String {
        val kept = mutableListOf<String>()
        for (decl in rawStyle.split(';')) {
            val colonIdx = decl.indexOf(':')
            if (colonIdx <= 0) continue
            val prop = decl.substring(0, colonIdx).trim().lowercase()
            if (prop !in ALLOWED_CSS_PROPS) continue

            var value = decl.substring(colonIdx + 1)
                .replace(Regex("!\\s*important", RegexOption.IGNORE_CASE), "")
                .trim()
            if (value.isEmpty()) continue

            val lowerVal = value.lowercase()
            if ("url(" in lowerVal ||
                "expression(" in lowerVal ||
                "javascript:" in lowerVal ||
                "@import" in lowerVal ||
                "behavior:" in lowerVal
            ) {
                continue
            }

            if (darkMode) {
                if (prop == "background" || prop == "background-color") {
                    val lum = parseColorLuminance(value)
                    if (lum == null || lum > 0.32) continue
                } else if (prop == "color") {
                    val lum = parseColorLuminance(value)
                    if (lum == null || lum < 0.42) continue
                }
            }
            kept += "$prop: $value"
        }
        return kept.joinToString("; ")
    }

    private fun parseColorLuminance(rawValue: String): Double? {
        val token = rawValue.trim().lowercase()
            .substringBefore('!')
            .trim()

        val rgb: IntArray? = when {
            token == "transparent" -> return 1.0
            token in COLOR_NAMED -> COLOR_NAMED[token]
            token.startsWith("#") -> parseHexRgb(token)
            token.startsWith("rgb") -> parseFuncRgb(token)
            else -> {
                val hexMatch = Regex("#[0-9a-f]{3,6}").find(token)?.value
                if (hexMatch != null) parseHexRgb(hexMatch) else null
            }
        }
        if (rgb == null) return null
        val r = rgb[0] / 255.0
        val g = rgb[1] / 255.0
        val b = rgb[2] / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun parseHexRgb(hex: String): IntArray? {
        val h = hex.removePrefix("#")
        return when (h.length) {
            3 -> {
                val r = h[0].digitToIntOrNull(16) ?: return null
                val g = h[1].digitToIntOrNull(16) ?: return null
                val b = h[2].digitToIntOrNull(16) ?: return null
                intArrayOf(r * 17, g * 17, b * 17)
            }
            6 -> {
                val r = h.substring(0, 2).toIntOrNull(16) ?: return null
                val g = h.substring(2, 4).toIntOrNull(16) ?: return null
                val b = h.substring(4, 6).toIntOrNull(16) ?: return null
                intArrayOf(r, g, b)
            }
            else -> null
        }
    }

    private fun parseFuncRgb(token: String): IntArray? {
        val inner = token.substringAfter('(', "").substringBefore(')')
        val parts = inner.split(',').map { it.trim().toDoubleOrNull() ?: return null }
        if (parts.size < 3) return null
        if (parts.size >= 4 && parts[3] <= 0.15) return intArrayOf(255, 255, 255)
        return intArrayOf(
            parts[0].toInt().coerceIn(0, 255),
            parts[1].toInt().coerceIn(0, 255),
            parts[2].toInt().coerceIn(0, 255)
        )
    }
}
