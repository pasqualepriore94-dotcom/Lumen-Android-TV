package app.lumen.tv.data

import android.util.LruCache
import app.lumen.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class TmdbClient(private val http: OkHttpClient, lowRam: Boolean) {
    private class Entry(val info: TmdbInfo?)

    private val cache = LruCache<String, Entry>(if (lowRam) 150 else 500)
    private val gate = Semaphore(4)
    private val posterSize = if (lowRam) "w185" else "w342"

    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    suspend fun lookup(name: String): TmdbInfo? {
        if (!isConfigured) return null
        val (title, year) = TitleCleaner.clean(name)
        if (title.isBlank()) return null
        val key = "$title|$year"
        cache.get(key)?.let { return it.info }
        val info = gate.withPermit {
            withContext(Dispatchers.IO) { runCatching { search(title, year) }.getOrNull() }
        }
        cache.put(key, Entry(info))
        return info
    }

    private fun request(path: String, params: Map<String, String>): JSONObject? {
        val key = BuildConfig.TMDB_API_KEY
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("language", "it-IT")
        if (!key.startsWith("eyJ")) url.addQueryParameter("api_key", key)
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val request = Request.Builder().url(url.build())
        if (key.startsWith("eyJ")) request.header("Authorization", "Bearer $key")
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return JSONObject(response.body?.string() ?: return null)
        }
    }

    private fun search(title: String, year: String?): TmdbInfo? {
        val results = request("search/multi", mapOf("include_adult" to "false", "query" to title))
            ?.getJSONArray("results") ?: return null
        var best: JSONObject? = null
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            if (candidate.optString("media_type") == "person") continue
            if (best == null) best = candidate
            if (year != null && date(candidate).startsWith(year)) {
                best = candidate
                break
            }
        }
        return best?.let(::parse)
    }

    private fun parse(found: JSONObject) = TmdbInfo(
        title = found.optString("title").ifBlank { found.optString("name") },
        originalTitle = found.optString("original_title").ifBlank { found.optString("original_name") }.ifBlank { null },
        overview = found.optString("overview"),
        poster = image(found, "poster_path", posterSize),
        backdrop = image(found, "backdrop_path", "w1280"),
        year = date(found).take(4).ifBlank { null },
        rating = found.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
    )

    private class Section(
        val title: String,
        val path: String,
        val kind: MediaKind?,
        val params: Map<String, String> = emptyMap(),
    )

    /** Righe del catalogo; una sezione che non si carica viene semplicemente saltata. */
    suspend fun catalog(): List<Row> {
        if (!isConfigured) return emptyList()
        return coroutineScope {
            sections.map { section -> async(Dispatchers.IO) { runCatching { load(section) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .filter { it.items.isNotEmpty() }
        }
    }

    private fun load(section: Section): Row {
        val results = request(section.path, section.params + ("region" to "IT"))?.getJSONArray("results")
        val items = ArrayList<PlaylistItem>()
        for (i in 0 until (results?.length() ?: 0)) {
            val json = results!!.getJSONObject(i)
            val kind = section.kind ?: when (json.optString("media_type")) {
                "movie" -> MediaKind.MOVIE
                "tv" -> MediaKind.SERIES
                else -> null
            } ?: continue
            val info = parse(json)
            if (info.poster == null || info.title.isBlank()) continue
            items += PlaylistItem(info.title, "", section.title, info.poster, kind, info)
        }
        return Row(section.title, items)
    }

    private val sections = listOf(
        Section("Di tendenza", "trending/all/week", null),
        Section("Film popolari", "movie/popular", MediaKind.MOVIE),
        Section("Serie popolari", "tv/popular", MediaKind.SERIES),
        Section("Film più votati", "movie/top_rated", MediaKind.MOVIE),
        Section("Serie più votate", "tv/top_rated", MediaKind.SERIES),
        Section("Azione", "discover/movie", MediaKind.MOVIE, mapOf("with_genres" to "28")),
        Section("Commedia", "discover/movie", MediaKind.MOVIE, mapOf("with_genres" to "35")),
        Section("Fantascienza", "discover/movie", MediaKind.MOVIE, mapOf("with_genres" to "878")),
        Section("Serie crime", "discover/tv", MediaKind.SERIES, mapOf("with_genres" to "80")),
    )

    private fun date(json: JSONObject) =
        json.optString("release_date").ifBlank { json.optString("first_air_date") }

    private fun image(json: JSONObject, field: String, size: String): String? {
        val path = json.optString(field)
        return if (path.isBlank() || path == "null") null else "https://image.tmdb.org/t/p/$size$path"
    }
}
