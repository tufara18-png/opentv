package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NativeSourceSetupView(
    context: Context,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
) : LinearLayout(context) {
    private val graph = ServiceLocator.get(context)
    private var kind = SourceKind.XTREAM

    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val typeRow = LinearLayout(context)
    private val server = field("Adresse du service")
    private val username = field("Identifiant")
    private val password = field("Mot de passe", password = true)
    private val mac = field("Adresse MAC")
    private val epg = field("URL EPG (optionnel)")
    private val status = TextView(context)
    private val progress = ProgressBar(context)
    private val connect = button("Continuer") { submit() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(64), dp(52), dp(64), dp(42))
        setBackgroundColor(Color.rgb(8, 12, 20))

        title.apply {
            text = "Configurer votre service"
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        subtitle.apply {
            text = "Xtream, M3U ou Stalker. Vos identifiants restent sur cet appareil."
            textSize = 16f
            setTextColor(0xFF94A3B8.toInt())
        }
        addView(title, lp(match = true).apply { bottomMargin = dp(8) })
        addView(subtitle, lp(match = true).apply { bottomMargin = dp(24) })

        typeRow.orientation = HORIZONTAL
        typeRow.gravity = Gravity.START
        typeRow.addView(button("Xtream") { setKind(SourceKind.XTREAM) })
        typeRow.addView(button("M3U") { setKind(SourceKind.M3U) })
        typeRow.addView(button("Stalker") { setKind(SourceKind.STALKER) })
        addView(typeRow, lp(match = true).apply { bottomMargin = dp(18) })

        addView(server, lp(match = true).apply { bottomMargin = dp(10) })
        addView(username, lp(match = true).apply { bottomMargin = dp(10) })
        addView(password, lp(match = true).apply { bottomMargin = dp(10) })
        addView(mac, lp(match = true).apply { bottomMargin = dp(10) })
        addView(epg, lp(match = true).apply { bottomMargin = dp(18) })

        status.apply {
            textSize = 14f
            setTextColor(0xFFCBD5E1.toInt())
        }
        progress.visibility = GONE
        addView(status, lp(match = true).apply { bottomMargin = dp(10) })
        addView(progress, lp(match = false).apply {
            width = dp(42)
            height = dp(42)
            bottomMargin = dp(12)
        })
        addView(connect, lp(match = false).apply {
            width = dp(220)
            height = dp(54)
        })

        setKind(SourceKind.XTREAM)
        post { server.requestFocus() }
    }

    private fun setKind(value: SourceKind) {
        kind = value
        username.visibility = if (value == SourceKind.XTREAM) VISIBLE else GONE
        password.visibility = if (value == SourceKind.XTREAM) VISIBLE else GONE
        mac.visibility = if (value == SourceKind.STALKER) VISIBLE else GONE
        epg.visibility = VISIBLE
        server.hint = when (value) {
            SourceKind.XTREAM -> "http://serveur:port"
            SourceKind.M3U -> "URL complète de la playlist M3U"
            SourceKind.STALKER -> "http://portal/..."
        }
    }

    private fun submit() {
        val url = server.text.toString().trim()
        if (url.isBlank()) {
            showError("Adresse requise.")
            server.requestFocus()
            return
        }
        if (kind == SourceKind.XTREAM &&
            (username.text.isNullOrBlank() || password.text.isNullOrBlank())
        ) {
            showError("Identifiant et mot de passe requis.")
            return
        }
        if (kind == SourceKind.STALKER && mac.text.isNullOrBlank()) {
            showError("Adresse MAC requise.")
            mac.requestFocus()
            return
        }

        val draft = Source(
            name = when (kind) {
                SourceKind.XTREAM -> "Xtream"
                SourceKind.M3U -> "Playlist"
                SourceKind.STALKER -> "Stalker"
            },
            kind = kind,
            url = url,
            username = username.text.toString().trim().takeIf { it.isNotBlank() },
            password = password.text.toString().takeIf { it.isNotBlank() },
            macAddress = mac.text.toString().trim().takeIf { it.isNotBlank() },
            epgUrl = epg.text.toString().trim().takeIf { it.isNotBlank() },
        )

        setBusy(true, "Connexion…")
        scope.launch {
            val tested = graph.sourceRepository.test(draft)
            if (tested.isFailure) {
                setBusy(false, tested.exceptionOrNull()?.message ?: "Connexion impossible.")
                return@launch
            }

            setBusy(true, "Chargement des chaînes…")
            val result = withContext(Dispatchers.IO) {
                val id = graph.sourceRepository.save(draft)
                val saved = graph.sourceRepository.byId(id)
                    ?: return@withContext Result.failure(IllegalStateException("Source introuvable après sauvegarde."))
                when (val sync = graph.catalogRepository.syncLive(saved, System.currentTimeMillis())) {
                    is app.tufaratv.data.repo.CatalogRepository.SyncResult.Success ->
                        Result.success(sync.channelCount)
                    is app.tufaratv.data.repo.CatalogRepository.SyncResult.Failed ->
                        Result.failure(IllegalStateException(sync.reason))
                }
            }

            if (result.isSuccess) {
                graph.settings.libraryPrepared = true
                status.text = "${result.getOrDefault(0)} chaînes chargées."
                onReady()
            } else {
                setBusy(false, result.exceptionOrNull()?.message ?: "Chargement impossible.")
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        status.text = message
        status.setTextColor(0xFFCBD5E1.toInt())
        progress.visibility = if (busy) VISIBLE else GONE
        connect.isEnabled = !busy
        server.isEnabled = !busy
        username.isEnabled = !busy
        password.isEnabled = !busy
        mac.isEnabled = !busy
        epg.isEnabled = !busy
    }

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(0xFFF87171.toInt())
    }

    private fun field(label: String, password: Boolean = false): EditText =
        EditText(context).apply {
            hint = label
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(0xFF111827.toInt())
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            isFocusable = true
            setOnFocusChangeListener { _, focused ->
                background = rounded(if (focused) 0xFF1E3A8A.toInt() else 0xFF111827.toInt())
            }
        }

    private fun button(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(0xFF1F2937.toInt())
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                animate().scaleX(if (focused) 1.04f else 1f)
                    .scaleY(if (focused) 1.04f else 1f)
                    .setDuration(130)
                    .start()
                background = rounded(if (focused) 0xFF2563EB.toInt() else 0xFF1F2937.toInt())
            }
        }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun lp(match: Boolean) =
        LayoutParams(if (match) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, dp(54))

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
