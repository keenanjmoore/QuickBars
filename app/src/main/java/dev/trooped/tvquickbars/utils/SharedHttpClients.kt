package dev.trooped.tvquickbars.utils

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttpClient registry sharing a single connection pool and dispatcher thread pool
 * across WebSocket clients, MJPEG loops, notification image fetches, and camera snapshots.
 */
object SharedHttpClients {
    private val connectionPool = ConnectionPool(8, 2, TimeUnit.MINUTES)

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Client configured for persistent Home Assistant WebSocket connections. */
    val webSocket: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** Client configured for chunked MJPEG multipart streaming with bounded stalls. */
    val mjpegStream: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Client configured for fast notification/snapshot media downloads with hard bounds. */
    val mediaFetch: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
