package com.example.playback

import com.example.data.preferences.PreferencesManager
import com.example.model.EqualizerBand
import com.example.model.EqualizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Unified Equalizer & Studio DSP controller backed directly by our in-process
 * [StudioFloatDspAudioProcessor] inside ExoPlayer's AudioSink.
 *
 * Works reliably on 100% of Android devices & emulators, before and during playback,
 * with zero hardware HAL drop-outs or double-gain conflicts.
 */
class EqualizerManager(private val preferencesManager: PreferencesManager) {

    private val _equalizerState = MutableStateFlow(loadInitialStateFromPreferences())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()

    init {
        syncToStudioDsp()
    }

    companion object {
        const val MIN_BAND_LEVEL_MB: Short = -1500
        const val MAX_BAND_LEVEL_MB: Short = 1500
        const val MIN_PREAMP_DB = -12f
        const val MAX_PREAMP_DB = 12f

        val TEN_BAND_CENTER_FREQS_HZ = intArrayOf(
            31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
        )
        val TEN_BAND_CUTOFF_FREQS_HZ = floatArrayOf(
            44f, 88f, 177f, 355f, 710f, 1420f, 2840f, 5680f, 11360f, 20000f
        )

        val STANDARD_PRESETS = listOf(
            "Обычный", "Flat", "Bass Boost", "Rock", "Pop",
            "Classical", "Jazz", "Vocal", "Electronic", "Deep House", "V-Shape", "Custom"
        )

        fun presetCurveDb(presetName: String): List<Float>? = when (presetName) {
            "Обычный", "Flat" -> listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            "Bass Boost" -> listOf(6.5f, 5.5f, 4.0f, 2.0f, 0.5f, 0f, 0f, 0f, 0.5f, 1.0f)
            "Rock" -> listOf(4.5f, 3.5f, 2.0f, -0.5f, -1.5f, -0.5f, 2.0f, 3.5f, 4.5f, 4.5f)
            "Pop" -> listOf(-1.5f, -0.5f, 1.5f, 3.0f, 3.5f, 2.5f, 1.0f, 0f, -0.5f, -1.0f)
            "Classical" -> listOf(4.0f, 3.0f, 2.0f, 1.0f, 0f, 0f, 1.5f, 2.5f, 3.5f, 4.0f)
            "Jazz" -> listOf(3.5f, 2.5f, 1.5f, 1.0f, -1.0f, -1.0f, 1.0f, 2.0f, 3.0f, 3.0f)
            "Vocal" -> listOf(-3.0f, -2.0f, -0.5f, 2.0f, 4.5f, 5.0f, 4.0f, 2.0f, 0.5f, 0f)
            "Electronic" -> listOf(5.5f, 4.5f, 3.0f, 1.0f, -1.0f, 0.5f, 1.5f, 3.5f, 4.5f, 5.0f)
            "Deep House" -> listOf(7.0f, 6.0f, 4.0f, 1.5f, -1.5f, -1.0f, 1.5f, 2.5f, 3.5f, 3.0f)
            "V-Shape" -> listOf(5.5f, 4.5f, 2.0f, -1.5f, -3.5f, -3.0f, 0.5f, 3.0f, 5.0f, 5.5f)
            else -> null
        }

        fun calculateBassBoostDeltaDb(bandIndex: Int, bassBoostStrength: Short): Float {
            val norm = (bassBoostStrength.toInt().coerceIn(0, 1000)) / 1000f
            if (norm <= 0f) return 0f
            return when (bandIndex) {
                0 -> 7.5f * norm
                1 -> 6.0f * norm
                2 -> 3.0f * norm
                else -> 0f
            }
        }

        fun calculateChannelInputGainDb(
            preAmpDb: Float,
            loudnessStrengthMb: Int,
            audioBalance: Float,
            channelIndex: Int
        ): Float {
            val clampedPreAmp = preAmpDb.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
            val loudnessDb = (loudnessStrengthMb.coerceIn(0, 1000)) / 100f
            val clampedBalance = audioBalance.coerceIn(-1f, 1f)
            val balanceAttenuationDb = when {
                clampedBalance < -0.02f && channelIndex == 1 -> -24f * abs(clampedBalance)
                clampedBalance > 0.02f && channelIndex == 0 -> -24f * abs(clampedBalance)
                else -> 0f
            }
            return (clampedPreAmp + loudnessDb + balanceAttenuationDb).coerceIn(-36f, 22f)
        }
    }

