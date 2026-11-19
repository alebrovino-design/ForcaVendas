package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object BonusConfigurationService {
    private const val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpConfigBonificacaoAppVenda"
    private val TOKEN =
        BuildConfig.ERP_TEST_TOKEN
    private const val PREFERENCES_NAME = "forca_vendas"

    fun sync(context: Context): Int {
        val encodedToken = URLEncoder.encode(TOKEN, Charsets.UTF_8.name())
        val connection = URL("$ENDPOINT?Parametrostegaceptoken=$encodedToken")
            .openConnection() as HttpURLConnection

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

            val configurations = JSONArray(response)
            context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
                .putString("bonus_configuration_synced_json", response)
                .putInt("bonus_configuration_synced_count", configurations.length())
                .putLong("bonus_configuration_synced_at", System.currentTimeMillis())
                .apply()
            configurations.length()
        } finally {
            connection.disconnect()
        }
    }

    fun loadCached(context: Context): JSONArray? {
        val response = context.getSharedPreferences(PREFERENCES_NAME, 0)
            .getString("bonus_configuration_synced_json", null)
            ?: return null
        return try {
            JSONArray(response)
        } catch (_: Exception) {
            null
        }
    }

    fun getBonusLimit(
        context: Context,
        regionId: String,
        productGroupId: String,
        soldQuantity: Double
    ): BonusLimit? {
        val configurations = loadCached(context) ?: return null
        var selected: org.json.JSONObject? = null
        var selectedThreshold = -1.0

        for (index in 0 until configurations.length()) {
            val item = configurations.optJSONObject(index) ?: continue
            if (item.optString("ConfigBonificacaoRegiaoERPId") != regionId) continue
            if (item.optString("ConfigBonificacaoGrupoProdutoId") != productGroupId) continue
            val threshold = item.optString("ConfigBonificacaoQtdeVendida")
                .replace(",", ".").toDoubleOrNull() ?: continue
            if (threshold <= soldQuantity && threshold > selectedThreshold) {
                selected = item
                selectedThreshold = threshold
            }
        }

        val config = selected ?: return null
        val bonusPercent = config.optString("ConfigBonificacaoPercBonificacao")
            .replace(",", ".").toDoubleOrNull() ?: 0.0
        val extraPercent = config.optString("ConfigBonificacaoPermitirMais")
            .replace(",", ".").toDoubleOrNull() ?: 0.0
        return BonusLimit(
            threshold = selectedThreshold,
            bonusPercent = bonusPercent,
            extraPercent = extraPercent,
            allowedBonus = calculateAllowedBonus(soldQuantity, bonusPercent, extraPercent)
        )
    }

    internal fun calculateAllowedBonus(
        soldQuantity: Double,
        bonusPercent: Double,
        extraPercent: Double
    ): Double = soldQuantity * (bonusPercent + extraPercent) / 100.0

    internal fun calculateBonifiableAmount(
        soldQuantity: Double,
        soldAmount: Double,
        bonusQuantity: Double,
        bonusPercent: Double
    ): Double {
        if (soldQuantity <= 0.0) return 0.0
        val baseBonus = soldQuantity * bonusPercent / 100.0
        return (baseBonus - bonusQuantity) * (soldAmount / soldQuantity)
    }

    data class BonusLimit(
        val threshold: Double,
        val bonusPercent: Double,
        val extraPercent: Double,
        val allowedBonus: Double
    )

}
