/*
 * QueueAdapter.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme

/**
 * The queue of the player, shown in the panel of the car UI.
 *
 * Every row carries a button to take the song out of the queue. A swipe to delete is the
 * least reliable gesture on a head unit, because the driver has to hit a moving target
 * without looking at it; a button is a fixed target.
 *
 * The list is in the order the songs are played, not in the order they were added. With the
 * shuffle turned on the two are different, and it is the play order that the driver has to
 * see.
 */
class QueueAdapter(
    private val onPlayClick: (Int) -> Unit,
    private val onRemoveClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var items: List<Track> = emptyList()

    /** The position of the song that is playing, drawn in a different colour. */
    private var currentIndex = -1

    fun submit(newItems: List<Track>, newCurrentIndex: Int) {
        val oldItems = items
        val oldIndex = currentIndex
        val changed = newItems != oldItems
        val currentChanged = newCurrentIndex != oldIndex
        if (!changed && !currentChanged) return

        items = newItems
        currentIndex = newCurrentIndex

        if (changed) {
            // The queue is a list of songs that mostly stays the same and only moves by one
            // row at a time, so the difference is worth computing: a full rebind throws away
            // every view holder and makes the list jump under the finger of the driver.
            DiffUtil.calculateDiff(
                QueueDiffCallback(oldItems, newItems, oldIndex, newCurrentIndex)
            ).dispatchUpdatesTo(this)
        }

        // The marker is painted by onBindViewHolder, so the two rows that carry it, the one
        // that lost it and the one that gained it, have to be drawn again either way.
        if (currentChanged) {
            if (oldIndex >= 0 && oldIndex < oldItems.size) notifyItemChanged(oldIndex)
            if (newCurrentIndex >= 0 && newCurrentIndex < items.size) {
                notifyItemChanged(newCurrentIndex)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val track = items[position]
        val ctx = holder.itemView.context
        val isCurrent = position == currentIndex
        val accent = CarTheme.accent(ctx, CarPalette.current())

        holder.title.text = track.title
        holder.artist.text = track.artist

        if (isCurrent) {
            holder.title.setTextColor(accent)
            holder.title.alpha = ALPHA_CURRENT
            holder.artist.setTextColor(CarTheme.onSurface(ctx))
            holder.artist.alpha = ALPHA_CURRENT
            holder.itemView.background = CarTheme.roundedFill(
                ctx,
                CarTheme.withAlpha(accent, CURRENT_BG_ALPHA),
                CarTheme.withAlpha(accent, CURRENT_BORDER_ALPHA),
                12f
            )
        } else {
            holder.title.setTextColor(CarTheme.onSurface(ctx))
            holder.title.alpha = ALPHA_OTHER
            holder.artist.setTextColor(CarTheme.onSurfaceSecondary(ctx))
            holder.artist.alpha = ALPHA_OTHER
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onPlayClick(holder.bindingAdapterPosition)
            }
        }
        holder.remove.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                onRemoveClick(adapterPosition)
            }
        }
    }

    class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.queue_title)
        val artist: TextView = view.findViewById(R.id.queue_artist)
        val remove: ImageButton = view.findViewById(R.id.queue_remove)
    }

    /**
     * Compares the two queues by the identity of the songs, so that a song that only moved
     * is moved in the list and not thrown away and rebuilt.
     *
     * The position of the playing song takes part in the comparison of the contents, because
     * it is drawn as part of the row: without it, the row that only gained or lost the marker
     * would count as unchanged and keep the wrong colour.
     */
    private class QueueDiffCallback(
        private val oldItems: List<Track>,
        private val newItems: List<Track>,
        private val oldIndex: Int,
        private val newIndex: Int
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size

        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition].id == newItems[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val markedBefore = oldItemPosition == oldIndex
            val markedAfter = newItemPosition == newIndex
            return markedBefore == markedAfter &&
                oldItems[oldItemPosition] == newItems[newItemPosition]
        }
    }

    private companion object {
        const val ALPHA_CURRENT = 1.0f
        const val ALPHA_OTHER = 0.78f
        const val CURRENT_BG_ALPHA = 38
        const val CURRENT_BORDER_ALPHA = 76
    }
}
