package dev.wave.player.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** User-facing sound settings. */
data class FxParams(
    val bassDb: Float = 0f,     // low-shelf gain, -12..+12 dB
    val freqHz: Float = 90f,    // shelf corner, 40..160 Hz
    val punch: Float = 0f,      // virtual-bass harmonics, 0..1 (makes bass audible on phone speakers)
    val preampDb: Float = 0f,   // input level, -12..+6 dB
    val preset: String = "Flat",
)

data class Preset(val name: String, val params: FxParams)

/** One analysed slice of what is playing, for visuals. */
class Visual {
    val bands = FloatArray(AudioFx.BANDS)
    var rms = 0f
    var bass = 0f
}

object AudioFx {
    const val BASS_MIN = -12f
    const val BASS_MAX = 12f
    const val FREQ_MIN = 40f
    const val FREQ_MAX = 160f
    const val PREAMP_MIN = -12f
    const val PREAMP_MAX = 6f
    const val BANDS = 32
    private const val PREFS = "wave_fx"

    val presets = listOf(
        Preset("Flat", FxParams(0f, 90f, 0f, 0f, "Flat")),
        Preset("Warm", FxParams(4f, 120f, 0.15f, -1f, "Warm")),
        Preset("Deep", FxParams(8f, 70f, 0.35f, -3f, "Deep")),
        Preset("Club", FxParams(10f, 90f, 0.55f, -4f, "Club")),
        Preset("Speaker", FxParams(4f, 140f, 0.85f, -2f, "Speaker")),
    )

    @Volatile var params = FxParams()
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        params = FxParams(
            bassDb = p.getFloat("bass", 0f),
            freqHz = p.getFloat("freq", 90f),
            punch = p.getFloat("punch", 0f),
            preampDb = p.getFloat("preamp", 0f),
            preset = p.getString("preset", "Flat") ?: "Flat",
        )
    }

    fun update(context: Context, next: FxParams) {
        params = next.copy(
            bassDb = next.bassDb.coerceIn(BASS_MIN, BASS_MAX),
            freqHz = next.freqHz.coerceIn(FREQ_MIN, FREQ_MAX),
            punch = next.punch.coerceIn(0f, 1f),
            preampDb = next.preampDb.coerceIn(PREAMP_MIN, PREAMP_MAX),
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("bass", params.bassDb).putFloat("freq", params.freqHz)
            .putFloat("punch", params.punch).putFloat("preamp", params.preampDb)
            .putString("preset", params.preset).apply()
    }

    // ---- Spectrum frames: written by the audio thread, read by the UI 60x a second ----
    private const val RING = 96
    private val times = LongArray(RING)
    private val ring = Array(RING) { FloatArray(BANDS) }
    private val rmsRing = FloatArray(RING)
    private val bassRing = FloatArray(RING)
    private var head = 0
    private val lock = Any()

    internal fun publish(timeNs: Long, bands: FloatArray, rms: Float, bass: Float) = synchronized(lock) {
        head = (head + 1) % RING
        times[head] = timeNs
        bands.copyInto(ring[head])
        rmsRing[head] = rms
        bassRing[head] = bass
    }

    /**
     * Copies the frame that is audible right now. Audio is processed ahead of the speaker,
     * so frames are looked up [delayMs] in the past to stay in sync with what you hear.
     * Returns false (and zeros) when nothing has played recently.
     */
    fun snapshot(into: Visual, delayMs: Long = 160): Boolean {
        synchronized(lock) {
        val target = System.nanoTime() - delayMs * 1_000_000
        var idx = -1
        for (k in 0 until RING) {
            val i = (head - k + RING) % RING
            if (times[i] != 0L && times[i] <= target) { idx = i; break }
        }
        if (idx < 0 || target - times[idx] > 400_000_000L) {
            into.bands.fill(0f); into.rms = 0f; into.bass = 0f
            return false
        }
        ring[idx].copyInto(into.bands)
        into.rms = rmsRing[idx]
        into.bass = bassRing[idx]
        return true
        }
    }
}

/** 2nd-order IIR filter (RBJ cookbook), one state set per channel. */
private class Biquad(channels: Int) {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private val x1 = DoubleArray(channels); private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels); private val y2 = DoubleArray(channels)

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]; x1[ch] = x; y2[ch] = y1[ch]; y1[ch] = y
        return y
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun lowShelf(fs: Int, f0: Double, db: Double) {
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * f0 / fs
        val cw = cos(w)
        val sa = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        set(
            a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
            (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
        )
    }

    fun lowPass(fs: Int, f0: Double, q: Double = 0.7071) {
        val w = 2 * PI * f0 / fs; val cw = cos(w); val al = sin(w) / (2 * q)
        set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al)
    }

    fun highPass(fs: Int, f0: Double, q: Double = 0.7071) {
        val w = 2 * PI * f0 / fs; val cw = cos(w); val al = sin(w) / (2 * q)
        set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
    }
}

/**
 * The sound engine, inside ExoPlayer's audio pipeline (16-bit PCM):
 *
 *   preamp -> low-shelf bass (freq + gain) -> + virtual-bass harmonics (punch)
 *          -> smooth peak limiter -> soft clip -> speaker
 *
 * "Punch" generates harmonics of the bass notes. Small phone speakers can't play 50-80 Hz,
 * but your ear rebuilds the missing low note from its harmonics, so bass feels deeper
 * without distorting the speaker. Every output frame also feeds a live FFT for the visuals.
 */
class SoundProcessor : BaseAudioProcessor() {
    private var fs = 44_100
    private var channels = 2

