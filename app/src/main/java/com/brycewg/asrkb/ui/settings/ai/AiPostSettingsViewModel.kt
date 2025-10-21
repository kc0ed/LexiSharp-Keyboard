package com.brycewg.asrkb.ui.settings.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * ViewModel for AI Post-Processing Settings
 * Manages LLM providers and prompt presets with reactive state updates
 */
class AiPostSettingsViewModel : ViewModel() {
    companion object {
        private const val TAG = "AiPostSettingsVM"
    }

    // State for LLM providers
    private val _llmProfiles = MutableStateFlow<List<Prefs.LlmProvider>>(emptyList())
    val llmProfiles: StateFlow<List<Prefs.LlmProvider>> = _llmProfiles.asStateFlow()

    private val _activeLlmProvider = MutableStateFlow<Prefs.LlmProvider?>(null)
    val activeLlmProvider: StateFlow<Prefs.LlmProvider?> = _activeLlmProvider.asStateFlow()

    // State for prompt presets
    private val _promptPresets = MutableStateFlow<List<PromptPreset>>(emptyList())
    val promptPresets: StateFlow<List<PromptPreset>> = _promptPresets.asStateFlow()

    private val _activePromptPreset = MutableStateFlow<PromptPreset?>(null)
    val activePromptPreset: StateFlow<PromptPreset?> = _activePromptPreset.asStateFlow()

    // ======== Initialization ========

    /**
     * Loads all data from preferences and updates state flows
     */
    fun loadData(prefs: Prefs) {
        loadLlmProviders(prefs)
        loadPromptPresets(prefs)
    }

    // ======== LLM Provider Management ========

    /**
     * Loads LLM providers from preferences
     */
    private fun loadLlmProviders(prefs: Prefs) {
        try {
            val profiles = prefs.getLlmProviders()
            _llmProfiles.value = profiles
            _activeLlmProvider.value = prefs.getActiveLlmProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LLM providers", e)
        }
    }