    private fun loadInitialStateFromPreferences(): EqualizerState {
        val defaultBands = TEN_BAND_CENTER_FREQS_HZ.mapIndexed { idx, freq ->
            EqualizerBand(
                bandIndex = idx.toShort(),
                centerFreqHz = freq,
                minLevelMilliBels = MIN_BAND_LEVEL_MB,
                maxLevelMilliBels = MAX_BAND_LEVEL_MB,
                currentLevelMilliBels = preferencesManager.get10BandLevel(idx.toShort(), 0)
                    .coerceIn(MIN_BAND_LEVEL_MB, MAX_BAND_LEVEL_MB)
            )
        }
        val currentIdx = detectMatchingPresetIndex(defaultBands, STANDARD_PRESETS)
        return EqualizerState(
            isAvailable = true,
            isEnabled = preferencesManager.equalizerEnabled.value,
            bands = defaultBands,
            bassBoostStrength = preferencesManager.bassBoostLevel.value.coerceIn(0, 1000),
            virtualizerStrength = preferencesManager.virtualizerStrength.value.coerceIn(0, 1000),
            loudnessEnhancerStrength = preferencesManager.loudnessGain.value.coerceIn(0, 1000),
            preAmpDb = preferencesManager.preAmpDb.value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
            limiterEnabled = preferencesManager.limiterEnabled.value,
            isDynamicsProcessing = true,
            presets = STANDARD_PRESETS,
            currentPresetIndex = currentIdx.toShort()
        )
    }

    @Synchronized
    fun syncToStudioDsp() {
        val bandGainsDb = FloatArray(TEN_BAND_CENTER_FREQS_HZ.size) { idx ->
            preferencesManager.get10BandLevel(idx.toShort(), 0)
                .coerceIn(MIN_BAND_LEVEL_MB, MAX_BAND_LEVEL_MB) / 100f
        }
        StudioDspEngine.updateConfig(
            StudioDspConfig(
                equalizerEnabled = preferencesManager.equalizerEnabled.value,
                bandGainsDb = bandGainsDb,
                preAmpDb = preferencesManager.preAmpDb.value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
                bassBoostStrength = preferencesManager.bassBoostLevel.value.toInt().coerceIn(0, 1000),
                virtualizerStrength = preferencesManager.virtualizerStrength.value.toInt().coerceIn(0, 1000),
                loudnessGainMb = preferencesManager.loudnessGain.value.coerceIn(0, 1000),
                stereoWidenerPercent = preferencesManager.stereoWidener.value.coerceIn(0, 100),
                audioBalance = preferencesManager.audioBalance.value.coerceIn(-1f, 1f),
                reverbPreset = preferencesManager.spatialReverbPreset.value,
                limiterEnabled = preferencesManager.limiterEnabled.value,
                volumeNormalize = preferencesManager.volumeNormalize.value,
                engineMode = preferencesManager.audioEngineMode.value
            )
        )
    }

