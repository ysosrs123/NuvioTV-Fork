# Nuvio IPTV code review — 6 October 2026

Scope: full IPTV diff from 574a2d4 to iptv/wip 8b84c11 plus the player-binding
continuation on iptv/player-binding. Read-only review; only the player-fixture stall
was changed in code. Each finding lists where it was traced. "Checked" means the code
path was re-read directly after the finding was raised; "traced" means it was followed
through callers/callees once and not independently re-checked.

## Fixed on iptv/player-binding

1. Player fixtures could never finish (checked). Batch queue and load cursor hold two
   rows; nothing retired played rows during playback, so a three-segment capture stopped
   at CAPACITY. Fixed by opt-in played-batch retirement; JVM regression added.
2. Explicit start 0 became the live edge (checked against the shipped
   MaskingMediaSource bytecode). Start is now nullable; explicit 0 is sent as 1ms.

3a. Medium-high (checked): epoch boundary or STOPPED aborted playback early.
   CaptureEpochPeriod.readDiscontinuity and SampleStream.maybeThrowError raised the
   boundary/stop error as soon as the snapshot changed, discarding staged rows. Both now
   raise only once the stream has reached the tail; ownership loss still fails at once.
   JVM regression added.

## Capture ownership, storage and transport

3. FIXED. High, latent (checked): dropped admission close ticket. SharedCaptureRuntime.join
   (openUpstream=false path) and LivePlaybackRuntime.open call admission.release and
   ignore the returned AcquisitionCloseTicket. If the other runtime's last lease is
   released between acquire and release, the acquisition stays closing for the process
   lifetime and keeps counting against the account upstream limit. Both upstream owners
   release their lease only after confirmed close (playback close / transport and store
   close), so a ticket received by a non-owner always follows confirmed closure; both
   sites now complete it. No deterministic test: the window has no injection point.
4. FIXED. Medium-high (checked): SegmentCaptureTransport.close cancels the worker then calls
   HlsCaptureSegmentSource.close, which returns false while owner != null. owner clears
   only after the cancelled worker resumes, so closing a live HLS capture usually reports
   unconfirmed, the session stays closing and nothing in production calls retryClosing.
   Fix: the first source close is an unblock; the transport joins the worker, retries
   body cleanup, then requires a confirming close. Test closes during a reload wait with
   a deliberately slow unwind (fails without the fix).
5. FIXED (same change; test retries a failed segment body close). Medium (traced): the transport's body-close retry (cleanupBody) is only reached after
   source.close returns true, but the HLS source returns false while its last body is
   unclosed, so the retry can never run for that source.
6. FIXED (session records transportStarted; test). Low-medium (traced): a session whose first join failed after pipeline creation (for
   example under-reserved consumer with uncertain close) keeps its transport in NEW;
   later joiners never start it and their readers WAIT indefinitely.
7. NO CHANGE (by design). Low (traced): CaptureSegmentStore stream close calls super.close before releasing the
   pin; a throwing close retains the pin. This matches the documented retain-on-uncertain
   rule, so it is noted rather than changed; it does block eviction and store close.
8. Low (traced): IptvCaptureHttp callTimeout(30s) also bounds body streaming; a slow
   segment transfer fails the transport with no retry. NO CHANGE: it is the only bound
   on a slow-drip body, and a segment slower than 30s cannot sustain live capture.
9. FIXED (primary failure kept, body retained for retry; test). Low (traced): a throwing body close in the transport's inner finally replaces
   BACKPRESSURE/STORAGE_BLOCKED with FAILED.

## Ingest, catalogue and guide

10. FIXED (overlay-less tombstones dropped with their identities; over-cap returns INVALID; churn test). High (checked): catalogue tombstones are never pruned. Every unmatched old row is
    carried forward as unavailable; IptvCatalogueStore requires channels+unavailable
    <= 60,000 and throws. M3U rows have no guide key, so token-rotating locators mint new
    IDs each refresh; a 10,000-channel list with rotating tokens fails permanently after
    about five refreshes and its favourites/hidden/mapping overlays stay on tombstones.
