package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TargetService {
    private const val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpmetaporrepreappvenda"
    private const val TOKEN =
        "dSJQ4qUlSwdNs3T/OipnOo8F0UOVuFHC7TCRjr9DcmEbMpCc1gwhUAYDj3M82WOQbHKRFAwSr5i8lNsBcQyVg=="

    data class TargetData(
        val target: Double,
        val achieved: Double
    )

    fun save(context: Context, representativeId: String, month: String, data: TargetData) {
        context.getSharedPreferences("forca_vendas", 0).edit()
            .putString("target_representative_id", representativeId)
            .putString("target_month", month)
            .putString("target_weight", data.target.toString())
            .putString("target_achieved", data.achieved.toString())
            .putLong("target_synced_at", System.currentTimeMillis())
            .apply()
    }

    fun loadCached(context: Context, representativeId: String, month: String): TargetData? {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val cachedRepresentative = preferences.getString("target_representative_id", null)
        val cachedMonth = preferences.getString("target_month", null)
        if (cachedRepresentative != representativeId || cachedMonth != month) return null

        val target = preferences.getString("target_weight", null)?.toDoubleOrNull()
        val achieved = preferences.getString("target_achieved", null)?.toDoubleOrNull()
        return if (target != null && achieved != null) TargetData(target, achieved) else null
    }

    fun load(representativeId: String, month: String): TargetData {
        require(representativeId.isNotBlank())

        val encodedToken = URLEncoder.encode(TOKEN, Charsets.UTF_8.name())
        val encodedRepresentative = URLEncoder.encode(representativeId, Charsets.UTF_8.name())
        val encodedMonth = URLEncoder.encode(month, Charsets.UTF_8.name())
        val endpoint = "$ENDPOINT?Representanteid=$encodedRepresentative" +
            "&Parametrostegaceptoken=$encodedToken" +
            "&Metarepresentanteanomes=$encodedMonth"

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IllegalStateException("Erro HTTP $responseCode")
            }

            parse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(response: String): TargetData {
        if (response.isBlank()) return TargetData(0.0, 0.0)

        val root = when {
            response.trimStart().startsWith("[") -> JSONArray(response)
            else -> JSONObject(response)
        }

        val firstObject = when (root) {
            is JSONArray -> if (root.length() == 0) null else root.optJSONObject(0)
            is JSONObject -> root
            else -> null
        } ?: return TargetData(0.0, 0.0)

        val target = firstNumeric(
            firstObject,
            listOf(
                "MetaRepresentantePeso",
                "MetaRepresentanteValorMeta",
                "MetarepresentanteValormeta",
                "MetaValor",
                "ValorMeta",
                "MetaRepresentanteValor",
                "Meta"
            )
        )
        val achieved = firstNumeric(
            firstObject,
            listOf(
                "MetaRepresentanteValorRealizado",
                "MetarepresentanteValorRealizado",
                "MetaValorRealizado",
                "ValorRealizado",
                "Vendido",
                "ValorVendido",
                "Realizado",
                "MetaRepresentanteVlrAtingido",
                "Atingido"
            )
        )
        return TargetData(target, achieved)
    }

    private fun firstNumeric(json: JSONObject, names: List<String>): Double {
        names.forEach { name ->
            if (json.has(name)) {
                val value = json.optDouble(name, Double.NaN)
                if (!value.isNaN()) return value

                val text = json.optString(name).replace(",", ".")
                val parsed = text.toDoubleOrNull()
                if (parsed != null) return parsed
            }
        }

        val keys = json.keys().asSequence().toList()
        names.forEach { name ->
            val normalizedName = normalize(name)
            val matchingKey = keys.firstOrNull { key ->
                val normalizedKey = normalize(key)
                normalizedKey.contains(normalizedName) || normalizedName.contains(normalizedKey)
            } ?: return@forEach

            val value = json.optDouble(matchingKey, Double.NaN)
            if (!value.isNaN()) return value

            val text = json.optString(matchingKey).replace(",", ".")
            val parsed = text.toDoubleOrNull()
            if (parsed != null) return parsed
        }
        return 0.0
    }

    private fun normalize(value: String): String =
        value.filter(Char::isLetterOrDigit).lowercase()
}
