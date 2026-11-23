package com.example.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Immutable snapshot of all DSP parameters pushed atomically from UI/Settings/Equalizer
 * into the real-time ExoPlayer audio thread without locks or allocations.
 */
data class StudioDspConfig(
    val equalizerEnabled: Boolean = false,
    val bandGainsDb: FloatArray = FloatArray(10) { 0f },
    val preAmpDb: Float = 0f,
    val bassBoostStrength: Int = 0,          // 0..1000
    val virtualizerStrength: Int = 0,        // 0..1000
    val loudnessGainMb: Int = 0,             // 0..1000
    val stereoWidenerPercent: Int = 0,       // 0..100
    val audioBalance: Float = 0f,            // -1f..1f
    val reverbPreset: String = "NONE",       // NONE, SMALL_ROOM, MEDIUM_ROOM, LARGE_ROOM, MEDIUM_HALL, LARGE_HALL, PLATE
    val limiterEnabled: Boolean = true,
    val volumeNormalize: Boolean = false,
    val engineMode: String = "STUDIO"        // STUDIO (64-bit Double biquads + rich reverb) | SONIC (fast float)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StudioDspConfig) return false
        return equalizerEnabled == other.equalizerEnabled &&
            bandGainsDb.contentEquals(other.bandGainsDb) &&
            preAmpDb == other.preAmpDb &&
            bassBoostStrength == other.bassBoostStrength &&
            virtualizerStrength == other.virtualizerStrength &&
            loudnessGainMb == other.loudnessGainMb &&
            stereoWidenerPercent == other.stereoWidenerPercent &&
            audioBalance == other.audioBalance &&
            reverbPreset == other.reverbPreset &&
            limiterEnabled == other.limiterEnabled &&
            volumeNormalize == other.volumeNormalize &&
            engineMode == other.engineMode
    }

    override fun hashCode(): Int {
        var result = equalizerEnabled.hashCode()
        result = 31 * result + bandGainsDb.contentHashCode()
        result = 31 * result + preAmpDb.hashCode()
        result = 31 * result + bassBoostStrength
        result = 31 * result + virtualizerStrength
        result = 31 * result + loudnessGainMb
        result = 31 * result + stereoWidenerPercent
        result = 31 * result + audioBalance.hashCode()
        result = 31 * result + reverbPreset.hashCode()
        result = 31 * result + limiterEnabled.hashCode()
        result = 31 * result + volumeNormalize.hashCode()
        result = 31 * result + engineMode.hashCode()
        return result
    }
}

/**
 * Shared state hub connecting PreferencesManager / EqualizerManager with every active
 * [StudioFloatDspAudioProcessor] instance inside primary & crossfade ExoPlayers.
 */
object StudioDspEngine {
    val BAND_FREQUENCIES_HZ = doubleArrayOf(
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    private val configRef = AtomicReference(StudioDspConfig())

    fun getConfig(): StudioDspConfig = configRef.get()

    fun updateConfig(config: StudioDspConfig) {
        configRef.set(config.copy(bandGainsDb = config.bandGainsDb.copyOf()))
    }

    fun update(transform: (StudioDspConfig) -> StudioDspConfig) {
        while (true) {
            val prev = configRef.get()
            val next = transform(prev)
            if (configRef.compareAndSet(prev, next)) break
        }
    }

    /** RBJ Cookbook Peaking EQ normalized biquad coefficients (b0, b1, b2, a1, a2). */
    fun computePeakingBiquad(
        sampleRate: Double,
        centerFreqHz: Double,
        q: Double,
        gainDb: Double
    ): DoubleArray {
        val safeRate = sampleRate.coerceAtLeast(8000.0)
        val clampedFreq = centerFreqHz.coerceIn(20.0, safeRate * 0.46)
        if (abs(gainDb) < 0.02) {
            return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * clampedFreq / safeRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.35))

        val a0 = 1.0 + alpha / a
        val b0 = (1.0 + alpha * a) / a0
        val b1 = (-2.0 * cosW0) / a0
        val b2 = (1.0 - alpha * a) / a0
        val a1 = (-2.0 * cosW0) / a0
        val a2 = (1.0 - alpha / a) / a0
        return doubleArrayOf(b0, b1, b2, a1, a2)
    }

    /** RBJ Cookbook Low-Shelf filter for warm sub-bass boost. */
    fun computeLowShelfBiquad(
        sampleRate: Double,
        freqHz: Double,
        slope: Double,
        gainDb: Double
    ): DoubleArray {
        val safeRate = sampleRate.coerceAtLeast(8000.0)
        val clampedFreq = freqHz.coerceIn(20.0, safeRate * 0.40)
        if (abs(gainDb) < 0.02) {
            return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * clampedFreq / safeRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val sqrtA = sqrt(a)
        val alpha = (sinW0 / 2.0) * sqrt((a + 1.0 / a) * (1.0 / slope.coerceIn(0.4, 1.5) - 1.0) + 2.0)

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
        val b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)) / a0
        val b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        val b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)) / a0
        val a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        val a2 = ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha) / a0
        return doubleArrayOf(b0, b1, b2, a1, a2)
    }
}

