package com.olcayertas.tripsplit.data.identity

/**
 * A flag that a backup or a device transfer never restores (PLAN §4.2). If the database exists and the
 * marker does not, the database came from another install.
 */
interface InstallMarker {
    fun exists(): Boolean

    fun create()
}
