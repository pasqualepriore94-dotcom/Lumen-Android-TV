package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.PlaylistRepository
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Row
import app.lumen.tv.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {
    private val updater by lazy { Updater(this, (application as LumenApp).http) }

    private lateinit var app: LumenApp
    private lateinit var prefs: Prefs
    private lateinit var repository: PlaylistRepository
    private lateinit var rows: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var tabs: Map<Button, MediaKind?>
    private var catalog: List<Row> = emptyList()
    private var playlistRows: List<Row> = emptyList()
    private var loadedUrl: String? = null
    private var loaded = false
    private var currentKind: MediaKind? = null
    private var currentTab: Button? = null
    private var shownLibraryVersion = -1
    private var shownEpgVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        app = application as LumenApp
        prefs = Prefs(this)
        repository = PlaylistRepository(this, app.http, prefs)
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
        findViewById<Button>(R.id.guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val auth = AuthRepository(app.http, prefs)
        lifecycleScope.launch {
            if (!auth.isSessionValid()) {
                auth.signOut()
                startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                finish()
            }
        }
        // Aggiorna ogni minuto il programma in onda sulle schede dei canali.
        lifecycleScope.launch {
            while (true) {
                delay(60_000)
                refreshCards()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate(updater)
        when {
            loadedUrl != prefs.playlistUrl -> load()
            loaded && shownLibraryVersion != app.library.version -> currentTab?.let { show(currentKind, it, keepFocus = true) }
        }
    }

    private fun load() {
        val url = prefs.playlistUrl
        loadedUrl = url
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        lifecycleScope.launch {
            if (catalog.isEmpty()) catalog = app.tmdb.catalog()
            val items = if (url.isBlank()) emptyList() else try {
                repository.load(url)
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Non riesco a caricare la playlist", Toast.LENGTH_LONG).show()
                emptyList()
            }
            app.playlist = items
            playlistRows = items.groupBy { it.group }.map { (group, list) -> Row(group, list.take(MAX_CARDS_PER_ROW)) }
            progress.visibility = View.GONE
            loaded = true
            val first = tabs.keys.first()
            show(null, first)
            first.requestFocus()
            loadGuide(items)
        }
    }

    private fun loadGuide(items: List<PlaylistItem>) {
        val channels = items.filter { it.kind == MediaKind.LIVE }
        if (channels.isEmpty()) return
        lifecycleScope.launch {
            app.epg.refresh(prefs.epgUrl.ifBlank { repository.epgUrl() }, channels)
            refreshCards()
        }
    }

    private fun refreshCards() {
        if (shownEpgVersion == app.epg.version && !app.epg.hasData) return
        shownEpgVersion = app.epg.version
        rows.adapter?.notifyItemRangeChanged(0, rows.adapter?.itemCount ?: 0)
    }

    private fun libraryRows(): List<Row> {
        val library = app.library
        val progressRow = library.continueWatching().map { it.item }
        return listOfNotNull(
            progressRow.takeIf { it.isNotEmpty() }?.let { Row("Continua a guardare", it) },
            library.myList().takeIf { it.isNotEmpty() }?.let { Row("La mia lista", it) },
        )
    }

    private fun show(kind: MediaKind?, selected: Button, keepFocus: Boolean = false) {
        currentKind = kind
        currentTab = selected
        shownLibraryVersion = app.library.version
        tabs.keys.forEach { it.isSelected = it == selected }
        val visible = (libraryRows() + catalog + playlistRows).mapNotNull { row ->
            val items = if (kind == null) row.items else row.items.filter { it.kind == kind }
            if (items.isEmpty()) null else Row(row.title, items)
        }
        val hadFocus = keepFocus && rows.hasFocus()
        rows.adapter = RowsAdapter(visible) { list ->
            CardAdapter(list, lifecycleScope, app.tmdb, app.epg, { app.library.progressFor(it)?.percent }, ::open)
        }
        if (hadFocus) rows.post { rows.requestFocus() }
        if (visible.isEmpty()) showMessage(emptyText(kind)) else message.visibility = View.GONE
    }

    private fun emptyText(kind: MediaKind?): String = when {
        kind == MediaKind.LIVE -> "Le dirette arrivano dalla playlist: puoi aggiungerla in Impostazioni, anche dal telefono."
        !app.tmdb.isConfigured -> "Il catalogo non è disponibile: non trovo la chiave TMDB nella configurazione di Lumen. Controlla la connessione e riapri l'app."
        else -> "Non riesco a caricare il catalogo. Controlla la connessione e riapri l'app."
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun open(item: PlaylistItem) {
        if (item.kind == MediaKind.LIVE) {
            startActivity(PlayerActivity.intent(this, app, item, item.url, fromStart = true))
        } else {
            startActivity(DetailActivity.intent(this, item))
        }
    }

    companion object {
        private const val MAX_CARDS_PER_ROW = 60
    }
}
