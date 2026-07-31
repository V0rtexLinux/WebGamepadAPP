package com.gamemapper.utils

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists per-domain login credentials (username + password) in SharedPreferences.
 *
 * Passwords are stored Base64-encoded — not cryptographically protected, but
 * good enough so they are not stored in plain text on screen. These are game
 * passwords, not banking credentials.
 *
 * Key: canonical hostname (e.g. "cpps.app", "play.cpjourney.net")
 */
object CredentialStorage {

    private val gson = Gson()

    data class SavedCredential(
        val domain: String,
        val username: String,
        /** Base64-encoded password. */
        val passwordEncoded: String
    ) {
        fun decryptedPassword(): String =
            String(Base64.decode(passwordEncoded, Base64.DEFAULT))
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Save (or overwrite) credentials for [domain]. */
    fun save(context: Context, domain: String, username: String, password: String) {
        val map = loadAll(context).toMutableMap()
        map[domain] = SavedCredential(
            domain = domain,
            username = username,
            passwordEncoded = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.DEFAULT
            )
        )
        persist(context, map)
    }

    /** Load credentials for [domain], or null if none saved. */
    fun load(context: Context, domain: String): SavedCredential? =
        loadAll(context)[domain]

    /** Delete saved credentials for [domain]. */
    fun delete(context: Context, domain: String) {
        val map = loadAll(context).toMutableMap()
        map.remove(domain)
        persist(context, map)
    }

    /** True if there are saved credentials for [domain]. */
    fun has(context: Context, domain: String): Boolean =
        loadAll(context).containsKey(domain)

    // ── Internal ────────────────────────────────────────────────────────────

    private fun loadAll(context: Context): Map<String, SavedCredential> {
        val json = prefs(context).getString(Constants.KEY_CREDENTIALS, null)
            ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, SavedCredential>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun persist(context: Context, map: Map<String, SavedCredential>) {
        prefs(context).edit()
            .putString(Constants.KEY_CREDENTIALS, gson.toJson(map))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Extract the canonical domain key from a full URL or hostname.
     * e.g. "https://play.cpjourney.net/en" → "play.cpjourney.net"
     */
    fun domainFromUrl(url: String): String {
        return try {
            val host = android.net.Uri.parse(url).host ?: url
            host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }
    }
}
