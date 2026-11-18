package com.maxfluency.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OnDeviceDefinitionTranslator(context: Context) : DefinitionTranslator {
    private val system = if (Build.VERSION.SDK_INT >= 31) SystemDefinitionTranslator(context.applicationContext) else null
    private val failedSystemPairs = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    override suspend fun provider(source: String, targetTag: String): DefinitionTranslationProvider? {
        val pair = source to targetTag
        if (Build.VERSION.SDK_INT >= 31 && pair !in failedSystemPairs && system?.available(source, targetTag) == true) {
            return DefinitionTranslationProvider.DEVICE
        }
        return if (modelLanguage(source) != null &&
            modelLanguage(targetTag) != null) DefinitionTranslationProvider.GOOGLE else null
    }

    override suspend fun translate(source: String, targetTag: String, texts: List<String>,
        provider: DefinitionTranslationProvider, downloadModels: Boolean): List<String> {
        require(texts.isNotEmpty() && texts.none { it.isBlank() })
        if (provider == DefinitionTranslationProvider.DEVICE) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    return system?.translate(source, targetTag, texts)
                        ?: throw IOException("System translation is unavailable")
                }
                throw IOException("This Android version has no system translation API")
            } catch (error: IOException) {
                failedSystemPairs += source to targetTag
                Log.w("DefinitionTranslation", "System translation failed; a retry may use the explicit Google fallback", error)
                throw error
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failedSystemPairs += source to targetTag
                throw IOException("System translation timed out", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                failedSystemPairs += source to targetTag
                throw IOException("System translation access is unavailable", error)
            } catch (error: UnsupportedOperationException) {
                failedSystemPairs += source to targetTag
                throw IOException("System translation is unsupported", error)
            } catch (error: IllegalStateException) {
                failedSystemPairs += source to targetTag
                throw IOException("System translation is no longer ready", error)
            }
        }
        val sourceCode = modelLanguage(source) ?: throw IOException("Unsupported translation source")
        val targetCode = modelLanguage(targetTag) ?: throw IOException("Unsupported translation target")
        return withContext(Dispatchers.Default) {
            val client = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode).setTargetLanguage(targetCode).build())
            try {
                if (downloadModels) client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
                val translated = texts.map { client.translate(it).await() }
                if (translated.size != texts.size || translated.any { it.isBlank() }) {
                    throw IOException("The translator returned incomplete definitions")
                }
                translated
            } catch (error: MlKitException) {
                if (!downloadModels && error.errorCode == MlKitException.NOT_FOUND) throw TranslationModelRequired()
                throw IOException("On-device translation failed", error)
            } finally { client.close() }
        }
    }

    override suspend fun removeDownloadedModels() {
        try {
            val manager = RemoteModelManager.getInstance()
            val models = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            models.forEach { manager.deleteDownloadedModel(it).await() }
        } catch (error: MlKitException) {
            throw IOException("Translation models could not be removed", error)
        }
    }

    override fun close() = Unit

    private fun modelLanguage(tag: String): String? =
        TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(tag).language)
}
