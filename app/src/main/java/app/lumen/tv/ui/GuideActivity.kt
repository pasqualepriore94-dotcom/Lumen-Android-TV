package app.lumen.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import app.lumen.tv.data.Programme
import coil.dispose
import coil.load
import kotlinx.coroutines.launch

/** Elenco dei canali a sinistra; a destra i programmi del canale selezionato. Con OK si guarda il canale. */
class GuideActivity : ComponentActivity() {
    private lateinit var app: LumenApp
    private lateinit var programmes: RecyclerView
    private lateinit var message: TextView
    private var channels: List<PlaylistItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        app = application as LumenApp
        message = findViewById(R.id.message)
        programmes = findViewById<RecyclerView>(R.id.programmes).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            itemAnimator = null
        }
        channels = app.playlist.filter { it.kind == MediaKind.LIVE }
        val list = findViewById<RecyclerView>(R.id.channels).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            itemAnimator = null
            adapter = ChannelAdapter()
        }
        if (channels.isEmpty()) {
            message.text = "Nessun canale: aggiungi una playlist con dirette in Impostazioni."
            return
        }
        list.post { list.getChildAt(0)?.requestFocus() }
        updateMessage()
        if (!app.epg.hasData) {
            message.text = "Carico la guida TV…"
            lifecycleScope.launch {
                val prefs = Prefs(this@GuideActivity)
                val url = prefs.epgUrl.ifBlank { PlaylistRepository(this@GuideActivity, app.http, prefs).epgUrl() }
                app.epg.refresh(url, channels)
                updateMessage()
                list.adapter?.notifyItemRangeChanged(0, channels.size)
                (list.focusedChild?.let { list.getChildAdapterPosition(it) } ?: 0).takeIf { it >= 0 }?.let { select(channels[it]) }
            }
        }
    }

    private fun updateMessage() {
        message.text = if (app.epg.hasData) "${channels.size} canali · OK per guardare"
        else "${channels.size} canali · guida TV non disponibile: aggiungi l'indirizzo XMLTV in Impostazioni"
    }

    private fun select(channel: PlaylistItem) {
        programmes.adapter = ProgrammeAdapter(app.epg.upcoming(channel, count = 30))
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.image)
            val name: TextView = view.findViewById(R.id.name)
            val now: TextView = view.findViewById(R.id.now)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel, parent, false))

        override fun getItemCount() = channels.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.name.text = channel.name
            holder.now.text = app.epg.now(channel)?.let { "${hhmm(it.start)}  ${it.title}" } ?: channel.group
            holder.image.dispose()
            holder.image.setImageDrawable(null)
            channel.logo?.let { holder.image.load(it) }
            holder.itemView.setOnFocusChangeListener { _, focused -> if (focused) select(channel) }
            holder.itemView.setOnClickListener {
                startActivity(PlayerActivity.intent(this@GuideActivity, app, channel, channel.url, fromStart = true))
            }
        }
    }

    private class ProgrammeAdapter(private val items: List<Programme>) : RecyclerView.Adapter<ProgrammeAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.time)
            val title: TextView = view.findViewById(R.id.title)
            val desc: TextView = view.findViewById(R.id.desc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_programme, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = items[position]
            val now = System.currentTimeMillis()
            holder.time.text = if (p.isOn(now)) "Ora" else hhmm(p.start)
            holder.title.text = p.title
            holder.desc.text = p.description
            holder.desc.visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
        }
    }
}
