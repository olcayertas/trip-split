package com.olcayertas.tripsplit.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * The marker is an AES key in the Android Keystore under a fixed alias. Keystore keys are not part of any
 * backup, and Android removes them when the app is uninstalled. The key is never used to encrypt anything.
 */
class KeystoreInstallMarker(private val alias: String = ALIAS) : InstallMarker {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun exists(): Boolean = store().containsAlias(alias)

    override fun create() {
        if (exists()) return
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        const val ALIAS = "tripsplit_install_marker"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
