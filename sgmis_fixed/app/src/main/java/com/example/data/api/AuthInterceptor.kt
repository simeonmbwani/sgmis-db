package com.example.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class AuthInterceptor(private val sessionManager: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = sessionManager.accessToken

        val request = if (!token.isNullOrBlank()) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        } else {
            originalRequest.newBuilder()
                .header("Accept", "application/json")
                .build()
        }

        val response = chain.proceed(request)

        // Handle expired token with automatic refresh
        val urlPath = originalRequest.url.encodedPath
        if (response.code == 401 && !urlPath.contains("auth/login") && !urlPath.contains("auth/refresh")) {
            val refreshToken = sessionManager.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                synchronized(this) {
                    // Re-check in case another thread refreshed already
                    val currentToken = sessionManager.accessToken
                    if (currentToken != token && !currentToken.isNullOrBlank()) {
                        response.close()
                        val retryRequest = originalRequest.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .header("Accept", "application/json")
                            .build()
                        return chain.proceed(retryRequest)
                    }

                    val baseUrl = sessionManager.serverUrl.trimEnd('/')
                    val refreshUrl = "$baseUrl/auth/refresh/"
                    val jsonBody = JSONObject().put("refresh", refreshToken).toString()
                    val refreshReq = Request.Builder()
                        .url(refreshUrl)
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .header("Accept", "application/json")
                        .build()

                    try {
                        // Use the chain's client mechanism or call via OkHttpClient
                        val refreshResponse = chain.proceed(refreshReq)
                        if (refreshResponse.isSuccessful) {
                            val bodyStr = refreshResponse.body?.string()
                            refreshResponse.close()
                            if (!bodyStr.isNullOrBlank()) {
                                val json = JSONObject(bodyStr)
                                val newAccess = json.optString("access", null)
                                if (!newAccess.isNullOrBlank()) {
                                    sessionManager.accessToken = newAccess
                                    val newRefresh = json.optString("refresh", null)
                                    if (!newRefresh.isNullOrBlank()) {
                                        sessionManager.refreshToken = newRefresh
                                    }

                                    response.close()
                                    val retryRequest = originalRequest.newBuilder()
                                        .header("Authorization", "Bearer $newAccess")
                                        .header("Accept", "application/json")
                                        .build()
                                    return chain.proceed(retryRequest)
                                }
                            }
                        } else {
                            refreshResponse.close()
                            sessionManager.clearSession()
                        }
                    } catch (e: Exception) {
                        sessionManager.clearSession()
                    }
                }
            } else {
                sessionManager.clearSession()
            }
        }

        return response
    }
}
