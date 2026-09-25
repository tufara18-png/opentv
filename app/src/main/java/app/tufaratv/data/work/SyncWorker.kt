/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.repo.CatalogRepository

/**
 * Explicit background refresh of live channels and the guide.
 *
 * The VOD catalogue is intentionally excluded: movies/series are a large persistent local library
 * and are refreshed only during first-run preparation or explicitly from Settings > Catalogue.
 *
 * Runs only after the user asks for a refresh. The channel catalogue otherwise stays in Room and
 * startup performs no provider request. WorkManager is retained so an explicit refresh survives
 * navigation away from Settings.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = ServiceLocator.get(applicationContext)
        val now = System.currentTimeMillis()
        val sources = graph.sourceRepository.enabled()
        if (sources.isEmpty()) return Result.success()

        var anyFailed = false

        for (source in sources) {
            when (val result = graph.catalogRepository.syncLive(source, now)) {
                is CatalogRepository.SyncResult.Success ->
                    Log.i(TAG, "Catalogue for ${source.name}: ${result.channelCount} channels")
                is CatalogRepository.SyncResult.Failed -> {
                    anyFailed = true
                    Log.w(TAG, "Catalogue for ${source.name} failed: ${result.reason}")
                }
            }

        }

        // Guides sync as one pass across every enabled feed — provider guides, built-in
        // free sources and user URLs merge into a single guide, then the matcher runs.
        val summary = graph.epgRepository.syncAll(now)
        if (summary.feedsFailed > 0) anyFailed = true
        Log.i(
            TAG,
            "Guide: ${summary.programmesWritten} programmes from ${summary.feedsSucceeded} " +
                "feed(s), ${summary.channelsMatched}/${summary.channelsTotal} channels matched",
        )

        // Book any newly-revealed series-link airings from this fresh guide.
        runCatching { graph.recordingEngine.rescanSeriesRules() }

        // A partial failure is still a retry — but the user keeps everything already on disk.
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "opentv-periodic-sync"
        private const val ONE_SHOT_WORK_NAME = "opentv-manual-sync"

        /**
         * Kick a single catalogue + guide refresh now, off the UI. Runs the same [doWork] as the
         * periodic job — so it honours the content-type toggles — but as one-time work, which means
         * it survives the user navigating away from the screen that triggered it (unlike a refresh
         * tied to a screen's ViewModel scope). REPLACE so repeated taps coalesce into one run.
         */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun disableAutomatic(context: Context) {
            // Also removes the periodic job created by older builds, otherwise upgrading would
            // leave it alive even though this version no longer schedules it.
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
