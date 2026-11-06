package app.lumen.tv.data

enum class MediaKind { MOVIE, SERIES, LIVE }

data class PlaylistItem(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val kind: MediaKind,
    val info: TmdbInfo? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
)

data class Row(val title: String, val items: List<PlaylistItem>)

data class TmdbInfo(
    val title: String,
    val originalTitle: String?,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val rating: Double?,
)

/** Un programma della guida TV. Orari in millisecondi. */
data class Programme(val start: Long, val stop: Long, val title: String, val description: String) {
    fun isOn(now: Long) = now in start until stop
    fun progress(now: Long): Int = if (stop <= start) 0 else ((now - start) * 100 / (stop - start)).toInt().coerceIn(0, 100)
}
