/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.player

/**
 * The ordered channel list the full-screen player can zap through.
 *
 * Set by whatever launches playback (the guide passes the list you were browsing), read by the
 * player for channel up/down and the in-player channel list. A plain in-memory handoff rather
 * than a nav argument, because the list can be thousands of channels — far too big for a URL —
 * and it only needs to survive the hop from the guide to the player.
 */
object PlaybackQueue {
    /**
     * The guide already has now/next data when it creates this queue. Carry the small strings into
     * the player instead of querying Room for every visible overlay row. This keeps the TiviMate-
     * style channel panel informative while scrolling remains allocation- and I/O-free.
     */
    data class Item(
        val id: Long,
        val name: String,
        val logoUrl: String?,
        val number: Int? = null,
        val nowTitle: String? = null,
        val nextTitle: String? = null,
        val favourite: Boolean = false,
    )

    @Volatile
    var items: List<Item> = emptyList()

    /** Name of the group that produced [items], shown by the in-player channel drawer. */
    @Volatile
    var groupName: String = ""
}
