package app.lumen.tv.data

import android.util.LruCache
import app.lumen.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
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

    private fun search(title: String, year: String?): TmdbInfo? {
        val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", BuildConfig.TMDB_API_KEY)
            .addQueryParameter("language", "it-IT")
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("query", title)
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val results = JSONObject(response.body?.string() ?: return null).getJSONArray("results")
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
            val found = best ?: return null
            return TmdbInfo(
                title = found.optString("title").ifBlank { found.optString("name") }.ifBlank { title },
                overview = found.optString("overview"),
                poster = image(found, "poster_path", posterSize),
                backdrop = image(found, "backdrop_path", "w1280"),
                year = date(found).take(4).ifBlank { null },
                rating = found.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            )
        }
    }

    private fun date(json: JSONObject) =
        json.optString("release_date").ifBlank { json.optString("first_air_date") }

    private fun image(json: JSONObject, field: String, size: String): String? {
        val path = json.optString(field)
        return if (path.isBlank() || path == "null") null else "https://image.tmdb.org/t/p/$size$path"
    }
}
