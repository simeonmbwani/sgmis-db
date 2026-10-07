package com.example.data.api

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.data.model.User
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sgmis_session_prefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val userAdapter = moshi.adapter(User::class.java)

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_JSON = "user_json"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_THEME_MODE = "theme_mode"
        
        private const val KEY_URL_MIGRATED_TO_PROD_V1 = "url_migrated_to_prod_v1"
        private const val KEY_URL_MIGRATED_TO_PROD_V2 = "url_migrated_to_prod_v2"

        val DEFAULT_SERVER_URL = BuildConfig.DEFAULT_API_URL
        const val PRODUCTION_SERVER_URL = "https://security-management-5u3m.onrender.com/"
        const val EMULATOR_SERVER_URL = "http://10.0.2.2:8000/"
    }

    init {
        migrateLegacyServerUrl()
    }

    private fun migrateLegacyServerUrl() {
        val hasMigratedV2 = prefs.getBoolean(KEY_URL_MIGRATED_TO_PROD_V2, false)
        if (!hasMigratedV2) {
            val savedUrl = prefs.getString(KEY_SERVER_URL, null)
            if (savedUrl.isNullOrBlank() || isLegacyOrLoopbackUrl(savedUrl)) {
                val prodUrl = DEFAULT_SERVER_URL.trim().trimEnd('/')
                prefs.edit()
                    .putString(KEY_SERVER_URL, prodUrl)
                    .putBoolean(KEY_URL_MIGRATED_TO_PROD_V2, true)
                    .apply()
            } else {
                prefs.edit().putBoolean(KEY_URL_MIGRATED_TO_PROD_V2, true).apply()
            }
        }
    }

    private fun isLegacyOrLoopbackUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("10.0.2.2") ||
               lower.contains("localhost") ||
               lower.contains("127.0.0.1") ||
               lower.contains("sgmis-db.onrender.com")
    }

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun getThemeMode(): com.example.ui.theme.ThemeMode {
        return try {
            com.example.ui.theme.ThemeMode.valueOf(themeMode)
        } catch (e: Exception) {
            com.example.ui.theme.ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: com.example.ui.theme.ThemeMode) {
        themeMode = mode.name
    }

    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER_URL, null)
            if (saved.isNullOrBlank()) {
                val defaultUrl = DEFAULT_SERVER_URL.trim().trimEnd('/')
                prefs.edit().putString(KEY_SERVER_URL, defaultUrl).apply()
                return defaultUrl
            }
            return saved
        }
        set(value) {
            val cleaned = value.trim().trimEnd('/')
            prefs.edit().putString(KEY_SERVER_URL, cleaned).apply()
        }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    fun saveUser(user: User) {
        try {
            val json = userAdapter.toJson(user)
            prefs.edit().putString(KEY_USER_JSON, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getUser(): User? {
        val json = prefs.getString(KEY_USER_JSON, null) ?: return null
        return try {
            userAdapter.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun isLoggedIn(): Boolean {
        return !accessToken.isNullOrBlank()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_JSON)
            .apply()
    }
}