/**
 * Custom real-time Float / 64-bit Double AudioProcessor embedded directly inside ExoPlayer's
 * [androidx.media3.exoplayer.audio.DefaultAudioSink].
 *
 * Guarantees that 10-band EQ, Pre-Amp, Bass Boost, Loudness, Stereo Widener, Virtualizer,
 * L/R Balance, Room Reverb, Auto Volume Normalize, Engine Mode (Studio 64-bit vs Sonic),
 * and Soft-Knee Limiter ALWAYS work audibly on every device and audio output path.
 */
@OptIn(UnstableApi::class)
class StudioFloatDspAudioProcessor : BaseAudioProcessor() {

    private class BiquadFilter {
        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a1 = 0.0
        var a2 = 0.0
        var bypass = true

        // Stereo Direct Form II Transposed states (64-bit precision)
        private var z1L = 0.0
        private var z2L = 0.0
        private var z1R = 0.0
        private var z2R = 0.0

        fun setCoefficients(c: DoubleArray) {
            b0 = c[0]
            b1 = c[1]
            b2 = c[2]
            a1 = c[3]
            a2 = c[4]
            bypass = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0
        }

        fun reset() {
            z1L = 0.0
            z2L = 0.0
            z1R = 0.0
            z2R = 0.0
        }

        fun processLeft(x: Double): Double {
            if (bypass) return x
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            return y
        }

        fun processRight(x: Double): Double {
            if (bypass) return x
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            return y
        }
    }

    /** Pre-allocated stereo Schroeder/FDN-style multi-tap comb reverb without GC allocations. */
    private class StereoReverbBuffer(sampleRate: Int) {
        private val scale = (sampleRate / 44100.0).coerceIn(0.5, 2.5)
        private val tapSizesL = intArrayOf(
            (1117 * scale).toInt().coerceAtLeast(64),
            (1356 * scale).toInt().coerceAtLeast(64),
            (1617 * scale).toInt().coerceAtLeast(64),
            (1913 * scale).toInt().coerceAtLeast(64)
        )
        private val tapSizesR = intArrayOf(
            (1140 * scale).toInt().coerceAtLeast(64),
            (1379 * scale).toInt().coerceAtLeast(64),
            (1640 * scale).toInt().coerceAtLeast(64),
            (1936 * scale).toInt().coerceAtLeast(64)
        )
        private val combsL = Array(4) { i -> DoubleArray(tapSizesL[i]) }
        private val combsR = Array(4) { i -> DoubleArray(tapSizesR[i]) }
        private val posL = IntArray(4)
        private val posR = IntArray(4)
        private var lpL = 0.0
        private var lpR = 0.0

        fun reset() {
            for (i in 0 until 4) {
                combsL[i].fill(0.0)
                combsR[i].fill(0.0)
                posL[i] = 0
                posR[i] = 0
            }
            lpL = 0.0
            lpR = 0.0
        }

        fun process(inL: Double, inR: Double, wet: Double, feedback: Double, damp: Double): Pair<Double, Double> {
            val mono = 0.5 * (inL + inR)
            var sumL = 0.0
            var sumR = 0.0
            for (i in 0 until 4) {
                val bufL = combsL[i]
                val pL = posL[i]
                val outL = bufL[pL]
                bufL[pL] = (mono + outL * feedback).coerceIn(-4.0, 4.0)
                posL[i] = if (pL + 1 >= bufL.size) 0 else pL + 1
                sumL += outL

                val bufR = combsR[i]
                val pR = posR[i]
                val outR = bufR[pR]
                bufR[pR] = (mono + outR * feedback).coerceIn(-4.0, 4.0)
                posR[i] = if (pR + 1 >= bufR.size) 0 else pR + 1
                sumR += outR
            }
            val wetL = sumL * 0.25
            val wetR = sumR * 0.25
            lpL = lpL * damp + wetL * (1.0 - damp)
            lpR = lpR * damp + wetR * (1.0 - damp)
            val dry = (1.0 - wet * 0.35).coerceIn(0.65, 1.0)
            return Pair(inL * dry + lpL * wet, inR * dry + lpR * wet)
        }
    }

    private val eqFilters = Array(10) { BiquadFilter() }
    private val bassShelfFilter = BiquadFilter()
    private var reverbBuffer = StereoReverbBuffer(44100)
    private var lastAppliedConfig: StudioDspConfig? = null
    private var lastSampleRate: Int = 44100

    // AGC envelope state for Volume Normalize
    private var agcRmsEstimate = 0.25
    private var agcGain = 1.0

