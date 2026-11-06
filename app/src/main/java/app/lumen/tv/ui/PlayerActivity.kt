package app.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import app.lumen.tv.R
import app.lumen.tv.data.Prefs

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var errorText: TextView
    private lateinit var prefs: Prefs
    private lateinit var url: String
    private var live = false
    private var player: ExoPlayer? = null
    private var triedHls = false
    private var startPosition = C.TIME_UNSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        errorText = findViewById(R.id.error)
        prefs = Prefs(this)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        live = intent.getBooleanExtra(EXTRA_LIVE, false)
        if (!live && !intent.getBooleanExtra(EXTRA_FROM_START, false)) {
            prefs.position(url).takeIf { it > 0 }?.let { startPosition = it }
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
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        playerView.player = null
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun prepare(hls: Boolean) {
        val exo = player ?: return
        val item = MediaItem.Builder().setUri(Uri.parse(url))
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (startPosition != C.TIME_UNSET) exo.setMediaItem(item.build(), startPosition) else exo.setMediaItem(item.build())
        exo.prepare()
    }

    private fun savePosition() {
        val exo = player ?: return
        if (live) return
        val duration = exo.duration
        val position = exo.currentPosition
        val finished = duration != C.TIME_UNSET && position > duration - 30_000
        val saved = if (finished) 0L else position
        prefs.savePosition(url, saved)
        startPosition = if (saved > 0) saved else C.TIME_UNSET
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) errorText.visibility = View.GONE
            if (state == Player.STATE_ENDED) {
                prefs.savePosition(url, 0L)
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
                    errorText.text = "Riproduzione non riuscita.\n${error.errorCodeName}"
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_FROM_START = "from_start"

        @Suppress("UNUSED_PARAMETER")
        fun intent(context: Context, name: String, url: String, live: Boolean, fromStart: Boolean) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_FROM_START, fromStart)
    }
}
