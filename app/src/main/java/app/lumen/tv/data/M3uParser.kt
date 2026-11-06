package app.lumen.tv.data

import java.io.BufferedReader

object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val episode = Regex("""\bS\d{1,2}\s*E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv")
    private val movieGroup = Regex("""film|movie|cinema|vod""", RegexOption.IGNORE_CASE)
    private val seriesGroup = Regex("""\bserie\b|\bseries\b""", RegexOption.IGNORE_CASE)

    /** Legge riga per riga, così anche le playlist molto grandi non occupano memoria inutile. */
    fun parse(reader: BufferedReader, limit: Int = 120_000): List<PlaylistItem> {
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
        val duration = info?.removePrefix("#EXTINF:")?.removePrefix("#extinf:")
            ?.takeWhile { it == '-' || it.isDigit() }?.toIntOrNull()
        val tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotBlank() }
        val kind = classify(name, url, group, duration, tvgId != null)
        return PlaylistItem(name, url, group, attrs["tvg-logo"]?.takeIf { it.isNotBlank() }, kind,
            tvgId = tvgId?.takeIf { kind == MediaKind.LIVE },
            tvgName = attrs["tvg-name"]?.trim()?.takeIf { it.isNotBlank() && kind == MediaKind.LIVE })
    }

    /** Indirizzo della guida TV indicato nell'intestazione #EXTM3U (url-tvg o x-tvg-url). */
    fun epgUrl(header: String): String? {
        if (!header.trimStart('\uFEFF', ' ').startsWith("#EXTM3U", ignoreCase = true)) return null
        val attrs = attribute.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["url-tvg"] ?: attrs["x-tvg-url"])
            ?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }
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

    private val vodWords = listOf("film", "movie", "cinema", "serie", "series", "vod", "stagion", "season", "anime", "documentar", "saga")
    private val qualitySuffix = Regex("""\b(F?HD|UHD|4K|SD)\s*$""", RegexOption.IGNORE_CASE)
    private val liveWords = Regex("""\b(live|diretta|dirette|tv|canali|canale|channel|channels|sport|news|radio|24/7|rai|mediaset|sky|dazn|eventi|ppv)\b""")

    /**
     * Film, serie o diretta, con lo stesso sistema a punteggio dell'app iPhone e qualche segnale in più
     * (tvg-id, flussi senza estensione tipici delle dirette Xtream). Un gruppo "Cinema" da solo non basta:
     * prima contano indirizzo e formato del file.
     */
    fun classify(name: String, url: String, group: String, duration: Int? = null, hasTvgId: Boolean = false): MediaKind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val file = path.substringAfterLast('/')
        val extension = if ('.' in file) file.substringAfterLast('.') else ""
        val g = group.lowercase()
        val parsed = TitleCleaner.parse(name)
        val seriesHint = parsed.isEpisode || "/series/" in path || listOf("serie", "series", "stagion", "season").any { it in g }

        var score = 0
        if (extension in videoExtensions) score += 3
        if (listOf("/movie/", "/movies/", "/series/", "/vod/").any { it in path }) score += 3
        if ("/live/" in path || extension == "ts") score -= 3
        if (extension == "m3u8" || extension == "mpd") score -= 1
        // Xtream: le dirette sono /utente/password/123 senza estensione
        if (extension.isEmpty() && file.all { it.isDigit() } && file.isNotEmpty()) score -= 2
        if (duration != null) score += if (duration > 0) 2 else -1
        if (vodWords.any { it in g }) score += 2
        if (liveWords.containsMatchIn(g)) score -= 2
        if (hasTvgId) score -= 2
        if (parsed.isEpisode) score += 3
        if (parsed.year != null) score += 1
        if (qualitySuffix.containsMatchIn(name) && !parsed.isEpisode && parsed.year == null) score -= 1

        return when {
            score <= 0 -> MediaKind.LIVE
            seriesHint -> MediaKind.SERIES
            else -> MediaKind.MOVIE
        }
    }
}

data class ParsedTitle(val title: String, val year: String?, val season: Int?, val episode: Int?) {
    val isEpisode: Boolean get() = season != null && episode != null
}

object TitleCleaner {
    private val prefix = Regex("""^\s*(\|[^|]{1,6}\||\[[^\]]{1,8}]|[A-Za-z]{2,3}\s*[:|\-])\s*""")
    private val sxe = Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""")
    private val nxn = Regex("""\b(\d{1,2})x(\d{1,3})\b""")
    private val year = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
    private val quality = Regex("""\b(4k|uhd|fhd|hd|sd|hdr|1080p|720p|2160p|ita|eng|sub|subita|multi|dual|h\.?264|h\.?265|x264|x265|hevc|web[- ]?dl|webrip|bluray|bdrip)\b""", RegexOption.IGNORE_CASE)
    private val brackets = Regex("""[\[(][^\])]*[\])]""")
    private val separators = Regex("""[._]""")
    private val spaces = Regex("""\s{2,}""")
    private val nonAlnum = Regex("""[^\p{L}\p{Nd}]""")
    private val marks = Regex("""\p{M}""")
    private val articles = Regex("""^(the|a|an|il|lo|la|i|gli|le|l|un|uno|una|le|der|die|das|el|los|las)\s+""")

    /** Titolo, anno, stagione ed episodio da un nome di playlist (stesse regole dell'app iPhone). */
    fun parse(raw: String): ParsedTitle {
        var t = raw.replace(prefix, "")
        var season: Int? = null
        var episode: Int? = null
        (sxe.find(t) ?: nxn.find(t))?.let { m ->
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            t = t.substring(0, m.range.first)
        }
        var y: String? = null
        year.find(t)?.let { m ->
            y = m.groupValues[1]
            t = t.removeRange(m.range)
        }
        t = t.replace(quality, " ").replace(brackets, " ").replace(separators, " ")
            .replace(spaces, " ").trim(' ', '-', '–', '|', ':')
        return ParsedTitle(t, y, season, episode)
    }

    fun clean(name: String): Pair<String, String?> = parse(name).let { it.title to it.year }

    /** Forma confrontabile di un titolo: senza accenti, punteggiatura e maiuscole. */
    fun key(title: String): String =
        java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .lowercase()
            .replace(nonAlnum, "")

    /** Varianti confrontabili: con e senza articolo iniziale, "&" come "e"/"and", senza sottotitolo. */
    fun keys(title: String): Set<String> {
        val base = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(marks, "").lowercase().replace("'", " ").replace("’", " ")
        val forms = linkedSetOf(base, base.replace(articles, ""), base.replace("&", " e "), base.replace("&", " and "))
        return forms.map(::key).filter { it.length >= 2 }.toSet()
    }


    /** Cerca nella playlist il titolo del catalogo, tollerando titoli italiani o originali. */
    fun findInPlaylist(titles: List<String>, year: String?, kind: MediaKind, playlist: List<PlaylistItem>): PlaylistItem? =
        PlaylistIndex(playlist).find(titles, year, kind)
}

