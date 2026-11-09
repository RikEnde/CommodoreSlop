// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * A 5¼" floppy disk as the 1541's head sees it: up to 42 tracks, each a ring of
 * bytes in Commodore's GCR (group code recording), passing the head at a rate
 * set by the track's speed zone.
 *
 * On disk, every 4 bits are written as 5 ([GCR]), so that there are never more
 * than two 0 bits in a row; ten or more 1 bits in a row form a **sync** mark,
 * which the drive's hardware detects. Each sector is a sync, a header block
 * (`$08`, checksum, sector, track, the disk ID's two bytes, `$0F`, `$0F`: 10
 * GCR bytes), a gap, another sync, and a data block (`$07`, 256 bytes,
 * checksum, two padding bytes: 325 GCR bytes), then a gap before the next
 * sector. The tracks are laid out as the 1541 DOS formats them:
 *
 * | Tracks | Sectors | Speed zone | Bytes a track | µs a byte |
 * |---|---|---|---|---|
 * | 1-17 | 21 | 3 | 7692 | 26 |
 * | 18-24 | 19 | 2 | 7142 | 28 |
 * | 25-30 | 18 | 1 | 6666 | 30 |
 * | 31-42 | 17 | 0 | 6250 | 32 |
 *
 * [parseD64] builds the tracks from a D64 image (the 256-byte sectors in order,
 * 35 or 40 tracks, with or without the error bytes some images append, which
 * are ignored). [toD64] decodes them back, so whatever the DOS has written
 * (files, a new directory, a whole format) ends up in the image. A sector that
 * no longer decodes keeps the contents it had.
 *
 * Bytes stay aligned with the ring: the drive reads and writes whole bytes, as
 * the 1541's DOS always does. Copy protections that depend on bit-level
 * timing, odd track lengths or half-tracks are not reproduced.
 */
class Disk private constructor(private val sectors: ByteArray, val trackCount: Int) {
    private val tracks: Array<ByteArray> = Array(MAX_TRACKS) { index ->
        val track = index + 1
        if (track <= trackCount) encodeTrack(track) else ByteArray(trackLength(track))
    }

    /** Writes fail with `WRITE PROTECT ON`, as with the notch covered. */
    var writeProtected: Boolean = false

    /** Counts writes to the surface, so a caller can tell when to save. */
    var changes: Long = 0
        private set

    /** The ring of GCR bytes for [track], 1-42. */
    fun track(track: Int): ByteArray = tracks[track - 1]

    /** Write [value] at [position] of [track], as the head does in write mode. */
    fun write(track: Int, position: Int, value: Int) {
        val ring = tracks[track - 1]
        ring[position % ring.size] = value.toByte()
        changes++
    }

    /** The disk as a D64 image, with [trackCount] tracks and no error bytes. */
    fun toD64(): ByteArray {
        val image = sectors.copyOf()
        for (track in 1..trackCount) decodeTrack(track, image)
        return image
    }

    /** The disk's name from its directory header, for display. */
    val name: String get() {
        val d64 = toD64()
        val bam = sectorOffset(DIRECTORY_TRACK, 0)
        return (0 until 16).map { d64[bam + 0x90 + it].toInt() and 0xFF }
            .takeWhile { it != 0xA0 }
            .map { if (it in 0x20..0x7E) it.toChar() else '?' }
            .joinToString("")
    }

    private fun encodeTrack(track: Int): ByteArray {
        val ring = ByteArray(trackLength(track))
        val count = sectorsPerTrack(track)
        val id1 = sectors[sectorOffset(DIRECTORY_TRACK, 0) + 0xA2].toInt() and 0xFF
        val id2 = sectors[sectorOffset(DIRECTORY_TRACK, 0) + 0xA3].toInt() and 0xFF
        val sectorBytes = SYNC_BYTES + 10 + HEADER_GAP + SYNC_BYTES + 325
        val gap = (ring.size - count * sectorBytes) / count
        var position = 0
        fun put(bytes: ByteArray) {
            bytes.copyInto(ring, position)
            position += bytes.size
        }
        fun fill(value: Int, count: Int) {
            ring.fill(value.toByte(), position, position + count)
            position += count
        }
        for (sector in 0 until count) {
            fill(0xFF, SYNC_BYTES)
            val checksum = sector xor track xor id1 xor id2
            put(GCR.encode(intArrayOf(0x08, checksum, sector, track, id2, id1, 0x0F, 0x0F)))
            fill(0x55, HEADER_GAP)
            fill(0xFF, SYNC_BYTES)
            val offset = sectorOffset(track, sector)
            val block = IntArray(260)
            block[0] = 0x07
            var sum = 0
            for (i in 0 until 256) {
                val value = sectors[offset + i].toInt() and 0xFF
                block[1 + i] = value
                sum = sum xor value
            }
            block[257] = sum
            put(GCR.encode(block))
            fill(0x55, if (sector == count - 1) ring.size - position else gap)
        }
        return ring
    }

