// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** 16-bit mono PCM output. [write] blocks when the device buffer is full. Copied from Beebslop. */
class AudioOutput private constructor(private val line: SourceDataLine) {
    private var bytes = ByteArray(0)

    fun write(samples: FloatArray, count: Int, volume: Float) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = (samples[i] * volume * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        line.write(bytes, 0, count * 2)
    }

    fun close() {
        line.stop()
        line.flush()
        line.close()
    }

    companion object {
        /** Opens the default device, or returns null if there is none. */
        fun open(sampleRate: Int, bufferSamples: Int): AudioOutput? = try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, bufferSamples * 2)
            line.start()
            AudioOutput(line)
        } catch (e: Exception) {
            System.err.println("Sound disabled: ${e.message}")
            null
        }
    }
}
