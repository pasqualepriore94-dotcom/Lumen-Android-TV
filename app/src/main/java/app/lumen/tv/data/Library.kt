package app.lumen.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Elemento salvato in La mia lista o in Continua a guardare. */
data class LibraryEntry(
    val item: PlaylistItem,
    val position: Long = 0,
    val duration: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val percent: Int get() = if (duration > 0) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
}

/**
 * La mia lista e Continua a guardare, salvate su questa TV (come sull'iPhone, senza sincronizzazione).
 * Le operazioni sono veloci e su pochi elementi, quindi il file si riscrive a ogni modifica.
 */
class Library(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val myList = LinkedHashMap<String, LibraryEntry>()
    private val progress = LinkedHashMap<String, LibraryEntry>()

    /** Cresce a ogni modifica: la Home lo usa per sapere quando ridisegnare le righe. */
    @Volatile var version = 0
        private set

    init {
        runCatching {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                read(json.optJSONArray("myList"), myList)
                read(json.optJSONArray("progress"), progress)
            }
        }
    }

    @Synchronized fun myList(): List<PlaylistItem> = myList.values.reversed().map { it.item }

    @Synchronized fun continueWatching(): List<LibraryEntry> =
        progress.values.sortedByDescending { it.updatedAt }.take(MAX_PROGRESS)

    @Synchronized fun inMyList(item: PlaylistItem) = key(item) in myList

    @Synchronized fun toggleMyList(item: PlaylistItem): Boolean {
        val k = key(item)
        val added = myList.remove(k) == null
        if (added) myList[k] = LibraryEntry(item)
        save()
        return added
    }

    @Synchronized fun progressFor(item: PlaylistItem): LibraryEntry? = progress[key(item)]

    /** Salva il punto raggiunto; un titolo finito esce da Continua a guardare. */
    @Synchronized fun updateProgress(item: PlaylistItem, position: Long, duration: Long) {
        val k = key(item)
        val finished = duration > 0 && position > duration - FINISHED_MARGIN_MS
        if (finished || position < MIN_POSITION_MS) progress.remove(k)
        else progress[k] = LibraryEntry(item, position, duration)
        save()
    }

    @Synchronized fun removeProgress(item: PlaylistItem) {
        if (progress.remove(key(item)) != null) save()
    }

    private fun save() {
        version++
        val json = JSONObject()
            .put("myList", JSONArray(myList.values.map(::toJson)))
            .put("progress", JSONArray(progress.values.map(::toJson)))
        runCatching {
            val temp = File(file.parentFile, "library.tmp")
            temp.writeText(json.toString())
            temp.renameTo(file)
        }
    }

    private fun read(array: JSONArray?, into: MutableMap<String, LibraryEntry>) {
        for (i in 0 until (array?.length() ?: 0)) {
            val entry = runCatching { fromJson(array!!.getJSONObject(i)) }.getOrNull() ?: continue
            into[key(entry.item)] = entry
        }
    }

    companion object {
        private const val MAX_PROGRESS = 30
        private const val FINISHED_MARGIN_MS = 3 * 60_000L
        private const val MIN_POSITION_MS = 60_000L

        /** Stesso titolo dal catalogo o dalla playlist: si riconosce da tipo, titolo e anno. */
        fun key(item: PlaylistItem): String {
            val title = item.info?.title ?: TitleCleaner.clean(item.name).first.ifBlank { item.name }
            val year = item.info?.year ?: TitleCleaner.clean(item.name).second
            return "${item.kind}|${TitleCleaner.key(title)}|${year.orEmpty()}" +
                if (item.info == null && item.kind == MediaKind.SERIES) "|${item.url}" else ""
        }

        private fun toJson(entry: LibraryEntry): JSONObject {
            val item = entry.item
            val json = JSONObject()
                .put("name", item.name).put("url", item.url).put("group", item.group)
                .put("logo", item.logo ?: "").put("kind", item.kind.name)
                .put("position", entry.position).put("duration", entry.duration).put("updatedAt", entry.updatedAt)
            item.info?.let { info ->
                json.put("info", JSONObject()
                    .put("title", info.title).put("originalTitle", info.originalTitle ?: "")
                    .put("overview", info.overview).put("poster", info.poster ?: "").put("backdrop", info.backdrop ?: "")
                    .put("year", info.year ?: "").put("rating", info.rating ?: 0.0)
                    .put("tmdbId", info.tmdbId ?: 0).put("series", info.series))
            }
            return json
        }

        private fun fromJson(json: JSONObject): LibraryEntry {
            val info = json.optJSONObject("info")?.let {
                TmdbInfo(
                    title = it.getString("title"),
                    originalTitle = it.optString("originalTitle").ifBlank { null },
                    overview = it.optString("overview"),
                    poster = it.optString("poster").ifBlank { null },
                    backdrop = it.optString("backdrop").ifBlank { null },
                    year = it.optString("year").ifBlank { null },
                    rating = it.optDouble("rating").takeIf { r -> !r.isNaN() && r > 0 },
                    tmdbId = it.optInt("tmdbId").takeIf { id -> id > 0 },
                    series = it.optBoolean("series"),
                )
            }
            val item = PlaylistItem(
                name = json.getString("name"),
                url = json.optString("url"),
                group = json.optString("group"),
                logo = json.optString("logo").ifBlank { null },
                kind = MediaKind.valueOf(json.getString("kind")),
                info = info,
            )
            return LibraryEntry(item, json.optLong("position"), json.optLong("duration"), json.optLong("updatedAt"))
        }
    }
}
