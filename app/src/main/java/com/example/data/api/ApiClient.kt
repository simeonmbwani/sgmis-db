package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class ApiClient(private val sessionManager: SessionManager) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = AuthInterceptor(sessionManager)

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentBaseUrl = ""
    private var cachedApiService: ApiService? = null

    fun getApiService(): ApiService {
        val configuredUrl = sessionManager.serverUrl.let {
            if (it.endsWith("/")) it else "$it/"
        }

        if (cachedApiService != null && currentBaseUrl == configuredUrl) {
            return cachedApiService!!
        }

        currentBaseUrl = configuredUrl
        val retrofit = Retrofit.Builder()
            .baseUrl(configuredUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val service = retrofit.create(ApiService::class.java)
        cachedApiService = service
        return service
    }

    fun invalidateClient() {
        cachedApiService = null
    }
}
