package com.example.forcavendas

import android.content.Context

/**
 * Centralized configuration for ERP API access
 * 
 * ⚠️ IMPORTANT: The ERP token must NOT be confused with:
 * - session_signature (local authentication)
 * - user_id (representante ID)
 * 
 * The ERP token is the actual authentication credential for the ERP system.
 */
object ERPConfig {
    
    // The test token is embedded in the APK; production needs a secure backend.
    private val DEFAULT_ERP_TOKEN = BuildConfig.ERP_TEST_TOKEN
    
    private const val ERP_API_TOKEN_KEY = "erp_api_token"
    private const val PREFERENCES_NAME = "forca_vendas"
    
    /**
     * Get the ERP API token for authentication
     * 
     * @param context Android context
     * @return The ERP API token
     */
    fun getERPToken(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        return preferences.getString(ERP_API_TOKEN_KEY, DEFAULT_ERP_TOKEN) ?: DEFAULT_ERP_TOKEN
    }
    
    /**
     * Set the ERP API token (called during login or configuration)
     * 
     * @param context Android context
     * @param token The new ERP API token
     */
    fun setERPToken(context: Context, token: String) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        preferences.edit().putString(ERP_API_TOKEN_KEY, token).apply()
    }
    
    /**
     * Clear the ERP API token (called during logout)
     * 
     * @param context Android context
     */
    fun clearERPToken(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        preferences.edit().remove(ERP_API_TOKEN_KEY).apply()
    }
    
    /**
     * Verify that the token is valid (not empty, not the default placeholder)
     * 
     * @param token The token to verify
     * @return true if token is valid
     */
    fun isValidToken(token: String): Boolean {
        return token.isNotEmpty() && token.length > 10
    }
}
