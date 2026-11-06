package app.lumen.tv.data

enum class MediaKind { MOVIE, SERIES, LIVE }

data class PlaylistItem(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val kind: MediaKind,
)

data class Row(val title: String, val items: List<PlaylistItem>)

data class TmdbInfo(
    val title: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val rating: Double?,
)
