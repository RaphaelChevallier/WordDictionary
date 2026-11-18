package com.maxfluency.app.data

import com.maxfluency.app.BuildConfig
import com.maxfluency.core.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class PublicDictionaryApi {
    suspend fun lookup(language: Language, query: String): WordEntry? = withContext(Dispatchers.IO) {
        requests.withLock {
            val word = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
            if (word.isEmpty() || word.length > 100 || word.any { it.isISOControl() }) {
                throw IOException("A dictionary lookup needs 1-100 printable characters")
            }
            val encoded = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
            val url = "https://${language.code}.wiktionary.org/w/api.php?action=parse&page=$encoded&prop=text&format=json&formatversion=2&redirects=1"
            val connection = open(URL(url))
            try {
                val status = connection.responseCode
                if (status == 404) return@withLock null
                if (status != 200) throw IOException("Wiktionary lookup failed (HTTP $status)")
                val text = connection.inputStream.buffered().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > 4_194_304) throw IOException("Dictionary response exceeds the safety limit")
                        bytes.write(buffer, 0, read)
                    }
                    bytes.toString("UTF-8")
                }
                val json = dictionaryJson.parseToJsonElement(text) as? JsonObject
                    ?: throw IOException("The public dictionary returned an invalid object")
                (json["error"] as? JsonObject)?.let {
                    if ((it["code"] as? JsonPrimitive)?.content == "missingtitle") return@withLock null
                    throw IOException("Wiktionary rejected the lookup")
                }
                val html = ((json["parse"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content
                    ?: throw IOException("Wiktionary response has no definition HTML")
                DictionaryHtml.wiki(language, word, html)
            } finally { connection.disconnect() }
        }
    }

    companion object {
        private val requests = Mutex()
        fun open(url: URL): HttpURLConnection {
            if (url.protocol != "https") throw IOException("Dictionary sources must use HTTPS")
            return (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "MaxFluency/${BuildConfig.VERSION_NAME} (Android dictionary client)")
                setRequestProperty("Accept", "application/json, application/zip;q=0.9")
            }
        }
    }
}
