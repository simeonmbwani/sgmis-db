package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("sgmis_session_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testDefaultServerUrlPointsToProduction() {
        val sessionManager = SessionManager(context)
        assertEquals("https://sgmis-db.onrender.com", sessionManager.serverUrl)
    }

    @Test
    fun testLegacyEmulatorUrlMigratedToProduction() {
        val prefs = context.getSharedPreferences("sgmis_session_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", "http://10.0.2.2:8000/").commit()

        val sessionManager = SessionManager(context)
        assertEquals("https://sgmis-db.onrender.com", sessionManager.serverUrl)
    }

    @Test
    fun testLegacyLocalhostUrlMigratedToProduction() {
        val prefs = context.getSharedPreferences("sgmis_session_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", "http://127.0.0.1:8000").commit()

        val sessionManager = SessionManager(context)
        assertEquals("https://sgmis-db.onrender.com", sessionManager.serverUrl)
    }

    @Test
    fun testExplicitUserOverridePreservedAfterMigration() {
        val prefs = context.getSharedPreferences("sgmis_session_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", "http://10.0.2.2:8000/").commit()

        // First initialization migrates legacy emulator to production
        val sessionManager1 = SessionManager(context)
        assertEquals("https://sgmis-db.onrender.com", sessionManager1.serverUrl)

        // User explicitly sets emulator or staging URL
        sessionManager1.serverUrl = "http://10.0.2.2:8000/"
        assertEquals("http://10.0.2.2:8000", sessionManager1.serverUrl)

        // Subsequent session respects user's explicit choice
        val sessionManager2 = SessionManager(context)
        assertEquals("http://10.0.2.2:8000", sessionManager2.serverUrl)
    }
}
