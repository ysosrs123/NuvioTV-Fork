package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.AllDebridFileNodeDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

data class AllDebridFile(
    val path: String,
    val size: Long?,
    val link: String?
) {
    val name: String
        get() = path.substringAfterLast('/')
}

internal fun List<AllDebridFileNodeDto>.flattenAllDebridFiles(parent: String = ""): List<AllDebridFile> =
    flatMap { node ->
        val name = node.name.orEmpty()
        val path = if (parent.isEmpty()) name else "$parent/$name"
        val children = node.entries
        if (children != null) {
            children.flattenAllDebridFiles(path)
        } else {
            listOf(AllDebridFile(path = path, size = node.size, link = node.link))
        }
    }

/** AllDebrid lists files as a folder tree, not in torrent order, so a file index from the add-on is not used. */
@Singleton
class AllDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<AllDebridFile>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): AllDebridFile? = selectDebridFile(
        files = files,
        resolve = resolve.copy(fileIdx = null),
        season = season,
        episode = episode,
        path = { it.path },
        isPlayable = { it.name.hasDebridVideoExtension() && !it.link.isNullOrBlank() },
        size = { it.size ?: 0L },
    )
}
