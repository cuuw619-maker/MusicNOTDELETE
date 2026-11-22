package com.example.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.data.preferences.PreferencesManager
import com.example.model.EqualizerBand
import com.example.model.EqualizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class EqualizerManager(private val preferencesManager: PreferencesManager) {

    private class SessionAudioEffects(
        val sessionId: Int,
        val dynamicsProcessing: DynamicsProcessing? = null,
        val legacyEqualizer: Equalizer? = null,
        val bassBoost: BassBoost? = null,
        val virtualizer: Virtualizer? = null,
        val loudnessEnhancer: LoudnessEnhancer? = null,
        val presetReverb: PresetReverb? = null
    ) {
        val usesDynamicsProcessing: Boolean
            get() = dynamicsProcessing != null

        fun release() {
            runCatching { dynamicsProcessing?.release() }
            runCatching { legacyEqualizer?.release() }
            runCatching { bassBoost?.release() }
            runCatching { virtualizer?.release() }
            runCatching { loudnessEnhancer?.release() }
            runCatching { presetReverb?.release() }
        }
    }

    private val sessionEffects = LinkedHashMap<Int, SessionAudioEffects>()
    private var primarySessionId: Int = 0
    private var secondarySessionId: Int = 0

    private val _equalizerState = MutableStateFlow(loadInitialStateFromPreferences())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()

    companion object {
        private const val TAG = "EqualizerManager"
        private const val CHANNEL_COUNT = 2
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
                0 -> 7.5f * norm // 31 Hz sub-bass
                1 -> 6.0f * norm // 62 Hz punch
                2 -> 3.0f * norm // 125 Hz warmth
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
        return EqualizerState(
            isAvailable = false,
            isEnabled = preferencesManager.equalizerEnabled.value,
            bands = defaultBands,
            bassBoostStrength = preferencesManager.bassBoostLevel.value.coerceIn(0, 1000),
            virtualizerStrength = preferencesManager.virtualizerStrength.value.coerceIn(0, 1000),
            loudnessEnhancerStrength = preferencesManager.loudnessGain.value.coerceIn(0, 1000),
            preAmpDb = preferencesManager.preAmpDb.value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
            limiterEnabled = preferencesManager.limiterEnabled.value,
            isDynamicsProcessing = false,
            presets = STANDARD_PRESETS,
            currentPresetIndex = 0
        )
    }

    @Synchronized
    fun bindAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        val oldPrimary = primarySessionId
        primarySessionId = audioSessionId
        if (oldPrimary != 0 && oldPrimary != audioSessionId && oldPrimary != secondarySessionId) {
            sessionEffects.remove(oldPrimary)?.release()
        }
        val holder = ensureSessionHolder(audioSessionId)
        publishStateFromHolder(holder)
    }

    @Synchronized
    fun bindSecondaryAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        val oldSecondary = secondarySessionId
        secondarySessionId = audioSessionId
        if (oldSecondary != 0 && oldSecondary != audioSessionId && oldSecondary != primarySessionId) {
            sessionEffects.remove(oldSecondary)?.release()
        }
        val holder = ensureSessionHolder(audioSessionId)
        if (primarySessionId == 0) {
            publishStateFromHolder(holder)
        }
    }

    @Synchronized
    fun unbindAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (primarySessionId == audioSessionId) primarySessionId = 0
        if (secondarySessionId == audioSessionId) secondarySessionId = 0
        sessionEffects.remove(audioSessionId)?.release()
    }

    private fun ensureSessionHolder(audioSessionId: Int): SessionAudioEffects? {
        sessionEffects[audioSessionId]?.let { existing ->
            syncHolderWithPreferences(existing)
            return existing
        }

        val created = createSessionEffects(audioSessionId)
        if (created != null) {
            sessionEffects[audioSessionId] = created
            syncHolderWithPreferences(created)
        } else if (sessionEffects.isEmpty()) {
            _equalizerState.value = loadInitialStateFromPreferences().copy(isAvailable = false)
        }
        return created
    }

    private fun createSessionEffects(audioSessionId: Int): SessionAudioEffects? {
        val virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        val presetReverb = runCatching { PresetReverb(0, audioSessionId) }.getOrNull()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dp = createDynamicsProcessing(audioSessionId)
            if (dp != null) {
                return SessionAudioEffects(
                    sessionId = audioSessionId,
                    dynamicsProcessing = dp,
                    virtualizer = virtualizer,
                    presetReverb = presetReverb
                )
            }
        }

        // API 24-27 fallback (or rare devices without DynamicsProcessing DSP): legacy Equalizer stack
        val eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        if (eq == null) {
            runCatching { virtualizer?.release() }
            runCatching { presetReverb?.release() }
            return null
        }
        val bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        val loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        return SessionAudioEffects(
            sessionId = audioSessionId,
            legacyEqualizer = eq,
            bassBoost = bassBoost,
            virtualizer = virtualizer,
            loudnessEnhancer = loudness,
            presetReverb = presetReverb
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDynamicsProcessing(audioSessionId: Int): DynamicsProcessing? {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNEL_COUNT,
                /* preEqInUse = */ false,
                /* preEqBandCount = */ 0,
                /* mbcInUse = */ true,
                /* mbcBandCount = */ 1,
                /* postEqInUse = */ true,
                /* postEqBandCount = */ TEN_BAND_CENTER_FREQS_HZ.size,
                /* limiterInUse = */ true
            ).build()
            DynamicsProcessing(0, audioSessionId, config)
        } catch (t: Throwable) {
            Log.w(TAG, "DynamicsProcessing unavailable on session $audioSessionId, falling back", t)
            null
        }
    }

    private fun syncHolderWithPreferences(holder: SessionAudioEffects) {
        val isEnabled = preferencesManager.equalizerEnabled.value
        val bassStrength = preferencesManager.bassBoostLevel.value.coerceIn(0, 1000)
        val virtStrength = preferencesManager.virtualizerStrength.value.coerceIn(0, 1000)
        val loudnessGain = preferencesManager.loudnessGain.value.coerceIn(0, 1000)
        val preAmpDb = preferencesManager.preAmpDb.value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
        val limiterEnabled = preferencesManager.limiterEnabled.value
        val audioBalance = preferencesManager.audioBalance.value.coerceIn(-1f, 1f)
        val reverbPreset = preferencesManager.spatialReverbPreset.value

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing != null) {
            applyDynamicsProcessingState(
                dp = holder.dynamicsProcessing,
                isEnabled = isEnabled,
                preAmpDb = preAmpDb,
                loudnessGainMb = loudnessGain,
                audioBalance = audioBalance,
                bassStrength = bassStrength,
                limiterEnabled = limiterEnabled
            )
        } else if (holder.legacyEqualizer != null) {
            val eq = holder.legacyEqualizer
            val range = eq.bandLevelRange
            val minL = range[0]
            val maxL = range[1]
            runCatching { eq.enabled = isEnabled }
            for (i in 0 until eq.numberOfBands) {
                val bandIdx = i.toShort()
                val mappedTenBandIdx = ((i * TEN_BAND_CENTER_FREQS_HZ.size) / eq.numberOfBands.coerceAtLeast(1))
                    .coerceIn(0, TEN_BAND_CENTER_FREQS_HZ.lastIndex)
                val targetMb = preferencesManager.get10BandLevel(mappedTenBandIdx.toShort(), 0)
                    .toInt()
                    .coerceIn(minL.toInt(), maxL.toInt())
                    .toShort()
                runCatching { eq.setBandLevel(bandIdx, targetMb) }
            }
            holder.bassBoost?.let { bb ->
                runCatching {
                    bb.enabled = isEnabled && bassStrength > 0
                    if (bassStrength > 0) bb.setStrength(bassStrength)
                }
            }
            holder.loudnessEnhancer?.let { le ->
                val effectiveGain = (loudnessGain + (preAmpDb * 100f).toInt()).coerceIn(0, 1500)
                runCatching {
                    le.enabled = isEnabled && effectiveGain > 0
                    if (effectiveGain > 0) le.setTargetGain(effectiveGain)
                }
            }
        }

        holder.virtualizer?.let { virt ->
            runCatching {
                virt.enabled = isEnabled && virtStrength > 0
                if (virtStrength > 0) virt.setStrength(virtStrength)
            }
        }
        holder.presetReverb?.let { rev ->
            applyReverbPresetInternal(rev, reverbPreset, isEnabled)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsProcessingState(
        dp: DynamicsProcessing,
        isEnabled: Boolean,
        preAmpDb: Float,
        loudnessGainMb: Int,
        audioBalance: Float,
        bassStrength: Short,
        limiterEnabled: Boolean
    ) {
        try {
            dp.enabled = isEnabled

            // Pre-Amp + Loudness Gain + true L/R Channel Balance
            for (ch in 0 until CHANNEL_COUNT) {
                val gainDb = calculateChannelInputGainDb(preAmpDb, loudnessGainMb, audioBalance, ch)
                dp.setInputGainbyChannel(ch, gainDb)
            }

            // 10-band PostEQ + sub-bass boost curve
            val postEq = DynamicsProcessing.Eq(true, isEnabled, TEN_BAND_CENTER_FREQS_HZ.size)
            for (i in TEN_BAND_CENTER_FREQS_HZ.indices) {
                val userMb = preferencesManager.get10BandLevel(i.toShort(), 0)
                    .coerceIn(MIN_BAND_LEVEL_MB, MAX_BAND_LEVEL_MB)
                val userDb = userMb / 100f
                val bassDelta = calculateBassBoostDeltaDb(i, bassStrength)
                val totalDb = (userDb + bassDelta).coerceIn(-15f, 15f)
                postEq.setBand(
                    i,
                    DynamicsProcessing.EqBand(true, TEN_BAND_CUTOFF_FREQS_HZ[i], totalDb)
                )
            }
            dp.setPostEqAllChannelsTo(postEq)

            // Subtle multiband low-end punch when Bass Boost is engaged
            val bassNorm = bassStrength.toInt().coerceIn(0, 1000) / 1000f
            val mbcEnabled = isEnabled && bassNorm > 0.05f
            val mbc = DynamicsProcessing.Mbc(true, mbcEnabled, 1)
            mbc.setBand(
                0,
                DynamicsProcessing.MbcBand(
                    mbcEnabled,
                    /* cutoffFrequency = */ 22000f,
                    /* attackTime = */ 8f,
                    /* releaseTime = */ 90f,
                    /* ratio = */ 1.0f + (bassNorm * 1.4f),
                    /* threshold = */ -18f,
                    /* kneeWidth = */ 4f,
                    /* noiseGateThreshold = */ -90f,
                    /* expanderRatio = */ 1f,
                    /* preGain = */ bassNorm * 1.5f,
                    /* postGain = */ 0f
                )
            )
            dp.setMbcAllChannelsTo(mbc)

            // Anti-clip brickwall soft-knee Limiter
            val limiter = DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ isEnabled && limiterEnabled,
                /* linkGroup = */ 0,
                /* attackTime = */ 1.0f,
                /* releaseTime = */ 60.0f,
                /* ratio = */ 10.0f,
                /* threshold = */ -0.5f,
                /* postGain = */ 0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed updating DynamicsProcessing parameters", t)
        }
    }

    private fun publishStateFromHolder(primaryHolder: SessionAudioEffects?) {
        val isDp = primaryHolder?.usesDynamicsProcessing == true
        val legacyEq = primaryHolder?.legacyEqualizer
        val isAvailable = primaryHolder != null

        val bands = if (isDp || legacyEq == null) {
            TEN_BAND_CENTER_FREQS_HZ.mapIndexed { idx, freqHz ->
                EqualizerBand(
                    bandIndex = idx.toShort(),
                    centerFreqHz = freqHz,
                    minLevelMilliBels = MIN_BAND_LEVEL_MB,
                    maxLevelMilliBels = MAX_BAND_LEVEL_MB,
                    currentLevelMilliBels = preferencesManager.get10BandLevel(idx.toShort(), 0)
                        .coerceIn(MIN_BAND_LEVEL_MB, MAX_BAND_LEVEL_MB)
                )
            }
        } else {
            val range = legacyEq.bandLevelRange
            val minL = range[0]
            val maxL = range[1]
            (0 until legacyEq.numberOfBands).map { i ->
                val idx = i.toShort()
                val freqHz = (legacyEq.getCenterFreq(idx) / 1000).coerceAtLeast(20)
                val mappedIdx = ((i * TEN_BAND_CENTER_FREQS_HZ.size) / legacyEq.numberOfBands.coerceAtLeast(1))
                    .coerceIn(0, TEN_BAND_CENTER_FREQS_HZ.lastIndex)
                val saved = preferencesManager.get10BandLevel(mappedIdx.toShort(), 0)
                    .coerceIn(minL, maxL)
                EqualizerBand(
                    bandIndex = idx,
                    centerFreqHz = freqHz,
                    minLevelMilliBels = minL,
                    maxLevelMilliBels = maxL,
                    currentLevelMilliBels = saved
                )
            }
        }

        val presetsList = mutableListOf<String>().apply {
            addAll(STANDARD_PRESETS)
            if (!isDp && legacyEq != null) {
                for (p in 0 until legacyEq.numberOfPresets) {
                    val name = runCatching { legacyEq.getPresetName(p.toShort()) }.getOrNull()
                    if (!name.isNullOrBlank() && !contains(name)) add(name)
                }
            }
        }

        val currentIdx = detectMatchingPresetIndex(bands, presetsList)
        _equalizerState.value = EqualizerState(
            isAvailable = isAvailable,
            isEnabled = preferencesManager.equalizerEnabled.value,
            bands = bands,
            bassBoostStrength = preferencesManager.bassBoostLevel.value.coerceIn(0, 1000),
            virtualizerStrength = preferencesManager.virtualizerStrength.value.coerceIn(0, 1000),
            loudnessEnhancerStrength = preferencesManager.loudnessGain.value.coerceIn(0, 1000),
            preAmpDb = preferencesManager.preAmpDb.value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
            limiterEnabled = preferencesManager.limiterEnabled.value,
            isDynamicsProcessing = isDp,
            presets = presetsList,
            currentPresetIndex = currentIdx.toShort()
        )
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
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(isEnabled = enabled)
    }

    @Synchronized
    fun setBandLevel(bandIndex: Short, level: Short) {
        val currentBands = _equalizerState.value.bands
        val targetBand = currentBands.find { it.bandIndex == bandIndex }
        val minL = targetBand?.minLevelMilliBels ?: MIN_BAND_LEVEL_MB
        val maxL = targetBand?.maxLevelMilliBels ?: MAX_BAND_LEVEL_MB
        val clamped = level.coerceIn(minL, maxL)

        if (_equalizerState.value.isDynamicsProcessing || currentBands.size == TEN_BAND_CENTER_FREQS_HZ.size) {
            preferencesManager.set10BandLevel(bandIndex, clamped)
        } else {
            val mappedIdx = ((bandIndex.toInt() * TEN_BAND_CENTER_FREQS_HZ.size) / currentBands.size.coerceAtLeast(1))
                .coerceIn(0, TEN_BAND_CENTER_FREQS_HZ.lastIndex)
            preferencesManager.set10BandLevel(mappedIdx.toShort(), clamped)
        }

        sessionEffects.values.forEach { syncHolderWithPreferences(it) }

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
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(
            bands = bands.map { it.copy(currentLevelMilliBels = 0) },
            currentPresetIndex = 0
        )
    }

    @Synchronized
    fun setPreAmpDb(db: Float) {
        val clamped = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
        preferencesManager.setPreAmpDb(clamped)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(preAmpDb = clamped)
    }

    @Synchronized
    fun setLimiterEnabled(enabled: Boolean) {
        preferencesManager.setLimiterEnabled(enabled)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(limiterEnabled = enabled)
    }

    @Synchronized
    fun setAudioBalance(balance: Float) {
        val clamped = balance.coerceIn(-1f, 1f)
        preferencesManager.setAudioBalance(clamped)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
    }

    @Synchronized
    fun setBassBoost(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        preferencesManager.setBassBoost(clamped)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(bassBoostStrength = clamped)
    }

    @Synchronized
    fun setVirtualizer(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        preferencesManager.setVirtualizer(clamped)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(virtualizerStrength = clamped)
    }

    @Synchronized
    fun setLoudnessEnhancer(targetGainmB: Int) {
        val clamped = targetGainmB.coerceIn(0, 1000)
        preferencesManager.setLoudnessGain(clamped)
        sessionEffects.values.forEach { syncHolderWithPreferences(it) }
        _equalizerState.value = _equalizerState.value.copy(loudnessEnhancerStrength = clamped)
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

        val dbCurve = presetCurveDb(presetName)
        if (dbCurve != null) {
            val minL = currentBands.first().minLevelMilliBels.toInt()
            val maxL = currentBands.first().maxLevelMilliBels.toInt()

            for (i in TEN_BAND_CENTER_FREQS_HZ.indices) {
                val targetMb = (dbCurve[i] * 100f).toInt().coerceIn(minL, maxL).toShort()
                preferencesManager.set10BandLevel(i.toShort(), targetMb)
            }
            sessionEffects.values.forEach { syncHolderWithPreferences(it) }

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
            return
        }

        // Check hardware presets in legacy Equalizer fallback
        val legacyEq = sessionEffects.values.firstNotNullOfOrNull { it.legacyEqualizer } ?: return
        val hwIdx = (0 until legacyEq.numberOfPresets).find {
            runCatching { legacyEq.getPresetName(it.toShort()) == presetName }.getOrDefault(false)
        }
        if (hwIdx != null) {
            runCatching {
                legacyEq.usePreset(hwIdx.toShort())
                val updatedBands = currentBands.map { band ->
                    val lvl = legacyEq.getBandLevel(band.bandIndex)
                    val mappedIdx = ((band.bandIndex.toInt() * TEN_BAND_CENTER_FREQS_HZ.size) / currentBands.size.coerceAtLeast(1))
                        .coerceIn(0, TEN_BAND_CENTER_FREQS_HZ.lastIndex)
                    preferencesManager.set10BandLevel(mappedIdx.toShort(), lvl)
                    band.copy(currentLevelMilliBels = lvl)
                }
                sessionEffects.values.forEach { syncHolderWithPreferences(it) }
                val pIdx = _equalizerState.value.presets.indexOf(presetName).coerceAtLeast(0)
                _equalizerState.value = _equalizerState.value.copy(
                    bands = updatedBands,
                    currentPresetIndex = pIdx.toShort()
                )
            }
        }
    }

    @Synchronized
    fun setSpatialReverbPreset(preset: String) {
        preferencesManager.setSpatialReverbPreset(preset)
        val isEnabled = _equalizerState.value.isEnabled
        sessionEffects.values.forEach { holder ->
            holder.presetReverb?.let { applyReverbPresetInternal(it, preset, isEnabled) }
        }
    }

    private fun applyReverbPresetInternal(rev: PresetReverb, preset: String, isEnabled: Boolean) {
        try {
            val hwPreset: Short = when (preset) {
                "SMALL_ROOM" -> PresetReverb.PRESET_SMALLROOM
                "MEDIUM_ROOM" -> PresetReverb.PRESET_MEDIUMROOM
                "LARGE_ROOM" -> PresetReverb.PRESET_LARGEROOM
                "MEDIUM_HALL" -> PresetReverb.PRESET_MEDIUMHALL
                "LARGE_HALL" -> PresetReverb.PRESET_LARGEHALL
                "PLATE" -> PresetReverb.PRESET_PLATE
                else -> PresetReverb.PRESET_NONE
            }
            if (hwPreset != PresetReverb.PRESET_NONE && isEnabled) {
                rev.enabled = true
                rev.preset = hwPreset
            } else {
                rev.enabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set reverb preset $preset", e)
        }
    }

    @Synchronized
    fun release() {
        sessionEffects.values.forEach { it.release() }
        sessionEffects.clear()
        primarySessionId = 0
        secondarySessionId = 0
    }
}
