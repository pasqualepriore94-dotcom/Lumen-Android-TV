package app.lumen.tv.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString("playlist_url", "").orEmpty()
        set(value) = sp.edit().putString("playlist_url", value).apply()

    var accessToken: String
        get() = sp.getString("access_token", "").orEmpty()
        set(value) = sp.edit().putString("access_token", value).apply()

    var tmdbKey: String
        get() = sp.getString("tmdb_key", "").orEmpty()
        set(value) = sp.edit().putString("tmdb_key", value).apply()

    var epgUrl: String
        get() = sp.getString("epg_url", "").orEmpty()
        set(value) = sp.edit().putString("epg_url", value).apply()

    var playlistCachedFrom: String
        get() = sp.getString("playlist_cached_from", "").orEmpty()
        set(value) = sp.edit().putString("playlist_cached_from", value).apply()

    fun position(url: String): Long = sp.getLong("pos_${url.hashCode()}", 0L)

    fun savePosition(url: String, position: Long) {
        sp.edit().putLong("pos_${url.hashCode()}", position).apply()
    }
}
