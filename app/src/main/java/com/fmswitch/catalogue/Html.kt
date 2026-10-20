package com.fmswitch.catalogue

/** Just enough HTML reading for the firmware pages: elements by tag, attributes and text. */
object Html {
    data class Element(val attributes: Map<String, String>, val inner: String)

    fun elements(tag: String, html: String): List<Element> {
        val pattern = Regex("(?si)<$tag((?:\\s[^>]*)?)>(.*?)</$tag\\s*>")
        return pattern.findAll(html).map { match ->
            Element(attributes(match.groupValues[1]), match.groupValues[2])
        }.toList()
    }

    fun attributes(text: String): Map<String, String> {
        val pattern = Regex(
            """(?s)([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?""",
        )
        val result = LinkedHashMap<String, String>()
        for (match in pattern.findAll(text)) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
            result[match.groupValues[1].lowercase()] = decodeEntities(value)
        }
        return result
    }

    fun withoutComments(html: String): String =
        html.replace(Regex("(?s)<!--.*?-->"), "")

    fun text(html: String): String {
        val withoutTags = html.replace(Regex("<[^>]*>"), " ")
        val collapsed = decodeEntities(withoutTags).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed
            .replace(" ,", ",")
            .replace(" .", ".")
            .replace(" )", ")")
            .replace("( ", "(")
    }

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "minus" to "−",
        )
        return Regex("""&(#[xX][0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);""").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", true) ->
                    body.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                else -> named[body.lowercase()] ?: match.value
            }
        }
    }
}
