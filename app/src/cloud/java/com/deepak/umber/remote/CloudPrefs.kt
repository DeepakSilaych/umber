package com.deepak.umber.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device credentials and sync bookkeeping, encrypted at rest.
 *
 * A single small preferences file rather than a Room table: this data is infrequently written,
 * has nothing to do with the ledger's conflict/dedupe invariants, and keeping it out of `umber.db`
 * means a ledger rebuild or a restored CSV backup can never touch the device's registration.
 */
internal class CloudPrefs(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Whether the user has opted in. Off by default — sync never runs until this is true. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Server cursor from the last successful sync — SYNC.md's `since`. 0 means "never synced". */
    var cursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_CURSOR, value).apply()

    var lastSyncAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value ?: -1L).apply()

    /** Human-readable reason the last attempt failed, or null if it didn't. */
    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /** Debug-only endpoint override. See `RemoteSync.baseUrlOverride`. */
    var baseUrlOverride: String?
        get() = prefs.getString(KEY_BASE_URL_OVERRIDE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_BASE_URL_OVERRIDE, value).apply()

    private companion object {
        const val FILE_NAME = "umber_cloud_prefs"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_ENABLED = "enabled"
        const val KEY_CURSOR = "cursor"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_BASE_URL_OVERRIDE = "base_url_override"
    }
}
