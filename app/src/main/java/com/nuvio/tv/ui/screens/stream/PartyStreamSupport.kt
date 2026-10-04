package com.nuvio.tv.ui.screens.stream

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyFingerprint
import com.nuvio.tv.core.party.PartyMatch
import com.nuvio.tv.core.party.PartyStreamMatcher
import com.nuvio.tv.domain.model.Stream

internal fun Stream.partyFingerprint(): PartyFingerprint = PartyFingerprint(
    infoHash = getEffectiveInfoHash()?.lowercase(),
    fileIdx = getEffectiveFileIdx(),
    filename = behaviorHints?.filename,
    sizeBytes = behaviorHints?.videoSize,
    videoHash = behaviorHints?.videoHash?.lowercase(),
)

@Composable
internal fun partyMatchLabel(host: PartyFingerprint, stream: Stream): String? =
    when (PartyStreamMatcher.match(host, stream.partyFingerprint())) {
        PartyMatch.SAME_FILE -> stringResource(R.string.party_match_same)
        PartyMatch.LIKELY_SAME -> stringResource(R.string.party_match_likely)
        PartyMatch.DIFFERENT -> stringResource(R.string.party_match_different)
        PartyMatch.UNKNOWN -> null
    }