    private var shelf = Biquad(2)
    private val harmIn = Biquad(1)
    private val harmHp = Biquad(1)
    private val harmLp = Biquad(1)

    // Smoothed parameters (no zipper noise when sliders move)
    private var curBass = 0.0; private var curFreq = 90.0; private var curPunch = 0.0; private var curPre = 0.0
    private var designedBass = Double.NaN; private var designedFreq = Double.NaN
    private var limGain = 1.0
    private var release = 0.0

    // Spectrum analysis
    private val fftN = 1024
    private val window = DoubleArray(fftN) { 0.5 - 0.5 * cos(2 * PI * it / (fftN - 1)) }
    private val history = FloatArray(fftN)
    private var histPos = 0
    private var hopCount = 0
    private val re = DoubleArray(fftN); private val im = DoubleArray(fftN)
    private val bands = FloatArray(AudioFx.BANDS)
    private var bandEdges = IntArray(AudioFx.BANDS + 1)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        fs = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onFlush() {
        shelf = Biquad(channels)
        designedBass = Double.NaN; designedFreq = Double.NaN
        limGain = 1.0
        release = 1 - exp(-1.0 / (0.15 * fs))
        bandEdges = IntArray(AudioFx.BANDS + 1) { i ->
            val hz = 30.0 * (min(16_000.0, fs / 2.0) / 30.0).pow(i.toDouble() / AudioFx.BANDS)
            (hz * fftN / fs).toInt().coerceIn(1, fftN / 2 - 1)
        }
    }

    override fun onReset() {
        fs = 44_100
        channels = 2
    }

    private fun smoothParams() {
        val p = AudioFx.params
        fun ease(cur: Double, target: Float) = if (abs(cur - target) < 0.01) target.toDouble() else cur + (target - cur) * 0.35
        curBass = ease(curBass, p.bassDb)
        curFreq = ease(curFreq, p.freqHz)
        curPunch = ease(curPunch, p.punch)
        curPre = ease(curPre, p.preampDb)
        if (curBass != designedBass || curFreq != designedFreq) {
            shelf.lowShelf(fs, curFreq, curBass)
            harmIn.lowPass(fs, curFreq * 1.3)
            harmHp.highPass(fs, curFreq * 0.9)
            harmLp.lowPass(fs, curFreq * 4.0)
            designedBass = curBass; designedFreq = curFreq
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        smoothParams()

        val bypass = abs(curBass) < 0.05 && curPunch < 0.005 && abs(curPre) < 0.05
        val pre = 10.0.pow(curPre / 20.0)
        val harmGain = curPunch * 1.6
        val frames = remaining / (2 * channels)
        val frame = DoubleArray(channels)
        val callTime = System.nanoTime()
        val hop = fs / 60

        for (f in 0 until frames) {
            var mono = 0.0
            for (ch in 0 until channels) {
                frame[ch] = inputBuffer.getShort() / 32768.0
                mono += frame[ch]
            }
            mono /= channels

            if (!bypass) {
                // Virtual bass: isolate lows, bend them to create harmonics, keep only the harmonics.
                var harm = 0.0
                if (harmGain > 0.0) {
                    val low = harmIn.process(mono * pre, 0)
                    val bent = tanh(low * 4.0) * 0.6 + abs(low) * 0.8
                    harm = harmLp.process(harmHp.process(bent, 0), 0) * harmGain
                }
                var peak = 0.0
                for (ch in 0 until channels) {
                    val y = shelf.process(frame[ch] * pre, ch) + harm
                    frame[ch] = y
                    peak = max(peak, abs(y))
                }
                // Peak limiter: instant attack, 150 ms release.
                val want = if (peak * limGain > 0.95) 0.95 / peak else 1.0
                limGain = if (want < limGain) want else limGain + (want - limGain) * release
            }

            var outMono = 0.0
            for (ch in 0 until channels) {
                var s = if (bypass) frame[ch] else frame[ch] * limGain
                if (!bypass && abs(s) > 0.9) s = (if (s < 0) -1 else 1) * (0.9 + 0.1 * tanh((abs(s) - 0.9) / 0.1))
                outMono += s
                out.putShort((s * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
            }

            history[histPos] = (outMono / channels).toFloat()
            histPos = (histPos + 1) % fftN
            if (++hopCount >= hop) {
                hopCount = 0
                analyse(callTime + f * 1_000_000_000L / fs)
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    // ---------- FFT spectrum ----------

    private fun analyse(timeNs: Long) {
        var sumSq = 0.0
        for (i in 0 until fftN) {
            val s = history[(histPos + i) % fftN].toDouble()
            sumSq += s * s
            re[i] = s * window[i]
            im[i] = 0.0
        }
        fft()
        var bassSum = 0f
        var bassCount = 0
        for (b in 0 until AudioFx.BANDS) {
            val lo = bandEdges[b]
            val hi = max(lo + 1, bandEdges[b + 1])
            var mag = 0.0
            for (k in lo until hi) mag = max(mag, sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20 * log10(mag * 4.0 / fftN + 1e-9)
            val v = ((db + 66) / 58).toFloat().coerceIn(0f, 1f)
            bands[b] = v
            if (hi * fs / fftN < 160) { bassSum += v; bassCount++ }
        }
        val rms = sqrt(sumSq / fftN).toFloat()
        AudioFx.publish(timeNs, bands, rms, if (bassCount > 0) bassSum / bassCount else 0f)
    }

    private fun fft() {
        val n = fftN
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
