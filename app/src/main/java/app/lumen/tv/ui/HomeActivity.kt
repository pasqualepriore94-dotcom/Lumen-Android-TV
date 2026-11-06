package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.PlaylistRepository
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Row
import app.lumen.tv.data.TmdbClient
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var repository: PlaylistRepository
    private lateinit var tmdb: TmdbClient
    private lateinit var rows: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var tabs: Map<Button, MediaKind?>
    private var items: List<PlaylistItem> = emptyList()
    private var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val app = application as LumenApp
        prefs = Prefs(this)
        repository = PlaylistRepository(this, app.http, prefs)
        tmdb = TmdbClient(app.http, app.isLowRam)
        rows = findViewById<RecyclerView>(R.id.rows).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            itemAnimator = null
        }
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        tabs = linkedMapOf(
            findViewById<Button>(R.id.tabAll) to null,
            findViewById<Button>(R.id.tabMovies) to MediaKind.MOVIE,
            findViewById<Button>(R.id.tabSeries) to MediaKind.SERIES,
            findViewById<Button>(R.id.tabLive) to MediaKind.LIVE,
        )
        tabs.forEach { (button, kind) -> button.setOnClickListener { show(kind, button) } }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (loadedUrl != prefs.playlistUrl) load()
    }

    private fun load() {
        val url = prefs.playlistUrl
        loadedUrl = url
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        rows.adapter = null
        lifecycleScope.launch {
            try {
                items = repository.load(url)
                progress.visibility = View.GONE
                val first = tabs.keys.first()
                show(null, first)
                first.requestFocus()
            } catch (e: Exception) {
                loadedUrl = null
                progress.visibility = View.GONE
                showMessage("Non riesco a caricare la playlist.\n${e.message.orEmpty()}\nControlla l'indirizzo in Impostazioni.")
                findViewById<Button>(R.id.settings).requestFocus()
            }
        }
    }

    private fun show(kind: MediaKind?, selected: Button) {
        tabs.keys.forEach { it.isSelected = it == selected }
        val visible = if (kind == null) items else items.filter { it.kind == kind }
        val grouped = visible.groupBy { it.group }.map { (group, list) -> Row(group, list.take(MAX_CARDS_PER_ROW)) }
        rows.adapter = RowsAdapter(grouped) { list -> CardAdapter(list, lifecycleScope, tmdb, ::open) }
        if (grouped.isEmpty()) showMessage("Nessun contenuto in questa sezione.") else message.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun open(item: PlaylistItem) {
        if (item.kind == MediaKind.LIVE) {
            startActivity(PlayerActivity.intent(this, item.name, item.url, live = true, fromStart = true))
        } else {
            startActivity(DetailActivity.intent(this, item))
        }
    }

    companion object {
        private const val MAX_CARDS_PER_ROW = 60
    }
}
