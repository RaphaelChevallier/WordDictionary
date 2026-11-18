package com.maxfluency.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PronunciationIndexTest {
    @Test
    fun `indexed lookup preserves guides and variants without guessing missing words`() {
        val source = buildMap {
            putAll(mapOf("disaster" to "dih-ZAS-ter", "can't" to "KANT", "read" to "RED / REED"))
            for (a in 'a'..'z') for (b in 'a'..'z') put("sample$a$b", "SAM-puhl")
        }
        val bytes = asset(source)
        var bytesRead = 0
        val reader = PronunciationIndex(bytes.size.toLong()) { offset, count ->
            bytesRead += count
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
        bytesRead = 0
        assertEquals("dih-ZAS-ter", reader.lookup("Disaster"))
        assertEquals("RED / REED", reader.lookup("read"))
        assertEquals("KANT", reader.lookup("can\u2019t"))
        assertNull(reader.lookup("not-in-this-dictionary"))
        assertNull(reader.lookup("US"))
        assertNull(reader.lookup("r\u00e9sum\u00e9"))
        assertTrue("Lookup must read individual records, not reload the complete asset", bytesRead < 8192)
    }

    @Test
    fun `corrupt offsets and truncated assets fail explicitly`() {
        val bytes = asset(mapOf("disaster" to "dih-ZAS-ter"))
        assertThrows(IOException::class.java) {
            val short = bytes.copyOf(bytes.size - 1)
            PronunciationIndex(short.size.toLong()) { offset, count ->
                short.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        }
        val corrupt = bytes.copyOf()
        ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(28, Int.MAX_VALUE)
        assertThrows(IOException::class.java) {
            PronunciationIndex(corrupt.size.toLong()) { offset, count ->
                corrupt.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        }
    }

    private fun asset(entries: Map<String, String>): ByteArray {
        val records = entries.toSortedMap().map { (word, guide) -> "$word\t$guide".toByteArray() }
        val indexBytes = (records.size + 1) * 4
        val result = ByteBuffer.allocate(24 + indexBytes + records.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        result.put("MFPR0001".toByteArray()).putInt(records.size).putInt(indexBytes)
            .putInt(records.sumOf { it.size }).putInt(1024)
        var offset = 0
        records.forEach { result.putInt(offset); offset += it.size }
        result.putInt(offset)
        records.forEach { result.put(it) }
        return result.array()
    }
}
