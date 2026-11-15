package com.nuvio.tv.core.image

private const val MIB_BYTES = 1024L * 1024
private const val SMALL_BOX_TOTAL_BYTES = 1_536L * MIB_BYTES

/** How much Home loads ahead of the viewer. Only small boxes load less; every other box keeps the full amounts. */
enum class HomeLoadLevel(
    val startRows: Int,
    val memoryRowsAhead: Int,
    val memoryScreensFromPosition: Int,
    val diskWarmScreens: Int,
    val warmOtherRowsToDisk: Boolean,
    val rowTitleLimit: Int,
) {
    Small(startRows = 4, memoryRowsAhead = 1, memoryScreensFromPosition = 1, diskWarmScreens = 1, warmOtherRowsToDisk = false, rowTitleLimit = 60),
    Full(startRows = 8, memoryRowsAhead = 3, memoryScreensFromPosition = 2, diskWarmScreens = HOME_POSTER_DISK_WARM_SCREENS, warmOtherRowsToDisk = true, rowTitleLimit = 150);

    companion object {
        @Volatile var current: HomeLoadLevel = Full
            private set

        fun init(totalRamBytes: Long, lowRamDevice: Boolean, cores: Int) {
            current = homeLoadLevel(totalRamBytes, lowRamDevice, cores)
        }

        internal fun setForTest(level: HomeLoadLevel) { current = level }
    }
}

/** Same line as the decode lanes: a low-RAM flag, under 1.5 GB or fewer than 4 cores is a small box. */
fun homeLoadLevel(totalRamBytes: Long, lowRamDevice: Boolean, cores: Int): HomeLoadLevel =
    if (lowRamDevice || totalRamBytes < SMALL_BOX_TOTAL_BYTES || cores < 4) HomeLoadLevel.Small else HomeLoadLevel.Full

/** A Home row longer than [limit] shows its first [limit] titles and ends with See all instead of loading more. */
fun homeRowEndsWithSeeAll(count: Int, hasMore: Boolean, limit: Int): Boolean =
    count > limit || (count == limit && hasMore)
