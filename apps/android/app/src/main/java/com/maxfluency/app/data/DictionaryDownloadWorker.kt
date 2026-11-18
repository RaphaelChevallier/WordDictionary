package com.maxfluency.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.maxfluency.app.R
import com.maxfluency.core.*
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException

class DictionaryDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("dictionary-downloads",
            applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val language = Language.fromCode(inputData.getString("language").orEmpty())
            ?: throw IOException("Missing download language")
        val notification = NotificationCompat.Builder(applicationContext, "dictionary-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_dictionary))
            .setContentText(language.nativeName)
            .setOngoing(true).setProgress(100, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.cancel_download), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(4400 + language.ordinal, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(4400 + language.ordinal, notification)
        }
    }

    override suspend fun doWork(): Result {
        val language = Language.fromCode(inputData.getString("language").orEmpty())
            ?: return Result.failure(workDataOf("error" to "Missing language"))
        val archive = File(applicationContext.cacheDir, "dictionary-download-${id}.zip")
        try {
            setForeground(getForegroundInfo())
            val connection = PublicDictionaryApi.open(URL(PublicDictionaries.url(language)))
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                if (connection.responseCode != 200) throw IOException("Dictionary download failed (HTTP ${connection.responseCode})")
                val expected = connection.contentLengthLong
                if (expected !in 1..250_000_000) throw IOException("Unexpected dictionary download size")
                var total = 0L
                connection.inputStream.buffered().use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var last = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > expected) throw IOException("Dictionary exceeds advertised download size")
                            output.write(buffer, 0, read); digest.update(buffer, 0, read)
                            val percent = (total * 100 / expected).toInt()
                            if (percent != last) {
                                setProgress(workDataOf("phase" to "download", "percent" to percent))
                                last = percent
                            }
                        }
                    }
                }
                if (total != expected) throw IOException("Dictionary download was incomplete")
            } finally { connection.disconnect() }
            val manifest = applicationContext.assets.open("lexicon/manifest.json").bufferedReader().use {
                dictionaryJson.decodeFromString<PackManifest>(it.readText())
            }
            val operation = currentCoroutineContext()
            FullDictionaryStore.get(applicationContext).install(language, archive,
                digest.digest().joinToString("") { "%02x".format(it) }, manifest.discovery,
                { percent -> setProgressAsync(workDataOf("phase" to "prepare", "percent" to percent)) },
                { operation.ensureActive() })
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.e("DictionaryDownload", "Public offline dictionary download failed", error)
            return Result.failure(workDataOf("error" to error.message))
        } catch (error: android.database.sqlite.SQLiteException) {
            Log.e("DictionaryDownload", "Offline dictionary index could not be prepared", error)
            return Result.failure(workDataOf("error" to error.message))
        } catch (error: SerializationException) {
            Log.e("DictionaryDownload", "Offline dictionary metadata is invalid", error)
            return Result.failure(workDataOf("error" to error.message))
        } finally {
            if (archive.exists() && !archive.delete()) Log.w("DictionaryDownload", "Could not delete the completed download archive")
        }
    }

    companion object {
        fun name(language: Language) = "full-dictionary-${language.code}"
        fun start(context: Context, language: Language) {
            val request = OneTimeWorkRequestBuilder<DictionaryDownloadWorker>()
                .setInputData(workDataOf("language" to language.code))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(language), ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, language: Language) { WorkManager.getInstance(context).cancelUniqueWork(name(language)) }
    }
}
