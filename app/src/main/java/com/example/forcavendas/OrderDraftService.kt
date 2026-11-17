package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object OrderDraftService {
    private const val PREFERENCES_NAME = "forca_vendas"
    private const val DRAFTS_KEY = "order_drafts_json"

    data class Draft(
        val mobileId: Long,
        val erpId: Long,
        val clientId: String,
        val clientName: String,
        val regionId: String,
        val createdAt: String,
        val total: Double,
        val status: String,
        val json: JSONObject,
        val clientReasonName: String = "",
        val clientFantasyName: String = "",
        val cachedWeight: Double? = null,
        val cachedCompanyName: String = "",
        val cachedRegionDescription: String = ""
    )

    fun load(context: Context): List<Draft> {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val json = preferences.getString(DRAFTS_KEY, "[]") ?: "[]"
        val array = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val result = mutableListOf<Draft>()
        for (index in 0 until array.length()) {
            val wrapper = array.optJSONObject(index) ?: continue
            val order = wrapper.optJSONObject("sdtPedidoAppVenda") ?: continue
            val mobileId = order.optLong("PedidoMobId", 0L)
            val erpId = order.optLong("PedidoERPId", 0L)
            val status = if (erpId > 0L) "ENVIADO AO ERP" else
                order.optString("PedidoStatus", "RASCUNHO")
            result.add(Draft(
                mobileId = mobileId,
                erpId = erpId,
                clientId = order.optString("PedidoEntidadeId"),
                clientName = order.optString("PedidoEntidadeNome").ifBlank {
                    order.optString("PedidoEntidadeNomeFantasia")
                },
                clientReasonName = order.optString("PedidoEntidadeNomeRazao"),
                clientFantasyName = order.optString("PedidoEntidadeNomeFantasia"),
                regionId = order.optString("PedidoEntidadeRegiaoid"),
                createdAt = order.optString("PedidoDtCadastro"),
                total = order.optDouble("PedidoVlrTotal", 0.0),
                status = status,
                json = wrapper
            ))
        }
        return result.sortedWith(compareByDescending<Draft> { it.mobileId })
    }

    fun remove(context: Context, mobileId: Long) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val array = try {
            JSONArray(preferences.getString(DRAFTS_KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val remaining = JSONArray()
        for (index in 0 until array.length()) {
            val wrapper = array.optJSONObject(index) ?: continue
            val order = wrapper.optJSONObject("sdtPedidoAppVenda")
            if (order?.optLong("PedidoMobId", 0L) != mobileId) remaining.put(wrapper)
        }
        preferences.edit().putString(DRAFTS_KEY, remaining.toString()).apply()
    }

    fun markSent(context: Context, mobileId: Long, erpId: Long) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val array = try {
            JSONArray(preferences.getString(DRAFTS_KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            return
        }
        for (index in 0 until array.length()) {
            val wrapper = array.optJSONObject(index) ?: continue
            val order = wrapper.optJSONObject("sdtPedidoAppVenda") ?: continue
            if (order.optLong("PedidoMobId", 0L) != mobileId) continue
            order.put("PedidoERPId", erpId)
            order.put("PedidoStatus", "ENVIADO AO ERP")
        }
        preferences.edit().putString(DRAFTS_KEY, array.toString()).apply()
    }
}
