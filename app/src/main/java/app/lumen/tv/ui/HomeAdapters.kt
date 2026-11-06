package app.lumen.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.R
import app.lumen.tv.data.Epg
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Row
import app.lumen.tv.data.TmdbClient
import coil.dispose
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RowsAdapter(
    private val rows: List<Row>,
    private val cards: (List<PlaylistItem>) -> CardAdapter,
) : RecyclerView.Adapter<RowsAdapter.Holder>() {
    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardAdapter.POSTER, 40)
        setMaxRecycledViews(CardAdapter.CHANNEL, 30)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val list: RecyclerView = view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            setRecycledViewPool(pool)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        val current = holder.list.adapter as? CardAdapter
        if (current != null && current.items === row.items) current.refresh() else holder.list.adapter = cards(row.items)
    }
}

class CardAdapter(
    val items: List<PlaylistItem>,
    private val scope: CoroutineScope,
    private val tmdb: TmdbClient,
    private val epg: Epg,
    private val progressOf: (PlaylistItem) -> Int?,
    private val onClick: (PlaylistItem) -> Unit,
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val name: TextView = view.findViewById(R.id.name)
        val now: TextView? = view.findViewById(R.id.now)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        var lookup: Job? = null
    }

    /** Aggiorna testi e barre (guida TV, avanzamento) senza ricaricare le immagini. */
    fun refresh() = notifyItemRangeChanged(0, items.size, REFRESH)

    override fun getItemViewType(position: Int) = if (items[position].kind == MediaKind.LIVE) CHANNEL else POSTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == CHANNEL) R.layout.item_channel else R.layout.item_card
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        view.scaleOnFocus(if (viewType == CHANNEL) 1.06f else 1.08f)
        return Holder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(REFRESH)) bindDetails(holder, items[position]) else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.info?.title ?: item.name
        holder.itemView.setOnClickListener { onClick(item) }
        holder.lookup?.cancel()
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        if (item.logo != null) {
            holder.image.load(item.logo)
        } else if (item.kind != MediaKind.LIVE) {
            holder.lookup = scope.launch {
                tmdb.lookup(item.name)?.poster?.let { holder.image.load(it) }
            }
        }
        bindDetails(holder, item)
    }

    private fun bindDetails(holder: Holder, item: PlaylistItem) {
        if (item.kind == MediaKind.LIVE) {
            val now = System.currentTimeMillis()
            val programme = epg.now(item, now)
            holder.now?.text = programme?.title ?: item.group
            holder.progress.visibility = if (programme != null) View.VISIBLE else View.GONE
            programme?.let { holder.progress.progress = it.progress(now) }
        } else {
            val percent = progressOf(item)
            holder.progress.visibility = if (percent != null && percent > 0) View.VISIBLE else View.GONE
            holder.progress.progress = percent ?: 0
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.lookup?.cancel()
        holder.image.dispose()
    }

    companion object {
        const val POSTER = 0
        const val CHANNEL = 1
        private const val REFRESH = "refresh"
    }
}
