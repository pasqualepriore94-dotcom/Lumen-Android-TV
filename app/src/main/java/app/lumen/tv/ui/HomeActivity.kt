package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
import app.lumen.tv.data.PlaylistIndex
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.PlaylistRepository
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Row
import app.lumen.tv.data.TitleCleaner
import coil.load
import java.util.Locale
import app.lumen.tv.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroOverview: TextView
    private var heroJob: Job? = null
    private var heroItem: PlaylistItem? = null

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
        heroImage = findViewById(R.id.heroImage)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroOverview = findViewById(R.id.heroOverview)
        tabs = linkedMapOf(
            findViewById<Button>(R.id.tabAll) to null,
            findViewById<Button>(R.id.tabMovies) to MediaKind.MOVIE,
            findViewById<Button>(R.id.tabSeries) to MediaKind.SERIES,
            findViewById<Button>(R.id.tabLive) to MediaKind.LIVE,
        )
        tabs.forEach { (button, kind) -> button.setOnClickListener { show(kind, button) } }
        findViewById<View>(R.id.search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<View>(R.id.settings).setOnClickListener {
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
            // Prima il catalogo, subito visibile; la playlist si aggiunge appena è pronta.
            if (catalog.isEmpty()) catalog = withTimeoutOrNull(20_000) { app.tmdb.catalog() }.orEmpty()
            if (url.isBlank()) app.setPlaylist(emptyList(), PlaylistIndex(emptyList()))
            playlistRows = rowsOf(app.playlist)
            showFirst()
            if (url.isBlank()) {
                progress.visibility = View.GONE
                return@launch
            }
            if (catalog.isNotEmpty()) {
                progress.visibility = View.GONE
                Toast.makeText(this@HomeActivity, "Carico la playlist…", Toast.LENGTH_SHORT).show()
            }
            app.playlistLoading = true
            app.playlistStatus = "Caricamento in corso…"
            val started = System.currentTimeMillis()
            val items = try {
                withContext(Dispatchers.Default) {
                    val list = repository.load(url)
                    app.setPlaylist(list, PlaylistIndex(list))
                    val counts = list.groupingBy { it.kind }.eachCount()
                    app.playlistStatus = "${list.size} voci: ${counts[MediaKind.LIVE] ?: 0} dirette, " +
                        "${counts[MediaKind.MOVIE] ?: 0} film, ${counts[MediaKind.SERIES] ?: 0} episodi di serie" +
                        " · ${list.map { it.group }.distinct().size} gruppi · pronta in ${(System.currentTimeMillis() - started) / 1000} s"
                    list
                }
            } catch (e: Exception) {
                app.playlistStatus = "Errore: ${e.message.orEmpty()}"
                Toast.makeText(this@HomeActivity, "Non riesco a caricare la playlist", Toast.LENGTH_LONG).show()
                emptyList()
            } finally {
                app.playlistLoading = false
            }
            progress.visibility = View.GONE
            playlistRows = withContext(Dispatchers.Default) { rowsOf(items) }
            currentTab?.let { show(currentKind, it, keepFocus = true) } ?: showFirst()
            loadGuide(items)
        }
    }

    private fun rowsOf(items: List<PlaylistItem>) =
        items.groupBy { it.group }.map { (group, list) -> Row(group, list.take(MAX_CARDS_PER_ROW)) }

    private fun showFirst() {
        loaded = true
        val first = tabs.keys.first()
        show(null, first)
        first.requestFocus()
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
            CardAdapter(list, lifecycleScope, app.tmdb, app.epg, { app.library.progressFor(it)?.percent }, ::open, ::updateHero)
        }
        if (!hadFocus) visible.firstOrNull()?.items?.firstOrNull()?.let(::updateHero)
        if (hadFocus) rows.post { rows.requestFocus() }
        if (visible.isEmpty()) showMessage(emptyText(kind)) else message.visibility = View.GONE
    }

    /** Anteprima in alto: immagine, titolo, voto e trama del titolo selezionato (con un attimo di ritardo mentre si scorre). */
    private fun updateHero(item: PlaylistItem) {
        if (heroItem === item) return
        heroItem = item
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(220)
            if (item.kind == MediaKind.LIVE) {
                val now = app.epg.now(item)
                setHero(item.name, listOfNotNull("In diretta", item.group).joinToString("  ·  "),
                    now?.let { "${hhmm(it.start)} – ${hhmm(it.stop)}  ${it.title}\n${it.description}" }.orEmpty(), null)
                return@launch
            }
            val info = item.info ?: app.tmdb.lookup(item.name, item.kind)
            val meta = listOfNotNull(
                info?.rating?.let { String.format(Locale.ITALY, "★ %.1f", it) },
                info?.year,
                if (item.kind == MediaKind.SERIES) "Serie" else "Film",
                app.library.progressFor(item)?.let { "Visto al ${it.percent}%" },
            ).joinToString("  ·  ")
            setHero(info?.title ?: TitleCleaner.clean(item.name).first, meta, info?.overview.orEmpty(), info?.backdrop)
        }
    }

    private fun setHero(title: String, meta: String, overview: String, image: String?) {
        heroTitle.text = title
        heroMeta.text = meta
        heroOverview.text = overview
        if (image == null) {
            heroImage.animate().alpha(0f).setDuration(200).start()
        } else {
            heroImage.load(image) {
                listener(onSuccess = { _, _ -> heroImage.animate().alpha(1f).setDuration(300).start() })
            }
        }
    }

    private fun emptyText(kind: MediaKind?): String = when {
        kind == MediaKind.LIVE && app.playlistLoading -> "Sto caricando la playlist: le dirette compaiono tra qualche secondo."
        kind == MediaKind.LIVE && prefs.playlistUrl.isNotBlank() -> "Nella playlist non ho riconosciuto dirette.\n${app.playlistStatus}"
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
