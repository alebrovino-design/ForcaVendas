package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object OrderDraftService {
    private const val PREFERENCES_NAME = "forca_vendas"
    private const val DRAFTS_KEY = "order_drafts_json"
    private val sending = mutableSetOf<Long>()

    enum class RemoveResult { REMOVED, NOT_FOUND, ALREADY_SENT, SENDING }

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

    @Synchronized
    fun removePending(context: Context, mobileId: Long): RemoveResult {
        require(mobileId > 0) { "Identificador móvel do pedido inválido." }
        if (mobileId in sending) return RemoveResult.SENDING

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val array = JSONArray(preferences.getString(DRAFTS_KEY, "[]") ?: "[]")
        val matches = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optJSONObject("sdtPedidoAppVenda")
                ?.takeIf { it.optLong("PedidoMobId", 0L) == mobileId }
        }
        if (matches.isEmpty()) return RemoveResult.NOT_FOUND
        check(matches.size == 1) { "Há mais de um pedido com o mesmo identificador." }
        if (matches.single().optLong("PedidoERPId", 0L) > 0L) return RemoveResult.ALREADY_SENT

        val remaining = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.get(index)
            val order = (item as? JSONObject)?.optJSONObject("sdtPedidoAppVenda")
            if (order?.optLong("PedidoMobId", 0L) != mobileId) remaining.put(item)
        }
        check(preferences.edit().putString(DRAFTS_KEY, remaining.toString()).commit()) {
            "Não foi possível salvar a exclusão do pedido."
        }
        return RemoveResult.REMOVED
    }

    @Synchronized
    fun beginSending(context: Context, mobileId: Long): Boolean {
        if (mobileId <= 0L || mobileId in sending) return false
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val array = JSONArray(preferences.getString(DRAFTS_KEY, "[]") ?: "[]")
        val matches = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optJSONObject("sdtPedidoAppVenda")
                ?.takeIf { it.optLong("PedidoMobId", 0L) == mobileId }
        }
        check(matches.size <= 1) { "Há mais de um pedido com o mesmo identificador." }
        if (matches.isEmpty() || matches.single().optLong("PedidoERPId", 0L) > 0L) return false
        sending.add(mobileId)
        return true
    }

    @Synchronized
    fun endSending(mobileId: Long) {
        sending.remove(mobileId)
    }

    @Synchronized
    fun markSent(context: Context, mobileId: Long, erpId: Long) {
        require(erpId > 0L) { "Identificador ERP do pedido inválido." }
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val array = JSONArray(preferences.getString(DRAFTS_KEY, "[]") ?: "[]")
        var matches = 0
        for (index in 0 until array.length()) {
            val wrapper = array.optJSONObject(index) ?: continue
            val order = wrapper.optJSONObject("sdtPedidoAppVenda") ?: continue
            if (order.optLong("PedidoMobId", 0L) != mobileId) continue
            matches++
            order.put("PedidoERPId", erpId)
            order.put("PedidoStatus", "ENVIADO AO ERP")
        }
        check(matches == 1) { "Não foi possível identificar o pedido enviado." }
        check(preferences.edit().putString(DRAFTS_KEY, array.toString()).commit()) {
            "Não foi possível salvar a confirmação do ERP."
        }
    }
}
