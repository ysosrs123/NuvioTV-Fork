package com.nuvio.tv.ui.screens.scout.ask

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.scout.ScoutArtwork
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.core.scout.ask.AskPick
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.core.scout.ask.ASK_MAX_QUESTION
import com.nuvio.tv.core.scout.ask.ASK_MAX_SAVED_ROWS
import com.nuvio.tv.core.scout.ask.AskConfig
import com.nuvio.tv.core.scout.ask.AskFailure
import com.nuvio.tv.core.scout.ask.AskItem
import com.nuvio.tv.core.scout.ask.AskKeyTest
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.ask.AskKeys
import com.nuvio.tv.core.scout.ask.AskOutcome
import com.nuvio.tv.core.scout.ask.AskPhonePage
import com.nuvio.tv.core.scout.ask.AskPhoneKeyStatus
import com.nuvio.tv.core.scout.ask.AskPhoneServer
import com.nuvio.tv.core.scout.ask.AskPhoneState
import com.nuvio.tv.core.scout.ask.AskProvider
import com.nuvio.tv.core.scout.ask.AskRowsStore
import com.nuvio.tv.core.scout.ask.AskRunner
import com.nuvio.tv.core.scout.ask.AskSavedRow
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.core.scout.ask.newAskPhoneToken
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.withAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class AskStage { CHOOSE_AI, ASK, THINKING, RESULTS }

data class AskStarter(val text: String, val meta: MetaPreview? = null)

data class AskAsked(val question: String, val posters: List<String>, val rowId: String?)

data class AskUiState(
    val stage: AskStage = AskStage.ASK,
    val keys: AskKeys = AskKeys(),
    val selected: AskProvider = AskProvider.GEMINI,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testGood: Boolean = false,
    val phoneUrl: String? = null,
    val phoneQr: Bitmap? = null,
    val phoneError: String? = null,
    val question: String = "",
    val rowName: String = "",
    val results: List<AskItem> = emptyList(),
    val showSeen: Boolean = false,
    val dimWatched: Boolean = false,
    val failure: String? = null,
    val rowSaved: Boolean = false,
    val rowMessage: String? = null,
    val recent: List<String> = emptyList(),
    val savedRows: List<AskSavedRow> = emptyList(),
    val recentArt: Map<String, List<String>> = emptyMap(),
    val starters: List<AskStarter> = emptyList(),
    val wall: List<String> = emptyList()
) {
    val items: List<AskItem>
        get() = if (showSeen) results else results.filterNot(AskItem::seen)

    val seenCount: Int
        get() = results.count(AskItem::seen)

    val asked: List<AskAsked>
        get() {
            val saved = savedRows.map { row ->
                AskAsked(row.question, row.items.mapNotNull { it.poster }.take(3), row.id)
            }
            val others = recent
                .filterNot { question -> savedRows.any { it.question.equals(question, ignoreCase = true) } }
                .map { AskAsked(it, recentArt[it].orEmpty(), null) }
            return saved + others
        }

    val providerName: String get() = (keys.active ?: selected).displayName()
}

internal fun AskProvider.displayName(): String = when (this) {
    AskProvider.GEMINI -> "Gemini"
    AskProvider.GROQ -> "Groq"
    AskProvider.CHATGPT -> "OpenAI"
    AskProvider.CLAUDE -> "Claude"
    AskProvider.OTHER -> "AI"
}

