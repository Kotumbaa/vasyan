package `fun`.tryliner.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import `fun`.tryliner.android.core.model.AuthSession
import `fun`.tryliner.android.core.network.SessionStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedSessionStorage(context: Context) : SessionStorage {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _sessionFlow = MutableStateFlow<AuthSession?>(null)
    val sessionFlow: StateFlow<AuthSession?> = _sessionFlow.asStateFlow()

    init {
        _sessionFlow.value = getSession()
    }

    override fun getSession(): AuthSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            json.decodeFromString<AuthSession>(raw)
        }.getOrNull()
    }

    override fun setSession(session: AuthSession?) {
        if (session == null) {
            prefs.edit().remove(KEY_SESSION).apply()
        } else {
            val encoded = json.encodeToString(session)
            prefs.edit().putString(KEY_SESSION, encoded).apply()
        }
        _sessionFlow.value = session
    }

    override fun getEdgeKey(): String? {
        return prefs.getString(KEY_EDGE_KEY, null)
    }

    override fun setEdgeKey(key: String?) {
        if (key == null) {
            prefs.edit().remove(KEY_EDGE_KEY).apply()
        } else {
            prefs.edit().putString(KEY_EDGE_KEY, key).apply()
        }
    }

    override fun getClearanceCookie(): String? {
        return prefs.getString(KEY_CLEARANCE_COOKIE, null)
    }

    override fun setClearanceCookie(value: String?) {
        if (value == null) {
            prefs.edit().remove(KEY_CLEARANCE_COOKIE).apply()
        } else {
            prefs.edit().putString(KEY_CLEARANCE_COOKIE, value).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "liner_encrypted_session"
        const val KEY_SESSION = "auth_session_v1"
        const val KEY_EDGE_KEY = "edge_key_v1"
        const val KEY_CLEARANCE_COOKIE = "cf_clearance_v1"
    }
}
