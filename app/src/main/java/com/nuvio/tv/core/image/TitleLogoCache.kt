package com.nuvio.tv.core.image

/** Home and Details share decoded title logos; Coil still validates the requested size. */
fun titleLogoCacheKey(url: String): String = "title-logo:$url"
