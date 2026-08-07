package com.adiag.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Refresca la base desde GitHub. Usa ETag: si el repo no cambio, GitHub
 * responde 304 y no se descargan los 7 MB. La app nunca queda sin base porque
 * el .db de assets es el piso.
 */
@HiltWorker
class DtcSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("adiag-sync", Context.MODE_PRIVATE)
        val etag = prefs.getString(KEY_ETAG, null)

        val head = Request.Builder().url(COMMIT_URL).apply {
            etag?.let { header("If-None-Match", it) }
        }.build()

        val meta = runCatching { client.newCall(head).execute() }.getOrNull()
            ?: return@withContext Result.retry()
        meta.use {
            if (it.code == 304) return@withContext Result.success()
            if (!it.isSuccessful) return@withContext Result.retry()
        }

        val tmp = File(applicationContext.cacheDir, "dtc-download.db")
        val ok = runCatching {
            client.newCall(Request.Builder().url(DB_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                tmp.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                true
            }
        }.getOrDefault(false)
        if (!ok || !isSane(tmp)) { tmp.delete(); return@withContext Result.retry() }

        val target = applicationContext.getDatabasePath(AdiagDatabase.FILE)
        target.parentFile?.mkdirs()
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
        prefs.edit().putString(KEY_ETAG, meta.header("ETag")).apply()
        Result.success()
    }

    /** Rechaza descargas truncadas o corruptas antes de reemplazar la base. */
    private fun isSane(file: File): Boolean {
        if (file.length() < 1_000_000) return false
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM dtc_definitions", null).use { c ->
                    c.moveToFirst() && c.getInt(0) > 20_000
                }
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val KEY_ETAG = "db_etag"
        private const val REPO = "Wal33D/dtc-database"
        private const val COMMIT_URL = "https://api.github.com/repos/$REPO/commits/main"
        private const val DB_URL =
            "https://raw.githubusercontent.com/$REPO/main/data/dtc_codes.db"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DtcSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dtc-sync", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
