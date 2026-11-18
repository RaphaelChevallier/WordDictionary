package com.maxfluency.app

import android.content.Context
import android.content.res.Configuration
import android.icu.util.ULocale
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationRequest
import android.view.translation.TranslationSpec
import android.view.translation.TranslationManager
import android.view.translation.Translator
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.DefinitionTranslationProvider
import com.maxfluency.app.data.OnDeviceDefinitionTranslator
import com.maxfluency.app.data.systemLanguageTag
import java.util.Locale
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTranslationManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class DefinitionTranslationProviderTest {
    @Test
    fun systemEngineIsPreferredOnlyWhenItsLanguagePairIsAlreadyOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = requireNotNull(context.getSystemService(TranslationManager::class.java))
        val translator = OnDeviceDefinitionTranslator(context)
        try {
            capabilities(manager, TranslationCapability.STATE_ON_DEVICE)
            assertEquals(DefinitionTranslationProvider.DEVICE, translator.provider("fr", "en-US"))
            capabilities(manager, TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD)
            assertEquals(DefinitionTranslationProvider.GOOGLE, translator.provider("fr", "en-US"))
            assertEquals(null, translator.provider("fr", "zz"))
        } finally { translator.close() }
    }

    @Test
    @Config(shadows = [HoldingTranslationManager::class])
    fun cancellingASystemRequestDoesNotBlacklistTheReadyEngine() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = requireNotNull(context.getSystemService(TranslationManager::class.java))
        val shadow = Shadow.extract<HoldingTranslationManager>(manager)
        capabilities(manager, TranslationCapability.STATE_ON_DEVICE)
        val translator = OnDeviceDefinitionTranslator(context)
        try {
            assertEquals(DefinitionTranslationProvider.DEVICE, translator.provider("fr", "en-US"))
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                translator.translate("fr", "en-US", listOf("Une définition."),
                    DefinitionTranslationProvider.DEVICE, downloadModels = false)
            }
            assertEquals(1, shadow.creations)
            request.cancelAndJoin()
            assertEquals(DefinitionTranslationProvider.DEVICE, translator.provider("fr", "en-US"))
        } finally { translator.close() }
    }

    @Test
    @Config(sdk = [30, 35])
    fun phoneLanguageDoesNotFollowTheApplicationResourceLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frenchUi = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.FRANCE)
        })
        assertEquals("fr", frenchUi.resources.configuration.locales[0].language)
        assertEquals("en-US", systemLanguageTag(frenchUi))
    }

    private fun capabilities(manager: TranslationManager, state: Int) =
        Shadow.extract<ShadowTranslationManager>(manager).setOnDeviceTranslationCapabilities(
            TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT,
            setOf(ReflectionHelpers.callConstructor(TranslationCapability::class.java,
                ClassParameter.from(Integer.TYPE, state),
                ClassParameter.from(TranslationSpec::class.java, TranslationSpec(ULocale.FRENCH, TranslationSpec.DATA_FORMAT_TEXT)),
                ClassParameter.from(TranslationSpec::class.java, TranslationSpec(ULocale.ENGLISH, TranslationSpec.DATA_FORMAT_TEXT)),
                ClassParameter.from(java.lang.Boolean.TYPE, true),
                ClassParameter.from(Integer.TYPE, TranslationRequest.FLAG_TRANSLATION_RESULT))),
        )
}

@Implements(TranslationManager::class, minSdk = 31)
class HoldingTranslationManager : ShadowTranslationManager() {
    var creations = 0

    @Implementation
    protected fun createOnDeviceTranslator(spec: TranslationContext, executor: Executor, callback: Consumer<Translator?>) {
        creations++
    }
}
