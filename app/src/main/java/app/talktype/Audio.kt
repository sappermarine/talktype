package app.talktype

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000

/** Captures 16 kHz mono PCM from the microphone into memory. */
class AudioRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false

    val isRecording get() = running

    /** Starts capturing. [onLevel] gets a 0..1 loudness value per buffer (called off the main thread). */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(maxSeconds: Int, onLevel: (Float) -> Unit, onLimit: () -> Unit): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return false
        }
        synchronized(chunks) { chunks.clear() }
        record = rec
        running = true
        thread = Thread({
            val buf = ShortArray(SAMPLE_RATE / 20) // 50 ms
            var total = 0L
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                onLevel(min(1f, (sqrt(sum / n) / 6000.0).toFloat()))
                total += n
                if (total >= maxSeconds.toLong() * SAMPLE_RATE) {
                    onLimit()
                    break
                }
            }
        }, "talktype-rec").also { it.start() }
        return true
    }

    /** Stops capturing and returns everything recorded since [start]. */
    fun stop(): ShortArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        synchronized(chunks) {
            val out = ShortArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            chunks.clear()
            return out
        }
    }
}

object Pcm {
    fun toFloats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        bb.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        val out = ByteArrayOutputStream(44 + dataLen)
        out.write(bb.array())
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(pcm)
        out.write(body.array())
        return out.toByteArray()
    }

    /**
     * Splits long audio into pieces of at most [maxSeconds], cutting at the quietest moment in the
     * last few seconds of each piece so words are not chopped in half. Whisper only sees 30 s at a time.
     */
    fun split(samples: FloatArray, maxSeconds: Int = 28, searchSeconds: Int = 8): List<FloatArray> {
        val max = maxSeconds * SAMPLE_RATE
        if (samples.size <= max) return listOf(samples)
        val frame = SAMPLE_RATE / 10
        val out = ArrayList<FloatArray>()
        var start = 0
        while (samples.size - start > max) {
            val from = start + max - searchSeconds * SAMPLE_RATE
            var best = start + max - frame
            var bestEnergy = Float.MAX_VALUE
            var i = from
            while (i + frame <= start + max) {
                var e = 0f
                for (j in i until i + frame) e += samples[j] * samples[j]
                if (e < bestEnergy) {
                    bestEnergy = e
                    best = i
                }
                i += frame
            }
            val cut = best + frame / 2
            out.add(samples.copyOfRange(start, cut))
            start = cut
        }
        out.add(samples.copyOfRange(start, samples.size))
        return out
    }
}
