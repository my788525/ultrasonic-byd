package org.moire.ultrasonic.di

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.koin.dsl.module

private const val CONNECT_TIMEOUT_SEC = 8L
private const val IO_TIMEOUT_SEC = 15L

/**
 * This Koin module provides base network dependencies.
 */
val baseNetworkModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
