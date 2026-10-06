package com.nuvio.tv.core.iptv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupDraftsTest {
    private fun body(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }.toString()
    private fun rejects(field: String, json: String) {
        try { SetupDrafts.parse(json); fail(json) } catch (error: SetupInputException) { assertEquals(json, field, error.field) }
    }

    @Test fun newPlaylistsNeedANameAndAWebAddress() {
        val draft = SetupDrafts.parse(body("kind" to "m3u", "label" to "  Lounge  ", "address" to "lists.example/tv.m3u?user=a&pass=b"))
        assertEquals(SetupKind.M3U, draft.kind)
        assertEquals("Lounge", draft.label)
        assertEquals("http://lists.example/tv.m3u?user=a&pass=b", draft.address)
        assertFalse(draft.edit)
        rejects("label", body("kind" to "m3u", "label" to " ", "address" to "http://a.example/x"))
        rejects("label", body("kind" to "m3u", "label" to "a\u0007b", "address" to "http://a.example/x"))
        rejects("label", body("kind" to "m3u", "label" to "x".repeat(241), "address" to "http://a.example/x"))
        rejects("address", body("kind" to "m3u", "label" to "A"))
        for (bad in listOf("javascript:alert(1)", "ftp://a.example/x", "file:///sdcard/x.m3u", "content://x/y", "http://user:pass@a.example/x",
                "http://a.example/x#frag", "http:// a.example/x", "http://", "x".repeat(16_385)))
            rejects("address", body("kind" to "m3u", "label" to "A", "address" to bad))
    }

    @Test fun guidesAcceptOnlyWebAddresses() {
        assertEquals("https://epg.example/guide.xml.gz", SetupDrafts.parse(body("kind" to "guide", "label" to "EPG", "address" to "https://epg.example/guide.xml.gz")).address)
        for (bad in listOf("content://media/guide", "file:///storage/guide.xml", "xtream-guide:abc"))
            rejects("address", body("kind" to "guide", "label" to "EPG", "address" to bad))
    }

    @Test fun xtreamNeedsLoginDetailsWhenAddedButNotWhenEdited() {
        val added = SetupDrafts.parse(body("kind" to "xtream", "label" to "X", "address" to "http://x.example:8080", "username" to " me ", "password" to "secret"))
        assertEquals("me", added.username); assertEquals("secret", added.password)
        rejects("username", body("kind" to "xtream", "label" to "X", "address" to "http://x.example", "password" to "secret"))
        rejects("password", body("kind" to "xtream", "label" to "X", "address" to "http://x.example", "username" to "me"))
        rejects("password", body("kind" to "xtream", "label" to "X", "address" to "http://x.example", "username" to "me", "password" to ".."))
        rejects("username", body("kind" to "xtream", "label" to "X", "address" to "http://x.example", "username" to "a\nb", "password" to "p"))
        rejects("address", body("kind" to "xtream", "label" to "X", "address" to "http://x.example/player_api.php", "username" to "u", "password" to "p"))
        rejects("address", body("kind" to "xtream", "label" to "X", "address" to "http://x.example/?a=1", "username" to "u", "password" to "p"))
        val edit = SetupDrafts.parse(body("kind" to "xtream", "id" to "src-1", "label" to "X2"))
        assertTrue(edit.edit); assertEquals("", edit.address); assertEquals("", edit.password)
        assertFalse(edit.toString().contains("X2"))
        assertFalse(added.toString().contains("secret"))
    }

    @Test fun stalkerMacIsCheckedAndNormalised() {
        val draft = SetupDrafts.parse(body("kind" to "stalker", "label" to "S", "address" to "http://portal.example/c/", "mac" to "00:1a:79:ab:cd:ef"))
        assertEquals("00:1A:79:AB:CD:EF", draft.username)
        rejects("mac", body("kind" to "stalker", "label" to "S", "address" to "http://portal.example/c/", "mac" to "00-1A-79-AB-CD-EF"))
        rejects("mac", body("kind" to "stalker", "label" to "S", "address" to "http://portal.example/c/"))
        rejects("address", body("kind" to "stalker", "label" to "S", "address" to "http://portal.example/c/?mac=1", "mac" to "00:1A:79:AB:CD:EF"))
        assertEquals("", SetupDrafts.parse(body("kind" to "stalker", "id" to "s1", "label" to "S")).username)
    }

    @Test fun malformedBodiesAndFieldsAreRejected() {
        rejects("body", "not json")
        rejects("body", "[]")
        rejects("body", " ".repeat(SetupDrafts.MAX_BODY_BYTES + 1))
        rejects("kind", body("kind" to "dvb", "label" to "A", "address" to "http://a.example"))
        rejects("kind", body("label" to "A", "address" to "http://a.example"))
        rejects("label", body("kind" to "m3u", "label" to 5, "address" to "http://a.example"))
        rejects("id", body("kind" to "m3u", "id" to "../etc", "label" to "A"))
        rejects("username", body("kind" to "xtream", "label" to "X", "address" to "http://x.example", "username" to "u".repeat(4097), "password" to "p"))
    }

    @Test fun blankFieldsKeepTheStoredValues() {
        val stored = SetupConnection("http://x.example", "me", "stored-secret")
        val renamed = SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "New"))
        renamed.connection(stored).let { assertEquals("http://x.example", it.endpoint); assertEquals("me", it.username); assertEquals("stored-secret", it.password) }
        val password = SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "New", "password" to "fresh"))
        password.connection(stored).let { assertEquals("me", it.username); assertEquals("fresh", it.password) }
        val moved = SetupDrafts.parse(body("kind" to "m3u", "id" to "a", "label" to "L", "address" to "https://new.example/list.m3u"))
        moved.connection(SetupConnection("http://old.example/list.m3u")).let { assertEquals("https://new.example/list.m3u", it.endpoint); assertNull(it.password) }
        val stalker = SetupDrafts.parse(body("kind" to "stalker", "id" to "a", "label" to "S"))
        assertEquals("00:1A:79:AB:CD:EF", stalker.connection(SetupConnection("http://portal.example/c/", "00:1a:79:ab:cd:ef")).username)
        assertThrows(IllegalArgumentException::class.java) { renamed.connection(null) }
        assertThrows(IllegalArgumentException::class.java) { renamed.connection(SetupConnection("http://x.example", "me", null)) }
        assertFalse(stored.toString().contains("stored-secret"))
    }

    @Test fun editsMustTargetAnEditableEntryOfTheSameKindAndChangeSomething() {
        val listing = SetupListing(
            sources = listOf(SetupListingItem("a", "Lounge", SetupKind.XTREAM, "x.example", true)),
            guides = listOf(SetupListingItem("g", "Provider", SetupKind.GUIDE, null, false), SetupListingItem("h", "EPG", SetupKind.GUIDE, "epg.example", true)))
        assertNull(SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "Lounge 2")), listing))
        assertEquals("unchanged", SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "Lounge")), listing))
        assertNull(SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "Lounge", "password" to "p")), listing))
        assertEquals("locked", SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "m3u", "id" to "a", "label" to "L")), listing))
        assertEquals("missing", SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "m3u", "id" to "zzz", "label" to "L")), listing))
        assertEquals("locked", SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "guide", "id" to "g", "label" to "P2")), listing))
        assertNull(SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "guide", "id" to "h", "label" to "EPG", "address" to "http://epg.example/new.xml")), listing))
        assertNull(SetupDrafts.checkTarget(SetupDrafts.parse(body("kind" to "m3u", "label" to "L", "address" to "http://a.example")), listing))
        val changes = SetupDrafts.parse(body("kind" to "xtream", "id" to "a", "label" to "Lounge", "username" to "u")).changes(listing.sources[0])
        assertEquals(setOf(SetupField.USERNAME), changes)
    }

    @Test fun listingsShowOnlyLabelKindAndHost() {
        val listing = SetupListing(listOf(SetupListingItem("a", "Lounge", SetupKind.XTREAM, SetupText.host("http://x.example:8080/live/"), true)))
        val json = JSONObject(listing.toJson(pending = true))
        val item = json.getJSONArray("sources").getJSONObject(0)
        assertEquals(setOf("id", "label", "kind", "host", "editable"), item.keySet())
        assertEquals("x.example:8080", item.getString("host"))
        assertTrue(json.getBoolean("pending"))
    }

    @Test fun addressesAreShownWithoutQueryValues() {
        assertEquals("lists.example/get.php?…", SetupText.displayAddress("http://lists.example/get.php?username=me&password=secret"))
        assertEquals("x.example:8080", SetupText.displayAddress("http://x.example:8080/"))
        assertEquals("", SetupText.displayAddress("xtream-guide:abc"))
        assertNull(SetupText.host("xtream-guide:abc"))
        assertNull(SetupText.host("content://media/guide"))
        assertEquals(40, SetupText.displayAddress("http://a.example/" + "p".repeat(100), limit = 40).length)
    }

    @Test fun changeBookHoldsOnePendingChangeAndOnlyItsOwnerSeesIt() {
        val book = SetupChangeBook()
        val draft = SetupDrafts.parse(body("kind" to "m3u", "label" to "A", "address" to "http://a.example"))
        val id = book.propose("owner", draft)!!
        assertNull(book.propose("other", draft))
        assertTrue(book.hasPending())
        assertEquals(SetupChangeBook.Status.PENDING, book.status("owner", id))
        assertNull(book.status("other", id))
        assertSame(draft, book.pending(id))
        assertTrue(book.resolve(id, SetupChangeBook.Status.SAVED))
        assertFalse(book.resolve(id, SetupChangeBook.Status.REJECTED))
        assertEquals(SetupChangeBook.Status.SAVED, book.status("owner", id))
        assertNull(book.pending(id))
        val next = book.propose("owner", draft)!!
        book.rejectPending()
        assertEquals(SetupChangeBook.Status.REJECTED, book.status("owner", next))
        assertFalse(book.hasPending())
    }
}