    /**
     * Selects a specific LLM provider by ID
     */
    fun selectLlmProvider(prefs: Prefs, profileId: String) {
        try {
            val profiles = _llmProfiles.value
            val selected = profiles.firstOrNull { it.id == profileId }
            if (selected != null) {
                prefs.activeLlmId = profileId
                _activeLlmProvider.value = selected
                Log.d(TAG, "Selected LLM provider: ${selected.name}")
            } else {
                Log.w(TAG, "LLM provider not found: $profileId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select LLM provider", e)
        }
    }

    /**
     * Updates the active LLM provider with a mutation function
     */
    fun updateActiveLlmProvider(
        prefs: Prefs,
        mutator: (Prefs.LlmProvider) -> Prefs.LlmProvider
    ) {
        try {
            val list = _llmProfiles.value.toMutableList()
            val activeId = prefs.activeLlmId
            val idx = list.indexOfFirst { it.id == activeId }

            if (idx >= 0) {
                list[idx] = mutator(list[idx])
            } else if (list.isNotEmpty()) {
                // Fallback to first profile if active ID is invalid
                list[0] = mutator(list[0])
                prefs.activeLlmId = list[0].id
            } else {
                // Create new profile if none exists
                val created = createDefaultLlmProvider(mutator)
                list.add(created)
                prefs.activeLlmId = created.id
            }

            prefs.setLlmProviders(list)
            _llmProfiles.value = list
            _activeLlmProvider.value = list.firstOrNull { it.id == prefs.activeLlmId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active LLM provider", e)
        }
    }

    /**
     * Creates a new default LLM provider with optional mutation
     */
    private fun createDefaultLlmProvider(
        mutator: ((Prefs.LlmProvider) -> Prefs.LlmProvider)? = null
    ): Prefs.LlmProvider {
        val defaultProvider = Prefs.LlmProvider(
            id = UUID.randomUUID().toString(),
            name = "",
            endpoint = Prefs.DEFAULT_LLM_ENDPOINT,
            apiKey = "",
            model = Prefs.DEFAULT_LLM_MODEL,
            temperature = Prefs.DEFAULT_LLM_TEMPERATURE
        )
        return mutator?.invoke(defaultProvider) ?: defaultProvider
    }

    /**
     * Adds a new LLM provider
     */
    fun addLlmProvider(prefs: Prefs, defaultProfileName: String): Boolean {
        return try {
            val id = UUID.randomUUID().toString()
            val newProvider = Prefs.LlmProvider(
                id = id,
                name = defaultProfileName,
                endpoint = Prefs.DEFAULT_LLM_ENDPOINT,
                apiKey = "",
                model = Prefs.DEFAULT_LLM_MODEL,
                temperature = Prefs.DEFAULT_LLM_TEMPERATURE
            )

            val list = _llmProfiles.value.toMutableList()
            list.add(newProvider)
            prefs.setLlmProviders(list)
            prefs.activeLlmId = id

            _llmProfiles.value = list
            _activeLlmProvider.value = newProvider
            Log.d(TAG, "Added new LLM provider: $defaultProfileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add LLM provider", e)
            false
        }
    }

    /**
     * Deletes the currently active LLM provider
     */
    fun deleteActiveLlmProvider(prefs: Prefs): Boolean {
        return try {
            val list = _llmProfiles.value.toMutableList()
            if (list.isEmpty()) {
                Log.w(TAG, "Cannot delete: no LLM providers exist")
                return false
            }

            val activeId = prefs.activeLlmId
            val idx = list.indexOfFirst { it.id == activeId }
            if (idx >= 0) {
                list.removeAt(idx)
                prefs.setLlmProviders(list)

                val newActive = list.firstOrNull()
                prefs.activeLlmId = newActive?.id ?: ""

                _llmProfiles.value = list
                _activeLlmProvider.value = newActive
                Log.d(TAG, "Deleted LLM provider at index $idx")
                true
            } else {
                Log.w(TAG, "Active LLM provider not found for deletion")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete LLM provider", e)
            false
        }
    }

    // ======== Prompt Preset Management ========

    /**
     * Loads prompt presets from preferences
     */
    private fun loadPromptPresets(prefs: Prefs) {
        try {
            val presets = prefs.getPromptPresets()
            _promptPresets.value = presets
            val activeId = prefs.activePromptId
            _activePromptPreset.value = presets.firstOrNull { it.id == activeId }
                ?: presets.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt presets", e)
        }
    }

    /**
     * Selects a specific prompt preset by ID
     */
    fun selectPromptPreset(prefs: Prefs, presetId: String) {
        try {
            val presets = _promptPresets.value
            val selected = presets.firstOrNull { it.id == presetId }
            if (selected != null) {
                prefs.activePromptId = presetId
                _activePromptPreset.value = selected
                Log.d(TAG, "Selected prompt preset: ${selected.title}")
            } else {
                Log.w(TAG, "Prompt preset not found: $presetId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select prompt preset", e)
        }
    }

    /**
     * Updates the active prompt preset with a mutation function
     */
    fun updateActivePromptPreset(
        prefs: Prefs,
        mutator: (PromptPreset) -> PromptPreset
    ) {
        try {
            val list = _promptPresets.value.toMutableList()
            val activeId = prefs.activePromptId
            val idx = list.indexOfFirst { it.id == activeId }

            if (idx >= 0) {
                list[idx] = mutator(list[idx])
                prefs.setPromptPresets(list)
                _promptPresets.value = list
                _activePromptPreset.value = list[idx]
            } else {
                Log.w(TAG, "Active prompt preset not found for update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active prompt preset", e)
        }
    }

    /**
     * Adds a new prompt preset
     */
    fun addPromptPreset(prefs: Prefs, defaultTitle: String, defaultContent: String): Boolean {
        return try {
            val newPreset = PromptPreset(
                id = UUID.randomUUID().toString(),
                title = defaultTitle,
                content = defaultContent
            )

            val list = _promptPresets.value.toMutableList()
            list.add(newPreset)
            prefs.setPromptPresets(list)
            prefs.activePromptId = newPreset.id

            _promptPresets.value = list
            _activePromptPreset.value = newPreset
            Log.d(TAG, "Added new prompt preset: $defaultTitle")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add prompt preset", e)
            false
        }
    }

    /**
     * Deletes the currently active prompt preset
     */
    fun deleteActivePromptPreset(prefs: Prefs): Boolean {
        return try {
            val list = _promptPresets.value.toMutableList()
            if (list.isEmpty()) {
                Log.w(TAG, "Cannot delete: no prompt presets exist")
                return false
            }

            val activeId = prefs.activePromptId
            val idx = list.indexOfFirst { it.id == activeId }
            if (idx >= 0) {
                list.removeAt(idx)
                prefs.setPromptPresets(list)

                val newActive = list.firstOrNull()
                prefs.activePromptId = newActive?.id ?: ""

                _promptPresets.value = list
                _activePromptPreset.value = newActive
                Log.d(TAG, "Deleted prompt preset at index $idx")
                true
            } else {
                Log.w(TAG, "Active prompt preset not found for deletion")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prompt preset", e)
            false
        }
    }

    // ======== Helper Methods ========

    /**
     * Gets the index of the active LLM provider
     */
    fun getActiveLlmProviderIndex(): Int {
        val activeId = _activeLlmProvider.value?.id ?: return 0
        return _llmProfiles.value.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
    }

    /**
     * Gets the index of the active prompt preset
     */
    fun getActivePromptPresetIndex(): Int {
        val activeId = _activePromptPreset.value?.id ?: return 0
        return _promptPresets.value.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
    }
}
