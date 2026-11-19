package com.example.forcavendas

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionManagerTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "session_manager_test_${UUID.randomUUID()}"
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(preferencesName, mode)
        }
        preferences = context.getSharedPreferences("forca_vendas", Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        baseContext.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun defaultLicenseMigratesMatchingSessionAndAvatar() {
        preferences.edit()
            .putString("user_id", "42")
            .putString("license", "7")
            .putString("session_signature", "42|7")
            .putString("avatar_uri_42_7", "content://photo")
            .commit()

        SessionManager.useDefaultLicense(context)

        assertEquals("1", preferences.getString("license", null))
        assertEquals("42|1", preferences.getString("session_signature", null))
        assertEquals("content://photo", preferences.getString("avatar_uri_42_1", null))
    }

    @Test
    fun defaultLicenseDoesNotClaimOtherSessionOrReplaceExistingAvatar() {
        preferences.edit()
            .putString("user_id", "42")
            .putString("license", "7")
            .putString("session_signature", "99|7")
            .putString("avatar_uri_42_7", "content://old")
            .putString("avatar_uri_42_1", "content://current")
            .commit()

        SessionManager.useDefaultLicense(context)

        assertEquals("1", preferences.getString("license", null))
        assertEquals("99|7", preferences.getString("session_signature", null))
        assertEquals("content://current", preferences.getString("avatar_uri_42_1", null))
    }
}