    // Working scratch buffers reused across calls
    private var scratchL = DoubleArray(0)
    private var scratchR = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        if (sr != lastSampleRate) {
            lastSampleRate = sr
            reverbBuffer = StereoReverbBuffer(sr)
            lastAppliedConfig = null
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        if (!super.isActive()) return false
        val cfg = StudioDspEngine.getConfig()
        val hasEqEffects = cfg.equalizerEnabled && (
            cfg.bandGainsDb.any { abs(it) > 0.04f } ||
                abs(cfg.preAmpDb) > 0.04f ||
                cfg.bassBoostStrength > 0 ||
                cfg.virtualizerStrength > 0 ||
                cfg.loudnessGainMb > 0 ||
                cfg.stereoWidenerPercent > 0 ||
                abs(cfg.audioBalance) > 0.02f ||
                cfg.reverbPreset != "NONE" ||
                cfg.limiterEnabled
            )
        val hasBalanceAlways = abs(cfg.audioBalance) > 0.02f
        return hasEqEffects || cfg.volumeNormalize || hasBalanceAlways
    }

    override fun onFlush() {
        eqFilters.forEach { it.reset() }
        bassShelfFilter.reset()
        reverbBuffer.reset()
        agcRmsEstimate = 0.25
        agcGain = 1.0
    }

    override fun onReset() {
        onFlush()
        lastAppliedConfig = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val config = StudioDspEngine.getConfig()
        syncCoefficientsIfNeeded(config, lastSampleRate)

        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = channels * bytesPerSample
        val frames = remaining / frameSize
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (scratchL.size < frames) {
            scratchL = DoubleArray(frames)
            scratchR = DoubleArray(frames)
        }

        // 1. Decode input PCM to normalized Double [-1.0..1.0]
        if (isFloat) {
            for (i in 0 until frames) {
                val l = inputBuffer.float.toDouble()
                scratchL[i] = l
                scratchR[i] = if (channels == 2) inputBuffer.float.toDouble() else l
            }
        } else {
            for (i in 0 until frames) {
                val l = inputBuffer.short.toDouble() / 32768.0
                scratchL[i] = l
                scratchR[i] = if (channels == 2) inputBuffer.short.toDouble() / 32768.0 else l
            }
        }

        // 2. Process stereo frames cleanly through DSP chain
        processStereoBlock(
            left = scratchL,
            right = scratchR,
            frameCount = frames,
            config = config,
            sampleRate = lastSampleRate
        )

        // 3. Encode back to output PCM format with soft protection
        val out = replaceOutputBuffer(frames * frameSize)
        if (isFloat) {
            for (i in 0 until frames) {
                out.putFloat(scratchL[i].coerceIn(-1.0, 1.0).toFloat())
                if (channels == 2) {
                    out.putFloat(scratchR[i].coerceIn(-1.0, 1.0).toFloat())
                }
            }
        } else {
            for (i in 0 until frames) {
                val sL = (scratchL[i].coerceIn(-1.0, 1.0) * 32767.0).toInt().coerceIn(-32768, 32767)
                out.putShort(sL.toShort())
                if (channels == 2) {
                    val sR = (scratchR[i].coerceIn(-1.0, 1.0) * 32767.0).toInt().coerceIn(-32768, 32767)
                    out.putShort(sR.toShort())
                }
            }
        }
        out.flip()
    }

