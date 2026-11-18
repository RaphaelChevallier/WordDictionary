package com.maxfluency.app.data

import android.content.Context
import android.os.ParcelFileDescriptor
import com.maxfluency.core.Language
import com.maxfluency.core.PronunciationIndex
import com.maxfluency.core.WordEntry
import com.maxfluency.core.dictionaryJson
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable

class PronunciationGuides(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var reader: PronunciationIndex? = null
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null

    @Synchronized
    fun enrich(entry: WordEntry): WordEntry {
        val guide = if (entry.language == Language.ENGLISH) openReader().lookup(entry.word).orEmpty() else ""
        return if (entry.pronunciationGuide == guide) entry else entry.copy(pronunciationGuide = guide)
    }

    private fun openReader(): PronunciationIndex {
        reader?.let { return it }
        val manifest = context.assets.open("pronunciations/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<GuideManifest>(it.readText())
        }
        if (manifest.schemaVersion != 1 || manifest.language != "en" || manifest.entries !in 1..250_000 ||
            manifest.bytes !in 28..16_777_216L || !manifest.sha256.matches(Regex("[a-f0-9]{64}")) ||
            manifest.file != "en-${manifest.sha256.take(16)}.mfpr") {
            throw IOException("Incompatible English pronunciation metadata")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open("pronunciations/${manifest.file}").use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                if (size > manifest.bytes) throw IOException("Pronunciation asset exceeds its expected size")
                digest.update(buffer, 0, count)
            }
        }
        if (size != manifest.bytes || digest.digest().joinToString("") { "%02x".format(it) } != manifest.sha256) {
            throw IOException("English pronunciation integrity check failed")
        }
        val asset = context.assets.openFd("pronunciations/${manifest.file}")
        val offset = asset.startOffset
        val length = asset.length
        val stream = try {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(asset.fileDescriptor))
        } finally { asset.close() }
        var accepted = false
        try {
            if (length != manifest.bytes) throw IOException("Pronunciation asset size mismatch")
            val index = PronunciationIndex(length) { position, bytes ->
                val buffer = ByteBuffer.allocate(bytes)
                var location = offset + position
                while (buffer.hasRemaining()) {
                    val count = stream.channel.read(buffer, location)
                    if (count <= 0) throw IOException("Truncated pronunciation asset")
                    location += count
                }
                buffer.array()
            }
            if (index.entries != manifest.entries) throw IOException("Pronunciation entry count mismatch")
            reader = index
            input = stream
            accepted = true
            return index
        } finally { if (!accepted) stream.close() }
    }

    @Synchronized
    override fun close() {
        input?.close()
        input = null
        reader = null
    }

    @Serializable
    private data class GuideManifest(
        val schemaVersion: Int, val language: String, val file: String,
        val entries: Int, val bytes: Long, val sha256: String,
    )
}