    /** Finds each sector's header and data block on [track] and copies the data into [image]. */
    private fun decodeTrack(track: Int, image: ByteArray) {
        val ring = tracks[track - 1]
        val size = ring.size
        fun at(i: Int) = ring[i % size].toInt() and 0xFF
        // Start just after a sync, so no block straddles the start of the scan.
        var start = 0
        while (start < size && !(at(start) == 0xFF && at(start + 1) != 0xFF)) start++
        if (start == size) return
        var i = start + 1
        var pendingSector = -1
        while (i < start + 1 + size) {
            if (at(i) == 0xFF) {
                i++
                continue
            }
            // A sync ends at i: what follows is a block.
            if (at(i - 1) == 0xFF) {
                val first = GCR.decode(IntArray(5) { at(i + it) })
                if (first != null && first[0] == 0x08) {
                    val header = GCR.decode(IntArray(10) { at(i + it) })
                    pendingSector = if (header != null && header[3] == track &&
                        header[2] < sectorsPerTrack(track)
                    ) header[2] else -1
                    i += 10
                    continue
                }
                if (first != null && first[0] == 0x07 && pendingSector >= 0) {
                    val data = GCR.decode(IntArray(325) { at(i + it) })
                    if (data != null) {
                        val offset = sectorOffset(track, pendingSector)
                        for (b in 0 until 256) image[offset + b] = data[1 + b].toByte()
                    }
                    pendingSector = -1
                    i += 325
                    continue
                }
            }
            i++
        }
    }

