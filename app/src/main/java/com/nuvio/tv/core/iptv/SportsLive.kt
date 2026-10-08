package com.nuvio.tv.core.iptv

import org.json.JSONObject

enum class SportsOverlayStyle { OFF, GLANCE, BUG, CARDS, TICKER }
enum class SportsAlertGames { FOLLOWED, FOLLOWED_AND_CLOSE, ALL_LIVE }
enum class SportsChangeKind { STARTED, SCORED, FINISHED }
enum class SportsNuvioAlert { POPUP, CHIP, OFF }

data class SportsChange(val fixture: SportsFixture, val kind: SportsChangeKind, val side: FixtureSide?, val detectedAt: Long) {
    val key: String get() = fixture.key
}

object SportsChanges {
    fun diff(previous: Map<String, SportsFixture>, current: List<SportsFixture>, nowMillis: Long): List<SportsChange> =
        current.distinctBy { it.key }.flatMap { fixture ->
            val before = previous[fixture.key] ?: return@flatMap emptyList()
            buildList {
                if (before.status == FixtureStatus.SCHEDULED && fixture.status == FixtureStatus.LIVE) add(SportsChange(fixture, SportsChangeKind.STARTED, null, nowMillis))
                scored(before, fixture)?.let { add(SportsChange(fixture, SportsChangeKind.SCORED, it.side, nowMillis)) }
                if (before.status == FixtureStatus.LIVE && fixture.status == FixtureStatus.FINAL) add(SportsChange(fixture, SportsChangeKind.FINISHED, null, nowMillis))
            }
        }

    private class Scored(val side: FixtureSide?)

    private fun scored(before: SportsFixture, after: SportsFixture): Scored? {
        if (after.status == FixtureStatus.SCHEDULED) return null
        val now = points(after) ?: return null
        val then = points(before) ?: if (before.status == FixtureStatus.SCHEDULED) 0 to 0 else return null
        val home = now.first > then.first
        val away = now.second > then.second
        return when {
            home && away -> Scored(null)
            home -> Scored(FixtureSide.HOME)
            away -> Scored(FixtureSide.AWAY)
            else -> null
        }
    }

    fun points(fixture: SportsFixture): Pair<Int, Int>? {
        val (home, away) = SportsFixtureText.scores(fixture) ?: return null
        return (value(home) ?: return null) to (value(away) ?: return null)
    }

    private val TOTAL = Regex("\\((\\d{1,5})\\)")
    private val LEADING = Regex("^\\d{1,5}")

    private fun value(text: String): Int? = TOTAL.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: LEADING.find(text.trim())?.value?.toIntOrNull()
}

class SportsAlertHold(holdMillis: Long, private val maxPending: Int = 50) {
    @Volatile var holdMillis: Long = holdMillis.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }
    private val pending = mutableListOf<SportsChange>()

    val size: Int @Synchronized get() = pending.size

    @Synchronized fun offer(changes: List<SportsChange>) {
        for (change in changes) {
            pending.removeAll { it.key == change.key && it.kind == change.kind }
            pending += change
        }
        while (pending.size > maxPending) pending.removeAt(pending.indices.minBy { pending[it].detectedAt })
    }

    @Synchronized fun release(nowMillis: Long, max: Int = MAX_RELEASED): List<SportsChange> {
        val ready = pending.withIndex().filter { it.value.detectedAt + holdMillis <= nowMillis }.sortedBy { it.value.detectedAt }.take(max)
        val taken = ready.map { it.index }.toSet()
        val kept = pending.filterIndexed { index, _ -> index !in taken }
        pending.clear(); pending += kept
        return ready.map { it.value }
    }

    @Synchronized fun clear() = pending.clear()

    companion object { const val MAX_RELEASED = 3 }
}

object SportsAlertFilter {
    fun wanted(change: SportsChange, games: SportsAlertGames, favourites: Set<String>, onScreen: Set<String>, kinds: Set<SportsChangeKind>): Boolean {
        if (change.kind !in kinds || change.key in onScreen) return false
        return when (games) {
            SportsAlertGames.FOLLOWED -> SportsFavourites.has(favourites, change.fixture)
            SportsAlertGames.FOLLOWED_AND_CLOSE -> SportsFavourites.has(favourites, change.fixture) ||
                (change.kind != SportsChangeKind.STARTED && close(change.fixture))
            SportsAlertGames.ALL_LIVE -> true
        }
    }

