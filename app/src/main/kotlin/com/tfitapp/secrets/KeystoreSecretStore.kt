package com.tfitapp.secrets

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * API keys behind the Android Keystore. The only place a key is ever read or
 * written. Never log a value from here (AGENTS.md, "API keys").
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val isSet = mutableMapOf<SecretKey, MutableStateFlow<Boolean>>()

    private fun flowFor(key: SecretKey): MutableStateFlow<Boolean> =
        isSet.getOrPut(key) { MutableStateFlow(prefs.contains(key.name)) }

    override suspend fun get(key: SecretKey): String? = withContext(Dispatchers.IO) { prefs.getString(key.name, null) }

    override suspend fun set(key: SecretKey, value: String?) = withContext(Dispatchers.IO) {
        prefs.edit().apply { if (value.isNullOrBlank()) remove(key.name) else putString(key.name, value.trim()) }.apply()
        flowFor(key).value = !value.isNullOrBlank()
    }

    override fun observeIsSet(key: SecretKey): Flow<Boolean> = flowFor(key).asStateFlow()

    private companion object {
        const val FILE = "tfit.secrets"
    }
}