11. FIXED (byte runs without '<' bounded at 4x text budget + 64KiB; test). Medium (traced): XMLTV size limits are applied after the parser has materialised a
    whole text/comment/attribute token; a small gzip with one 60MiB comment can raise
    OutOfMemoryError, which the Exception handlers do not catch.
12. FIXED (rows sealed with a Keystore-wrapped data key, one Keystore operation per process; a 13,740-channel Xtream refresh on the AM9 was slow before the change; timing after it pending). Medium (traced): each catalogue commit decrypts/seals every row through Keystore
    inside one BEGIN IMMEDIATE transaction that also serialises all reads; large
    refreshes can block browsing and zapping for a long time. Needs device timing.
13. FIXED (malformed/orphan programmes quarantined up to max(16, 2%); duplicate channels merge names; device tests updated). Medium (checked): one zero-length or out-of-window programme, or a duplicated channel
    id with different names, rejects the whole guide feed. Common in merged feeds.
14. FIXED (toString withholds guide URLs; test). Low (traced): PlaylistCatalogue's generated toString includes guideUrls, which for
    providers usually carry username/password. Not logged today; field is unused.
15. FIXED (validators sent only before any redirect; test). Low (traced): the playlist metadata client resends If-None-Match/If-Modified-Since
    across redirects and accepts a 304 after redirect; the guide client strips them.
16. FIXED (both clients follow up to six redirects; tests for six and seven). Low (traced): repeat(6) permits five redirects, not six. Decide intent; add a test.
17. FIXED (NFKC + upper/lower folding; schema v4 re-indexes; test). Low (traced): search uses NFKC + lowercase, not case folding (final sigma, ß).
18. NO CHANGE (documented). Low (traced): source refresh and live browse run outside the profile access fence;
    store-level profile checks make a late commit fail rather than leak. Holding the
    access lock across network refreshes would block profile removal, so the store
    checks plus the ViewModels' session identity checks are the intended protection.

## Foreground UI and integration

19. FIXED (pop back to an existing Sources/Live entry; compile-unverified). Medium (checked): Sources and Live push each other with launchSingleTop only, so
    alternating grows the back stack and each Live entry keeps its own ViewModel loop.
20. FIXED (FEATURE_IPTV_ENABLED: on for iptvPrototype, off for full; test). Medium (checked): Settings always lists IPTV, so full-flavour builds expose the
    unfinished screens. Gate on the prototype flavour or a build flag before release.
21. NO CHANGE (no later signal can confirm a timed-out release). Medium (traced): a release timeout reported through onPlayerError makes
    IptvLivePlayback.close return false permanently; LivePlaybackRuntime then answers
    CLOSE_UNCONFIRMED to every later open until the process restarts. Consistent with
    retain-on-uncertain, but there is no recovery path or user-visible reset.
22. FIXED (credential-store removal runs on IO; Live opens its session on IO; compile-unverified). Medium (traced): profile deletion runs IPTV store deletes on the main thread inside
    the profile lock, and an exception skips the remaining cleanup.
23. FIXED (background refresh keeps loading/focus/programmes; compile-unverified). Low-medium (traced): the 30s live refresh sets loading, disabling the focused
    Previous/Next button (focus loss) and blanking the programme list.
24. FIXED (live player uses extension renderers ON; class compile-checked). Low-medium (traced): the live player uses default renderers without the bundled
    ffmpeg extension that VOD uses, so AC-3/E-AC-3 channels can be silent on TVs
    without those decoders.
25. FIXED (dropped document grants released when no feed uses them; device test added). Low (traced): persisted document URI permissions are never released on feed edit or
    profile removal.

## Repository hygiene

- FIXED: added KDoc/trailing comment removed; 26 blank lines left after opening braces
  by comment stripping removed (only lines the IPTV diff added).
- Validation documents, scripts and evidence carry local machine paths, a LAN device
  address and earlier tooling names; evidence XML records the host name. Decide what
  stays versioned before any pull request.
