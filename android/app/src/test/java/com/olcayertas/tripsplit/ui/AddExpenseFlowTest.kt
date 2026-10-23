package com.olcayertas.tripsplit.ui

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
import com.olcayertas.tripsplit.core.ExpenseDraft
import com.olcayertas.tripsplit.core.ExpenseValues
import com.olcayertas.tripsplit.core.MemberState
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.SplitMode
import com.olcayertas.tripsplit.data.RoomOpStore
import com.olcayertas.tripsplit.data.TripRepository
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import com.olcayertas.tripsplit.ui.theme.TripSplitTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class AddExpenseFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: TripSplitDatabase
    private var ids = 0
    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TripSplitDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val ali = MemberState("20000000-0000-4000-8000-00000000000a", "Ali", false, null)
    private val ayse = MemberState("20000000-0000-4000-8000-00000000000b", "Ayşe", false, null)

    private fun draft() = ExpenseDraft(baseCurrency = "EUR", paidBy = ali.id, selected = setOf(ali.id, ayse.id))

    @Test
    fun typingATitleAndACommaAmountSavesTheValues() {
        var saved: ExpenseValues? = null
        compose.setContent {
            TripSplitTheme(dynamic = false) {
                ExpenseEditorScreen(listOf(ali, ayse), draft(), editing = false, onBack = {}, onSave = { saved = it })
            }
        }
        compose.onNodeWithTag("title").performTextInput("Dinner")
        compose.onNodeWithTag("amount").performTextInput("12,50")
        compose.onNodeWithTag("save").performClick()
        assertNotNull(saved)
        val v = saved!!
        assertEquals("Dinner", v.title)
        assertEquals(1250L, v.amountMinor)
        assertEquals("EUR", v.currency)
    }

    @Test
    fun anInvalidFormShowsErrorsAndDoesNotSave() {
        var saved: ExpenseValues? = null
        compose.setContent {
            TripSplitTheme(dynamic = false) {
                ExpenseEditorScreen(listOf(ali, ayse), draft(), editing = false, onBack = {}, onSave = { saved = it })
            }
        }
        compose.onNodeWithTag("save").performClick()
        assertNull(saved)
        compose.onNodeWithText("Enter a title of 1 to 100 characters.").assertIsDisplayed()
        compose.onNodeWithText("Enter an amount above zero. A comma or a dot both work.").assertIsDisplayed()
    }

    @Test
    fun theExpenseRouteWritesAnOpThatTheFoldShows() {
        val repo = TripRepository(db, Device("aaaaaaaa-0000-4000-8000-000000000001", RoomOpStore(db), wall = { 1_761_210_000_000L }, newId = ::nextId))
        val g = runBlocking { repo.createGroup("Trip", "EUR", "Ali") }
        val ayseId = runBlocking { repo.addMember(g.groupId, "Ayşe", false).memberId }
        var done = false
        compose.setContent {
            TripSplitTheme(dynamic = false) {
                ExpenseRoute(repo, g.groupId, null, onDone = { done = true })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("title") > 0 }
        compose.onNodeWithTag("title").performTextInput("Taxi")
        compose.onNodeWithTag("amount").performTextInput("30,00")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { done }
        val e = runBlocking { repo.foldNow(g.groupId) }.expenses.single()
        assertEquals("Taxi", e.title)
        assertEquals(3000L, e.baseAmountMinor)
        assertEquals(mapOf(g.creatorMemberId to 1500L, ayseId to 1500L), e.baseShares)
        assertEquals(g.creatorMemberId, e.paidBy)
        assertEquals(SplitMode.EQUAL, ExpenseDraft.from(e, "EUR").mode)
        assertEquals(OpBodies.equalSplit(listOf(g.creatorMemberId, ayseId).sorted()), e.money["split"])
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