@HiltViewModel
class AskViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val service: AskService,
    private val vault: AskKeyVault,
    private val rows: AskRowsStore,
    private val runner: AskRunner,
    private val artwork: ScoutArtwork,
    private val layout: LayoutPreferenceDataStore,
    private val engine: dagger.Lazy<ScoutEngine>
) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private val token = newAskPhoneToken()
    private val startQuestion: String? = savedStateHandle.get<String>(ARG_QUESTION)
    val leavesWithBack: Boolean get() = startQuestion != null
    @Volatile
    private var server: AskPhoneServer? = null
    @Volatile
    private var phoneWanted = false
    private var askJob: Job? = null

    init {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { vault.current() }
            _state.update {
                it.copy(
                    keys = keys,
                    selected = keys.active ?: it.selected,
                    stage = if (keys.activeConfig == null) AskStage.CHOOSE_AI else AskStage.ASK
                )
            }
            if (keys.activeConfig != null) startQuestion?.let(::ask)
        }
        viewModelScope.launch { rows.recent.collect { list -> _state.update { it.copy(recent = list) } } }
        viewModelScope.launch { rows.recentArt.collect { art -> _state.update { it.copy(recentArt = art) } } }
        _state.update { it.copy(starters = starterTexts().map { text -> AskStarter(text) }) }
        viewModelScope.launch { loadArtwork() }
        viewModelScope.launch {
            layout.watchedTitlesMode.collect { mode -> _state.update { it.copy(dimWatched = mode != WatchedTitlesMode.SHOW) } }
        }
        viewModelScope.launch { rows.rows.collect { list -> _state.update { it.copy(savedRows = list) } } }
    }

    private fun text(id: Int, vararg args: Any): String = context.withAppLocale().getString(id, *args)

    private fun starterTexts(): List<String> = listOf(
        R.string.scout_question_example_1, R.string.scout_question_example_2,
        R.string.scout_question_example_3, R.string.scout_question_example_4
    ).map { unquoted(text(it)) }

    private suspend fun loadArtwork() {
        val show = { art: List<MetaPreview?> ->
            _state.update { current ->
                current.copy(starters = current.starters.mapIndexed { index, starter -> starter.copy(meta = art.getOrNull(index) ?: starter.meta) })
            }
        }
        val art = withContext(Dispatchers.IO) { runCatching { service.titleArt(STARTER_TITLES, onFound = { show(it) }) }.getOrNull() }.orEmpty()
        if (art.isNotEmpty()) show(art)
        val own = withContext(Dispatchers.IO) {
            runCatching {
                val pattern = patternForScreen(
                    layout.customPosterUrlPattern.first(),
                    CustomPosterScreen.SEARCH,
                    layout.customPosterEnabledScreens.first()
                )
                artwork.withPattern(
                    engine.get().pool.value?.picked.orEmpty().take(WALL_POSTERS).mapNotNull { pick ->
                        pick.poster?.let { poster ->
                            MetaPreview(
                                id = "tmdb:${pick.tmdbId}",
                                type = if (pick.tv) ContentType.SERIES else ContentType.MOVIE,
                                rawType = if (pick.tv) "series" else "movie",
                                name = pick.name,
                                poster = poster,
                                posterShape = PosterShape.POSTER,
                                background = null,
                                logo = null,
                                description = null,
                                releaseInfo = null,
                                imdbRating = null,
                                genres = emptyList()
                            )
                        }
                    },
                    pattern
                ).mapNotNull { it.poster }
            }.getOrNull()
        }.orEmpty()
        _state.update { current ->
            current.copy(
                wall = (own + current.asked.flatMap { it.posters } + art.filterNotNull().mapNotNull { it.poster })
                    .distinct()
                    .take(WALL_POSTERS)
            )
        }
    }

    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }

    fun startPhonePage() {
        if (phoneWanted) return
        phoneWanted = true
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _state.update { it.copy(phoneError = text(R.string.error_network_required)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val started = AskPhoneServer.startOnAvailablePort(
                token = token,
                page = { AskPhonePage.html(context, token, startOnKey = _state.value.stage == AskStage.CHOOSE_AI) },
                state = ::phoneState,
                onKey = ::acceptKey,
                onQuestion = { viewModelScope.launch { ask(it) } },
                logo = { logoBytes }
            )
            if (started == null) {
                _state.update { it.copy(phoneError = text(R.string.error_server_ports_unavailable)) }
                return@launch
            }
            if (!phoneWanted) {
                started.stop()
                return@launch
            }
            server = started
            val url = "http://$ip:${started.listeningPort}/?t=$token"
            val qr = QrCodeGenerator.generate(url, 512)
            _state.update { it.copy(phoneUrl = "http://$ip:${started.listeningPort}", phoneQr = qr, phoneError = null) }
        }
    }

    fun stopPhonePage() {
        phoneWanted = false
        server?.stop()
        server = null
        _state.update { it.copy(phoneUrl = null, phoneQr = null) }
    }

    private fun phoneState(): AskPhoneState {
        val state = _state.value
        return AskPhoneState(
            selected = state.selected,
            active = state.keys.active,
            providers = AskProvider.entries.map { provider ->
                val config = state.keys.configs[provider]
                AskPhoneKeyStatus(provider, config != null, config?.model.orEmpty())
            },
            testMessage = state.testMessage,
            testGood = state.testGood
        )
    }

    private fun acceptKey(config: AskConfig): Boolean {
        vault.save(config)
        val keys = vault.current()
        _state.update { it.copy(keys = keys, selected = config.provider, testMessage = null, testGood = false) }
        runTest(config, advanceWhenGood = true)
        return true
    }

    fun lookAtProvider(provider: AskProvider) {
        if (provider != _state.value.selected) {
            _state.update { it.copy(selected = provider, testMessage = null, testGood = false) }
        }
    }

    fun selectProvider(provider: AskProvider) {
        val hasKey = provider in _state.value.keys.configs
        _state.update { it.copy(selected = provider, testMessage = null, testGood = false) }
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) {
                runCatching { if (hasKey) vault.select(provider) }
                runCatching { vault.current() }.getOrNull()
            } ?: return@launch
            _state.update { it.copy(keys = keys) }
        }
    }

    fun testSelected() {
        val config = _state.value.keys.configs[_state.value.selected] ?: return
        runTest(config, advanceWhenGood = false)
    }

    fun removeSelectedKey() {
        val provider = _state.value.selected
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) {
                runCatching { vault.remove(provider) }
                runCatching { vault.current() }.getOrNull()
            } ?: return@launch
            _state.update { it.copy(keys = keys, selected = keys.active ?: it.selected, testMessage = null, testGood = false) }
        }
    }

    private fun runTest(config: AskConfig, advanceWhenGood: Boolean) {
        _state.update { it.copy(testing = true) }
        viewModelScope.launch {
            val result = service.testKey(config)
            val good = result is AskKeyTest.Good
            val message = when (result) {
                AskKeyTest.Good -> text(R.string.scout_ai_test_good)
                is AskKeyTest.Failed -> text(failureText(result.failure))
            }
            _state.update {
                it.copy(
                    testing = false,
                    testMessage = message,
                    testGood = good,
                    stage = if (good && advanceWhenGood && it.stage == AskStage.CHOOSE_AI) AskStage.ASK else it.stage
                )
            }
        }
    }

    fun openChooseAi() = _state.update { it.copy(stage = AskStage.CHOOSE_AI) }
    fun backToAsk() = _state.update { it.copy(stage = AskStage.ASK, failure = null) }

    fun cancelAsk() {
        askJob?.cancel()
        _state.update { it.copy(stage = AskStage.ASK) }
    }

    fun ask(rawQuestion: String) {
        val question = rawQuestion.trim().take(ASK_MAX_QUESTION)
        if (question.isEmpty()) return
        askJob?.cancel()
        _state.update {
            it.copy(stage = AskStage.THINKING, question = question, failure = null, results = emptyList(), showSeen = false, rowSaved = false, rowMessage = null)
        }
        askJob = viewModelScope.launch {
            when (val outcome = runner.ask(question)) {
                is AskOutcome.Answer -> {
                    _state.update {
                        it.copy(
                            stage = AskStage.RESULTS,
                            rowName = outcome.rowName,
                            results = outcome.items + outcome.held,
                            showSeen = outcome.items.isEmpty() || outcome.items.any(AskItem::seen)
                        )
                    }
                }
                is AskOutcome.Failed -> _state.update {
                    it.copy(
                        stage = if (outcome.failure == AskFailure.NO_KEY) AskStage.CHOOSE_AI else AskStage.ASK,
                        failure = text(failureText(outcome.failure))
                    )
                }
            }
        }
    }

    fun toggleSeen() = _state.update { it.copy(showSeen = !it.showSeen) }

    fun saveRow() {
        val state = _state.value
        viewModelScope.launch {
            val saved = rows.save(state.question, state.rowName, state.items)
            _state.update {
                it.copy(
                    rowSaved = saved,
                    rowMessage = if (saved) text(R.string.scout_question_saved_note)
                    else text(R.string.scout_question_rows_full, ASK_MAX_SAVED_ROWS)
                )
            }
        }
    }

    fun clearAsked() {
        viewModelScope.launch { rows.clearRecent() }
    }

    fun forgetAsked(question: String) {
        viewModelScope.launch { rows.forgetRecent(question) }
    }

    fun removeRow(id: String) {
        viewModelScope.launch { rows.removeRow(id) }
    }

    override fun onCleared() {
        stopPhonePage()
        super.onCleared()
    }
}

