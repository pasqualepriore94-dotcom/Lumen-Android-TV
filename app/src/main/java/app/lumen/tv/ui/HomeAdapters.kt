package app.lumen.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.R
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
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40) }

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
        holder.list.adapter = cards(row.items)
    }
}

class CardAdapter(
    private val items: List<PlaylistItem>,
    private val scope: CoroutineScope,
    private val tmdb: TmdbClient,
    private val onClick: (PlaylistItem) -> Unit,
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val name: TextView = view.findViewById(R.id.name)
        var lookup: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        view.scaleOnFocus()
        return Holder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
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
    }

    override fun onViewRecycled(holder: Holder) {
        holder.lookup?.cancel()
        holder.image.dispose()
    }
}
