package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object BonusSummaryService {
    private const val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpBonificacaoPedidoAppVenda"
    private val TOKEN =
        BuildConfig.ERP_TEST_TOKEN
    private const val PREFERENCES_NAME = "forca_vendas"

    data class Summary(val balance: Double, val toBonify: Double, val bonified: Double)

    private fun cacheKey(representativeId: String, month: String) =
        "bonus_summary_${representativeId}_$month"

    private fun currentMonth(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Calendar.getInstance().time)

    fun loadCached(context: Context, representativeId: String): Summary? {
        val value = context.getSharedPreferences(PREFERENCES_NAME, 0)
            .getString(cacheKey(representativeId, currentMonth()), null) ?: return null
        val parts = value.split(";").mapNotNull { it.toDoubleOrNull() }
        return if (parts.size == 3) Summary(parts[0], parts[1], parts[2]) else null
    }

    // Soma, do mês atual, os registros do representante informado.
    fun load(context: Context, representativeId: String): Summary {
        require(representativeId.isNotBlank())
        val calendar = Calendar.getInstance()
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val start = formatter.format(calendar.time)
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = formatter.format(calendar.time)

        val encodedToken = URLEncoder.encode(TOKEN, Charsets.UTF_8.name())
        val connection = URL("$ENDPOINT?Parametrostegaceptoken=$encodedToken&Iniciodt=$start&Finaldt=$end")
            .openConnection() as HttpURLConnection

        val response = try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Erro HTTP $code")
            body
        } finally {
            connection.disconnect()
        }

        val items = if (response.isBlank()) JSONArray() else JSONArray(response)
        fun number(item: org.json.JSONObject, name: String) =
            item.optString(name).replace(",", ".").toDoubleOrNull() ?: 0.0

        var balance = 0.0
        var toBonify = 0.0
        var bonified = 0.0
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("BonificacaoPedidoRepResentanteId").trim() != representativeId.trim()) continue
            balance += number(item, "BonificacaoPedidoSaldo")
            toBonify += number(item, "BonificacaoPedidoVlrBonificar")
            bonified += number(item, "BonificacaoPedidoVlrBonificacao")
        }

        val summary = Summary(balance, toBonify, bonified)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString(
                cacheKey(representativeId, currentMonth()),
                "${summary.balance};${summary.toBonify};${summary.bonified}"
            )
            .apply()
        return summary
    }
}
