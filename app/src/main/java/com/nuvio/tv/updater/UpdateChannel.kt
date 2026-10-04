package com.nuvio.tv.updater

/** Compatibility type for old callers. All stored channel choices join the fork beta stream. */
enum class UpdateChannel(val storedValue: String) {
    BETA("beta");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel = BETA
        fun defaultForVersion(versionName: String): UpdateChannel = BETA
    }
}
