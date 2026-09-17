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
        
        val DEFAULT_SERVER_URL = BuildConfig.DEFAULT_API_URL
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
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

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
