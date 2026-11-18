package com.maxfluency.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class DictZipReader(file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val uncompressedBytes: Long
    private val chunkSize: Int
    private val offsets: LongArray
    private val lengths: IntArray
    private val cache = object : LinkedHashMap<Int, ByteArray>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 4
    }

    init {
        try {
            if (input.length() < 24 || input.readUnsignedByte() != 31 || input.readUnsignedByte() != 139 ||
                input.readUnsignedByte() != 8) throw IOException("Not a gzip dictionary")
            val flags = input.readUnsignedByte()
            if (flags and 4 == 0) throw IOException("Dictionary has no random-access table")
            input.skipBytes(6)
            val extraEnd = input.filePointer + 2 + u16()
            var size = 0
            var sizes: IntArray? = null
            while (input.filePointer < extraEnd) {
                val a = input.readUnsignedByte()
                val b = input.readUnsignedByte()
                val length = u16()
                val end = input.filePointer + length
                if (end > extraEnd) throw IOException("Invalid dictzip extra data")
                if (a == 'R'.code && b == 'A'.code) {
                    if (u16() != 1) throw IOException("Unsupported dictzip version")
                    size = u16()
                    val count = u16()
                    if (size < 1 || count < 1 || length != 6 + count * 2) throw IOException("Invalid dictzip chunks")
                    sizes = IntArray(count) { u16() }
                }
                input.seek(end)
            }
            if (flags and 8 != 0) skipTerminated()
            if (flags and 16 != 0) skipTerminated()
            if (flags and 2 != 0) input.skipBytes(2)
            chunkSize = size
            lengths = sizes ?: throw IOException("Missing dictzip random-access table")
            var offset = input.filePointer
            offsets = LongArray(lengths.size) { i -> offset.also { offset += lengths[i] } }
            if (offset > input.length() - 8) throw IOException("Truncated dictzip chunks")
            input.seek(input.length() - 4)
            uncompressedBytes = (0..3).fold(0L) { total, shift -> total or (input.readUnsignedByte().toLong() shl (shift * 8)) }
            if (uncompressedBytes < 1 || (uncompressedBytes + chunkSize - 1) / chunkSize != lengths.size.toLong()) {
                throw IOException("Invalid dictzip uncompressed size")
            }
        } catch (error: IOException) {
            input.close()
            throw error
        }
    }

    @Synchronized
    fun read(offset: Long, size: Int): ByteArray {
        if (offset < 0 || size !in 0..2_097_152 || offset + size > uncompressedBytes) throw IOException("Invalid dictionary article range")
        val result = ByteArray(size)
        var copied = 0
        while (copied < size) {
            val absolute = offset + copied
            val index = (absolute / chunkSize).toInt()
            val chunk = cache.getOrPut(index) { decode(index) }
            val start = (absolute % chunkSize).toInt()
            val length = minOf(size - copied, chunk.size - start)
            if (length <= 0) throw IOException("Truncated dictionary article")
            chunk.copyInto(result, copied, start, start + length)
            copied += length
        }
        return result
    }

    private fun decode(index: Int): ByteArray {
        val bytes = ByteArray(lengths[index])
        input.seek(offsets[index])
        input.readFully(bytes)
        val expected = minOf(chunkSize.toLong(), uncompressedBytes - index.toLong() * chunkSize).toInt()
        val result = ByteArray(expected)
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            var size = 0
            while (size < result.size) {
                val read = inflater.inflate(result, size, result.size - size)
                if (read == 0) break
                size += read
            }
            if (size != expected) throw IOException("Truncated dictzip frame")
        } catch (error: DataFormatException) {
            throw IOException("Corrupt dictzip frame", error)
        } finally { inflater.end() }
        return result
    }

    private fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)

    private fun skipTerminated() {
        repeat(4096) { if (input.readUnsignedByte() == 0) return }
        throw IOException("Dictionary header string exceeds limit")
    }

    override fun close() { input.close() }
}
