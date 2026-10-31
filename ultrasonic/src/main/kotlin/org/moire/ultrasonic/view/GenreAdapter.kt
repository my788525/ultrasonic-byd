/*
 * GenreAdapter.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.view

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.SectionIndexer
import android.widget.TextView
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Genre

/**
 * The list of genres.
 *
 * Every row carries a button that starts a radio of that genre. On a head unit the driver
 * wants to hear a kind of music, not to look at a list of names, so the two steps "pick the
 * genre" and "start the music" happen in one tap.
 */
class GenreAdapter(
    context: Context,
    genres: List<Genre>,
    private val onRadioClick: (Genre) -> Unit
) : ArrayAdapter<Genre?>(context, R.layout.list_item_genre, genres), SectionIndexer {
    private val layoutInflater: LayoutInflater

    // Both arrays are indexed by section ID.
    private val sections: Array<Any>
    private val positions: Array<Int>

    init {
        layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val sectionSet: MutableCollection<String> = LinkedHashSet(INITIAL_CAPACITY)
        val positionList: MutableList<Int> = ArrayList(INITIAL_CAPACITY)
        for (i in genres.indices) {
            val (index) = genres[i]
            if (!sectionSet.contains(index)) {
                sectionSet.add(index)
                positionList.add(i)
            }
        }
        sections = sectionSet.toTypedArray()
        positions = positionList.toTypedArray()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var rowView = convertView
        if (rowView == null) {
            rowView = layoutInflater.inflate(R.layout.list_item_genre, parent, false)
        }
        val genre = getItem(position)!!
        // The row is a container now, so the text is found by its id and not by the type of
        // the row itself.
        val text = rowView?.findViewById<TextView>(android.R.id.text1)
        text?.text = genre.name
        val radio = rowView?.findViewById<ImageButton>(R.id.genre_radio)
        radio?.setOnClickListener { onRadioClick(genre) }
        return rowView!!
    }

    override fun getSections(): Array<Any> {
        return sections
    }

    override fun getPositionForSection(section: Int): Int {
        return positions[section]
    }

    override fun getSectionForPosition(pos: Int): Int {
        for (i in 0 until sections.size - 1) {
            if (pos < positions[i + 1]) {
                return i
            }
        }
        return sections.size - 1
    }

    companion object {
        const val INITIAL_CAPACITY: Int = 30
    }
}
