package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.util.Locale

fun foldSearchText(value: String): String {
    val compatible = Normalizer.normalize(value, Normalizer.Form.NFKC)
    return Normalizer.normalize(compatible.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFKC)
}
