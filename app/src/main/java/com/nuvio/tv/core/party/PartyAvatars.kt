package com.nuvio.tv.core.party

/**
 * The avatar set built into the app (assets/party-avatars/<set>/<name>.svg). A box refers to one as "fun:<set>/<name>",
 * so every box with this app shows the same picture without loading it from anywhere.
 */
object PartyAvatars {
    const val PREFIX = "fun:"
    const val ASSET_DIR = "party-avatars"
    private val REF = Regex("fun:[a-z0-9-]{1,32}/[a-z0-9-]{1,32}")

    /** Sets in the order the picker shows them. */
    val SETS = listOf("fun-emoji", "animals", "adventurer", "lorelei", "notionists", "bottts", "pixel-art", "thumbs")

    fun isBundled(ref: String?): Boolean = ref != null && REF.matches(ref) && ref.removePrefix(PREFIX).substringBefore('/') in SETS

    fun ref(set: String, name: String): String = "$PREFIX$set/$name"

    /** What an image loader can open: the bundled file, or the https picture as it is. */
    fun imageUri(ref: String?): String? = when {
        ref == null -> null
        isBundled(ref) -> "file:///android_asset/$ASSET_DIR/${ref.removePrefix(PREFIX)}.svg"
        ref.startsWith("https://") -> ref
        else -> null
    }
}
