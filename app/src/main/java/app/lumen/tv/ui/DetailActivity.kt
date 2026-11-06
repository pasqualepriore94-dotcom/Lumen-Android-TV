package app.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.TitleCleaner
import app.lumen.tv.data.TmdbInfo
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DetailActivity : ComponentActivity() {
    private lateinit var app: LumenApp
    private lateinit var name: String
    private lateinit var group: String
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var overview: TextView
    private lateinit var notice: TextView
    private lateinit var backdrop: ImageView
    private lateinit var play: Button
    private lateinit var restart: Button
    private lateinit var myList: Button
    private var info: TmdbInfo? = null
    private var logo: String? = null
    private lateinit var kind: MediaKind
    private var source: String? = null
    private var titles: List<String> = emptyList()
    private var year: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        app = application as LumenApp
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        group = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        kind = MediaKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: MediaKind.MOVIE.name)
        source = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        val isCatalog = source == null

        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        overview = findViewById(R.id.overview)
        notice = findViewById(R.id.notice)
        backdrop = findViewById(R.id.backdrop)
        play = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        myList = findViewById(R.id.myList)
        logo = intent.getStringExtra(EXTRA_LOGO)
        myList.setOnClickListener {
            val added = app.library.toggleMyList(currentItem())
            Toast.makeText(this, if (added) "Aggiunto a La mia lista" else "Rimosso da La mia lista", Toast.LENGTH_SHORT).show()
            refreshButtons()
        }

        title.text = TitleCleaner.clean(name).first.ifBlank { name }
        meta.text = group
        intent.getStringExtra(EXTRA_LOGO)?.let { backdrop.load(it) }
        play.setOnClickListener { onPlay(fromStart = false) }
        restart.setOnClickListener { onPlay(fromStart = true) }
        play.requestFocus()

        if (isCatalog) {
            show(
                TmdbInfo(
                    title = name,
                    originalTitle = intent.getStringExtra(EXTRA_ORIGINAL),
                    overview = intent.getStringExtra(EXTRA_OVERVIEW).orEmpty(),
                    poster = intent.getStringExtra(EXTRA_LOGO),
                    backdrop = intent.getStringExtra(EXTRA_BACKDROP),
                    year = intent.getStringExtra(EXTRA_YEAR),
                    rating = intent.getDoubleExtra(EXTRA_RATING, 0.0).takeIf { it > 0 },
                ),
            )
        } else {
            lifecycleScope.launch { app.tmdb.lookup(name, kind)?.let(::show) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
        if (source == null && titles.isNotEmpty()) findSource()
    }

    /** Il titolo come si salva in La mia lista e Continua a guardare. */
    private fun currentItem() = PlaylistItem(name, source.orEmpty(), group, info?.poster ?: logo, kind, info)

    private fun show(info: TmdbInfo) {
        this.info = info
        title.text = info.title
        overview.text = info.overview
        meta.text = listOfNotNull(
            info.year,
            info.rating?.let { String.format(Locale.ITALY, "★ %.1f", it) },
            group,
        ).joinToString("  ·  ")
        (info.backdrop ?: info.poster)?.let { backdrop.load(it) }
        refreshButtons()
        if (source == null) {
            titles = listOfNotNull(info.title, info.originalTitle)
            year = info.year
            findSource()
        }
    }

    private fun findSource() {
        lifecycleScope.launch {
            source = withContext(Dispatchers.Default) {
                app.playlistIndex.find(titles, year, kind)
            }?.url
            refreshButtons()
        }
    }

    private fun refreshButtons() {
        val url = source
        val entry = app.library.progressFor(currentItem())
        val saved = url != null && (entry != null || Prefs(this).position(url) > 0)
        notice.visibility = if (url == null) View.VISIBLE else View.GONE
        if (url == null) {
            val hasPlaylist = Prefs(this).playlistUrl.isNotBlank()
            notice.text = if (hasPlaylist) "Questo titolo non è nella tua playlist." else "Per guardarlo serve una playlist."
            play.text = if (hasPlaylist) "Cambia playlist" else "Aggiungi playlist"
        } else {
            play.text = if (saved) "Riprendi" + (entry?.let { " (${it.percent}%)" } ?: "") else "Riproduci"
        }
        restart.visibility = if (saved) View.VISIBLE else View.GONE
        myList.text = if (app.library.inMyList(currentItem())) "✓ La mia lista" else "+ La mia lista"
    }

    private fun onPlay(fromStart: Boolean) {
        val url = source
        if (url == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            startActivity(PlayerActivity.intent(this, app, currentItem(), url, fromStart = fromStart))
        }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_OVERVIEW = "overview"
        private const val EXTRA_BACKDROP = "backdrop"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_RATING = "rating"

        fun intent(context: Context, item: PlaylistItem) = Intent(context, DetailActivity::class.java)
            .putExtra(EXTRA_NAME, item.name)
            .putExtra(EXTRA_URL, item.url)
            .putExtra(EXTRA_LOGO, item.logo)
            .putExtra(EXTRA_GROUP, item.group)
            .putExtra(EXTRA_KIND, item.kind.name)
            .putExtra(EXTRA_ORIGINAL, item.info?.originalTitle)
            .putExtra(EXTRA_OVERVIEW, item.info?.overview)
            .putExtra(EXTRA_BACKDROP, item.info?.backdrop)
            .putExtra(EXTRA_YEAR, item.info?.year)
            .putExtra(EXTRA_RATING, item.info?.rating ?: 0.0)
    }
}
