package com.maxfluency.app.data

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

@RequiresApi(31)
internal class SystemDefinitionTranslator(private val context: Context) {
    private val ready = mutableMapOf<Pair<String, String>, TranslationCapability>()

    suspend fun available(source: String, targetTag: String): Boolean {
        return try {
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    val manager = context.getSystemService(TranslationManager::class.java) ?: return@withContext false
                    val target = Locale.forLanguageTag(targetTag)
                    val capability = manager.getOnDeviceTranslationCapabilities(
                        TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT,
                    ).firstOrNull {
                        it.state == TranslationCapability.STATE_ON_DEVICE &&
                            it.sourceSpec.locale.language == source && it.targetSpec.locale.language == target.language &&
                            (target.script.isBlank() || it.targetSpec.locale.script == target.script)
                    } ?: return@withContext false
                    synchronized(ready) { ready[source to targetTag] = capability }
                    true
                }
            }
        } catch (error: SecurityException) {
            Log.w("DefinitionTranslation", "System translation access is unavailable", error)
            false
        } catch (error: UnsupportedOperationException) {
            Log.w("DefinitionTranslation", "System translation provider is unsupported", error)
            false
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w("DefinitionTranslation", "System translation capability check timed out", error)
            false
        }
    }

    suspend fun translate(source: String, targetTag: String, definitions: List<String>): List<String> =
        withTimeout(30_000) {
            val capability = synchronized(ready) { ready[source to targetTag] }
                ?: throw IOException("The system translation capability is no longer available")
            val manager = context.getSystemService(TranslationManager::class.java)
                ?: throw IOException("The system translation service disappeared")
            val spec = TranslationContext.Builder(capability.sourceSpec, capability.targetSpec).build()
            val translator = suspendCancellableCoroutine<Translator?> { continuation ->
                manager.createOnDeviceTranslator(spec, context.mainExecutor) { value ->
                    continuation.resume(value) { _, abandoned, _ -> abandoned?.destroy() }
                }
            } ?: throw IOException("The system could not create an on-device translator")
            try {
                val request = TranslationRequest.Builder()
                    .setFlags(TranslationRequest.FLAG_TRANSLATION_RESULT)
                    .setTranslationRequestValues(definitions.map(TranslationRequestValue::forText))
                    .build()
                val response = suspendCancellableCoroutine<TranslationResponse> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    translator.translate(request, cancellation, context.mainExecutor) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                }
                if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS || !response.isFinalResponse) {
                    throw IOException("The system translator did not return a complete translation")
                }
                definitions.indices.map { index ->
                    val value = response.translationResponseValues[index]
                    if (value == null || value.statusCode != TranslationResponseValue.STATUS_SUCCESS ||
                        value.text.isNullOrBlank()) throw IOException("A translated definition is missing")
                    value.text.toString()
                }
            } finally { translator.destroy() }
        }
}
