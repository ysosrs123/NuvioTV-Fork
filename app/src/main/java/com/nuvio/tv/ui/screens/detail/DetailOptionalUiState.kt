package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.*

internal data class DetailCommentState(
    val comments: List<TraktCommentReview>, val currentPage: Int, val pageCount: Int,
    val loading: Boolean, val loadingMore: Boolean, val error: String?
) { val canLoadMore: Boolean get() = currentPage in 1 until pageCount }
internal fun MetaDetailsUiState.commentState() = DetailCommentState(
    comments, commentsCurrentPage, commentsPageCount, isCommentsLoading, isCommentsLoadingMore, commentsError)

internal data class DetailRatingState(val ratings: MDBListRatings?, val active: Boolean, val tmdb: Float?)
internal fun MetaDetailsUiState.ratingState() = DetailRatingState(mdbListRatings, isMdbListRatingsActive, tmdbRating)
internal data class DetailPeopleState(
    val cast: List<String>, val castMembers: List<MetaCastMember>,
    val companies: List<MetaCompany>, val networks: List<MetaCompany>)
internal fun MetaDetailsUiState.peopleState() = DetailPeopleState(
    meta?.cast.orEmpty(), meta?.castMembers.orEmpty(), meta?.productionCompanies.orEmpty(), meta?.networks.orEmpty())

/** Optional payloads are collected where displayed, without invalidating the navigation root. */
internal fun MetaDetailsUiState.entryState() = copy(
    meta = meta?.copy(cast = emptyList(), castMembers = emptyList(), productionCompanies = emptyList(), networks = emptyList()),
    comments = emptyList(), commentsCurrentPage = 0, commentsPageCount = 0,
    isCommentsLoading = false, isCommentsLoadingMore = false, commentsError = null,
    mdbListRatings = null, isMdbListRatingsActive = false, tmdbRating = null)
