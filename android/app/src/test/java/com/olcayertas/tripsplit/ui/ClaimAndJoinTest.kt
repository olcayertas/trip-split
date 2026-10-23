package com.olcayertas.tripsplit.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.makeInvite
import com.olcayertas.tripsplit.data.FakeMarker
import com.olcayertas.tripsplit.data.RoomOpStore
import com.olcayertas.tripsplit.data.TripRepository
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import com.olcayertas.tripsplit.data.identity.IdentityManager
import com.olcayertas.tripsplit.ui.theme.TripSplitTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaimAndJoinTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: TripSplitDatabase
    private var ids = 0
    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TripSplitDatabase::class.java)
            .allowMainThreadQueries()
            // Run Room work on the calling thread. A pool thread would resume the Compose coroutines off the
            // main thread, which Robolectric rejects (CalledFromWrongThreadException) and which made these tests flaky.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** [wall] differs per phone: a phone that joins later writes later, so its ops sort after what it saw. */
    private fun repoFor(deviceId: String, wall: Long = 1_761_210_000_000L) =
        TripRepository(db, Device(deviceId, RoomOpStore(db), wall = { wall }, newId = ::nextId))

    // --- The claim rules after a restore ------------------------------------------------------------------------

    @Test
    fun aRestoredPhoneTakesItsOldMemberOver() = runBlocking {
        val marker = FakeMarker()
        val before = IdentityManager(db, marker, ::nextId).ensure()
        val oldRepo = repoFor(before.deviceId)
        val g = oldRepo.createGroup("Trip", "EUR", "Ali")
        assertEquals(before.deviceId, oldRepo.foldNow(g.groupId).members.single().claimedBy)

        // The database comes back from a backup on a new install: no marker, so a new device id.
        val after = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        assertNotEquals(before.deviceId, after.deviceId)
        assertEquals(listOf(before.deviceId), after.previousIds)
        val newRepo = repoFor(after.deviceId, wall = 1_761_210_600_000L)
        // Claiming is not possible: the old id still holds the member.
        newRepo.claim(g.groupId, g.creatorMemberId)
        assertEquals(before.deviceId, newRepo.foldNow(g.groupId).members.single().claimedBy)
        // Take over: unclaim and claim again.
        newRepo.takeOver(g.groupId, g.creatorMemberId)
        val member = newRepo.foldNow(g.groupId).members.single()
        assertEquals(after.deviceId, member.claimedBy)
        assertTrue(member.claimedBy !in after.previousIds)
    }

    @Test
    fun releaseFreesTheMemberAndAClaimOfAFreeMemberWorks() = runBlocking {
        val repo = repoFor("aaaaaaaa-0000-4000-8000-000000000001")
        val g = repo.createGroup("Trip", "EUR", "Ali")
        val ayse = repo.addMember(g.groupId, "Ayşe", false).memberId
        repo.release(g.groupId, g.creatorMemberId)
        assertNull(repo.foldNow(g.groupId).members.first { it.id == g.creatorMemberId }.claimedBy)
        assertTrue(repo.isFirstClaim(g.groupId, ayse))
        repo.claim(g.groupId, ayse)
        assertEquals("aaaaaaaa-0000-4000-8000-000000000001", repo.foldNow(g.groupId).members.first { it.id == ayse }.claimedBy)
        assertTrue(!repo.isFirstClaim(g.groupId, ayse))
    }

    @Test
    fun firstClaimWithEarlierExpensesPutsTheMemberIntoTheSplit() = runBlocking {
        val repo = repoFor("aaaaaaaa-0000-4000-8000-000000000001")
        val g = repo.createGroup("Trip", "EUR", "Ali")
        val ayse = repo.addMember(g.groupId, "Ayşe", false).memberId
        repo.append(
            g.groupId, "expense.add",
            OpBodies.expenseAdd(nextId(), "Dinner", "2026-10-23", "food", g.creatorMemberId, OpBodies.money(1000, "EUR", "1", OpBodies.equalSplit(listOf(g.creatorMemberId)))),
        )
        // A second phone claims Ayşe and picks "Also earlier expenses".
        val other = repoFor("bbbbbbbb-0000-4000-8000-000000000002", wall = 1_761_210_600_000L)
        other.claim(g.groupId, ayse, includeEarlier = true)
        val state = other.foldNow(g.groupId)
        assertEquals(mapOf(g.creatorMemberId to 500L, ayse to -500L).toSortedMap(), state.balances.toSortedMap())
    }

    // --- Dialog texts and actions -------------------------------------------------------------------------------

    @Test
    fun takeOverDialogUsesTheAgreedTextAndConfirms() {
        var confirmed = false
        compose.setContent {
            TripSplitTheme(dynamic = false) { TakeOverDialog("Ayşe", fromEarlierId = true, onDismiss = {}, onConfirm = { confirmed = true }) }
        }
        compose.onNodeWithTag("takeover_body").assertIsDisplayed()
        compose.onNodeWithText("This member is linked to another phone. Take it over only if that phone is yours or lost.").assertIsDisplayed()
        compose.onNodeWithTag("restore_note").assertIsDisplayed()
        compose.onNodeWithTag("takeover_confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun takeOverDialogHidesTheRestoreNoteForAnotherPerson() {
        compose.setContent {
            TripSplitTheme(dynamic = false) { TakeOverDialog("Ayşe", fromEarlierId = false, onDismiss = {}, onConfirm = {}) }
        }
        compose.onNodeWithTag("takeover_body").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("restore_note")).fetchSemanticsNodes().size)
    }

    @Test
    fun firstClaimDefaultsToFromNowOn() {
        var earlier: Boolean? = null
        compose.setContent {
            TripSplitTheme(dynamic = false) { FirstClaimDialog("Ayşe", onDismiss = {}, onConfirm = { earlier = it }) }
        }
        compose.onNodeWithTag("claim_confirm").performClick()
        assertEquals(false, earlier)
    }

    @Test
    fun firstClaimCanChooseEarlierExpenses() {
        var earlier: Boolean? = null
        compose.setContent {
            TripSplitTheme(dynamic = false) { FirstClaimDialog("Ayşe", onDismiss = {}, onConfirm = { earlier = it }) }
        }
        compose.onNodeWithText("Also earlier expenses").performClick()
        compose.onNodeWithTag("claim_confirm").performClick()
        assertEquals(true, earlier)
    }

    // --- Invite and join ----------------------------------------------------------------------------------------

    @Test
    fun sharingTheLinkShowsTheWarningFirstAndNeverTouchesTheClipboard() {
        val link = makeInvite("00000000-0000-4000-8000-000000000001", ByteArray(32) { 1 })
        compose.setContent { TripSplitTheme(dynamic = false) { InviteSheet(link, onDismiss = {}) } }
        compose.onNodeWithTag("qr").assertIsDisplayed()
        compose.onNodeWithTag("share").performClick()
        compose.onNodeWithTag("warning").assertIsDisplayed()
        compose.onNodeWithText("Anyone who gets this link can read and change the group. Send it only to group members.").assertIsDisplayed()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun pastingAValidLinkJoinsTheGroup() {
        val repo = repoFor("bbbbbbbb-0000-4000-8000-000000000002")
        val gid = "00000000-0000-4000-8000-0000000000aa"
        val link = makeInvite(gid, ByteArray(32) { 5 })
        var nearby = false
        compose.setContent { TripSplitTheme(dynamic = false) { JoinScreen(repo, null, onBack = {}, onOpenNearby = { nearby = true }) } }
        compose.onNodeWithText("Paste link").performClick()
        compose.onNodeWithTag("link").performTextInput(link)
        compose.onNodeWithTag("join").performClick()
        compose.awaitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("open_nearby")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ByteArray(32) { 5 }.toList(), runBlocking { repo.device.store.group(gid)!!.key.toList() })
        compose.onNodeWithTag("open_nearby").performClick()
        assertTrue(nearby)
    }

    @Test
    fun anInvalidLinkShowsAnErrorAndStoresNothing() {
        val repo = repoFor("bbbbbbbb-0000-4000-8000-000000000002")
        compose.setContent { TripSplitTheme(dynamic = false) { JoinScreen(repo, null, onBack = {}, onOpenNearby = {}) } }
        compose.onNodeWithText("Paste link").performClick()
        compose.onNodeWithTag("link").performTextInput("https://example.com/join?g=1")
        compose.onNodeWithTag("join").performClick()
        compose.onNodeWithText("This is not a TripSplit invite.").assertIsDisplayed()
        assertTrue(runBlocking { repo.device.store.groups() }.isEmpty())
    }

    @Test
    fun aLinkFromOutsideJoinsAtOnce() {
        val repo = repoFor("bbbbbbbb-0000-4000-8000-000000000002")
        val gid = "00000000-0000-4000-8000-0000000000bb"
        compose.setContent {
            TripSplitTheme(dynamic = false) { JoinScreen(repo, makeInvite(gid, ByteArray(32) { 9 }), onBack = {}, onOpenNearby = {}) }
        }
        compose.awaitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("open_nearby")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { repo.device.store.group(gid) } != null)
    }
}
