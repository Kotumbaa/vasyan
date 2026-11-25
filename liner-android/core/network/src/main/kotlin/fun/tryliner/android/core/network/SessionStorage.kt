package `fun`.tryliner.android.core.network

import `fun`.tryliner.android.core.model.AuthSession

/**
 * Persistence abstraction for Liner authentication tokens, edge key, and Cloudflare clearance cookie.
 * Android `:app` implements this with `EncryptedSharedPreferences`.
 */
interface SessionStorage {
    fun getSession(): AuthSession?
    fun setSession(session: AuthSession?)
    fun getEdgeKey(): String? = null
    fun setEdgeKey(key: String?) {}
    fun getClearanceCookie(): String? = null
    fun setClearanceCookie(value: String?) {}
}

/**
 * Simple thread-safe in-memory [SessionStorage] for tests and ephemeral usage.
 */
class InMemorySessionStorage(
    initialSession: AuthSession? = null,
    initialEdgeKey: String? = null,
    initialClearanceCookie: String? = null,
) : SessionStorage {
    @Volatile
    private var session: AuthSession? = initialSession

    @Volatile
    private var edgeKey: String? = initialEdgeKey

    @Volatile
    private var clearanceCookie: String? = initialClearanceCookie

    override fun getSession(): AuthSession? = session

    override fun setSession(session: AuthSession?) {
        this.session = session
    }

    override fun getEdgeKey(): String? = edgeKey

    override fun setEdgeKey(key: String?) {
        this.edgeKey = key
    }

    override fun getClearanceCookie(): String? = clearanceCookie

    override fun setClearanceCookie(value: String?) {
        this.clearanceCookie = value
    }
}
