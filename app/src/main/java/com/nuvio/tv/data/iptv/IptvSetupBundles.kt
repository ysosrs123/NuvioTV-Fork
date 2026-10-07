package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.AccountGroups
import com.nuvio.tv.core.iptv.BundleAccount
import com.nuvio.tv.core.iptv.BundleGuide
import com.nuvio.tv.core.iptv.BundleLinks
import com.nuvio.tv.core.iptv.BundleOverlay
import com.nuvio.tv.core.iptv.BundleSettings
import com.nuvio.tv.core.iptv.BundleSource
import com.nuvio.tv.core.iptv.ChannelMatch
import com.nuvio.tv.core.iptv.DEFAULT_ACCOUNT_ID
import com.nuvio.tv.core.iptv.ExistingGuide
import com.nuvio.tv.core.iptv.ExistingSource
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.MatchableChannel
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.iptv.SetupBundle
import com.nuvio.tv.core.iptv.SetupBundles
import com.nuvio.tv.core.iptv.SetupDrafts
import com.nuvio.tv.core.iptv.SetupImportMode
import com.nuvio.tv.core.iptv.SetupImportPlan
import com.nuvio.tv.core.iptv.SetupKind
import com.nuvio.tv.core.iptv.SetupMerge
import com.nuvio.tv.core.iptv.SetupPendingImport
import com.nuvio.tv.core.iptv.SetupSettings
import com.nuvio.tv.core.iptv.SourceConnections
import com.nuvio.tv.core.iptv.StalkerPortal
import com.nuvio.tv.core.iptv.XtreamGuideReference
import java.security.SecureRandom

class IptvSetupImport(val created: List<IptvSourceRef>, val reused: Int, val skipped: Int, val feeds: List<IptvGuideRef>, val overlays: Int) {
    override fun toString() = "IptvSetupImport(created=${created.size}, reused=$reused, skipped=$skipped)"
}

