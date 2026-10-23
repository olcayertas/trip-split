package com.olcayertas.tripsplit

import android.content.Context
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.data.RoomOpStore
import com.olcayertas.tripsplit.net.NearbyController
import com.olcayertas.tripsplit.data.TripRepository
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import com.olcayertas.tripsplit.data.identity.DeviceIdentity
import com.olcayertas.tripsplit.data.identity.IdentityManager
import com.olcayertas.tripsplit.data.identity.KeystoreInstallMarker
import kotlinx.coroutines.CompletableDeferred

/** The objects the app shares. Built once, in [TripSplitApp]. */
class AppContainer(context: Context) {
    val database: TripSplitDatabase = TripSplitDatabase.open(context.applicationContext)
    val store = RoomOpStore(database)
    val identityManager = IdentityManager(database, KeystoreInstallMarker())

    /** A `tripsplit://join` link that the app got from outside. The root screen opens the join screen with it. */
    val pendingInvite = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Set once [IdentityManager.ensure] has run. Await it before you use [repository]. */
    val identity = CompletableDeferred<DeviceIdentity>()
    lateinit var repository: TripRepository
        private set

    /** Sync while the Nearby screen is open. */
    val nearby = NearbyController(context.applicationContext) { repository.device }

    suspend fun start() {
        val id = identityManager.ensure()
        repository = TripRepository(database, Device(id.deviceId, store))
        identity.complete(id)
    }
}
