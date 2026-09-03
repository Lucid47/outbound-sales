package com.lucid47.soheeyagaja.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordedAudioTranscriberTest {
    @Test
    fun downmixesStereoPcmByAveragingChannels() {
        val stereo = pcm16(1_000, 3_000, -2_000, 2_000)

        val mono = downmixPcm16(stereo, channelCount = 2)

        assertArrayEquals(pcm16(2_000, 0), mono)
    }

    @Test
    fun extractsCompletedAndFinalVoskTextSafely() {
        assertEquals("안녕하세요 고객님", extractVoskText("{\"text\":\" 안녕하세요   고객님 \"}"))
        assertNull(extractVoskText("{\"text\":\"\"}"))
        assertNull(extractVoskText("not-json"))
    }

    private fun pcm16(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
    }
}
