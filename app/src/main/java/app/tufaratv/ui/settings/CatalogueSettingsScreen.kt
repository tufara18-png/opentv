/*
 * This file is part of TufaraTV, a fork of OpenTV.
 */
package app.tufaratv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tufaratv.core.ServiceLocator
import app.tufaratv.ui.SourcesViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Explicit maintenance surface for the provider catalogue.
 *
 * Normal launches never refresh VOD: they read the Room library immediately. This screen is the
 * one obvious place where the user can request a complete provider refresh.
 */
@Composable
fun CatalogueSettingsScreen(
    onBack: () -> Unit,
    viewModel: SourcesViewModel,
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()
    val settings = ServiceLocator.get(context).settings
    val lastSync = settings.vodSyncedAtMillis

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Catalogue", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Films, séries, chaînes et guide enregistrés localement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Terminé") }
        }

        Spacer(Modifier.height(24.dp))

        Card(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Cache local", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "L'application utilise le catalogue enregistré sur l'appareil à chaque ouverture. " +
                        "Aucun téléchargement complet ne se lance automatiquement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    if (lastSync > 0L) {
                        "Dernière mise à jour : " +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))
                    } else {
                        "Aucune mise à jour complète enregistrée."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (ui.catalogueRefreshing) {
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { ui.catalogueProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        ui.syncMessage ?: "Mise à jour du catalogue…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ui.syncMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.refreshCatalogue() },
                    enabled = !ui.catalogueRefreshing && !ui.syncing,
                ) {
                    Text(if (ui.catalogueRefreshing) "Mise à jour…" else "Mettre à jour le catalogue")
                }
            }
        }
    }
}
