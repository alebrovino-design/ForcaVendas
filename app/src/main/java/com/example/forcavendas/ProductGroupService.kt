package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object ProductGroupService {
    private val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpGrupoProdutoAppVenda" +
            "?Parametrostegaceptoken=${URLEncoder.encode(BuildConfig.ERP_TEST_TOKEN, "UTF-8")}"

    fun sync(context: Context): Int {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val responseCode = connection.responseCode
            val response = connection.getResponseStream(responseCode)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

            val groups = try {
                JSONObject(response).optJSONArray("value") ?: JSONArray()
            } catch (_: Exception) {
                JSONArray(response)
            }
            val normalized = JSONArray()
            for (index in 0 until groups.length()) {
                val source = groups.optJSONObject(index) ?: continue
                normalized.put(JSONObject()
                    .put("GrupoProdutoId", source.optString("GrupoProdutoId"))
                    .put("GrupoProdutoDescricao", source.optString("GrupoProdutoDescricao"))
                    .put("GrupoProdutoSigla", source.optString("GrupoProdutoSigla")))
            }
            context.getSharedPreferences("forca_vendas", 0).edit()
                .putString("product_groups_synced_json", normalized.toString())
                .putInt("product_groups_synced_count", normalized.length())
                .putLong("product_groups_synced_at", System.currentTimeMillis())
                .apply()
            normalized.length()
        } finally {
            connection.disconnect()
        }
    }

    fun load(context: Context): JSONArray = try {
        JSONArray(
            context.getSharedPreferences("forca_vendas", 0)
                .getString("product_groups_synced_json", "[]") ?: "[]"
        )
    } catch (_: Exception) {
        JSONArray()
    }

    private fun HttpURLConnection.getResponseStream(responseCode: Int) =
        if (responseCode in 200..299) inputStream else errorStream
}
