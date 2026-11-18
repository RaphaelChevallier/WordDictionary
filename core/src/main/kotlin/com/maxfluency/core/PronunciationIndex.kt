package com.maxfluency.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

class PronunciationIndex(
    private val fileLength: Long,
    private val readAt: (Long, Int) -> ByteArray,
) {
    val entries: Int
    private val offsets: IntArray
    private val dataOffset: Long

    init {
        val header = ByteBuffer.wrap(read(0, 24)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also { header.get(it) }
        if (magic.toString(StandardCharsets.US_ASCII) != "MFPR0001") corrupt("Invalid pronunciation header")
        entries = header.int
        val indexBytes = header.int
        val dataBytes = header.int
        if (entries !in 1..250_000 || indexBytes != (entries + 1) * 4 ||
            dataBytes !in 1..16_777_216 || header.int != 1024) corrupt("Invalid pronunciation sizes")
        dataOffset = 24L + indexBytes
        if (dataOffset + dataBytes != fileLength) corrupt("Truncated pronunciation asset")
        val index = ByteBuffer.wrap(read(24, indexBytes)).order(ByteOrder.LITTLE_ENDIAN)
        offsets = IntArray(entries + 1) { index.int }
        if (offsets.first() != 0 || offsets.last() != dataBytes || (0 until entries).any {
            offsets[it] < 0 || offsets[it + 1] - offsets[it] !in 1..1024
        }) corrupt("Invalid pronunciation offsets")
    }

    fun lookup(word: String): String? {
        val key = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('\u2019', '\'')
        if (!wordPattern.matches(key) || (word.length > 1 && word.all { it in 'A'..'Z' })) return null
        var low = 0
        var high = entries
        while (low < high) {
            val middle = (low + high) ushr 1
            val (spelling, guide) = record(middle)
            when {
                spelling < key -> low = middle + 1
                spelling > key -> high = middle
                else -> return guide
            }
        }
        return null
    }

    private fun record(position: Int): Pair<String, String> {
        val bytes = read(dataOffset + offsets[position], offsets[position + 1] - offsets[position])
        if (bytes.any { it < 0 }) corrupt("Invalid pronunciation encoding")
        val text = bytes.toString(StandardCharsets.US_ASCII)
        val separator = text.indexOf('\t')
        if (separator <= 0 || text.indexOf('\t', separator + 1) >= 0) corrupt("Invalid pronunciation record")
        val word = text.substring(0, separator)
        val guide = text.substring(separator + 1)
        if (!wordPattern.matches(word) || !guidePattern.matches(guide)) corrupt("Invalid pronunciation text")
        return word to guide
    }

    private fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > fileLength) corrupt("Truncated pronunciation read")
        return readAt(offset, length).also { if (it.size != length) corrupt("Short pronunciation read") }
    }

    companion object {
        private val wordPattern = Regex("^[a-z][a-z'.-]{0,127}$")
        private val guidePattern = Regex("^[a-zA-Z -]+(?: / [a-zA-Z -]+)?$")
        private fun corrupt(message: String): Nothing = throw IOException(message)
    }
}
