package app.lumen.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Guida TV in formato XMLTV, anche compressa (.xml.gz).
 * Il file si legge in streaming e si tengono solo i canali della playlist e le prossime ore,
 * così anche guide molto grandi non pesano sulla memoria della TV.
 */
class Epg(context: Context, private val http: OkHttpClient) {
    private val cache = File(context.cacheDir, "epg.xml")
    private val lock = Mutex()
    @Volatile private var programmes: Map<String, List<Programme>> = emptyMap()
    @Volatile private var loadedFrom: String? = null
    @Volatile private var loadedAt = 0L

    /** Cresce a ogni caricamento: le schermate lo usano per aggiornarsi. */
    @Volatile var version = 0
        private set

    fun invalidate() {
        loadedFrom = null
        cache.delete()
    }

    fun now(channel: PlaylistItem, at: Long = System.currentTimeMillis()): Programme? =
        programmes[channel.url]?.firstOrNull { it.isOn(at) }

    fun upcoming(channel: PlaylistItem, at: Long = System.currentTimeMillis(), count: Int = 12): List<Programme> =
        programmes[channel.url].orEmpty().filter { it.stop > at }.take(count)

    val hasData: Boolean get() = programmes.isNotEmpty()

    /** Scarica e legge la guida se manca o ha più di 6 ore. Gli errori lasciano la guida precedente. */
    suspend fun refresh(url: String?, channels: List<PlaylistItem>) = lock.withLock {
        if (url.isNullOrBlank() || channels.isEmpty()) return@withLock
        val fresh = loadedFrom == url && System.currentTimeMillis() - loadedAt < MAX_AGE_MS
        if (fresh) return@withLock
        withContext(Dispatchers.IO) {
            runCatching {
                val cacheFresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS &&
                    loadedFrom in listOf(null, url)
                if (!cacheFresh) download(url)
                programmes = cache.inputStream().use { parse(it, channels, System.currentTimeMillis()) }
                loadedFrom = url
                loadedAt = System.currentTimeMillis()
                version++
            }
        }
    }

    private fun download(url: String) {
        val request = Request.Builder().url(url).header("User-Agent", "Lumen-TV").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guida TV: il server ha risposto ${response.code}")
            val temp = File(cache.parentFile, "epg.tmp")
            temp.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
            if (!temp.renameTo(cache)) throw IOException("Impossibile salvare la guida TV")
        }
    }

    companion object {
        private const val MAX_AGE_MS = 6L * 60 * 60 * 1000
        private const val WINDOW_PAST_MS = 2L * 60 * 60 * 1000
        private const val WINDOW_FUTURE_MS = 18L * 60 * 60 * 1000

        private val numberWords = mapOf(
            "uno" to "1", "due" to "2", "tre" to "3", "quattro" to "4", "cinque" to "5",
            "sei" to "6", "sette" to "7", "otto" to "8", "nove" to "9", "one" to "1", "two" to "2", "three" to "3",
        )
        private val countryPrefix = Regex("""^\s*(\|[^|]{1,6}\||\[[^\]]{1,8}]|[a-z]{2,3}\s*[:|]\s+|[a-z]{2,3}\s+-\s+)""")
        private val noise = Regex("""\b(hd|fhd|uhd|4k|sd|hevc|h265|h264|backup|bk|alt|vip|ita|it)\b""")

        /**
         * Nome confrontabile di un canale: senza prefisso del paese ("IT:"), qualità ("HD"), suffissi
         * del tvg-id (".it") e con i numeri in cifre ("Rai Uno" = "Rai 1"). "+1" resta: è un canale diverso.
         */
        fun normalize(name: String): String {
            var t = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "").lowercase()
            t = t.replace(countryPrefix, "").replace(Regex("""\.(it|uk|us|de|fr|es|com|tv)$"""), "")
            t = t.replace("+1", " plus1 ").replace("+2", " plus2 ")
            t = t.split(Regex("""[^\p{L}\p{Nd}]+""")).joinToString(" ") { numberWords[it] ?: it }
            val cleaned = t.replace(noise, " ").replace(Regex("""[^\p{L}\p{Nd}]"""), "")
            return cleaned.ifEmpty { t.replace(Regex("""[^\p{L}\p{Nd}]"""), "") }
        }

        /** Legge l'XMLTV abbinando i canali prima per tvg-id e poi per nome. Chiave del risultato: URL del canale. */
        fun parse(input: InputStream, channels: List<PlaylistItem>, now: Long): Map<String, List<Programme>> {
            val byId = HashMap<String, MutableList<String>>()
            val byName = HashMap<String, MutableList<String>>()
            for (c in channels) {
                c.tvgId?.let {
                    byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.url)
                    byName.getOrPut(normalize(it)) { ArrayList() }.add(c.url)
                }
                for (n in listOfNotNull(c.name, c.tvgName).map(::normalize).filter { it.isNotEmpty() }.toSet()) {
                    byName.getOrPut(n) { ArrayList() }.add(c.url)
                }
            }
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val stream = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(stream, null)
            val channelUrls = HashMap<String, List<String>>()
            val result = HashMap<String, MutableList<Programme>>()
            var channelId: String? = null
            var progChannel: List<String>? = null
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var field: String? = null
            val from = now - WINDOW_PAST_MS
            val to = now + WINDOW_FUTURE_MS

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")
                            channelId?.let { id ->
                                (byId[id.lowercase()] ?: byName[normalize(id)])?.let { channelUrls[id] = it }
                            }
                        }
                        "display-name" -> if (channelId != null) field = "display-name"
                        "programme" -> {
                            progChannel = channelUrls[parser.getAttributeValue(null, "channel")]
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                            desc = ""
                            if (progChannel == null || stop < from || start > to) progChannel = null
                        }
                        "title" -> if (progChannel != null && title.isEmpty()) field = "title"
                        "desc" -> if (progChannel != null && desc.isEmpty()) field = "desc"
                    }
                    XmlPullParser.TEXT -> when (field) {
                        "display-name" -> {
                            val id = channelId
                            if (id != null && id !in channelUrls) byName[normalize(parser.text)]?.let { channelUrls[id] = it }
                        }
                        "title" -> title = parser.text.trim()
                        "desc" -> desc = parser.text.trim().take(400)
                    }
                    XmlPullParser.END_TAG -> {
                        field = null
                        when (parser.name) {
                            "channel" -> channelId = null
                            "programme" -> {
                                val urls = progChannel
                                if (urls != null && start > 0 && stop > start && title.isNotEmpty()) {
                                    val p = Programme(start, stop, title, desc)
                                    for (u in urls) result.getOrPut(u) { ArrayList() }.add(p)
                                }
                                progChannel = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
            return result.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
        }

        /** Formato XMLTV: "20261106183000 +0100" (fuso facoltativo, UTC se assente). */
        fun parseTime(value: String?): Long {
            if (value == null) return 0
            val digits = value.trim().takeWhile { it.isDigit() }
            if (digits.length < 12) return 0
            val zone = value.trim().drop(digits.length).trim()
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(
                digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt() - 1, digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(),
                if (digits.length >= 14) digits.substring(12, 14).toInt() else 0,
            )
            var millis = calendar.timeInMillis
            if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
                val offset = (zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt()) * 60_000L
                millis -= if (zone[0] == '+') offset else -offset
            }
            return millis
        }
    }
}