internal const val ARG_QUESTION = "q"
private const val WALL_POSTERS = 16
private val STARTER_TITLES = listOf(
    AskPick("Interstellar", 2014, false, ""),
    AskPick("Dark", 2017, true, ""),
    AskPick("Frozen Planet", 2011, true, ""),
    AskPick("Severance", 2022, true, "")
)

internal fun unquoted(text: String): String =
    text.trim().trim('"', '\u201C', '\u201D', '\u201E', '\u00AB', '\u00BB', '\u300C', '\u300D', '\u2018', '\u2019', ' ')

internal fun failureText(failure: AskFailure): Int = when (failure) {
    AskFailure.NO_KEY -> R.string.scout_question_error_no_key
    AskFailure.BAD_KEY -> R.string.scout_question_error_bad_key
    AskFailure.NO_CREDIT -> R.string.scout_question_error_no_credit
    AskFailure.RATE_LIMITED -> R.string.scout_question_error_rate
    AskFailure.MODEL_GONE -> R.string.scout_question_error_model
    AskFailure.REGION -> R.string.scout_question_error_region
    AskFailure.DECLINED -> R.string.scout_question_error_declined
    AskFailure.CUT_OFF -> R.string.scout_question_error_cut
    AskFailure.BUSY -> R.string.scout_question_error_busy
    AskFailure.NETWORK -> R.string.scout_question_error_network
    AskFailure.BAD_ANSWER -> R.string.scout_question_error_answer
    AskFailure.NOTHING_FOUND -> R.string.scout_question_error_nothing
    AskFailure.REJECTED -> R.string.scout_question_error_rejected
}