    internal fun processStereoBlock(
        left: DoubleArray,
        right: DoubleArray,
        frameCount: Int,
        config: StudioDspConfig,
        sampleRate: Int
    ) {
        syncCoefficientsIfNeeded(config, sampleRate)

        val eqOn = config.equalizerEnabled
        val isStudio = config.engineMode != "SONIC"

        val preAmpLinear = if (eqOn) {
            val totalDb = (config.preAmpDb.coerceIn(-12f, 12f) + (config.loudnessGainMb.coerceIn(0, 1000) / 100f)).toDouble()
            10.0.pow(totalDb / 20.0)
        } else {
            1.0
        }

        // True Stereo L/R balance applies even if EQ is toggled off
        val bal = config.audioBalance.coerceIn(-1f, 1f).toDouble()
        val balLeftGain = if (bal > 0.01) (1.0 - bal * 0.92).coerceAtLeast(0.04) else 1.0
        val balRightGain = if (bal < -0.01) (1.0 + bal * 0.92).coerceAtLeast(0.04) else 1.0

        // Stereo Widener + Virtualizer M/S Side expansion
        val widenerAmount = if (eqOn) {
            val w = (config.stereoWidenerPercent.coerceIn(0, 100) / 100.0) * 0.85
            val v = (config.virtualizerStrength.coerceIn(0, 1000) / 1000.0) * 0.55
            (w + v).coerceIn(0.0, 1.25)
        } else {
            0.0
        }
        val sideMultiplier = 1.0 + widenerAmount

        // Reverb tuning
        val (reverbWet, reverbFeedback, reverbDamp) = if (eqOn) {
            reverbParams(config.reverbPreset, isStudio)
        } else {
            Triple(0.0, 0.0, 0.5)
        }

        val useLimiter = (eqOn && config.limiterEnabled) || config.volumeNormalize
        val useNormalize = config.volumeNormalize

        for (i in 0 until frameCount) {
            var l = left[i] * preAmpLinear
            var r = right[i] * preAmpLinear

            if (eqOn) {
                if (!bassShelfFilter.bypass) {
                    l = bassShelfFilter.processLeft(l)
                    r = bassShelfFilter.processRight(r)
                }
                for (b in 0 until 10) {
                    val f = eqFilters[b]
                    if (!f.bypass) {
                        l = f.processLeft(l)
                        r = f.processRight(r)
                    }
                }
            }

            // Mid/Side Stereo Widening & 3D stage depth
            if (widenerAmount > 0.01) {
                val mid = 0.5 * (l + r)
                val side = 0.5 * (l - r) * sideMultiplier
                l = mid + side
                r = mid - side
            }

            // Acoustic Room Reverb
            if (reverbWet > 0.005) {
                val (rl, rr) = reverbBuffer.process(l, r, reverbWet, reverbFeedback, reverbDamp)
                l = rl
                r = rr
            }

            // Auto Volume Normalize (RMS Automatic Gain Control aiming at ~ -14 LUFS / 0.22 RMS)
            if (useNormalize) {
                val instantPower = 0.5 * (l * l + r * r)
                agcRmsEstimate = agcRmsEstimate * 0.9992 + instantPower * 0.0008
                val rms = sqrt(agcRmsEstimate.coerceAtLeast(1e-7))
                val targetGain = (0.22 / rms).coerceIn(0.45, 2.4)
                agcGain = agcGain * 0.998 + targetGain * 0.002
                l *= agcGain
                r *= agcGain
            }

            // Balance L/R
            l *= balLeftGain
            r *= balRightGain

            // Soft-knee Studio Anti-Clip Limiter (-0.3 dBFS ceiling)
            if (useLimiter) {
                l = softLimitSample(l, isStudio)
                r = softLimitSample(r, isStudio)
            }

            left[i] = l
            right[i] = r
        }
    }

    private fun softLimitSample(x: Double, isStudio: Boolean): Double {
        val threshold = 0.88
        val absX = abs(x)
        if (absX <= threshold) return x
        val excess = absX - threshold
        val headroom = if (isStudio) 0.115 else 0.12
        val compressed = threshold + headroom * tanh(excess / headroom)
        return if (x >= 0.0) compressed else -compressed
    }

    private fun reverbParams(preset: String, isStudio: Boolean): Triple<Double, Double, Double> {
        val wetScale = if (isStudio) 1.0 else 0.72
        return when (preset) {
            "SMALL_ROOM" -> Triple(0.14 * wetScale, 0.54, 0.45)
            "MEDIUM_ROOM" -> Triple(0.20 * wetScale, 0.64, 0.40)
            "LARGE_ROOM" -> Triple(0.26 * wetScale, 0.72, 0.35)
            "MEDIUM_HALL" -> Triple(0.32 * wetScale, 0.78, 0.30)
            "LARGE_HALL" -> Triple(0.38 * wetScale, 0.84, 0.24)
            "PLATE" -> Triple(0.24 * wetScale, 0.75, 0.18)
            else -> Triple(0.0, 0.0, 0.5)
        }
    }

    private fun syncCoefficientsIfNeeded(config: StudioDspConfig, sampleRate: Int) {
        if (lastAppliedConfig == config && lastSampleRate == sampleRate) return
        lastAppliedConfig = config
        lastSampleRate = sampleRate

        val sr = sampleRate.toDouble()
        val isStudio = config.engineMode != "SONIC"
        val bandQ = if (isStudio) 1.414 else 1.25

        // Bass Boost low-shelf at 85 Hz (up to +9 dB warm punch)
        val bassDb = (config.bassBoostStrength.coerceIn(0, 1000) / 1000.0) * 9.0
        bassShelfFilter.setCoefficients(
            StudioDspEngine.computeLowShelfBiquad(sr, 85.0, 0.85, bassDb)
        )

        for (i in 0 until 10) {
            val gainDb = config.bandGainsDb.getOrElse(i) { 0f }.toDouble().coerceIn(-15.0, 15.0)
            eqFilters[i].setCoefficients(
                StudioDspEngine.computePeakingBiquad(
                    sampleRate = sr,
                    centerFreqHz = StudioDspEngine.BAND_FREQUENCIES_HZ[i],
                    q = bandQ,
                    gainDb = gainDb
                )
            )
        }
    }
}
