package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object PriceTableItemService {
    private val TOKEN =
        BuildConfig.ERP_TEST_TOKEN
    private const val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpItemTabelaPrecoRepAPPVenda"

    fun sync(context: Context): Int {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val userId = preferences.getString("user_id", "").orEmpty()
        if (userId.isBlank()) throw Exception("Código do representante não disponível")
        val endpoint = "$ENDPOINT?Parametrostegaceptoken=" +
            URLEncoder.encode(TOKEN, "UTF-8") +
            "&Usuarioid=${URLEncoder.encode(userId, "UTF-8")}"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

            val items = try {
                JSONObject(response).optJSONArray("value") ?: JSONArray()
            } catch (_: Exception) {
                JSONArray(response)
            }
            preferences.edit()
                .putString("price_table_items_synced_json", items.toString())
                .putInt("price_table_items_synced_count", items.length())
                .putLong("price_table_items_synced_at", System.currentTimeMillis())
                .apply()
            items.length()
        } finally {
            connection.disconnect()
        }
    }

    fun load(context: Context): JSONArray = try {
        JSONArray(
            context.getSharedPreferences("forca_vendas", 0)
                .getString("price_table_items_synced_json", "[]") ?: "[]"
        )
    } catch (_: Exception) {
        JSONArray()
    }

    fun findByProduct(context: Context, productCode: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val items = load(context)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("ItemtabelaProdutoCodigo") == productCode) result.add(item)
        }
        return result
    }
}
