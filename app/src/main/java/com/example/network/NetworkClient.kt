package com.example.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP clients:
 * - [okHttpClient]: balanced timeouts for Spotify API, lyrics, and long metadata calls.
 * - [fastProbeClient]: aggressive fail-fast timeouts for parallel mirror racing & stream probing.
 */
object NetworkClient {

    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) imux-music-player/2.5"
    private val sharedConnectionPool = ConnectionPool(12, 5, TimeUnit.MINUTES)

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", DEFAULT_UA)
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    val fastProbeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", DEFAULT_UA)
                }
                chain.proceed(builder.build())
            }
            .build()
    }
}
