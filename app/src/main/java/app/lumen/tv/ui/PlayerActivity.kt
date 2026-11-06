package app.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Prefs

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var app: LumenApp
    private lateinit var playerView: PlayerView
    private lateinit var errorText: TextView
    private lateinit var liveInfo: View
    private lateinit var prefs: Prefs
    private lateinit var url: String
    private var item: PlaylistItem? = null
    private var live = false
    private var player: ExoPlayer? = null
    private var triedHls = false
    private var startPosition = C.TIME_UNSET
    private val hideInfo = Runnable { liveInfo.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        app = application as LumenApp
        playerView = findViewById(R.id.playerView)
        errorText = findViewById(R.id.error)
        liveInfo = findViewById(R.id.liveInfo)
        prefs = Prefs(this)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        item = app.playing?.takeIf { it.url == url || it.kind != MediaKind.LIVE }
        live = intent.getBooleanExtra(EXTRA_LIVE, false)
        if (!live && !intent.getBooleanExtra(EXTRA_FROM_START, false)) {
            val saved = item?.let { app.library.progressFor(it)?.position } ?: prefs.position(url)
            saved.takeIf { it > 0 }?.let { startPosition = it }
        }
    }

    override fun onStart() {
        super.onStart()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Lumen-TV")
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo.addListener(listener)
        exo.playWhenReady = true
        player = exo
        playerView.player = exo
        playerView.requestFocus()
        prepare(hls = triedHls)
        if (live) showLiveInfo()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        liveInfo.removeCallbacks(hideInfo)
        playerView.player = null
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (live && event.action == KeyEvent.ACTION_DOWN) {
            val controls = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> return zap(+1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> return zap(-1)
                KeyEvent.KEYCODE_DPAD_UP -> if (!controls) return zap(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls) return zap(+1)
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showLiveInfo(); return true }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** Canale precedente o successivo nello stesso gruppo della playlist. */
    private fun zap(step: Int): Boolean {
        val current = item ?: return false
        val channels = app.playlist.filter { it.kind == MediaKind.LIVE && it.group == current.group }
        if (channels.size < 2) return false
        val index = channels.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
        val next = channels[(index + step + channels.size) % channels.size]
        item = next
        app.playing = next
        url = next.url
        triedHls = false
        startPosition = C.TIME_UNSET
        errorText.visibility = View.GONE
        prepare(hls = false)
        showLiveInfo()
        return true
    }

    private fun showLiveInfo() {
        val channel = item ?: return
        val now = System.currentTimeMillis()
        val programmes = app.epg.upcoming(channel, now, 2)
        val current = programmes.firstOrNull { it.isOn(now) }
        val next = programmes.firstOrNull { it.start >= (current?.stop ?: now) }
        findViewById<TextView>(R.id.liveChannel).text = channel.name
        findViewById<TextView>(R.id.liveNow).text = current?.let { "${hhmm(it.start)} – ${hhmm(it.stop)}  ${it.title}" }
            ?: "Guida TV non disponibile per questo canale"
        findViewById<ProgressBar>(R.id.liveProgress).apply {
            visibility = if (current != null) View.VISIBLE else View.GONE
            progress = current?.progress(now) ?: 0
        }
        findViewById<TextView>(R.id.liveNext).apply {
            text = next?.let { "Dopo: ${hhmm(it.start)}  ${it.title}" }.orEmpty()
            visibility = if (next != null) View.VISIBLE else View.GONE
        }
        liveInfo.visibility = View.VISIBLE
        liveInfo.removeCallbacks(hideInfo)
        liveInfo.postDelayed(hideInfo, 6_000)
    }

    private fun prepare(hls: Boolean) {
        val exo = player ?: return
        val media = MediaItem.Builder().setUri(Uri.parse(url))
        if (hls) media.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (startPosition != C.TIME_UNSET) exo.setMediaItem(media.build(), startPosition) else exo.setMediaItem(media.build())
        exo.prepare()
    }

    private fun savePosition() {
        val exo = player ?: return
        if (live) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val position = exo.currentPosition
        val finished = duration > 0 && position > duration - 30_000
        val saved = if (finished) 0L else position
        prefs.savePosition(url, saved)
        item?.let { app.library.updateProgress(it, position, duration) }
        startPosition = if (saved > 0) saved else C.TIME_UNSET
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) errorText.visibility = View.GONE
            if (state == Player.STATE_ENDED && !live) {
                prefs.savePosition(url, 0L)
                item?.let { app.library.removeProgress(it) }
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            when {
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                }
                // Alcuni flussi HLS non hanno .m3u8 nell'indirizzo: si riprova una volta come HLS.
                !triedHls && error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    triedHls = true
                    prepare(hls = true)
                }
                else -> {
                    errorText.text = "Riproduzione non riuscita.\n${error.errorCodeName}" +
                        if (live) "\n▲ ▼ per cambiare canale" else ""
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_FROM_START = "from_start"

        fun intent(context: Context, app: LumenApp, item: PlaylistItem, url: String, fromStart: Boolean): Intent {
            app.playing = item.copy(url = url)
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_LIVE, item.kind == MediaKind.LIVE)
                .putExtra(EXTRA_FROM_START, fromStart)
        }
    }
}
