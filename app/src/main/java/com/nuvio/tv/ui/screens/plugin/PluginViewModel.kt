package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginSafety
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.RepositoryConfigServer
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.domain.model.NewRepoProviders
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginUiState())
    val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()

    val isReadOnly: Boolean
        get() {
            val profile = profileManager.activeProfile ?: return false
            return !profile.isPrimary && profile.usesPrimaryPlugins
        }

    private var repoServer: RepositoryConfigServer? = null
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }

    init {
        observePluginData()
        viewModelScope.launch {
            pluginManager.lastOutcomes.collect { outcomes -> _uiState.update { it.copy(outcomes = outcomes) } }
        }
    }

    private fun observePluginData() {
        viewModelScope.launch {
            combine(
                pluginManager.pluginsEnabled,
                pluginManager.groupStreamsByRepository,
                pluginManager.repositories,
                pluginManager.scrapers,
                pluginManager.syncedReposToReview
            ) { enabled, groupStreamsByRepository, repos, scrapers, toReview ->
                PluginUiState(
                    pluginsEnabled = enabled,
                    groupStreamsByRepository = groupStreamsByRepository,
                    repositories = repos,
                    scrapers = scrapers,
                    syncedReposToReview = repos.filter { it.id in toReview }
                )
            }.collect { nextState ->
                val visibleScrapers = if (isReadOnly) {
                    nextState.scrapers.filter { it.enabled }
                } else {
                    nextState.scrapers
                }
                _uiState.update {
                    it.copy(
                        pluginsEnabled = nextState.pluginsEnabled,
                        groupStreamsByRepository = nextState.groupStreamsByRepository,
                        repositories = nextState.repositories,
                        scrapers = visibleScrapers,
                        syncedReposToReview = if (isReadOnly) emptyList() else nextState.syncedReposToReview
                    )
                }
            }
        }
    }

    fun onEvent(event: PluginUiEvent) {
        when (event) {
            is PluginUiEvent.AddRepository -> requestAddRepository(event.url)
            is PluginUiEvent.RemoveRepository -> removeRepository(event.repoId)
            is PluginUiEvent.RefreshRepository -> refreshRepository(event.repoId)
            is PluginUiEvent.ToggleScraper -> toggleScraper(event.scraperId, event.enabled)
            is PluginUiEvent.ToggleAllScrapersForRepo -> toggleAllScrapersForRepo(event.repoId, event.enabled)
            is PluginUiEvent.TestScraper -> testScraper(event.scraperId)
            is PluginUiEvent.SetPluginsEnabled -> setPluginsEnabled(event.enabled)
            is PluginUiEvent.SetGroupStreamsByRepository -> setGroupStreamsByRepository(event.enabled)
            PluginUiEvent.ClearTestResults -> _uiState.update { it.copy(testResults = null, testDiagnostics = null, testScraperId = null) }
            PluginUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            PluginUiEvent.ClearSuccess -> _uiState.update { it.copy(successMessage = null) }
            PluginUiEvent.StartQrMode -> startQrMode()
            PluginUiEvent.StopQrMode -> stopQrMode()
            PluginUiEvent.ConfirmPendingRepoChange -> confirmPendingRepoChange()
            PluginUiEvent.RejectPendingRepoChange -> rejectPendingRepoChange()
            PluginUiEvent.ConfirmPendingScraperEnable -> confirmPendingScraperEnable()
            PluginUiEvent.DismissPendingScraperEnable -> dismissPendingScraperEnable()
            PluginUiEvent.ConfirmPendingRepoAdd -> {
                val url = _uiState.value.pendingRepoAdd
                _uiState.update { it.copy(pendingRepoAdd = null) }
                if (url != null) addRepository(url)
            }
            PluginUiEvent.DismissPendingRepoAdd -> _uiState.update { it.copy(pendingRepoAdd = null) }
            PluginUiEvent.AcknowledgeSyncedRepos -> viewModelScope.launch { pluginManager.acknowledgeSyncedRepos() }
            is PluginUiEvent.OpenProviderChooser -> openProviderChooser(event.repoId, isNewRepo = false)
            is PluginUiEvent.ChooserToggle -> updateChooser { it.toggle(event.scraperId) }
            is PluginUiEvent.ChooserType -> updateChooser { it.copy(type = event.type) }
            is PluginUiEvent.ChooserLanguage -> updateChooser { it.copy(language = event.language) }
            PluginUiEvent.ChooserSelectShown -> updateChooser { it.selectShown() }
            PluginUiEvent.ChooserClear -> updateChooser { it.clear() }
            PluginUiEvent.ChooserApply -> closeChooser(apply = true)
            PluginUiEvent.ChooserKeepAllOff -> updateChooser { it.clear() }.also { closeChooser(apply = true) }
            PluginUiEvent.ChooserDismiss -> {
                val isNewRepo = _uiState.value.providerChooser?.isNewRepo == true
                if (isNewRepo) updateChooser { it.clear() }
                closeChooser(apply = isNewRepo)
            }
        }
    }

    private fun updateChooser(change: (ProviderChooserState) -> ProviderChooserState) {
        _uiState.update { state -> state.copy(providerChooser = state.providerChooser?.let(change)) }
    }

    private fun openProviderChooser(repoId: String, isNewRepo: Boolean) {
        if (isReadOnly) return
        viewModelScope.launch {
            val repo = pluginManager.repositories.first().firstOrNull { it.id == repoId } ?: return@launch
            val providers = pluginManager.scrapers.first().filter { it.repositoryId == repoId }
            if (providers.size < 2 && isNewRepo) return@launch
            _uiState.update {
                it.copy(
                    providerChooser = ProviderChooserState.forRepo(
                        repoId = repoId,
                        repoName = repo.name.ifBlank { repo.url },
                        providers = providers,
                        isNewRepo = isNewRepo
                    )
                )
            }
        }
    }

    private fun closeChooser(apply: Boolean) {
        val chooser = _uiState.value.providerChooser ?: return
        _uiState.update { it.copy(providerChooser = null) }
        if (!apply) return
        viewModelScope.launch {
            pluginManager.setProvidersForRepo(chooser.repoId, chooser.selected)
            pluginManager.acknowledgeSyncedRepo(chooser.repoId)
        }
    }

    private fun requestAddRepository(url: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.plugin_error_invalid_url)) }
            return
        }
        _uiState.update { it.copy(pendingRepoAdd = url.trim()) }
    }

    private fun addRepository(url: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.plugin_error_invalid_url)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingRepo = true, errorMessage = null) }

            val knownRepoIds = pluginManager.repositories.first().map { it.id }.toSet()
            val result = pluginManager.addRepository(url, NewRepoProviders.OFF_IF_SEVERAL)

            result.fold(
                onSuccess = { repo ->
                    _uiState.update {
                        it.copy(
                            isAddingRepo = false,
                            successMessage = context.getString(
                                R.string.plugin_repo_added_with_providers,
                                repo.name,
                                repo.scraperCount
                            )
                        )
                    }
                    if (repo.id !in knownRepoIds) openProviderChooser(repo.id, isNewRepo = true)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isAddingRepo = false,
                            errorMessage = context.getString(R.string.plugin_error_add_repo, e.message ?: "")
                        )
                    }
                }
            )
        }
    }

    private fun removeRepository(repoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            pluginManager.removeRepository(repoId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    successMessage = context.getString(R.string.plugin_repo_removed)
                )
            }
        }
    }

    private fun refreshRepository(repoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = pluginManager.refreshRepository(repoId)

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = context.getString(R.string.plugin_repo_refreshed)
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = context.getString(R.string.plugin_error_refresh, e.message ?: "")
                        )
                    }
                }
            )
        }
    }

    private fun toggleScraper(scraperId: String, enabled: Boolean) {
        val scraper = _uiState.value.scrapers.firstOrNull { it.id == scraperId }
        if (enabled && scraper != null && PluginSafety.isVideoEasyScraper(scraper.id, scraper.name, scraper.filename)) {
            _uiState.update {
                it.copy(
                    pendingScraperEnable = PendingScraperEnableInfo(
                        scraperId = scraper.id,
                        scraperName = scraper.name
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            pluginManager.toggleScraper(scraperId, enabled)
        }
    }

    private fun toggleAllScrapersForRepo(repoId: String, enabled: Boolean) {
        viewModelScope.launch {
            pluginManager.toggleAllScrapersForRepo(repoId, enabled)
        }
    }

    private fun confirmPendingScraperEnable() {
        val pending = _uiState.value.pendingScraperEnable ?: return
        _uiState.update { it.copy(pendingScraperEnable = null) }
        viewModelScope.launch {
            pluginManager.toggleScraper(pending.scraperId, true)
        }
    }

    private fun dismissPendingScraperEnable() {
        _uiState.update { it.copy(pendingScraperEnable = null) }
    }

    private fun setPluginsEnabled(enabled: Boolean) {
        if (isReadOnly) return
        viewModelScope.launch {
            pluginManager.setPluginsEnabled(enabled)
        }
    }

    private fun setGroupStreamsByRepository(enabled: Boolean) {
        if (isReadOnly) return
        viewModelScope.launch {
            pluginManager.setGroupStreamsByRepository(enabled)
        }
    }

    private fun testScraper(scraperId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testScraperId = scraperId, testResults = null, testDiagnostics = null) }

            val result = pluginManager.testScraper(scraperId)

            result.fold(
                onSuccess = { (results, diagnostics) ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResults = results,
                            testDiagnostics = diagnostics,
                            successMessage = if (results.isEmpty()) {
                                context.getString(R.string.plugin_test_no_results)
                            } else {
                                context.getString(R.string.plugin_test_found_streams, results.size)
                            }
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResults = emptyList(),
                            testDiagnostics = null,
                            errorMessage = context.getString(
                                R.string.plugin_error_test,
                                e.message ?: context.getString(R.string.error_unknown)
                            )
                        )
                    }
                }
            )
        }
    }

    private fun normalizeUrlForComparison(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }

    private fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.error_network_required)) }
            return
        }

        stopRepoServerInternal()

        repoServer = RepositoryConfigServer.startOnAvailablePort(
            context = context,
            currentRepositoriesProvider = {
                _uiState.value.repositories.map { repo ->
                    RepositoryConfigServer.RepositoryInfo(
                        url = repo.url,
                        name = repo.name.ifBlank { repo.url },
                        description = repo.description
                    )
                }
            },
            onChangeProposed = { change -> handleRepoChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = repoServer
        if (activeServer == null) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                errorMessage = null
            )
        }
    }

    fun stopQrMode() {
        stopRepoServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingRepoChange = null
            )
        }
    }

    private fun stopRepoServerInternal() {
        repoServer?.stop()
        repoServer = null
    }

    private fun handleRepoChangeProposed(change: RepositoryConfigServer.PendingRepoChange) {
        val currentUrls = _uiState.value.repositories.map { normalizeUrlForComparison(it.url) }.toSet()
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentUrls }
        val removed = _uiState.value.repositories
            .map { it.url }
            .filter { normalizeUrlForComparison(it) !in proposedNormalized }

        _uiState.update {
            it.copy(
                pendingRepoChange = PendingRepoChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    addedUrls = added,
                    removedUrls = removed
                )
            )
        }
    }

    private fun confirmPendingRepoChange() {
        val pending = _uiState.value.pendingRepoChange ?: return

        _uiState.update { it.copy(pendingRepoChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            for (url in pending.addedUrls) {
                pluginManager.addRepository(url, NewRepoProviders.ALL_OFF)
            }

            val currentRepos = _uiState.value.repositories
            for (url in pending.removedUrls) {
                val repo = currentRepos.find { normalizeUrlForComparison(it.url) == normalizeUrlForComparison(url) }
                if (repo != null) {
                    pluginManager.removeRepository(repo.id)
                }
            }

            repoServer?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingRepoChange = null) }

            delay(2500)

            stopRepoServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    private fun rejectPendingRepoChange() {
        val pending = _uiState.value.pendingRepoChange ?: return
        repoServer?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingRepoChange = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopRepoServerInternal()
    }
}
