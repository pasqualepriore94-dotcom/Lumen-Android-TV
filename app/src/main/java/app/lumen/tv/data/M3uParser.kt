package app.lumen.tv.data

import java.io.BufferedReader

object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val episode = Regex("""\bS\d{1,2}\s*E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv")
    private val movieGroup = Regex("""film|movie|cinema|vod""", RegexOption.IGNORE_CASE)
    private val seriesGroup = Regex("""\bserie\b|\bseries\b""", RegexOption.IGNORE_CASE)

    /** Legge riga per riga, così anche le playlist molto grandi non occupano memoria inutile. */
    fun parse(reader: BufferedReader, limit: Int = 50_000): List<PlaylistItem> {
        val items = ArrayList<PlaylistItem>()
        var info: String? = null
        var extGroup: String? = null
        reader.forEachLine { raw ->
            if (items.size >= limit) return@forEachLine
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    item(info, extGroup, line)?.let(items::add)
                    info = null
                    extGroup = null
                }
            }
        }
        return items
    }

    private fun item(info: String?, extGroup: String?, url: String): PlaylistItem? {
        if (!url.contains("://")) return null
        val attrs = info?.let { line -> attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] } }.orEmpty()
        val name = info?.let(::displayName)?.takeIf { it.isNotBlank() }
            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?')
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup?.takeIf { it.isNotBlank() } ?: "Altro"
        return PlaylistItem(name, url, group, attrs["tvg-logo"]?.takeIf { it.isNotBlank() }, classify(name, url, group))
    }

    /** Il titolo è dopo la prima virgola fuori dalle virgolette. */
    private fun displayName(line: String): String {
        var quoted = false
        line.forEachIndexed { index, c ->
            if (c == '"') quoted = !quoted
            if (c == ',' && !quoted) return line.substring(index + 1).trim()
        }
        return ""
    }

    fun classify(name: String, url: String, group: String): MediaKind {
        val path = url.substringBefore('?').lowercase()
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            "/series/" in path || episode.containsMatchIn(name) -> MediaKind.SERIES
            "/movie/" in path -> MediaKind.MOVIE
            "/live/" in path -> MediaKind.LIVE
            seriesGroup.containsMatchIn(group) -> MediaKind.SERIES
            movieGroup.containsMatchIn(group) || extension in videoExtensions -> MediaKind.MOVIE
            else -> MediaKind.LIVE
        }
    }
}

object TitleCleaner {
    private val prefix = Regex("""^[A-Za-z]{2,3}\s*[|:\-]\s*""")
    private val year = Regex("""\b(19|20)\d{2}\b""")
    private val brackets = Regex("""[\[(][^\])]*[\])]""")
    private val episodeOnward = Regex("""\bS\d{1,2}\s*E\d{1,3}\b.*""", RegexOption.IGNORE_CASE)
    private val quality = Regex("""\b(4K|UHD|FHD|HD|SD|1080p|720p|HEVC|H\.?26[45]|x26[45]|ITA|ENG|SUB)\b""", RegexOption.IGNORE_CASE)

    fun clean(name: String): Pair<String, String?> {
        val found = year.find(name)?.value
        val title = name
            .replace(prefix, "")
            .replace(episodeOnward, "")
            .replace(brackets, " ")
            .replace(year, " ")
            .replace(quality, " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':', '.')
        return title to found
    }
}
