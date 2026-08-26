package com.deepak.umber.remote

import android.content.Context
import android.os.Build
import com.deepak.umber.BuildConfig
import com.deepak.umber.data.repo.UmberRepository
import com.deepak.umber.work.SyncWorker
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.HttpException
import retrofit2.Retrofit

/**
 * The cloud flavour's [RemoteSync]: Retrofit + OkHttp + kotlinx.serialization against the endpoints
 * in `docs/SYNC.md`, backed by [CloudPrefs] for the device's credentials and sync bookkeeping.
 *
 * Every network or auth failure is caught here and turned into a [SyncOutcome] — nothing above this
 * class ever needs to guard a sync call, because sync is never load-bearing (SYNC.md's "Failure
 * behaviour"): a dead server degrades to exactly what the privacy build does.
 */
internal class HttpRemoteSync(
    private val context: Context,
    private val repository: UmberRepository,
) : RemoteSync {

    private val prefs = CloudPrefs(context)
    private val syncMutex = Mutex()

    private val _status = MutableStateFlow(currentStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override var baseUrlOverride: String?
        get() = prefs.baseUrlOverride
        set(value) {
            prefs.baseUrlOverride = value
            cachedApi = null // rebuilt against the new host on next use
        }

    init {
        // WorkManager persists periodic work across process death on its own, but re-asserting the
        // schedule on every launch is cheap (KEEP is a no-op if it already exists) and self-heals a
        // wiped WorkManager database without anyone needing to toggle the Settings switch.
        if (prefs.enabled) SyncWorker.schedule(context)
    }

    // ---------------------------------------------------------------- registration

    override suspend fun enable(setupKey: String): RegisterOutcome {
        if (setupKey.isBlank()) {
            val detail = "Setup key can't be empty"
            prefs.lastError = detail
            _status.value = currentStatus()
            return RegisterOutcome.Failed(detail)
        }

        return try {
            val response = api().registerDevice(
                RegisterDeviceRequest(setupKey = setupKey.trim(), label = Build.MODEL),
            )
            prefs.deviceId = response.deviceId
            prefs.token = response.token
            prefs.enabled = true
            prefs.lastError = null
            _status.value = currentStatus()
            SyncWorker.schedule(context)
            RegisterOutcome.Success
        } catch (e: Exception) {
            val detail = describeFailure(e, forRegistration = true)
            prefs.lastError = detail
            _status.value = currentStatus()
            RegisterOutcome.Failed(detail)
        }
    }

    override fun disable() {
        prefs.enabled = false
        // A deliberate disable isn't a failure — clear any stale error so re-enabling later (or
        // just glancing at Settings) doesn't show a stale "authentication failed" from before.
        prefs.lastError = null
        _status.value = currentStatus()
        SyncWorker.cancel(context)
    }

    // ---------------------------------------------------------------------- sync

    override suspend fun sync(): SyncOutcome {
        if (!prefs.enabled) return SyncOutcome.Disabled

        val deviceId = prefs.deviceId
        val token = prefs.token
        if (deviceId == null || token == null) {
            // Enabled but no credentials shouldn't be reachable through the UI, but a wiped prefs
            // file or a debug hack could get here — fail closed the same way an auth rejection does.
            prefs.enabled = false
            prefs.lastError = "not registered — enable sync again in Settings"
            _status.value = currentStatus()
            return SyncOutcome.Unauthorised
        }

        // One sync at a time. Without this a periodic run and a Settings "sync now" tap could
        // overlap and race on the same pending-rows batch and cursor.
        return syncMutex.withLock {
            _status.value = _status.value.copy(syncing = true)
            try {
                val pending = repository.pendingSync(PUSH_BATCH_LIMIT).map { it.toWire() }
                val response = api().sync(
                    SyncRequest(deviceId = deviceId, since = prefs.cursor, transactions = pending),
                )

                val rejected = response.rejected.map { it.clientId }.toSet()
                for (sent in pending) {
                    if (sent.clientId !in rejected) repository.markSynced(sent.clientId, sent.updatedAt)
                }

                for (wire in response.transactions) {
                    // A single malformed or conflicting row is skipped, not fatal to the sync —
                    // same "degrade, don't break" posture as an unreachable server.
                    runCatching { repository.applyRemoteTxn(wire.toRemoteTxn()) }
                }

                prefs.cursor = response.cursor
                prefs.lastSyncAt = System.currentTimeMillis()
                prefs.lastError = null
                _status.value = currentStatus()
                SyncOutcome.Success(
                    pushed = pending.size - rejected.size,
                    pulled = response.transactions.size,
                    cursor = response.cursor,
                )
            } catch (e: Exception) {
                if (isAuthFailure(e)) {
                    // "Auth rejected -> sync disables itself and says so in Settings rather than
                    // retrying forever" — docs/SYNC.md's Failure behaviour, verbatim.
                    prefs.enabled = false
                    prefs.lastError = "authentication failed, check your setup key in Settings"
                    _status.value = currentStatus()
                    SyncWorker.cancel(context)
                    SyncOutcome.Unauthorised
                } else {
                    val detail = describeFailure(e, forRegistration = false)
                    prefs.lastError = detail
                    _status.value = currentStatus()
                    SyncOutcome.Unreachable(detail)
                }
            } finally {
                _status.value = _status.value.copy(syncing = false)
            }
        }
    }

    // -------------------------------------------------------------------- plumbing

    private fun currentStatus() = SyncStatus(
        enabled = prefs.enabled,
        syncing = false,
        lastSyncAt = prefs.lastSyncAt,
        lastError = prefs.lastError,
        hasCredentials = prefs.deviceId != null && prefs.token != null,
    )

    private fun isAuthFailure(e: Exception): Boolean =
        e is HttpException && (e.code() == 401 || e.code() == 403)

    private fun describeFailure(e: Exception, forRegistration: Boolean): String = when {
        forRegistration && isAuthFailure(e) -> "setup key rejected"
        e is HttpException -> "server error (${e.code()})"
        e is IOException -> "can't reach the server"
        else -> e.message ?: e::class.simpleName ?: "sync failed"
    }

    /**
     * Debug builds only: an explicit [baseUrlOverride] (e.g. `http://10.0.2.2:8000` from an
     * emulator) wins over the compiled-in default. Release builds ignore any stored override
     * entirely, even a stray one, so the shipped app can never be pointed anywhere but the real
     * server by a leftover debug setting.
     */
    private fun effectiveBaseUrl(): String {
        val override = if (BuildConfig.DEBUG) prefs.baseUrlOverride else null
        val base = override ?: BuildConfig.SYNC_BASE_URL
        return if (base.endsWith("/")) base else "$base/"
    }

    private val authInterceptor = Interceptor { chain ->
        val token = prefs.token
        val request = chain.request().let { original ->
            if (token != null) original.newBuilder().addHeader("Authorization", "Bearer $token").build() else original
        }
        chain.proceed(request)
    }

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedApi: SyncApi? = null

    private fun api(): SyncApi {
        val url = effectiveBaseUrl()
        cachedApi?.let { if (cachedBaseUrl == url) return it }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(SyncApi::class.java).also {
            cachedApi = it
            cachedBaseUrl = url
        }
    }

    private companion object {
        /**
         * Defensive cap on one push. SYNC.md doesn't mandate chunking, but a first sync on a
         * years-old install could otherwise try to serialise the entire ledger in one request; the
         * remainder simply becomes next run's pending batch since `syncedAt` only advances for rows
         * actually sent.
         */
        const val PUSH_BATCH_LIMIT = 500
    }
}
