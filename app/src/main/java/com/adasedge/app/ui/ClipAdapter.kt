package com.adasedge.app.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.adasedge.app.recording.ClipRepository
import com.adasedge.app.recording.ClipRepository.Clip
import java.util.concurrent.Executor

/**
 * RecyclerView adapter for the clip library. Renders clip rows (thumbnail + name + datetime /
 * duration / size), loads thumbnails lazily off [thumbnailExecutor], and supports a multi-select
 * mode (keyed by clip URI, so it survives list refreshes). Row tap / long-press are reported to
 * the activity, which owns the action logic (play, set replay, share, delete, selection).
 */
class ClipAdapter(
    private val thumbnailExecutor: Executor,
    private val loadThumb: (Clip) -> Bitmap?,
    private val onClick: (Clip) -> Unit,
    private val onLongClick: (Clip) -> Unit,
) : RecyclerView.Adapter<ClipAdapter.VH>() {

    private val items = ArrayList<Clip>()
    private val selected = LinkedHashSet<String>()   // clip URI strings
    var selectionMode = false
        private set

    fun submit(clips: List<Clip>) {
        items.clear(); items.addAll(clips)
        selected.retainAll(items.map { it.uri.toString() }.toSet())
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
    }

    fun enterSelection(clip: Clip) {
        selectionMode = true
        selected.add(clip.uri.toString())
        notifyDataSetChanged()
    }

    /** Toggle a clip in selection mode; returns the new selected count. */
    fun toggle(clip: Clip): Int {
        val key = clip.uri.toString()
        if (!selected.remove(key)) selected.add(key)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        return selected.size
    }

    fun clearSelection() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedCount(): Int = selected.size
    fun selectedClips(): List<Clip> = items.filter { selected.contains(it.uri.toString()) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(
            com.adasedge.app.R.layout.item_clip, parent, false
        )
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val clip = items[position]
        holder.name.text = clip.name
        holder.meta.text = if (clip.isReplay) {
            "${holder.itemView.context.getString(com.adasedge.app.R.string.clip_replay_label)}  ·  ${fmtSize(clip.sizeBytes)}"
        } else buildString {
            append(ClipRepository.formatDateTime(clip.captureMillis))
            if (clip.durationMs > 0) append("  ·  ").append(fmtDuration(clip.durationMs))
            append("  ·  ").append(fmtSize(clip.sizeBytes))
        }
        holder.check.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.check.isChecked = selected.contains(clip.uri.toString())

        // Lazy thumbnail: tag the view with this clip's URI; only apply the loaded bitmap if the
        // holder is still bound to the same clip (avoids cross-row bleed on fast scroll).
        val key = clip.uri.toString()
        holder.thumb.tag = key
        holder.thumb.setImageDrawable(null)
        thumbnailExecutor.execute {
            val bmp = loadThumb(clip) ?: return@execute
            holder.thumb.post { if (holder.thumb.tag == key) holder.thumb.setImageBitmap(bmp) }
        }

        holder.itemView.setOnClickListener { onClick(clip) }
        holder.itemView.setOnLongClickListener { onLongClick(clip); true }
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val thumb: android.widget.ImageView = v.findViewById(com.adasedge.app.R.id.thumb)
        val name: android.widget.TextView = v.findViewById(com.adasedge.app.R.id.name)
        val meta: android.widget.TextView = v.findViewById(com.adasedge.app.R.id.meta)
        val check: android.widget.CheckBox = v.findViewById(com.adasedge.app.R.id.check)
    }

    companion object {
        fun fmtDuration(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }

        fun fmtSize(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
