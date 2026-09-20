/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.db

/**
 * Turns free-text search input into an FTS4 `MATCH` query.
 *
 * Room's `@Fts4` tables take SQLite's own query syntax, not a plain substring — a bare user
 * string containing `"`, `-` or other FTS operators can throw at query time, and a bare word
 * only matches whole tokens, not "typing as you go". [prefixMatch] tokenises the input and turns
 * every token into a prefix match (`renderfh` → `renderfh*`), space-joined — FTS4's default is to
 * AND separate terms together, so a multi-word query narrows the way a user expects.
 */
object FtsQuery {
    private val TOKEN = Regex("""[\p{L}\p{Nd}]+""")

    /** Empty/blank input yields an empty string — callers should skip the search entirely rather
     *  than run a `MATCH ''`, which matches nothing. */
    fun prefixMatch(raw: String): String =
        TOKEN.findAll(raw).joinToString(" ") { "${it.value}*" }
}
