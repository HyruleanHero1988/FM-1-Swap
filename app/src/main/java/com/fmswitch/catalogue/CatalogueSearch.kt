package com.fmswitch.catalogue

import java.text.Normalizer
import java.util.Locale

/**
 * The FIRMWARE search box. Every word typed must appear somewhere in the entry: name, author,
 * description, kinds, About line, or the GitHub accounts it is published under, so "rustypawz"
 * finds RedtailWorks and "rene" finds René. Spacing and punctuation are ignored as a fallback,
 * so "charlesvestal" and "mvave" match "Charles Vestal" and "M-VAVE".
 */
object CatalogueSearch {
    fun matches(entry: CatalogueEntry, query: String): Boolean {
        val words = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val fields = fields(entry).map(::fold)
        val squeezed = fields.map(::squeeze)
        return words.all { word ->
            fields.any { word in it } || squeeze(word).let { bare -> bare.isNotEmpty() && squeezed.any { bare in it } }
        }
    }

    /** GitHub users and organisations behind an entry's links and package. */
    fun accounts(entry: CatalogueEntry): Set<String> =
        (entry.links.map { it.url } + listOfNotNull(entry.packageURL))
            .flatMap { url -> ACCOUNT_PATTERNS.mapNotNull { it.find(url)?.groupValues?.get(1) } }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

    private fun fields(entry: CatalogueEntry): List<String> = buildList {
        add(entry.name)
        entry.author?.let(::add)
        entry.what?.let(::add)
        entry.about?.let(::add)
        addAll(entry.kinds)
        entry.kinds.forEach { kind -> KIND_WORDS[kind]?.let(::addAll) }
        if (entry.vintage) add("vintage")
        addAll(accounts(entry))
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private fun squeeze(text: String): String = text.filter { it.isLetterOrDigit() }

    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val KIND_WORDS = mapOf(
        "synth" to listOf("synth", "synths"),
        "groove" to listOf("groove", "groovebox", "grooveboxes"),
        "drums" to listOf("drums", "sampler", "samplers"),
        "chords" to listOf("chord", "chords"),
        "tracker" to listOf("tracker", "trackers"),
        "dev" to listOf("dev", "developer", "developers"),
        "other" to listOf("other"),
    )

    private val ACCOUNT_PATTERNS = listOf(
        Regex("""^https?://(?:www\.)?github\.com/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([A-Za-z0-9-]+)\.github\.io""", RegexOption.IGNORE_CASE),
    )
}
