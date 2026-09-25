/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the product UI language by wrapping a base [Context] with an overridden locale.
 *
 * Done at attachBaseContext time (rather than the newer per-app-language API) so it works the same
 * on every supported version — minSdk 23 through the latest — and on Fire OS, without pulling in
 * AppCompat. TufaraTV is intentionally French-first: the UI must stay French even when the TV or
 * emulator uses English, otherwise untranslated fallback resources leak into the living-room UI.
 */
object LocaleUtils {

    private const val PRODUCT_LANGUAGE = "fr"

    fun wrap(base: Context): Context = wrap(base, PRODUCT_LANGUAGE)

    fun wrap(base: Context, tag: String): Context {
        val locale = Locale.forLanguageTag(tag.ifBlank { PRODUCT_LANGUAGE })
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
