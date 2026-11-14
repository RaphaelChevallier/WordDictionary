package com.maxfluency.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class PackedMetadata(val language: String, val sources: List<DictionarySource>)

class BlockLexicon(
    private val fileLength: Long,
    private val readAt: (Long, Int) -> ByteArray,
    private val decompress: (ByteArray, Int) -> ByteArray,
) {
    val metadata: PackedMetadata
    val entries: Int
    val daily: IntArray
    private val words: ByteArray
    private val checkpoints: IntArray
    private val anagrams: IntArray
    private val directory: ByteArray
    private val dataOffset: Int
    private val blockEntries: Int
    private val pageCache = object : LinkedHashMap<Int, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?): Boolean = size > 16
    }
    private val blockCache = object : LinkedHashMap<Int, ByteArray>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 2
    }

    init {
        val header = Cursor(read(0, 64))
        if (header.take(8).toString(StandardCharsets.US_ASCII) != "MFLX0003") corrupt("Invalid dictionary header")
        val descriptionBytes = header.u32()
        entries = header.u32()
        val dailyCount = header.u32()
        blockEntries = header.u32()
        val blocks = header.u32()
        val indexBytes = header.u32()
        val compressedIndexBytes = header.u32()
        val directoryOffset = header.u32()
        dataOffset = header.u32()
        val dataBytes = header.u32()
        val maximumBlockBytes = header.u32()
        if (entries !in 1..5_000_000 || dailyCount !in 1..entries
            || blockEntries !in 64..32768 || blockEntries and (blockEntries - 1) != 0
            || blocks != (entries + blockEntries - 1) / blockEntries
            || descriptionBytes !in 1..65_536 || indexBytes !in 1..67_108_864
            || directoryOffset.toLong() != 64L + descriptionBytes + compressedIndexBytes
            || dataOffset.toLong() != directoryOffset.toLong() + blocks * 12L
            || fileLength != dataOffset.toLong() + dataBytes || maximumBlockBytes != 16_777_216) {
            corrupt("Invalid dictionary sizes or offsets")
        }
        metadata = dictionaryJson.decodeFromString<PackedMetadata>(read(64, descriptionBytes).decodeToString())
        if (Language.fromCode(metadata.language) == null || metadata.sources.size !in 1..16
            || metadata.sources.map { it.id }.toSet().size != metadata.sources.size) corrupt("Invalid dictionary sources")
        val index = Cursor(decode(read(64L + descriptionBytes, compressedIndexBytes), indexBytes))
        if (index.u32() != 128 || index.u32() != (entries + 127) / 128) corrupt("Invalid dictionary word pages")
        checkpoints = IntArray((entries + 127) / 128) { index.u32() }
        words = index.take(index.u32())
        if (checkpoints.first() != 0 || checkpoints.withIndex().any { (i, offset) ->
            offset !in words.indices || (i > 0 && offset <= checkpoints[i - 1])
        }) corrupt("Invalid dictionary word checkpoints")
        if (index.u32() != entries) corrupt("Invalid anagram index")
        anagrams = IntArray(entries) { index.u32() }
        if (index.u32() != dailyCount) corrupt("Invalid daily index")
        daily = IntArray(dailyCount) { index.u32() }
        index.end()
        validatePositions(anagrams)
        validatePositions(daily)
        directory = read(directoryOffset.toLong(), blocks * 12)
        var end = 0L
        repeat(blocks) { block ->
            val pointer = Cursor(directory, block * 12, block * 12 + 12)
            val offset = pointer.u32()
            val length = pointer.u32()
            val decoded = pointer.u32()
            if (offset.toLong() != end || length < 1 || decoded !in 1..maximumBlockBytes) corrupt("Invalid dictionary block")
            end += length
        }
        if (end != dataBytes.toLong()) corrupt("Truncated dictionary blocks")
    }

    fun wordAt(ordinal: Int): String = keyAt(ordinal).substringAfter(LOOKUP_SEPARATOR)

    fun entry(word: String): WordEntry? {
        val key = lookupKey(word)
        val position = lowerBound(key)
        return if (position < entries && keyAt(position) == key) entryAt(position) else null
    }

    fun search(plan: SearchPlan, checkCancellation: () -> Unit = {}): List<WordEntry> {
        val positions = when (plan) {
            SearchPlan.Empty -> emptyList()
            SearchPlan.NoLetters -> throw IllegalArgumentException("Anagrams require letters")
            is SearchPlan.Anagram -> {
                var low = 0
                var high = anagrams.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (compareUtf8(anagramAt(mid), plan.value) < 0) low = mid + 1 else high = mid
                }
                buildList {
                    while (low < anagrams.size && size < 60 && anagramAt(low) == plan.value) add(anagrams[low++])
                }
            }
            is SearchPlan.Prefix -> scan(plan.value, checkCancellation) { it.startsWith(plan.value) }
            is SearchPlan.Wildcard -> {
                val tokens = globTokens(plan.value)
                val prefix = String(tokens.takeWhile { it >= 0 }.toIntArray(), 0, tokens.takeWhile { it >= 0 }.size)
                scan(prefix, checkCancellation) { matchesGlob(it, tokens) }
            }
        }
        return positions.map { entryAt(it) }
    }

    fun entryAt(ordinal: Int): WordEntry {
        val key = keyAt(ordinal)
        val blockNumber = ordinal / blockEntries
        val block = blockCache.getOrPut(blockNumber) {
            val pointer = Cursor(directory, blockNumber * 12, blockNumber * 12 + 12)
            val offset = pointer.u32()
            val bytes = pointer.u32()
            decode(read(dataOffset.toLong() + offset, bytes), pointer.u32())
        }
        val count = minOf(blockEntries, entries - blockNumber * blockEntries)
        val pointer = Cursor(block, ordinal % blockEntries * 4)
        val start = pointer.u32()
        val end = pointer.u32()
        val base = (count + 1) * 4
        if (end < start || base.toLong() + end > block.size) corrupt("Invalid definition offsets")
        val record = dictionaryJson.parseToJsonElement(
            block.decodeToString(base + start, base + end, throwOnInvalidSequence = true),
        ).tuple(4)
        val sourceIds = sources(record[0].integer())
        val pronunciation = record[1].text()
        val senses = record[2].collection(64).map {
            val sense = it.tuple(3)
            val part = parts.getOrNull(sense[0].integer()) ?: corrupt("Invalid dictionary part of speech")
            Sense(part, sense[2].text(), sources(sense[1].integer()).map { source -> source.id })
        }
        if (senses.isEmpty()) corrupt("Empty dictionary definition")
        val related = record[3].collection(64).map { it.text() }
        val word = key.substringAfter(LOOKUP_SEPARATOR)
        return WordEntry(
            id = "${metadata.language}:$word",
            language = Language.fromCode(metadata.language) ?: corrupt("Unsupported dictionary language"),
            word = word,
            pronunciation = pronunciation,
            senses = senses,
            related = related,
            sourceEdition = sourceIds.first().edition,
            sources = sourceIds,
        )
    }

    private fun scan(prefix: String, checkCancellation: () -> Unit, matches: (String) -> Boolean): List<Int> = buildList {
        var position = if (prefix.isEmpty()) 0 else lowerBound(prefix)
        while (position < entries && size < 60) {
            if (position % 128 == 0) checkCancellation()
            val word = keyAt(position).substringBefore(LOOKUP_SEPARATOR)
            if (!word.startsWith(prefix)) break
            if (matches(word)) add(position)
            position++
        }
    }

    private fun anagramAt(position: Int): String =
        anagramKey(keyAt(anagrams[position]).substringAfter(LOOKUP_SEPARATOR))

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = entries
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareUtf8(keyAt(mid), key) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun keyAt(ordinal: Int): String {
        if (ordinal !in 0 until entries) corrupt("Invalid word position")
        val page = ordinal / 128
        val terms = pageCache.getOrPut(page) {
            val reader = Cursor(words, checkpoints[page], checkpoints.getOrElse(page + 1) { words.size })
            var previous = byteArrayOf()
            val result = List(minOf(128, entries - page * 128)) {
                val prefix = reader.varint()
                if (prefix > previous.size) corrupt("Invalid word prefix")
                val suffix = reader.take(reader.varint())
                previous = previous.copyOfRange(0, prefix) + suffix
                previous.decodeToString(throwOnInvalidSequence = true).also {
                    val separator = it.indexOf(LOOKUP_SEPARATOR)
                    if (separator < 0 || separator == it.lastIndex) corrupt("Invalid word key")
                }
            }
            reader.end()
            result
        }
        return terms[ordinal % 128]
    }

    private fun sources(mask: Int): List<DictionarySource> {
        if (mask <= 0 || mask >= 1 shl metadata.sources.size) corrupt("Invalid dictionary source mask")
        return metadata.sources.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    }

    private fun validatePositions(values: IntArray) {
        val seen = BooleanArray(entries)
        values.forEach { value ->
            if (value !in 0 until entries || seen[value]) corrupt("Invalid dictionary index position")
            seen[value] = true
        }
    }

    private fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > fileLength) corrupt("Truncated dictionary file")
        return readAt(offset, length).also { if (it.size != length) corrupt("Truncated dictionary read") }
    }

    private fun decode(bytes: ByteArray, expected: Int): ByteArray =
        decompress(bytes, expected).also { if (it.size != expected) corrupt("Dictionary decoded length mismatch") }

    private class Cursor(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
        fun take(length: Int): ByteArray {
            if (length < 0 || position.toLong() + length > end) corrupt("Truncated dictionary record")
            return bytes.copyOfRange(position, position + length).also { position += length }
        }
        fun u32(): Int {
            val value = take(4)
            if (value[3].toInt() and 0x80 != 0) corrupt("Dictionary integer exceeds limit")
            return (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8) or
                ((value[2].toInt() and 0xff) shl 16) or ((value[3].toInt() and 0xff) shl 24)
        }
        fun varint(): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                val byte = take(1)[0].toInt() and 0xff
                if (shift == 28 && byte and 0xf8 != 0) corrupt("Dictionary varint exceeds limit")
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return value
            }
            corrupt("Invalid dictionary varint")
        }
        fun count(maximum: Int): Int = varint().also { if (it > maximum) corrupt("Dictionary collection exceeds limit") }
        fun string(): String = take(varint()).decodeToString(throwOnInvalidSequence = true)
        fun end() { if (position != end) corrupt("Trailing dictionary record bytes") }
    }

    companion object {
        private val parts = listOf("noun", "verb", "adj", "adv", "pron", "det", "prep", "conj", "intj",
            "num", "phrase", "proverb", "contraction", "prefix", "suffix", "affix", "particle", "article")

        private fun corrupt(message: String): Nothing = throw IOException(message)

        private fun JsonElement.collection(maximum: Int): JsonArray {
            val array = this as? JsonArray ?: corrupt("Invalid dictionary array")
            if (array.size > maximum) corrupt("Dictionary collection exceeds limit")
            return array
        }

        private fun JsonElement.tuple(size: Int): JsonArray = collection(size).also {
            if (it.size != size) corrupt("Invalid dictionary tuple")
        }

        private fun JsonElement.integer(): Int =
            (this as? JsonPrimitive)?.intOrNull ?: corrupt("Invalid dictionary integer")

        private fun JsonElement.text(): String {
            val primitive = this as? JsonPrimitive ?: corrupt("Invalid dictionary text")
            if (!primitive.isString) corrupt("Invalid dictionary text")
            return primitive.content
        }

        private fun compareUtf8(first: String, second: String): Int {
            val a = first.toByteArray(StandardCharsets.UTF_8)
            val b = second.toByteArray(StandardCharsets.UTF_8)
            for (i in 0 until minOf(a.size, b.size)) {
                val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }

        private fun globTokens(pattern: String): IntArray = buildList {
            var index = 0
            while (index < pattern.length) {
                when {
                    pattern.startsWith("[[]", index) -> { add('['.code); index += 3 }
                    pattern.startsWith("[]]", index) -> { add(']'.code); index += 3 }
                    pattern[index] == '*' -> { add(-1); index++ }
                    pattern[index] == '?' -> { add(-2); index++ }
                    else -> {
                        val point = pattern.codePointAt(index)
                        add(point)
                        index += Character.charCount(point)
                    }
                }
            }
        }.toIntArray()

        private fun matchesGlob(word: String, pattern: IntArray): Boolean {
            val text = word.codePoints().toArray()
            var i = 0
            var j = 0
            var star = -1
            var mark = 0
            while (i < text.size) {
                when {
                    j < pattern.size && (pattern[j] == -2 || pattern[j] == text[i]) -> { i++; j++ }
                    j < pattern.size && pattern[j] == -1 -> { star = j++; mark = i }
                    star >= 0 -> { j = star + 1; i = ++mark }
                    else -> return false
                }
            }
            while (j < pattern.size && pattern[j] == -1) j++
            return j == pattern.size
        }
    }
}