class IptvSetupBundles(private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore, private val preferences: IptvLivePreferences,
    private val random: SecureRandom = SecureRandom()) {
    private val xtreamGuides = IptvXtreamGuides(catalogue, guides)
    private val overlays = IptvSetupOverlays(catalogue)

    fun export(profileId: Int, logins: Boolean): SetupBundle {
        val sources = catalogue.sources(profileId).take(SetupBundles.MAX_SOURCES)
        val accounts = catalogue.accounts(profileId)
        val feeds = feeds(profileId)
        val guideKeys = LinkedHashMap<String, BundleGuide>()
        for ((feed, endpoint) in feeds) {
            if (guideKeys.size >= SetupBundles.MAX_GUIDES) break
            SetupDrafts.address(SetupKind.GUIDE, endpoint)?.takeIf { it == endpoint }?.let { guideKeys[feed.ref.feedId] = BundleGuide("g${guideKeys.size}", feed.label, it) }
        }
        val provider = feeds.mapNotNull { (feed, endpoint) -> XtreamGuideReference.sourceId(endpoint)?.let { feed.ref.feedId to it } }.toMap()
        val shared = accounts.filter { it.sources.isNotEmpty() && AccountGroups.isGroup(it.id, it.sources.size) && it.sources.any { ref -> sources.any { s -> s.ref == ref } } }
        val accountKeys = shared.withIndex().associate { (index, account) -> account.id to BundleAccount("a$index", account.label, account.maxStreams) }
        fun guideKey(feedId: String, sourceId: String): String? = guideKeys[feedId]?.key ?: SetupBundles.PROVIDER.takeIf { provider[feedId] == sourceId }
        val bundleSources = mutableListOf<BundleSource>()
        val links = mutableListOf<BundleLinks>()
        val bundleOverlays = mutableListOf<BundleOverlay>()
        for ((index, source) in sources.withIndex()) {
            val key = "s$index"
            val kind = SetupKind.valueOf(source.kind.name)
            val connection = catalogue.connection(source.ref)
            val group = accountKeys[source.accountId]?.key
            val limit = accounts.firstOrNull { it.id == source.accountId }?.maxStreams ?: 1
            bundleSources += BundleSource(key, source.label, kind, SetupDrafts.address(kind, connection.endpoint)?.takeIf { it == connection.endpoint },
                connection.username, connection.password, group,
                if (group == null && preferences.connectionsManual(source.ref)) limit.coerceIn(1, SourceConnections.MAX) else null)
            val associations = catalogue.guideAssociations(source.ref)
            val order = (associations.priority + associations.feedIds).distinct().mapNotNull { guideKey(it, source.ref.sourceId) }.distinct().take(SetupBundles.MAX_LINKED)
            if (order.isNotEmpty()) links += BundleLinks(key, order)
            if (source.activeGeneration != null) for (item in catalogue.snapshot(source.ref).channels) {
                if (IptvSetupOverlays.unset(item.overlay) || bundleOverlays.size >= SetupBundles.MAX_OVERLAYS) continue
                val data = item.channel.data
                val manual = item.overlay.manualGuide?.let { manual -> guideKey(manual.feedId, source.ref.sourceId)?.let { it to manual.externalId } }
                bundleOverlays += BundleOverlay(key, ChannelMatch(data.name, data.providerId, SetupBundles.locatorDigest(data.locator), data.guideId),
                    item.overlay.customName?.takeIf(String::isNotBlank), item.overlay.favouriteRank, item.overlay.hidden, SetupSettings.wire(item.overlay.streamFormat),
                    manual?.first, manual?.second)
            }
        }
        val bundle = SetupBundle(true, bundleSources, accountKeys.values.toList(), guideKeys.values.toList(), links, bundleOverlays, settings())
        return if (logins) bundle else bundle.withoutLogins()
    }

    fun plan(profileId: Int, bundle: SetupBundle, mode: SetupImportMode): SetupImportPlan {
        val existing = catalogue.sources(profileId).map { source ->
            val connection = catalogue.connection(source.ref)
            ExistingSource(source.ref.sourceId, SetupKind.valueOf(source.kind.name), connection.endpoint, connection.username)
        }
        return SetupMerge.plan(bundle, existing, feeds(profileId).map { (feed, endpoint) -> ExistingGuide(feed.ref.feedId, endpoint) }, mode)
    }

    fun import(profileId: Int, bundle: SetupBundle, mode: SetupImportMode): IptvSetupImport {
        val plan = plan(profileId, bundle, mode)
        for (id in plan.removeSources) {
            val ref = IptvSourceRef(profileId, id)
            xtreamGuides.removeSource(ref)
            preferences.removeSource(ref)
            catalogue.pending.removeSource(ref)
        }
        val remaining = feeds(profileId).map { it.first.ref.feedId }.toSet()
        for (id in plan.removeGuides) if (id in remaining) xtreamGuides.removeFeed(IptvGuideRef(profileId, id))
        val guideIds = HashMap(plan.reuseGuides)
        val createdFeeds = plan.createGuides.map { guide -> guides.createFeed(profileId, guide.label, guide.endpoint).also { guideIds[guide.key] = it.feedId } }
        val groups = HashMap<String, String>()
        for (account in bundle.accounts) {
            if (plan.createSources.none { it.account == account.key }) continue
            val id = AccountGroups.newId(random)
            catalogue.saveAccount(profileId, id, account.label, account.limit)
            groups[account.key] = id
        }
        val refs = LinkedHashMap<String, IptvSourceRef>()
        for (source in plan.createSources) {
            val kind = IptvSourceKind.valueOf(source.kind.name)
            val endpoint = requireNotNull(source.endpoint)
            val connection = when (kind) {
                IptvSourceKind.XTREAM -> IptvSourceConnection(endpoint, source.username, source.password)
                IptvSourceKind.STALKER -> IptvSourceConnection(endpoint, StalkerPortal.normalizeMac(source.username))
                IptvSourceKind.M3U -> IptvSourceConnection(endpoint)
            }
            val created = catalogue.createSource(profileId, source.label, kind, DEFAULT_ACCOUNT_ID, connection)
            val group = source.account?.let(groups::get)
            if (group != null) {
                catalogue.assignAccount(created.ref, group)
                preferences.setConnectionsManual(created.ref, true)
            } else {
                val own = SourceConnections.ownAccount(created.ref.sourceId)
                catalogue.saveAccount(profileId, own, created.label, source.connections ?: 1)
                catalogue.assignAccount(created.ref, own)
                preferences.setConnectionsManual(created.ref, source.connections != null)
            }
            refs[source.key] = created.ref
        }
        for ((key, id) in plan.reuseSources) refs[key] = IptvSourceRef(profileId, id)
        val links = bundle.links.associateBy { it.source }
        val byOverlay = bundle.overlays.groupBy { it.source }
        var applied = 0
        for ((key, ref) in refs) {
            val order = links[key]?.guides?.mapNotNull { if (it == SetupBundles.PROVIDER) it else guideIds[it] }
            val pending = SetupPendingImport(order, byOverlay[key].orEmpty().map { overlay ->
                val feed = overlay.guideFeed?.let { if (it == SetupBundles.PROVIDER) it else guideIds[it] }
                overlay.copy(guideFeed = feed, guideChannel = overlay.guideChannel?.takeIf { feed != null })
            }, onlyUnset = mode == SetupImportMode.MERGE)
            if (pending.links == null && pending.overlays.isEmpty()) continue
            val source = catalogue.sources(profileId).single { it.ref == ref }
            if (source.activeGeneration != null) applied += overlays.apply(ref, pending, xtreamGuides.linked(ref))
            else {
                order?.filter { it != SetupBundles.PROVIDER }?.takeIf { it.isNotEmpty() }?.let { direct ->
                    val current = catalogue.guideAssociations(ref)
                    catalogue.setGuideFeeds(ref, SetupMerge.links(current.feedIds, direct).map { IptvGuideRef(profileId, it) }, current.priority.map { IptvGuideRef(profileId, it) })
                }
                catalogue.pending.saveImport(ref, pending)
            }
        }
        if (plan.applySettings) bundle.settings?.let(::apply)
        IptvLog.info("setup import mode=$mode created=${plan.createSources.size} reused=${plan.reuseSources.size} skipped=${plan.skippedSources.size} guides=${createdFeeds.size}")
        return IptvSetupImport(plan.createSources.mapNotNull { refs[it.key] }, plan.reuseSources.size, plan.skippedSources.size, createdFeeds, applied)
    }

    fun settings(): BundleSettings {
        val appearance = preferences.currentAppearance
        return BundleSettings(SetupSettings(SetupSettings.wire(preferences.defaultFormat), preferences.timeshift, preferences.sport, SetupSettings.wire(preferences.startView),
            SetupSettings.wire(preferences.multiviewLayout), SetupSettings.wire(preferences.multiviewQuality), preferences.recordEarlyMinutes, preferences.recordLateMinutes),
            preferences.autoPreview, preferences.showStats, appearance.theme?.takeIf { it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
            appearance.black, appearance.solidPanels, appearance.plainBackground)
    }

    private fun apply(settings: BundleSettings) {
        val live = settings.live
        preferences.defaultFormat = SetupSettings.choice(IptvStreamFormat.entries, live.format)
        preferences.timeshift = live.timeshift
        preferences.sport = live.sport
        preferences.startView = SetupSettings.choice(IptvStartView.entries, live.startView).let { if (it == IptvStartView.SPORT && !live.sport) IptvStartView.LAST else it }
        preferences.multiviewLayout = SetupSettings.choice(MultiviewLayout.entries, live.layout)
        preferences.multiviewQuality = SetupSettings.choice(MultiviewQuality.entries, live.quality)
        preferences.recordEarlyMinutes = live.recordEarly
        preferences.recordLateMinutes = live.recordLate
        preferences.autoPreview = settings.preview
        preferences.showStats = settings.stats
        preferences.updateAppearance { IptvAppearance(settings.theme, settings.black, settings.solid, settings.plain) }
    }

    private fun feeds(profileId: Int): List<Pair<IptvGuideFeed, String>> = buildList {
        while (true) {
            val page = guides.feeds(profileId, offset = size, limit = 200)
            page.forEach { feed -> add(feed to guides.endpoint(feed.ref)) }
            if (page.size < 200) break
        }
    }
}

class IptvSetupOverlays(private val store: IptvCatalogueStore) {
    fun apply(ref: IptvSourceRef, pending: SetupPendingImport, providerGuide: IptvGuideRef?): Int {
        fun feed(id: String?): String? = if (id == SetupBundles.PROVIDER) providerGuide?.feedId else id
        pending.links?.let { wanted ->
            val current = store.guideAssociations(ref)
            val resolved = wanted.mapNotNull(::feed).distinct()
            val feeds = SetupMerge.links(current.feedIds, resolved)
            val priority = (if (pending.onlyUnset) current.priority + resolved else resolved + current.priority).distinct().filter { it in feeds }
            store.setGuideFeeds(ref, feeds.map { IptvGuideRef(ref.profileId, it) }, priority.map { IptvGuideRef(ref.profileId, it) })
        }
        if (pending.overlays.isEmpty()) return 0
        val channels = store.snapshot(ref).channels
        val byId = channels.associateBy { it.channel.id }
        val matchable = channels.map { MatchableChannel(it.channel.id, it.channel.data.name, it.channel.data.providerId,
            SetupBundles.locatorDigest(it.channel.data.locator), it.channel.data.guideId) }
        var applied = 0
        for ((overlay, id) in SetupMerge.match(pending.overlays, matchable)) {
            val item = byId.getValue(id)
            if (pending.onlyUnset && !unset(item.overlay)) continue
            val guide = feed(overlay.guideFeed)?.let { f -> overlay.guideChannel?.let { GuideKey(f, it) } }
            store.setOverlay(ref, id, item.overlay.copy(customName = overlay.customName, favouriteRank = overlay.favourite, hidden = overlay.hidden,
                manualGuide = guide, streamFormat = SetupSettings.choice(IptvStreamFormat.entries, overlay.format)))
            applied++
        }
        return applied
    }

    companion object {
        fun unset(overlay: IptvChannelOverlay): Boolean = overlay.customName == null && overlay.favouriteRank == null && !overlay.hidden &&
            overlay.manualGuide == null && overlay.streamFormat == IptvStreamFormat.AUTO
    }
}