- tools/iptv-device-tests pins okhttp 5.3.2 / coroutines 1.10.2 directly rather than
  the catalogue versions; earlier notes state these match the app's resolved runtime,
  which needs a Gradle dependency report to confirm.

## Media inspection, staging and Media3 period

26. FIXED. High (checked against the shipped PesReader bytecode):
    TsCaptureInspector accepts video PES with a declared length, but the end-of-segment
    flush in LocalTsSegmentExtractor only finalises an unbounded (length 0) video PES.
    With a declared length PesReader finishes the packet as not-end-of-input and the
    synthetic PUSI does nothing, so the last access unit is never emitted. Timing
    validation then fails and staging fails. The fixtures pass because ffmpeg writes
    length 0; many hardware encoders write lengths. Options: reject declared-length video
    at inspection (explicit, narrows the profile) or signal end of input to the H.264
    reader directly. Fix: LocalTsSegmentExtractor now feeds whole packets, hashes the
    original bytes, then clears the length of each video PES header so every video PES is
    unbounded and the existing end-of-input flush emits the final access unit. The
    inspector already guarantees declared lengths match the assembled PES exactly. JVM
    test stages a declared-length copy of segment00 through the real stager and gets the
    same 50 video frames, timestamps, flags and sizes. Device decode still to rerun.
27. FIXED (atomic acquireCurrentBorrow/createCurrent). Medium-low (traced): CaptureEpochMediaSource.createPeriod reads reader.state.value
    and then borrows it; an IO-worker publish between the two makes the borrow fail and
    createPeriod throw a fatal "Stale or owned capture snapshot". Fix: bounded retry or
    an atomic borrow-current method on the reader.
28. FIXED. Low (traced): skipData moves past the last buffered sample while still live; Media3
    queues only do that once loading has ended. A late-frame keyframe drop near the live
    edge can discard the rest of the buffered GOP. Fix: skip to the end only when ENDED.
29. FIXED (default window limit now 4096, matching store retention). Low, latent (traced): CaptureSampleTimeline keeps 256 windows while the store can
    retain up to 4096 segments, so a cursor anchored on an older retained segment fails.
30. Note: each played-batch retirement moves the window start forward, so the
    window-relative currentPosition steps back by one segment. UI position/seek code must
    use period or absolute capture time.


## Review of the Live TV redesign (1b7952c..ccf3931)

Independent read-only review; status after the follow-up commit.

1. FIXED. Full screen closed on every zap, number entry, last channel, start over and
   catch-up end (player briefly null while tuning). Now closes only when nothing is
   tuning or playing.
2. FIXED. Search results took focus from the search field.
3. FIXED. Back with the category rail open left Live TV; it now closes the rail.
4. FIXED. Catch-up skips and reconnects restarted the programme: catch-up streams now
   allow ranged requests with a real length (seekable TS), and reconnects keep the
   position outside live.
5. FIXED. Returning from full screen lost focus and scroll; the guide list state is
   held by the screen and scrolls to the row before focusing.
6. FIXED. Background reloads cut the list to 200 rows; they now reload up to the
   loaded length (at most 1,000 rows).
7. FIXED. The first OK after a long-press menu was swallowed.
8. FIXED. Hiding a category or choosing the current one in the rail lost focus.
9. FIXED. Watch from a source's options now opens that source.
10. FIXED. Sources actions were ignored during a status-triggered reload.
11. OPEN (mitigated). Automatic provider guides are re-linked on refresh, so they can
    no longer be unlinked or removed on their own; they go with their source.
    Header-linked M3U guides can still be removed but come back on the next refresh.
12. FIXED. The Xtream short guide is fetched again when nothing covers the current
    time.
13. FIXED: Automatic shows its check when no track is overridden. OPEN: number entry
    reaches only loaded channels; the short guide uses `java.util.Base64` (API 26)
    like other code in the app.

## After the review

Work after 1b7952c was reviewed once (above); later commits are host-tested and
CI-built only.
