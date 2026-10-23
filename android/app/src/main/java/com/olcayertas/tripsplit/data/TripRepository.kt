package com.olcayertas.tripsplit.data

import com.olcayertas.tripsplit.core.CreatedGroup
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.FoldState
import com.olcayertas.tripsplit.core.Invite
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.planEarlierExpenses
import com.olcayertas.tripsplit.core.fold
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class AddMemberResult(val memberId: String, val untouchedExpenses: List<String>)

/** A group the device holds. The name and the balances are fold results, not columns. */
class GroupSummary(val groupId: String, val state: FoldState)

/**
 * The read and write side the UI uses. Writes go through [Device], so the HLC and `seq` rules of the
 * sync engine hold for local ops too. Reads fold the stored ops.
 */
class TripRepository(
    private val db: TripSplitDatabase,
    val device: Device,
) {
    suspend fun createGroup(name: String, baseCurrency: String, creatorName: String): CreatedGroup =
        device.createGroup(name, baseCurrency, creatorName)

    suspend fun join(invite: Invite) = device.join(invite)

    suspend fun append(groupId: String, type: String, body: JsonObject): JsonObject =
        device.append(groupId, type, body)

    /** The fold of one group. It emits again whenever an op arrives, local or from a peer. */
    fun observeFold(groupId: String): Flow<FoldState> =
        db.ops().observeOpJson(groupId)
            .map { rows -> fold(groupId, rows.map { Json.parseToJsonElement(it).jsonObject }) }

    /** The raw ops of one group, for the history. */
    fun observeOps(groupId: String): Flow<List<JsonObject>> =
        db.ops().observeOpJson(groupId).map { rows -> rows.map { Json.parseToJsonElement(it).jsonObject } }

    /**
     * Adds a member (PLAN §7.1). With [includeEarlier] the new member also joins the split of every
     * earlier expense that is not deleted. The result lists the expenses the app could not change.
     */
    suspend fun addMember(groupId: String, name: String, includeEarlier: Boolean): AddMemberResult {
        val memberId = device.newId()
        append(groupId, "member.add", OpBodies.memberAdd(memberId, name))
        val untouched = if (includeEarlier) includeInEarlier(groupId, memberId) else emptyList()
        return AddMemberResult(memberId, untouched)
    }

    /** Writes one `expense.edit` for each earlier expense that can take [memberId]. Returns the ids it left. */
    suspend fun includeInEarlier(groupId: String, memberId: String): List<String> {
        val plan = planEarlierExpenses(foldNow(groupId).expenses, memberId)
        for (edit in plan.edits) append(groupId, "expense.edit", OpBodies.expenseEdit(edit.expenseId, edit.fields()))
        return plan.untouched
    }

    fun observeGroupIds(): Flow<List<String>> =
        db.groups().observeAll().map { rows -> rows.map { it.groupId } }.distinctUntilChanged()

    suspend fun foldNow(groupId: String): FoldState = fold(groupId, device.store.ops(groupId))
}
