/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.database.EqualizerPresetEntity
import com.podcasty.app.database.PodcastyDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Coordinates the Equalizer & Audio Effects UI state, Room custom preset persistence,
 * DataStore settings persistence, and live [PodcastyAudioEffectsEngine] hardware updates.
 *
 * - Real-time band/preamp/balance adjustments update the active audio session in <1 ms
 *   without touching disk on every drag event (disk writes debounced by 180 ms).
 * - Automatically reapplies persisted preset & settings whenever the active ExoPlayer
 *   audio session attaches or changes.
 */
@OptIn(UnstableApi::class)
class EqualizerController(
    private val engine: PodcastyAudioEffectsEngine,
    private val preferences: PodcastPreferencesRepository,
    private val dao: PodcastyDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var initializedFromPrefs = false

    private val _uiState = MutableStateFlow(
        EqualizerUiState(
            capabilities = engine.capabilities.value,
            diagnostics = engine.diagnostics.value,
        ),
    )
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    init {
        // Hydrate persisted state once on startup and apply immediately without clobbering live capabilities
        scope.launch {
            val settings = preferences.settings.first()
            val customEntities = dao.getAllEqualizerPresets()
            val customPresets = customEntities.map { it.toDomain() }
            val latestCaps = engine.capabilities.value
            val latestDiag = engine.diagnostics.value
            val resolvedBands = resolveInitialBands(
                presetId = settings.eqPresetId,
                savedCsv = settings.eqBandLevelsCsv,
                customPresets = customPresets,
                capabilities = latestCaps,
            )
            val headroom = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                eqEnabled = settings.eqEnabled,
                autoClippingProtection = settings.eqAutoClippingProtection,
                bandLevelsMb = resolvedBands,
            )
            _uiState.update { current ->
                val caps = if (engine.capabilities.value.isAttached) engine.capabilities.value else current.capabilities
                val bands = if (caps.bandCount > 0 && resolvedBands.size != caps.bandCount) {
                    resolveInitialBands(settings.eqPresetId, settings.eqBandLevelsCsv, customPresets, caps)
                } else {
                    resolvedBands
                }
                current.copy(
                    enabled = settings.eqEnabled,
                    selectedPresetId = settings.eqPresetId,
                    bandLevelsMb = bands,
                    preampDb = settings.eqPreampDb,
                    autoClippingProtection = settings.eqAutoClippingProtection,
                    effectiveHeadroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                        eqEnabled = settings.eqEnabled,
                        autoClippingProtection = settings.eqAutoClippingProtection,
                        bandLevelsMb = bands,
                    ),
                    bassBoostStrength = settings.eqBassBoostStrength,
                    virtualizerStrength = settings.eqVirtualizerStrength,
                    balance = settings.eqBalance,
                    monoAudio = settings.eqMonoAudio,
                    capabilities = caps,
                    customPresets = customPresets,
                    diagnostics = if (engine.diagnostics.value.audioSessionId > 0) engine.diagnostics.value else latestDiag,
                )
            }
            initializedFromPrefs = true
            pushStateToEngine(_uiState.value)
        }

        // Observe hardware capability updates (e.g. when ExoPlayer attaches audioSessionId)
        scope.launch {
            engine.capabilities.collect { caps ->
                _uiState.update { current ->
                    val updatedBands = when {
                        caps.bandCount <= 0 -> current.bandLevelsMb
                        current.bandLevelsMb.size == caps.bandCount ->
                            current.bandLevelsMb.map { it.coerceIn(caps.minGainMb, caps.maxGainMb) }
                        else -> resolveInitialBands(
                            presetId = current.selectedPresetId,
                            savedCsv = BuiltInEqualizerPresets.encodeCsvInts(current.bandLevelsMb),
                            customPresets = current.customPresets,
                            capabilities = caps,
                        )
                    }
                    val headroom = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                        eqEnabled = current.enabled,
                        autoClippingProtection = current.autoClippingProtection,
                        bandLevelsMb = updatedBands,
                    )
                    current.copy(
                        capabilities = caps,
                        bandLevelsMb = updatedBands,
                        effectiveHeadroomDb = headroom,
                    )
                }
                if (initializedFromPrefs && caps.isAttached) {
                    pushStateToEngine(_uiState.value)
                }
            }
        }

        // Observe live audio diagnostics from Media3 sink
        scope.launch {
            engine.diagnostics.collect { diag ->
                _uiState.update { it.copy(diagnostics = diag) }
            }
        }

        // Observe Room-persisted user custom presets
        scope.launch {
            dao.observeEqualizerPresets().collect { entities ->
                val customPresets = entities.map { it.toDomain() }
                _uiState.update { it.copy(customPresets = customPresets) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        updateStateAndApply(immediatePersist = true) { current ->
            val bands = ensureBandSize(current.bandLevelsMb, current.capabilities)
            current.copy(
                enabled = enabled,
                bandLevelsMb = bands,
                effectiveHeadroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                    eqEnabled = enabled,
                    autoClippingProtection = current.autoClippingProtection,
                    bandLevelsMb = bands,
                ),
            )
        }
    }

    fun selectPreset(presetId: String) {
        updateStateAndApply(immediatePersist = true) { current ->
            val caps = current.capabilities
            val custom = current.customPresets.firstOrNull { it.id == presetId }
            when {
                custom != null -> {
                    val mappedBands = BuiltInEqualizerPresets.remapBandsToDevice(
                        savedLevelsMb = custom.bandLevelsMb,
                        savedFreqsHz = custom.bandFrequenciesHz,
                        capabilities = caps,
                    )
                    current.copy(
                        selectedPresetId = custom.id,
                        bandLevelsMb = mappedBands,
                        preampDb = custom.preampDb.coerceIn(-15f, 6f),
                        bassBoostStrength = custom.bassBoostStrength.coerceIn(0, 1000),
                        virtualizerStrength = custom.virtualizerStrength.coerceIn(0, 1000),
                        balance = custom.balance.coerceIn(-1f, 1f),
                        monoAudio = custom.monoAudio,
                        effectiveHeadroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                            eqEnabled = current.enabled,
                            autoClippingProtection = current.autoClippingProtection,
                            bandLevelsMb = mappedBands,
                        ),
                    )
                }
                presetId == BuiltInEqualizerPresets.CustomId -> {
                    val bands = ensureBandSize(current.bandLevelsMb, caps)
                    current.copy(selectedPresetId = BuiltInEqualizerPresets.CustomId, bandLevelsMb = bands)
                }
                else -> {
                    val resolvedBands = BuiltInEqualizerPresets.resolveBandLevelsMb(presetId, caps)
                    current.copy(
                        selectedPresetId = presetId,
                        bandLevelsMb = resolvedBands,
                        effectiveHeadroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                            eqEnabled = current.enabled,
                            autoClippingProtection = current.autoClippingProtection,
                            bandLevelsMb = resolvedBands,
                        ),
                    )
                }
            }
        }
    }

    fun setBandLevelMb(bandIndex: Int, levelMb: Int) {
        val current = _uiState.value
        val caps = current.capabilities
        if (bandIndex !in caps.bands.indices) return
        val clamped = levelMb.coerceIn(caps.minGainMb, caps.maxGainMb)
        val updatedBands = ensureBandSize(current.bandLevelsMb, caps).toMutableList()
        if (updatedBands[bandIndex] == clamped) return
        updatedBands[bandIndex] = clamped

        val nextPresetId = if (BuiltInEqualizerPresets.isBuiltInId(current.selectedPresetId)) {
            BuiltInEqualizerPresets.CustomId
        } else {
            current.selectedPresetId
        }
        val headroom = BuiltInEqualizerPresets.computeAutoHeadroomDb(
            eqEnabled = current.enabled,
            autoClippingProtection = current.autoClippingProtection,
            bandLevelsMb = updatedBands,
        )
        val next = current.copy(
            selectedPresetId = nextPresetId,
            bandLevelsMb = updatedBands,
            effectiveHeadroomDb = headroom,
        )
        _uiState.update { latest -> latest.copy(
            selectedPresetId = next.selectedPresetId,
            bandLevelsMb = next.bandLevelsMb,
            effectiveHeadroomDb = next.effectiveHeadroomDb,
        ) }
        // Direct single-band update for zero-jitter slider dragging
        engine.updateSingleBand(bandIndex, clamped)
        schedulePersist(next, immediate = false)
    }

    fun resetBand(bandIndex: Int) {
        setBandLevelMb(bandIndex, 0)
    }

    fun setPreampDb(preampDb: Float) {
        val clamped = (Math.round(preampDb.coerceIn(-15f, 6f) * 2f) / 2f)
        updateStateAndApply(immediatePersist = false) { it.copy(preampDb = clamped) }
    }

    fun setAutoClippingProtection(enabled: Boolean) {
        updateStateAndApply(immediatePersist = true) { current ->
            current.copy(
                autoClippingProtection = enabled,
                effectiveHeadroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
                    eqEnabled = current.enabled,
                    autoClippingProtection = enabled,
                    bandLevelsMb = current.bandLevelsMb,
                ),
            )
        }
    }

    fun setBassBoostStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        updateStateAndApply(immediatePersist = false) { it.copy(bassBoostStrength = clamped) }
    }

    fun setVirtualizerStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        updateStateAndApply(immediatePersist = false) { it.copy(virtualizerStrength = clamped) }
    }

    fun setBalance(balance: Float) {
        val snapped = if (kotlin.math.abs(balance) < 0.04f) 0f else balance.coerceIn(-1f, 1f)
        updateStateAndApply(immediatePersist = false) { it.copy(balance = snapped) }
    }

    fun setMonoAudio(enabled: Boolean) {
        updateStateAndApply(immediatePersist = true) { it.copy(monoAudio = enabled) }
    }

    fun resetEqualizer() {
        updateStateAndApply(immediatePersist = true) { current ->
            val zeros = List(current.capabilities.bandCount) { 0 }
            current.copy(
                selectedPresetId = BuiltInEqualizerPresets.FlatId,
                bandLevelsMb = zeros,
                preampDb = 0f,
                bassBoostStrength = 0,
                virtualizerStrength = 0,
                balance = 0f,
                monoAudio = false,
                effectiveHeadroomDb = 0f,
            )
        }
    }

    /**
     * Saves the current equalizer + DSP configuration as a new Room-persisted custom preset
     * and activates it immediately.
     */
    fun saveAsCustomPreset(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        val current = _uiState.value
        val now = System.currentTimeMillis()
        val id = "eq_user_${UUID.randomUUID().toString().take(12)}"
        val bands = ensureBandSize(current.bandLevelsMb, current.capabilities)
        val freqs = current.capabilities.bands.map { it.centerFreqHz }
        val entity = EqualizerPresetEntity(
            id = id,
            name = cleaned,
            bandLevelsMbCsv = BuiltInEqualizerPresets.encodeCsvInts(bands),
            bandFrequenciesHzCsv = BuiltInEqualizerPresets.encodeCsvInts(freqs),
            preampDb = current.preampDb,
            bassBoostStrength = current.bassBoostStrength,
            virtualizerStrength = current.virtualizerStrength,
            balance = current.balance,
            monoAudio = current.monoAudio,
            createdAt = now,
            updatedAt = now,
        )
        scope.launch(Dispatchers.IO) {
            dao.upsertEqualizerPreset(entity)
        }
        updateStateAndApply(immediatePersist = true) { it.copy(selectedPresetId = id) }
    }

    /**
     * Overwrites an existing custom preset with the currently active band/preamp/effect values.
     */
    fun updateCustomPreset(presetId: String) {
        val current = _uiState.value
        val existing = current.customPresets.firstOrNull { it.id == presetId } ?: return
        val now = System.currentTimeMillis()
        val bands = ensureBandSize(current.bandLevelsMb, current.capabilities)
        val freqs = current.capabilities.bands.map { it.centerFreqHz }
        val entity = EqualizerPresetEntity(
            id = existing.id,
            name = existing.name,
            bandLevelsMbCsv = BuiltInEqualizerPresets.encodeCsvInts(bands),
            bandFrequenciesHzCsv = BuiltInEqualizerPresets.encodeCsvInts(freqs),
            preampDb = current.preampDb,
            bassBoostStrength = current.bassBoostStrength,
            virtualizerStrength = current.virtualizerStrength,
            balance = current.balance,
            monoAudio = current.monoAudio,
            createdAt = existing.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        scope.launch(Dispatchers.IO) {
            dao.upsertEqualizerPreset(entity)
        }
        schedulePersist(current, immediate = true)
    }

    fun renameCustomPreset(presetId: String, newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isEmpty() || BuiltInEqualizerPresets.isBuiltInId(presetId)) return
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            dao.renameEqualizerPreset(presetId, cleaned, now)
        }
    }

    fun deleteCustomPreset(presetId: String) {
        if (BuiltInEqualizerPresets.isBuiltInId(presetId)) return
        scope.launch(Dispatchers.IO) {
            dao.deleteEqualizerPreset(presetId)
        }
        if (_uiState.value.selectedPresetId == presetId) {
            selectPreset(BuiltInEqualizerPresets.FlatId)
        }
    }

    fun restoreDefaultPresets() {
        scope.launch(Dispatchers.IO) {
            dao.deleteAllCustomEqualizerPresets()
        }
        resetEqualizer()
    }

    /**
     * Exposes live hardware AudioEffect snapshot for diagnostic & instrumentation verification.
     */
    fun readHardwareSnapshot(): HardwareEqualizerSnapshot = engine.readHardwareStateSnapshot()

    private fun updateStateAndApply(
        immediatePersist: Boolean,
        transform: (EqualizerUiState) -> EqualizerUiState,
    ) {
        // Hardware diagnostics and Room observers can publish concurrently. Preserve
        // their latest fields instead of overwriting a newer preset list with a stale copy.
        val next = _uiState.updateAndGet(transform)
        pushStateToEngine(next)
        schedulePersist(next, immediate = immediatePersist)
    }

    private fun pushStateToEngine(state: EqualizerUiState) {
        engine.applySettings(
            enabled = state.enabled,
            bandLevelsMb = state.bandLevelsMb,
            preampDb = state.preampDb,
            autoClippingProtection = state.autoClippingProtection,
            bassBoostStrength = state.bassBoostStrength,
            virtualizerStrength = state.virtualizerStrength,
            balance = state.balance,
            monoAudio = state.monoAudio,
        )
    }

    private fun schedulePersist(state: EqualizerUiState, immediate: Boolean) {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            if (!immediate) delay(180L)
            preferences.saveEqualizerState(
                enabled = state.enabled,
                presetId = state.selectedPresetId,
                bandLevelsCsv = BuiltInEqualizerPresets.encodeCsvInts(state.bandLevelsMb),
                preampDb = state.preampDb,
                autoClippingProtection = state.autoClippingProtection,
                bassBoostStrength = state.bassBoostStrength,
                virtualizerStrength = state.virtualizerStrength,
                balance = state.balance,
                monoAudio = state.monoAudio,
            )
        }
    }

    private fun ensureBandSize(levels: List<Int>, caps: EqualizerCapabilities): List<Int> {
        if (caps.bandCount <= 0) return levels
        return if (levels.size == caps.bandCount) {
            levels.map { it.coerceIn(caps.minGainMb, caps.maxGainMb) }
        } else {
            List(caps.bandCount) { idx -> levels.getOrElse(idx) { 0 }.coerceIn(caps.minGainMb, caps.maxGainMb) }
        }
    }

    private fun resolveInitialBands(
        presetId: String,
        savedCsv: String,
        customPresets: List<EqualizerPreset>,
        capabilities: EqualizerCapabilities,
    ): List<Int> {
        val parsed = BuiltInEqualizerPresets.parseCsvInts(savedCsv)
        if (capabilities.bandCount <= 0) return parsed
        if (parsed.size == capabilities.bandCount) {
            return parsed.map { it.coerceIn(capabilities.minGainMb, capabilities.maxGainMb) }
        }
        val custom = customPresets.firstOrNull { it.id == presetId }
        if (custom != null) {
            return BuiltInEqualizerPresets.remapBandsToDevice(
                savedLevelsMb = custom.bandLevelsMb,
                savedFreqsHz = custom.bandFrequenciesHz,
                capabilities = capabilities,
            )
        }
        if (parsed.isNotEmpty() && presetId == BuiltInEqualizerPresets.CustomId) {
            return BuiltInEqualizerPresets.remapBandsToDevice(parsed, emptyList(), capabilities)
        }
        return BuiltInEqualizerPresets.resolveBandLevelsMb(presetId, capabilities)
    }

    private fun EqualizerPresetEntity.toDomain(): EqualizerPreset = EqualizerPreset(
        id = id,
        name = name,
        isBuiltIn = false,
        bandLevelsMb = BuiltInEqualizerPresets.parseCsvInts(bandLevelsMbCsv),
        bandFrequenciesHz = BuiltInEqualizerPresets.parseCsvInts(bandFrequenciesHzCsv),
        preampDb = preampDb,
        bassBoostStrength = bassBoostStrength,
        virtualizerStrength = virtualizerStrength,
        balance = balance,
        monoAudio = monoAudio,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