    companion object {
        const val MAX_TRACKS = 42
        const val DIRECTORY_TRACK = 18
        const val SECTOR_SIZE = 256
        private const val SYNC_BYTES = 5
        private const val HEADER_GAP = 9

        /** Sectors in a 35-track image: 174,848 bytes. */
        const val SECTORS_35 = 683

        /** Sectors in a 40-track image: 196,608 bytes. */
        const val SECTORS_40 = 768

        fun sectorsPerTrack(track: Int): Int = when {
            track <= 17 -> 21
            track <= 24 -> 19
            track <= 30 -> 18
            else -> 17
        }

        /** The speed zone the DOS selects for [track], 0-3; 3 is the fastest, on the outer tracks. */
        fun speedZone(track: Int): Int = when {
            track <= 17 -> 3
            track <= 24 -> 2
            track <= 30 -> 1
            else -> 0
        }

        /** Bytes in one turn of [track] at its zone's bit rate, at 300 rpm. */
        fun trackLength(track: Int): Int = TRACK_LENGTHS[speedZone(track)]

        /** 1 MHz cycles for one byte to pass the head in speed [zone]. */
        fun cyclesPerByte(zone: Int): Int = 32 - 2 * zone

        private val TRACK_LENGTHS = intArrayOf(6250, 6666, 7142, 7692)

        /** Where [track]'s [sector] starts in a D64 image. */
        fun sectorOffset(track: Int, sector: Int): Int {
            var offset = 0
            for (t in 1 until track) offset += sectorsPerTrack(t)
            return (offset + sector) * SECTOR_SIZE
        }

        /** @throws IllegalArgumentException if [image] is not a D64 image of 35 or 40 tracks. */
        fun parseD64(image: ByteArray): Disk {
            val tracks = when (image.size) {
                SECTORS_35 * SECTOR_SIZE, SECTORS_35 * (SECTOR_SIZE + 1) -> 35
                SECTORS_40 * SECTOR_SIZE, SECTORS_40 * (SECTOR_SIZE + 1) -> 40
                else -> throw IllegalArgumentException("Not a D64 image: ${image.size} bytes")
            }
            val sectors = if (tracks == 35) SECTORS_35 else SECTORS_40
            return Disk(image.copyOf(sectors * SECTOR_SIZE), tracks)
        }

        /**
         * A freshly formatted, empty 35-track disk, as `OPEN 15,8,15,"N:NAME,ID"`
         * leaves it: a BAM with every sector free except the directory's two, and
         * an empty directory.
         */
        fun blank(name: String = "BLANK", id: String = "00"): Disk {
            val image = ByteArray(SECTORS_35 * SECTOR_SIZE)
            val bam = sectorOffset(DIRECTORY_TRACK, 0)
            image[bam] = DIRECTORY_TRACK.toByte()
            image[bam + 1] = 1
            image[bam + 2] = 0x41 // 'A': the 1541's disk format
            for (track in 1..35) {
                val entry = bam + 4 * track
                val used = if (track == DIRECTORY_TRACK) setOf(0, 1) else emptySet()
                val free = (0 until sectorsPerTrack(track)).filter { it !in used }
                image[entry] = free.size.toByte()
                for (sector in free) {
                    val byte = entry + 1 + sector / 8
                    image[byte] = (image[byte].toInt() or (1 shl (sector % 8))).toByte()
                }
            }
            for (i in 0x90 until 0xAB) image[bam + i] = 0xA0.toByte()
            name.uppercase().take(16).forEachIndexed { i, c -> image[bam + 0x90 + i] = c.code.toByte() }
            id.uppercase().padEnd(2, '0').take(2).forEachIndexed { i, c -> image[bam + 0xA2 + i] = c.code.toByte() }
            image[bam + 0xA5] = '2'.code.toByte()
            image[bam + 0xA6] = 'A'.code.toByte()
            val directory = sectorOffset(DIRECTORY_TRACK, 1)
            image[directory + 1] = 0xFF.toByte()
            return Disk(image, 35)
        }
    }
}

/** Commodore's 4-to-5 group code: each nybble becomes 5 bits, never with three 0s in a row. */
object GCR {
    private val CODES = intArrayOf(
        0x0A, 0x0B, 0x12, 0x13, 0x0E, 0x0F, 0x16, 0x17, 0x09, 0x19, 0x1A, 0x1B, 0x0D, 0x1D, 0x1E, 0x15,
    )
    private val DECODE = IntArray(32) { -1 }.also { table -> CODES.forEachIndexed { nybble, code -> table[code] = nybble } }

    /** Encodes bytes, a multiple of 4, into 5 GCR bytes for each 4. */
    fun encode(bytes: IntArray): ByteArray {
        require(bytes.size % 4 == 0)
        val out = ByteArray(bytes.size / 4 * 5)
        for (group in 0 until bytes.size / 4) {
            var bits = 0L
            for (i in 0 until 4) {
                val b = bytes[group * 4 + i]
                bits = (bits shl 10) or (CODES[b shr 4].toLong() shl 5) or CODES[b and 0x0F].toLong()
            }
            for (i in 0 until 5) out[group * 5 + i] = (bits shr (32 - 8 * i)).toByte()
        }
        return out
    }

    /** Decodes GCR bytes, a multiple of 5; null if any 5-bit code is invalid. */
    fun decode(gcr: IntArray): IntArray? {
        require(gcr.size % 5 == 0)
        val out = IntArray(gcr.size / 5 * 4)
        for (group in 0 until gcr.size / 5) {
            var bits = 0L
            for (i in 0 until 5) bits = (bits shl 8) or (gcr[group * 5 + i].toLong() and 0xFF)
            for (i in 0 until 8) {
                val nybble = DECODE[((bits shr (35 - 5 * i)) and 0x1F).toInt()]
                if (nybble < 0) return null
                val index = group * 4 + i / 2
                out[index] = if (i % 2 == 0) nybble shl 4 else out[index] or nybble
            }
        }
        return out
    }
}