    @Synchronized
    fun bindAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        syncToStudioDsp()
    }

    @Synchronized
    fun bindSecondaryAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        syncToStudioDsp()
    }

    @Synchronized
    fun unbindAudioSession(@Suppress("UNUSED_PARAMETER") audioSessionId: Int) {
        // Custom in-pipeline DSP lives inside ExoPlayer's AudioSink; no native HAL effect teardown needed.
    }

    private fun detectMatchingPresetIndex(bands: List<EqualizerBand>, presets: List<String>): Int {
        if (bands.isEmpty()) return 0
        if (bands.all { abs(it.currentLevelMilliBels.toInt()) <= 25 }) return 0
        for (preset in STANDARD_PRESETS) {
            val curve = presetCurveDb(preset) ?: continue
            val matches = bands.indices.all { idx ->
                val cIdx = (idx * curve.size / bands.size).coerceIn(0, curve.lastIndex)
                val targetMb = (curve[cIdx] * 100f).toInt()
                abs(bands[idx].currentLevelMilliBels.toInt() - targetMb) <= 35
            }
            if (matches) return presets.indexOf(preset).coerceAtLeast(0)
        }
        return presets.indexOf("Custom").coerceAtLeast(0)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        preferencesManager.setEqualizerEnabled(enabled)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(isEnabled = enabled)
    }

    @Synchronized
    fun setBandLevel(bandIndex: Short, level: Short) {
        val currentBands = _equalizerState.value.bands
        val targetBand = currentBands.find { it.bandIndex == bandIndex }
        val minL = targetBand?.minLevelMilliBels ?: MIN_BAND_LEVEL_MB
        val maxL = targetBand?.maxLevelMilliBels ?: MAX_BAND_LEVEL_MB
        val clamped = level.coerceIn(minL, maxL)

        preferencesManager.set10BandLevel(bandIndex, clamped)
        syncToStudioDsp()

        val updatedBands = currentBands.map {
            if (it.bandIndex == bandIndex) it.copy(currentLevelMilliBels = clamped) else it
        }
        val customIdx = _equalizerState.value.presets.indexOf("Custom").coerceAtLeast(0)
        _equalizerState.value = _equalizerState.value.copy(
            bands = updatedBands,
            currentPresetIndex = customIdx.toShort()
        )
    }

    @Synchronized
    fun resetAllBands() {
        val bands = _equalizerState.value.bands
        for (i in TEN_BAND_CENTER_FREQS_HZ.indices) {
            preferencesManager.set10BandLevel(i.toShort(), 0)
        }
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(
            bands = bands.map { it.copy(currentLevelMilliBels = 0) },
            currentPresetIndex = 0
        )
    }

    @Synchronized
    fun setPreAmpDb(db: Float) {
        val clamped = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
        preferencesManager.setPreAmpDb(clamped)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(preAmpDb = clamped)
    }

    @Synchronized
    fun setLimiterEnabled(enabled: Boolean) {
        preferencesManager.setLimiterEnabled(enabled)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(limiterEnabled = enabled)
    }

    @Synchronized
    fun setAudioBalance(balance: Float) {
        val clamped = balance.coerceIn(-1f, 1f)
        preferencesManager.setAudioBalance(clamped)
        syncToStudioDsp()
    }

    @Synchronized
    fun setBassBoost(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        preferencesManager.setBassBoost(clamped)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(bassBoostStrength = clamped)
    }

    @Synchronized
    fun setVirtualizer(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        preferencesManager.setVirtualizer(clamped)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(virtualizerStrength = clamped)
    }

    @Synchronized
    fun setLoudnessEnhancer(targetGainmB: Int) {
        val clamped = targetGainmB.coerceIn(0, 1000)
        preferencesManager.setLoudnessGain(clamped)
        syncToStudioDsp()
        _equalizerState.value = _equalizerState.value.copy(loudnessEnhancerStrength = clamped)
    }

    @Synchronized
    fun setStereoWidener(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        preferencesManager.setStereoWidener(clamped)
        syncToStudioDsp()
    }

    @Synchronized
    fun setVolumeNormalize(enabled: Boolean) {
        preferencesManager.setVolumeNormalize(enabled)
        syncToStudioDsp()
    }

    @Synchronized
    fun setAudioEngineMode(mode: String) {
        val clean = if (mode.uppercase() == "SONIC") "SONIC" else "STUDIO"
        preferencesManager.setAudioEngineMode(clean)
        syncToStudioDsp()
    }

    @Synchronized
    fun usePreset(presetIndex: Short) {
        val presets = _equalizerState.value.presets
        if (presetIndex in presets.indices) {
            applyPreset(presets[presetIndex.toInt()])
        }
    }

    @Synchronized
    fun applyPreset(presetName: String) {
        val currentBands = _equalizerState.value.bands
        if (currentBands.isEmpty()) return

        val dbCurve = presetCurveDb(presetName) ?: return
        val minL = currentBands.first().minLevelMilliBels.toInt()
        val maxL = currentBands.first().maxLevelMilliBels.toInt()

        for (i in TEN_BAND_CENTER_FREQS_HZ.indices) {
            val targetMb = (dbCurve[i] * 100f).toInt().coerceIn(minL, maxL).toShort()
            preferencesManager.set10BandLevel(i.toShort(), targetMb)
        }
        syncToStudioDsp()

        val updatedBands = currentBands.mapIndexed { index, band ->
            val curveIdx = (index * dbCurve.size / currentBands.size).coerceIn(0, dbCurve.lastIndex)
            val targetMb = (dbCurve[curveIdx] * 100f).toInt()
                .coerceIn(band.minLevelMilliBels.toInt(), band.maxLevelMilliBels.toInt())
                .toShort()
            band.copy(currentLevelMilliBels = targetMb)
        }
        val presetIdx = _equalizerState.value.presets.indexOf(presetName).coerceAtLeast(0)
        _equalizerState.value = _equalizerState.value.copy(
            bands = updatedBands,
            currentPresetIndex = presetIdx.toShort()
        )
    }

    @Synchronized
    fun setSpatialReverbPreset(preset: String) {
        preferencesManager.setSpatialReverbPreset(preset)
        syncToStudioDsp()
    }

    @Synchronized
    fun release() {
        // Nothing to release for pure-Kotlin in-pipeline DSP
    }
}
