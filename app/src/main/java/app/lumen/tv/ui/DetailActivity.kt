package app.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.CastMember
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.TitleCleaner
import app.lumen.tv.data.TmdbDetails
import app.lumen.tv.data.TmdbInfo
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DetailActivity : ComponentActivity() {
    private lateinit var app: LumenApp
    private lateinit var name: String
    private lateinit var group: String
    private lateinit var kind: MediaKind
    private lateinit var title: TextView
    private lateinit var tagline: TextView
    private lateinit var chips: LinearLayout
    private lateinit var meta: TextView
    private lateinit var overview: TextView
    private lateinit var credits: TextView
    private lateinit var notice: TextView
    private lateinit var backdrop: ImageView
    private lateinit var play: Button
    private lateinit var restart: Button
    private lateinit var myList: Button
    private var info: TmdbInfo? = null
    private var details: TmdbDetails? = null
    private var logo: String? = null
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
        logo = intent.getStringExtra(EXTRA_LOGO)

        title = findViewById(R.id.title)
        tagline = findViewById(R.id.tagline)
        chips = findViewById(R.id.chips)
        meta = findViewById(R.id.meta)
        overview = findViewById(R.id.overview)
        credits = findViewById(R.id.credits)
        notice = findViewById(R.id.notice)
        backdrop = findViewById(R.id.backdrop)
        play = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        myList = findViewById(R.id.myList)

        title.text = TitleCleaner.clean(name).first.ifBlank { name }
        meta.text = group
        logo?.let { backdrop.load(it) }
        play.setOnClickListener { onPlay(fromStart = false) }
        restart.setOnClickListener { onPlay(fromStart = true) }
        myList.setOnClickListener {
            val added = app.library.toggleMyList(currentItem())
            Toast.makeText(this, if (added) "Aggiunto a La mia lista" else "Rimosso da La mia lista", Toast.LENGTH_SHORT).show()
            refreshButtons()
        }
        play.requestFocus()

        val passed = intent.getStringExtra(EXTRA_INFO_TITLE)?.let {
            TmdbInfo(
                title = it,
                originalTitle = intent.getStringExtra(EXTRA_ORIGINAL),
                overview = intent.getStringExtra(EXTRA_OVERVIEW).orEmpty(),
                poster = logo,
                backdrop = intent.getStringExtra(EXTRA_BACKDROP),
                year = intent.getStringExtra(EXTRA_YEAR),
                rating = intent.getDoubleExtra(EXTRA_RATING, 0.0).takeIf { r -> r > 0 },
                tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0).takeIf { id -> id > 0 },
                series = intent.getBooleanExtra(EXTRA_SERIES, kind == MediaKind.SERIES),
            )
        }
        if (passed != null) show(passed) else lifecycleScope.launch { app.tmdb.lookup(name, kind)?.let(::show) }
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
        (info.backdrop ?: info.poster)?.let { backdrop.load(it) { crossfade(250) } }
        renderChips()
        refreshButtons()
        if (source == null) {
            titles = listOfNotNull(info.title, info.originalTitle)
            year = info.year
            findSource()
        }
        lifecycleScope.launch {
            val loaded = app.tmdb.details(info) ?: return@launch
            details = loaded
            if (info.overview.isBlank()) loaded.overview?.let { overview.text = it }
            loaded.tagline?.let {
                tagline.text = it
                tagline.visibility = View.VISIBLE
            }
            credits.text = loaded.director?.let { (if (info.series) "Ideata da " else "Regia di ") + it }.orEmpty()
            credits.visibility = if (loaded.director != null) View.VISIBLE else View.GONE
            renderChips()
            showCast(loaded.cast)
        }
    }

    /** Anno, voto, durata o stagioni come etichette; i generi sotto, in testo. */
    private fun renderChips() {
        val info = info ?: return
        val d = details
        chips.removeAllViews()
        val labels = listOfNotNull(
            info.rating?.let { String.format(Locale.ITALY, "★ %.1f", it) },
            info.year,
            d?.runtimeMinutes?.let { m -> if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }?.takeIf { !info.series },
            d?.seasons?.let { if (it == 1) "1 stagione" else "$it stagioni" },
            if (info.series) "Serie" else "Film",
        )
        labels.forEachIndexed { index, label ->
            val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as TextView
            chip.text = label
            if (index == 0 && info.rating != null) chip.setTextColor(getColor(R.color.gold))
            chips.addView(chip)
        }
        meta.text = d?.genres?.take(4)?.joinToString("  ·  ")?.ifBlank { null } ?: group
    }

    private fun showCast(cast: List<CastMember>) {
        if (cast.isEmpty()) return
        findViewById<View>(R.id.castTitle).visibility = View.VISIBLE
        findViewById<RecyclerView>(R.id.cast).apply {
            visibility = View.VISIBLE
            layoutManager = LinearLayoutManager(this@DetailActivity, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            adapter = CastAdapter(cast)
        }
    }

    private fun findSource() {
        lifecycleScope.launch {
            source = withContext(Dispatchers.Default) { app.playlistIndex.find(titles, year, kind) }?.url
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
            play.text = if (saved) "Riprendi" + (entry?.let { "  ${it.percent}%" } ?: "") else "Riproduci"
        }
        restart.visibility = if (saved) View.VISIBLE else View.GONE
        val inList = app.library.inMyList(currentItem())
        myList.setCompoundDrawablesRelativeWithIntrinsicBounds(if (inList) R.drawable.ic_check else R.drawable.ic_add, 0, 0, 0)
    }

    private fun onPlay(fromStart: Boolean) {
        val url = source
        if (url == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            startActivity(PlayerActivity.intent(this, app, currentItem(), url, fromStart = fromStart))
        }
    }

    private class CastAdapter(private val cast: List<CastMember>) : RecyclerView.Adapter<CastAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.photo)
            val name: TextView = view.findViewById(R.id.name)
            val role: TextView = view.findViewById(R.id.role)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false)
            view.scaleOnFocus(1.06f)
            return Holder(view)
        }

        override fun getItemCount() = cast.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val member = cast[position]
            holder.name.text = member.name
            holder.role.text = member.character
            holder.photo.setImageDrawable(null)
            member.photo?.let { holder.photo.load(it) { transformations(CircleCropTransformation()) } }
        }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_INFO_TITLE = "info_title"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_OVERVIEW = "overview"
        private const val EXTRA_BACKDROP = "backdrop"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_RATING = "rating"
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_SERIES = "series"

        fun intent(context: Context, item: PlaylistItem): Intent {
            val intent = Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_URL, item.url)
                .putExtra(EXTRA_LOGO, item.logo)
                .putExtra(EXTRA_GROUP, item.group)
                .putExtra(EXTRA_KIND, item.kind.name)
            item.info?.let { info ->
                intent.putExtra(EXTRA_INFO_TITLE, info.title)
                    .putExtra(EXTRA_ORIGINAL, info.originalTitle)
                    .putExtra(EXTRA_OVERVIEW, info.overview)
                    .putExtra(EXTRA_BACKDROP, info.backdrop)
                    .putExtra(EXTRA_YEAR, info.year)
                    .putExtra(EXTRA_RATING, info.rating ?: 0.0)
                    .putExtra(EXTRA_TMDB_ID, info.tmdbId ?: 0)
                    .putExtra(EXTRA_SERIES, info.series)
            }
            return intent
        }
    }
}
