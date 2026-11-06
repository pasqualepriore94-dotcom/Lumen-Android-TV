package app.lumen.tv

import android.app.ActivityManager
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import app.lumen.tv.data.Epg
import app.lumen.tv.data.Library
import app.lumen.tv.data.PlaylistIndex
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.TmdbClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LumenApp : Application(), ImageLoaderFactory {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val isLowRam: Boolean by lazy {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    val tmdb: TmdbClient by lazy { TmdbClient(http, isLowRam, Prefs(this)) }

    val library: Library by lazy { Library(this) }
    val epg: Epg by lazy { Epg(this, http) }

    /** Titolo in riproduzione: serve al lettore per Continua a guardare e per cambiare canale. */
    @Volatile var playing: PlaylistItem? = null

    /** Titoli della playlist caricata, usati per abbinarli al catalogo. */
    @Volatile var playlist: List<PlaylistItem> = emptyList()
        private set

    /** Indice per abbinare i titoli del catalogo alla playlist. */
    @Volatile var playlistIndex: PlaylistIndex = PlaylistIndex(emptyList())
        private set

    /** Da chiamare fuori dal thread principale: l'indice di una playlist grande richiede qualche secondo. */
    fun setPlaylist(items: List<PlaylistItem>, index: PlaylistIndex) {
        playlistIndex = index
        playlist = items
    }

    /** Le immagini restano leggere: poca memoria, cache su disco limitata, colori a 16 bit dove possibile. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(http)
        .allowRgb565(true)
        .crossfade(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (isLowRam) 0.10 else 0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(if (isLowRam) 30L * 1024 * 1024 else 80L * 1024 * 1024)
                .build()
        }
        .build()
}
