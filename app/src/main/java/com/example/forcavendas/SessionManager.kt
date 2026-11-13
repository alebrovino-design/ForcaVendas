package com.example.forcavendas

import android.content.Context

object SessionManager {
    private fun preferencesName(): String = "forca_vendas"

    fun logout(context: Context) {
        context.getSharedPreferences(preferencesName(), 0).edit()
            .putBoolean("logged_in", false)
            .remove("username")
            .remove("license")
            .remove("user_id")
            .remove("password_hash")
            .remove("password_salt")
            .remove("auto_sync_last_day")
            .remove("session_signature")
            .remove("target_representative_id")
            .remove("target_month")
            .remove("target_weight")
            .remove("target_achieved")
            .remove("target_synced_at")
            .remove("clients_synced_json")
            .remove("clients_synced_count")
            .remove("clients_synced_at")
            .remove("financial_synced_json")
            .remove("financial_synced_count")
            .remove("financial_synced_at")
            .remove("loads_synced_json")
            .remove("loads_synced_count")
            .remove("loads_synced_at")
            .remove("bonus_configuration_synced_json")
            .remove("bonus_configuration_synced_count")
            .remove("bonus_configuration_synced_at")
            .remove("tax_groups_synced_json")
            .remove("tax_groups_synced_count")
            .remove("tax_groups_synced_at")
            .remove("products_synced_at")
            .apply()
    }

    fun currentUserId(context: Context): String =
        context.getSharedPreferences(preferencesName(), 0)
            .getString("user_id", "") ?: ""

    fun currentLicense(context: Context): String =
        context.getSharedPreferences(preferencesName(), 0)
            .getString("license", "") ?: ""

    fun sessionSignature(context: Context): String =
        "${currentUserId(context)}|${currentLicense(context)}"

    fun bindCurrentSession(context: Context) {
        context.getSharedPreferences(preferencesName(), 0).edit()
            .putString("session_signature", sessionSignature(context))
            .apply()
    }

    fun cachedDataBelongsToCurrentSession(context: Context): Boolean {
        val preferences = context.getSharedPreferences(preferencesName(), 0)
        val stored = preferences.getString("session_signature", null) ?: return true
        return stored == sessionSignature(context)
    }
}
