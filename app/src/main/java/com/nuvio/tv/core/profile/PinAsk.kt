package com.nuvio.tv.core.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** When a profile's PIN is asked. Kept on this box only, like the rating limits. */
enum class PinAsk(val code: String) {
    EVERY_TIME("every"),
    NOT_AT_START("not_start"),
    LIMITS_ONLY("limits");

    /** Null while the PIN state is not known yet. */
    fun atStart(hasPin: Boolean?): Boolean? = hasPin?.let { it && this == EVERY_TIME }

    fun toOpen(hasPin: Boolean): Boolean = hasPin && this != LIMITS_ONLY

    companion object {
        fun fromCode(code: String?): PinAsk = entries.firstOrNull { it.code == code } ?: EVERY_TIME

        /** Only the main profile's PIN guards the kids' limits, so only it can be asked for those alone. */
        fun choicesFor(isPrimary: Boolean): List<PinAsk> = if (isPrimary) entries else listOf(EVERY_TIME, NOT_AT_START)

        /** Profiles holding a relaxed choice without a PIN (removed here or on another device): they start again from Every time. */
        fun withoutPin(asks: Map<Int, PinAsk>, pinStates: Map<Int, Boolean>): Set<Int> =
            asks.filter { (id, ask) -> ask != EVERY_TIME && pinStates[id] != true }.keys
    }
}

@Singleton
class PinAskStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences("arc_pin_ask", Context.MODE_PRIVATE) }
    private val _asks by lazy { MutableStateFlow(read()) }
    val asks: StateFlow<Map<Int, PinAsk>> by lazy { _asks.asStateFlow() }

    private fun read(): Map<Int, PinAsk> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix("p").toIntOrNull() ?: return@mapNotNull null
        id to PinAsk.fromCode(value as? String)
    }.toMap()

    fun askFor(profileId: Int): PinAsk = asks.value[profileId] ?: PinAsk.EVERY_TIME

    fun set(profileId: Int, ask: PinAsk) {
        prefs.edit().apply { if (ask == PinAsk.EVERY_TIME) remove("p$profileId") else putString("p$profileId", ask.code) }.apply()
        _asks.value = if (ask == PinAsk.EVERY_TIME) _asks.value - profileId else _asks.value + (profileId to ask)
    }

    /** A removed PIN or profile starts again from Every time. */
    fun forgetProfile(profileId: Int) = set(profileId, PinAsk.EVERY_TIME)

    fun forgetAll() {
        prefs.edit().clear().apply()
        _asks.value = emptyMap()
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PinAskEntryPoint {
    fun pinAsks(): PinAskStore
}

fun pinAskStore(context: Context): PinAskStore =
    dagger.hilt.android.EntryPointAccessors.fromApplication(context, PinAskEntryPoint::class.java).pinAsks()
