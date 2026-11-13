package com.example.forcavendas

import android.content.Context
import org.json.JSONArray

object PaymentConditionService {
    private const val PREFERENCES_NAME = "forca_vendas"
    private const val CONDITIONS_KEY = "payment_conditions_synced_json"
    private const val COUNT_KEY = "payment_conditions_synced_count"
    private const val SYNCED_AT_KEY = "payment_conditions_synced_at"

    data class Condition(
        val id: String,
        val description: String,
        val installments: Int,
        val averageTerm: Int,
        val minimumValue: Double,
        val dueDays: List<Int>
    )

    fun save(context: Context, response: String) {
        val conditions = JSONArray(response)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString(CONDITIONS_KEY, response)
            .putInt(COUNT_KEY, conditions.length())
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): List<Condition> {
        val json = context.getSharedPreferences(PREFERENCES_NAME, 0)
            .getString(CONDITIONS_KEY, null) ?: return emptyList()
        val conditions = mutableListOf<Condition>()
        return try {
            val array = JSONArray(json)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val dueDays = (1..15).map { parcel ->
                    val numberedDay = item.optInt("CondPgtodias$parcel", 0)
                    if (numberedDay > 0) {
                        numberedDay
                    } else {
                        item.optInt("CondPgtoDias$parcel", 0)
                    }
                }
                conditions.add(
                    Condition(
                        id = item.optString("CondPgtoId"),
                        description = item.optString("CondPgtoDescricao"),
                        installments = item.optInt("CondPgtoNroParcelas", 1),
                        averageTerm = item.optInt("CondPgtoPrazoMedio", 0),
                        minimumValue = item.optString("CondicaoPagVendaMinVlr")
                            .replace(",", ".")
                            .toDoubleOrNull() ?: 0.0,
                        dueDays = dueDays
                    )
                )
            }
            conditions
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun count(context: Context): Int =
        context.getSharedPreferences(PREFERENCES_NAME, 0).getInt(COUNT_KEY, 0)

    fun syncedAt(context: Context): Long =
        context.getSharedPreferences(PREFERENCES_NAME, 0).getLong(SYNCED_AT_KEY, 0L)
}
