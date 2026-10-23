package com.olcayertas.tripsplit.data.identity

import com.olcayertas.tripsplit.data.db.DeviceEntity
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import java.util.UUID

/** The device id of this install and the ids it had before a restore. */
data class DeviceIdentity(val deviceId: String, val displayName: String, val previousIds: List<String>)

/**
 * Decides the device id at launch (PLAN §4.2).
 *
 * - No device row: first run. Make an id and the marker.
 * - Row and marker: normal launch.
 * - Row and no marker: the database came from a backup or another phone. Make a new id, keep the old id
 *   in `previous_ids` and make the marker. The old ops stay valid under the old id.
 */
class IdentityManager(
    private val db: TripSplitDatabase,
    private val marker: InstallMarker,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun ensure(): DeviceIdentity {
        val row = db.device().get()
        val entity = when {
            row == null -> DeviceEntity(deviceId = newId(), displayName = "", hlcMillis = 0, hlcCounter = 0)
            marker.exists() -> row
            else -> row.copy(
                deviceId = newId(),
                previousIds = (row.previousIds.split(',').filter { it.isNotEmpty() } + row.deviceId).joinToString(","),
            )
        }
        if (entity !== row) db.device().put(entity)
        if (!marker.exists()) marker.create()
        return entity.toIdentity()
    }

    suspend fun setDisplayName(name: String) = db.device().setName(name)

    private fun DeviceEntity.toIdentity() =
        DeviceIdentity(deviceId, displayName, previousIds.split(',').filter { it.isNotEmpty() })
}