/**
 * Indice della playlist per abbinare i titoli del catalogo ai contenuti: si costruisce una volta
 * al caricamento, così la ricerca è immediata anche con decine di migliaia di voci.
 */
class PlaylistIndex(playlist: List<PlaylistItem>) {
    private class Entry(val item: PlaylistItem, val parsed: ParsedTitle)

    private val byKey = HashMap<String, MutableList<Entry>>()
    private val byMain = HashMap<String, MutableList<Entry>>()

    init {
        for (item in playlist) {
            if (item.kind == MediaKind.LIVE) continue
            val parsed = TitleCleaner.parse(item.name)
            val entry = Entry(item, parsed)
            for (k in TitleCleaner.keys(parsed.title)) byKey.getOrPut(k) { ArrayList() }.add(entry)
            val main = parsed.title.split(':', '–').first().split(" - ").first()
            if (main.length < parsed.title.length) {
                for (k in TitleCleaner.keys(main)) byMain.getOrPut(k) { ArrayList() }.add(entry)
            }
        }
    }

    fun find(titles: List<String>, year: String?, kind: MediaKind): PlaylistItem? {
        val wanted = titles.filter { it.isNotBlank() }
        val exact = wanted.flatMap { title -> TitleCleaner.keys(title).flatMap { byKey[it].orEmpty() } }
        pick(exact, year, kind, strictYear = false)?.let { return it }
        // Titolo del catalogo con sottotitolo ("Dune: Parte due") contro playlist che ha solo "Dune Parte due" o viceversa:
        // si accetta solo con lo stesso anno, per non confondere i capitoli di una saga.
        if (year == null) return null
        val partial = wanted.flatMap { title ->
            val main = title.split(':', '–').first().split(" - ").first()
            TitleCleaner.keys(main).flatMap { byKey[it].orEmpty() + byMain[it].orEmpty() } + TitleCleaner.keys(title).flatMap { byMain[it].orEmpty() }
        }
        return pick(partial, year, kind, strictYear = true)
    }

    private fun pick(candidates: List<Entry>, year: String?, kind: MediaKind, strictYear: Boolean): PlaylistItem? {
        val ofKind = candidates.filter { it.item.kind == kind }.distinctBy { it.item.url }
        if (ofKind.isEmpty()) return null
        val y = year?.toIntOrNull()
        fun yearOk(e: Entry): Boolean {
            val ey = e.parsed.year?.toIntOrNull() ?: return !strictYear
            return y == null || kotlin.math.abs(ey - y) <= if (strictYear) 0 else 1
        }
        val ok = ofKind.filter(::yearOk)
        val best = ok.sortedWith(compareBy<Entry>(
            { if (y != null && it.parsed.year?.toIntOrNull() == y) 0 else 1 },
            { it.parsed.season ?: 0 },
            { it.parsed.episode ?: 0 },
        ))
        return best.firstOrNull()?.item
    }
}
