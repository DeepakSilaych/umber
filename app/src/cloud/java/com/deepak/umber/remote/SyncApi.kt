package com.deepak.umber.remote

import retrofit2.http.Body
import retrofit2.http.POST

/** The two endpoints this flavour needs. See `docs/SYNC.md`. */
internal interface SyncApi {

    @POST("v1/devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): RegisterDeviceResponse

    @POST("v1/sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse
}