    fun close(fixture: SportsFixture): Boolean = fixture.status != FixtureStatus.SCHEDULED && SportsFixtureSections.close(fixture.copy(status = FixtureStatus.LIVE))
}

object SportsOverlaySelection {
    const val SOON_MILLIS = 15L * 60 * 1000

    fun pick(fixtures: List<SportsFixture>, favourites: Set<String>, onScreen: Set<String>, games: SportsAlertGames, skipOnScreen: Boolean,
        nowMillis: Long, max: Int): List<SportsFixture> {
        if (max <= 0) return emptyList()
        val order = SportsLeagues.ALL.withIndex().associate { it.value.id to it.index }
        return fixtures.distinctBy { it.key }.filter { fixture ->
            (fixture.status == FixtureStatus.LIVE || soon(fixture, nowMillis)) && !(skipOnScreen && fixture.key in onScreen) && when (games) {
                SportsAlertGames.FOLLOWED -> SportsFavourites.has(favourites, fixture)
                SportsAlertGames.FOLLOWED_AND_CLOSE -> SportsFavourites.has(favourites, fixture) || SportsFixtureSections.close(fixture)
                SportsAlertGames.ALL_LIVE -> true
            }
        }.sortedWith(compareBy<SportsFixture>({ rank(it, favourites) }, { if (it.status == FixtureStatus.LIVE) 0 else 1 }, { it.startMillis },
            { order[it.league] ?: Int.MAX_VALUE }, { it.title })).take(max)
    }

    fun soon(fixture: SportsFixture, nowMillis: Long): Boolean = fixture.status == FixtureStatus.SCHEDULED &&
        fixture.startMillis <= nowMillis + SOON_MILLIS && fixture.startMillis + SportsRefresh.durationMillis(fixture) > nowMillis

    private fun rank(fixture: SportsFixture, favourites: Set<String>): Int = when {
        SportsFavourites.has(favourites, fixture) -> 0
        SportsFixtureSections.close(fixture) -> 1
        fixture.status == FixtureStatus.LIVE -> 2
        else -> 3
    }
}

data class SportsRecordedWindow(val title: String, val startMillis: Long, val endMillis: Long)

object SportsSpoilers {
    private const val SLACK_MILLIS = 30L * 60 * 1000

    fun keys(fixtures: List<SportsFixture>, recordings: List<SportsRecordedWindow>): Set<String> {
        if (recordings.isEmpty()) return emptySet()
        val titled = recordings.filter { it.endMillis > it.startMillis }.map { it to SportsGuide.normalise(it.title) }.filter { it.second.isNotEmpty() }
        return fixtures.filter { fixture ->
            val home = fixture.home ?: return@filter false
            val away = fixture.away ?: return@filter false
            titled.any { (window, text) ->
                window.startMillis <= fixture.startMillis + SLACK_MILLIS && window.endMillis >= fixture.startMillis - SLACK_MILLIS && named(home, text) && named(away, text)
            }
        }.map { it.key }.toSet()
    }

    fun hidden(fixture: SportsFixture, keys: Set<String>): Boolean = fixture.key in keys

    private fun named(team: FixtureTeam, text: String): Boolean =
        team.strongNames.any { name -> SportsFixtureMatching.variants(name).any { SportsFixtureMatching.has(text, it) } } ||
            team.weakNames.any { name -> SportsFixtureMatching.variants(name).any { it.length >= 3 && SportsFixtureMatching.has(text, it) } }
}

data class SportsTimelineItem(val fixture: SportsFixture, val startFraction: Float, val endFraction: Float, val subLane: Int)
data class SportsTimelineLane(val sport: String, val items: List<SportsTimelineItem>) {
    val subLanes: Int get() = (items.maxOfOrNull { it.subLane } ?: -1) + 1
}

object SportsTimeline {
    fun lanes(fixtures: List<SportsFixture>, fromMillis: Long, untilMillis: Long): List<SportsTimelineLane> {
        require(untilMillis > fromMillis)
        val span = (untilMillis - fromMillis).toDouble()
        val leagues = SportsLeagues.ALL.withIndex().associate { it.value.id to it.index }
        val sports = SportsLeagues.ALL.map { it.sport }.distinct().withIndex().associate { it.value to it.index }
        fun fraction(millis: Long) = ((millis - fromMillis) / span).coerceIn(0.0, 1.0).toFloat()
        return fixtures.distinctBy { it.key }.filter { it.startMillis < untilMillis && it.startMillis + SportsRefresh.durationMillis(it) > fromMillis }
            .groupBy { it.sport }.entries.sortedWith(compareBy({ sports[it.key] ?: Int.MAX_VALUE }, { it.key })).map { (sport, items) ->
                val ends = mutableListOf<Long>()
                SportsTimelineLane(sport, items.sortedWith(compareBy({ it.startMillis }, { leagues[it.league] ?: Int.MAX_VALUE }, { it.title })).map { fixture ->
                    val end = fixture.startMillis + SportsRefresh.durationMillis(fixture)
                    val lane = ends.indexOfFirst { it <= fixture.startMillis }.takeIf { it >= 0 } ?: ends.size.also { ends += 0L }
                    ends[lane] = end
                    SportsTimelineItem(fixture, fraction(fixture.startMillis), fraction(end), lane)
                })
            }
    }
}

