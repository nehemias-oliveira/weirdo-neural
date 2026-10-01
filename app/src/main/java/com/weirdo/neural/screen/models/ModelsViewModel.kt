package com.weirdo.neural.screen.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weirdo.neural.core.data.db.entity.InstalledModelEntity
import com.weirdo.neural.core.data.model.ModelInfo
import com.weirdo.neural.core.data.prefs.SettingsRepository
import com.weirdo.neural.core.data.repo.DownloadProgress
import com.weirdo.neural.core.data.repo.ModelsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelUiItem(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val contextSize: Int,
    val tags: List<String>,
    val recommendedFor: String,
    val installed: InstalledModelEntity?,
    val info: ModelInfo?,
    val download: DownloadProgress?,
    val isActive: Boolean,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val repo: ModelsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _catalog = MutableStateFlow<List<ModelInfo>>(emptyList())
    private val _installed = MutableStateFlow<Map<String, InstalledModelEntity>>(emptyMap())
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val _activeTag = MutableStateFlow<String?>(null)
    private val _activeModelId = MutableStateFlow<String?>(null)
    private val _loading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _importing = MutableStateFlow(false)

    private val downloadJobs = mutableMapOf<String, Job>()

    val items: StateFlow<List<ModelUiItem>> = combine(
        _catalog, _installed, _downloads, _activeTag, _activeModelId,
    ) { catalog, installed, downloads, tag, activeId ->
        val fromCatalog = catalog.map { info ->
            ModelUiItem(
                id = info.id,
                name = info.name,
                description = info.description,
                sizeBytes = info.sizeBytes,
                contextSize = info.contextSize,
                tags = info.tags,
                recommendedFor = info.recommendedFor,
                installed = installed[info.id],
                info = info,
                download = downloads[info.id],
                isActive = activeId == info.id,
            )
        }
        val importedOnly = installed.values
            .filter { it.source == "imported" }
            .map { entity ->
                ModelUiItem(
                    id = entity.modelId,
                    name = entity.displayName,
                    description = "Importado localmente",
                    sizeBytes = entity.sizeBytes,
                    contextSize = entity.contextSize,
                    tags = listOf("importado"),
                    recommendedFor = "",
                    installed = entity,
                    info = null,
                    download = null,
                    isActive = activeId == entity.modelId,
                )
            }
        val merged = fromCatalog + importedOnly
        if (tag == null) merged else merged.filter { tag in it.tags }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loading: StateFlow<Boolean> = _loading
    val error: StateFlow<String?> = _error
    val activeTag: StateFlow<String?> = _activeTag
    val importing: StateFlow<Boolean> = _importing

    init {
        viewModelScope.launch {
            repo.observeInstalled().collect { list ->
                _installed.value = list.associateBy { it.modelId }
            }
        }
        viewModelScope.launch {
            settings.activeModelId.collect { _activeModelId.value = it }
        }
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val catalog = repo.getCatalog(forceRefresh = force)
                _catalog.value = catalog.models
            } catch (t: Throwable) {
                _error.value = t.message ?: "Erro ao carregar catálogo"
            } finally {
                _loading.value = false
            }
        }
    }

    fun setTagFilter(tag: String?) {
        _activeTag.value = tag
    }

    fun startDownload(info: ModelInfo) {
        if (downloadJobs.containsKey(info.id)) return
        val job = viewModelScope.launch {
            try {
                repo.download(info).collect { progress ->
                    _downloads.value = _downloads.value + (info.id to progress)
                }
            } finally {
                kotlinx.coroutines.delay(2_000)
                _downloads.value = _downloads.value - info.id
                downloadJobs.remove(info.id)
            }
        }
        downloadJobs[info.id] = job
    }

    fun cancelDownload(info: ModelInfo) {
        downloadJobs[info.id]?.cancel()
        downloadJobs.remove(info.id)
        _downloads.value = _downloads.value - info.id
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            try {
                repo.delete(modelId)
                if (_activeModelId.value == modelId) {
                    settings.setActiveModel(null)
                }
            } catch (t: Throwable) {
                _error.value = "Falha ao excluir: ${t.message}"
            }
        }
    }

    fun setAsActive(modelId: String) {
        viewModelScope.launch { settings.setActiveModel(modelId) }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            _error.value = null
            try {
                repo.importFromUri(uri)
            } catch (t: Throwable) {
                _error.value = "Falha ao importar: ${t.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
