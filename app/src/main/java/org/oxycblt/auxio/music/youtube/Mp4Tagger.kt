/*
 * Copyright (c) 2026 Beyond Player
 * Mp4Tagger.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.youtube

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes iTunes-style tags (title, artist, album, year, cover, plus our own YouTube id) into an
 * m4a/mp4 file. No native code: it rewrites only the `moov` box and copies the rest untouched.
 *
 * Works for fragmented files (what YouTube serves) and for classic ones, where the chunk offsets in
 * `moov` are shifted to account for the size change.
 */
object Mp4Tagger {
    class Meta(
        val title: String,
        val artist: String,
        val album: String?,
        val year: Int?,
        val videoId: String?,
        val cover: ByteArray?,
        val coverIsPng: Boolean = false,
    )

    private class Box(val start: Long, val header: Int, val size: Long, val type: String)

    /** Rewrites [file] in place. Throws if it is not a readable mp4. */
    fun write(file: File, meta: Meta) {
        val tmp = File(file.path + ".tagged")
        try {
            RandomAccessFile(file, "r").use { src ->
                val top = readBoxes(src, 0, src.length())
                if (top.none { it.type == "ftyp" }) throw IllegalArgumentException("Not an mp4")
                val moov =
                    top.firstOrNull { it.type == "moov" }
                        ?: throw IllegalArgumentException("No moov box")
                val oldMoov = ByteArray((moov.size - moov.header).toInt())
                src.seek(moov.start + moov.header)
                src.readFully(oldMoov)

                val children = readBoxes(oldMoov, 0, oldMoov.size)
                val kept = ByteArrayOutputStream()
                // Existing udta is replaced: it only holds tags we are about to rewrite.
                for (c in children) {
                    if (c.type != "udta") kept.write(oldMoov, c.start.toInt(), c.size.toInt())
                }
                kept.write(buildUdta(meta))
                var newPayload = kept.toByteArray()
                val newSize = newPayload.size + 8L
                val delta = newSize - moov.size

                val fragmented =
                    top.any { it.type == "moof" } || children.any { it.type == "mvex" }
                val firstMdat = top.firstOrNull { it.type == "mdat" }
                if (!fragmented && firstMdat != null && firstMdat.start > moov.start && delta != 0L) {
                    shiftChunkOffsets(newPayload, 0, newPayload.size, delta)
                }

                tmp.outputStream().buffered().use { out ->
                    copyRange(src, 0, moov.start, out)
                    out.write(int(newSize.toInt()))
                    out.write("moov".toByteArray(Charsets.ISO_8859_1))
                    out.write(newPayload)
                    copyRange(src, moov.start + moov.size, src.length(), out)
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // --- building atoms ---

    private fun buildUdta(m: Meta): ByteArray {
        val items = ByteArrayOutputStream()
        items.write(textItem("©nam", m.title))
        if (m.artist.isNotBlank()) {
            items.write(textItem("©ART", m.artist))
            items.write(textItem("aART", m.artist))
        }
        if (!m.album.isNullOrBlank()) items.write(textItem("©alb", m.album))
        if (m.year != null) items.write(textItem("©day", m.year.toString()))
        if (!m.videoId.isNullOrBlank()) {
            items.write(
                box(
                    "----",
                    box("mean", int(0) + "com.apple.iTunes".toByteArray()),
                    box("name", int(0) + "BEYOND_YT_ID".toByteArray()),
                    box("data", int(1) + int(0) + m.videoId.toByteArray()),
                )
            )
        }
        if (m.cover != null) {
            items.write(
                box("covr", box("data", int(if (m.coverIsPng) 14 else 13) + int(0) + m.cover))
            )
        }
        val hdlr =
            box(
                "hdlr",
                int(0) +
                    int(0) +
                    "mdir".toByteArray() +
                    "appl".toByteArray() +
                    int(0) +
                    int(0) +
                    byteArrayOf(0),
            )
        val ilst = box("ilst", items.toByteArray())
        return box("udta", box("meta", int(0) + hdlr + ilst))
    }

    private fun textItem(name: String, value: String) =
        box(name, box("data", int(1) + int(0) + value.toByteArray(Charsets.UTF_8)))

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = parts.sumOf { it.size }
        out.write(int(payload + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun int(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    // --- reading boxes ---

    private fun readBoxes(src: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        val head = ByteArray(16)
        while (pos + 8 <= to) {
            src.seek(pos)
            src.readFully(head, 0, 8)
            var size = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                src.readFully(head, 8, 8)
                size = u64(head, 8)
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header || pos + size > to) break
            out.add(Box(pos, header, size, type))
            pos += size
        }
        return out
    }

    private fun readBoxes(buf: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            var size = u32(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && pos + 16 <= to) {
                size = u64(buf, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < header || pos + size > to) break
            out.add(Box(pos.toLong(), header, size, type))
            pos += size.toInt()
        }
        return out
    }

    /** Add [delta] to every stco/co64 chunk offset under [from, to) of [buf]. */
    private fun shiftChunkOffsets(buf: ByteArray, from: Int, to: Int, delta: Long) {
        for (b in readBoxes(buf, from, to)) {
            val bodyStart = b.start.toInt() + b.header
            val end = (b.start + b.size).toInt()
            when (b.type) {
                "trak",
                "mdia",
                "minf",
                "stbl" -> shiftChunkOffsets(buf, bodyStart, end, delta)
                "stco" -> {
                    val count = u32(buf, bodyStart + 4).toInt()
                    for (i in 0 until count) {
                        val at = bodyStart + 8 + i * 4
                        putU32(buf, at, u32(buf, at) + delta)
                    }
                }
                "co64" -> {
                    val count = u32(buf, bodyStart + 4).toInt()
                    for (i in 0 until count) {
                        val at = bodyStart + 8 + i * 8
                        putU64(buf, at, u64(buf, at) + delta)
                    }
                }
            }
        }
    }

    private fun copyRange(src: RandomAccessFile, from: Long, to: Long, out: java.io.OutputStream) {
        val buf = ByteArray(256 * 1024)
        src.seek(from)
        var left = to - from
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException()
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun putU64(b: ByteArray, at: Int, v: Long) {
        putU32(b, at, v ushr 32)
        putU32(b, at + 4, v and 0xFFFFFFFFL)
    }
}