data class SportsReminder(val key: String, val title: String, val startMillis: Long, val leadMillis: Long) {
    val dueAt: Long get() = startMillis - leadMillis
}

object SportsReminders {
    const val MAX = 100
    const val KEEP_MILLIS = 6L * 60 * 60 * 1000
    const val LATE_MILLIS = 30L * 60 * 1000

    fun due(reminders: Collection<SportsReminder>, nowMillis: Long, lastCheckedMillis: Long): List<SportsReminder> =
        reminders.filter { it.dueAt > lastCheckedMillis && it.dueAt <= nowMillis && nowMillis < it.startMillis + LATE_MILLIS }.sortedBy { it.startMillis }

    fun prune(reminders: Collection<SportsReminder>, nowMillis: Long): List<SportsReminder> =
        reminders.filter { nowMillis <= it.startMillis + KEEP_MILLIS }.distinctBy { it.key }.sortedBy { it.startMillis }.take(MAX)

    fun fixture(reminder: SportsReminder): SportsFixture {
        val league = reminder.key.substringBefore(':')
        return SportsFixture(reminder.key.substringAfter(':'), league, SportsLeagues.byId(league)?.sport.orEmpty(), reminder.title, null, null,
            reminder.startMillis, FixtureStatus.SCHEDULED)
    }

    fun encode(reminder: SportsReminder): String = JSONObject().put("key", reminder.key).put("title", reminder.title)
        .put("start", reminder.startMillis).put("lead", reminder.leadMillis).toString()

    fun decode(text: String): SportsReminder? = runCatching {
        val json = JSONObject(text)
        val key = json.getString("key").takeIf { it.isNotEmpty() && it.length <= 400 } ?: return null
        SportsReminder(key, json.optString("title").take(240), json.getLong("start"), json.optLong("lead").coerceIn(0, 24L * 60 * 60 * 1000))
    }.getOrNull()
}

object SportsPolling {
    const val LIVE_TV_MILLIS = 60_000L
    const val FOLLOWED_MILLIS = 30_000L
    const val IDLE_MILLIS = 10L * 60 * 1000

    fun followedActive(fixtures: List<SportsFixture>, favourites: Set<String>, nowMillis: Long): Boolean = favourites.isNotEmpty() &&
        fixtures.any { (it.status == FixtureStatus.LIVE || SportsOverlaySelection.soon(it, nowMillis)) && SportsFavourites.has(favourites, it) }

    fun liveTvDelay(fixtures: List<SportsFixture>, favourites: Set<String>, nowMillis: Long): Long =
        if (fixtures.any { it.status == FixtureStatus.LIVE && SportsFavourites.has(favourites, it) }) FOLLOWED_MILLIS else LIVE_TV_MILLIS

    fun backgroundDelay(fixtures: List<SportsFixture>, favourites: Set<String>, nowMillis: Long): Long =
        if (followedActive(fixtures, favourites, nowMillis)) FOLLOWED_MILLIS else IDLE_MILLIS

    fun due(entry: SportsCacheEntry?, favourites: Set<String>, nowMillis: Long): Boolean {
        if (SportsRefresh.due(entry, nowMillis)) return true
        if (entry == null || favourites.isEmpty()) return false
        val failedAt = entry.failedAt
        if (entry.failures > 0 && failedAt != null && nowMillis - failedAt in 0 until SportsRefresh.backoff(entry.failures)) return false
        val fetched = entry.fetchedAt ?: return false
        return nowMillis - fetched >= FOLLOWED_MILLIS && entry.fixtures.any { it.status == FixtureStatus.LIVE && SportsFavourites.has(favourites, it) }
    }

    fun followedLeagues(favourites: Set<String>): Set<String> = favourites.mapNotNull { SportsFavourites.parse(it)?.first }.toSet()
}
