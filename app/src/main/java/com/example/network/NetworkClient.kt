package com.example.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared high-performance HTTP client for Spotify API, YouTube Music stream resolvers,
 * and multi-source online lyrics fetchers.
 */
object NetworkClient {

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) imux-music-player/2.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
