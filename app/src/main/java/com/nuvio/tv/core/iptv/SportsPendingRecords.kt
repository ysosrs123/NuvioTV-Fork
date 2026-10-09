package com.nuvio.tv.core.iptv

data class SportsPendingRecord(val profileId: Int, val key: String, val startMillis: Long, val untilMillis: Long)

object SportsPendingRecords {
    const val MAX = 50

    fun of(profileId: Int, fixture: SportsFixture): SportsPendingRecord =
        SportsPendingRecord(profileId, fixture.key, fixture.startMillis, fixture.startMillis + SportsRefresh.durationMillis(fixture))

    fun wanted(fixture: SportsFixture, nowMillis: Long): Boolean =
        fixture.status != FixtureStatus.FINAL && fixture.startMillis + SportsRefresh.durationMillis(fixture) > nowMillis

    fun encode(record: SportsPendingRecord): String = "${record.profileId}|${record.startMillis}|${record.untilMillis}|${record.key}"

    fun decode(text: String): SportsPendingRecord? {
        val parts = text.split('|', limit = 4)
        if (parts.size != 4 || parts[3].isEmpty() || parts[3].length > 400) return null
        val profile = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val start = parts[1].toLongOrNull() ?: return null
        val until = parts[2].toLongOrNull()?.takeIf { it > start } ?: return null
        return SportsPendingRecord(profile, parts[3], start, until)
    }

    fun prune(all: Collection<SportsPendingRecord>, nowMillis: Long): List<SportsPendingRecord> =
        all.filter { it.untilMillis > nowMillis }.distinctBy { it.profileId to it.key }.sortedBy { it.startMillis }.take(MAX)

    fun toggle(all: Collection<SportsPendingRecord>, profileId: Int, fixture: SportsFixture, nowMillis: Long): List<SportsPendingRecord> {
        val kept = all.filterNot { it.profileId == profileId && it.key == fixture.key }
        return prune(if (kept.size < all.size || !wanted(fixture, nowMillis)) kept else kept + of(profileId, fixture), nowMillis)
    }

    fun ready(all: Collection<SportsPendingRecord>, profileId: Int, fixtures: Collection<SportsFixture>, linked: (SportsFixture) -> Boolean,
        nowMillis: Long): List<SportsFixture> {
        val wanted = all.filter { it.profileId == profileId && it.untilMillis > nowMillis }.map { it.key }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return fixtures.filter { it.key in wanted && wanted(it, nowMillis) && linked(it) }.distinctBy { it.key }.sortedBy { it.startMillis }
    }
}
