package app.lumen.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class PlaylistRepository(context: Context, private val http: OkHttpClient, private val prefs: Prefs) {
    private val cache = File(context.filesDir, "playlist.m3u")

    suspend fun load(url: String): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val usable = cache.exists() && prefs.playlistCachedFrom == url
        val fresh = usable && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!fresh) {
            try {
                download(url)
            } catch (e: IOException) {
                if (!usable) throw e
            }
        }
        cache.bufferedReader().use { M3uParser.parse(it) }
    }

    private fun download(url: String) {
        val request = Request.Builder().url(url).header("User-Agent", "Lumen-TV").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Il server ha risposto ${response.code}")
            val body = response.body ?: throw IOException("Risposta vuota")
            val temp = File(cache.parentFile, "playlist.tmp")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!temp.renameTo(cache)) throw IOException("Impossibile salvare la playlist")
            prefs.playlistCachedFrom = url
        }
    }

    companion object {
        private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}
