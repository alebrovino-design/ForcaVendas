package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object TaxGroupService {
    private const val TOKEN =
        "dSJQ4qUlSwdNs3T/OipnOo8F0UOVuFHC7TCRjr9DcmEbMpCc1gwhUAYDj3M82WOQbHKRFAwSr5i8lNsBcQyVg=="
    private const val ENDPOINT =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpICMSGrupoTributarioAppVendas"

    fun sync(context: Context): Int {
        val endpoint = "$ENDPOINT?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
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

            val groups = try {
                extractArray(JSONObject(response))
            } catch (_: Exception) {
                JSONArray(response)
            }

            context.getSharedPreferences("forca_vendas", 0).edit()
                .putString("tax_groups_synced_json", groups.toString())
                .putInt("tax_groups_synced_count", groups.length())
                .putLong("tax_groups_synced_at", System.currentTimeMillis())
                .apply()

            groups.length()
        } finally {
            connection.disconnect()
        }
    }

    fun load(context: Context): JSONArray = try {
        JSONArray(
            context.getSharedPreferences("forca_vendas", 0)
                .getString("tax_groups_synced_json", "[]") ?: "[]"
        )
    } catch (_: Exception) {
        JSONArray()
    }

    data class TaxRates(
        val perIcmsProduct: String,
        val perReduction: String,
        val mva: String,
        val perIcmsState: String,
        val perFcp: String
    )

    fun find(
        context: Context,
        companyId: String,
        taxGroupId: String,
        consumerFinal: String,
        outgoingIcmsType: String,
        destinationUf: String,
        originUf: String,
        activityOperationId: String
    ): TaxRates? {
        val groups = load(context)
        val branchId = activityOperationId.trim().ifBlank { "0" }

        fun matches(item: JSONObject): Boolean {
            if (normalizeNumber(item.optString("EmpresaId")) != normalizeNumber(companyId)) return false
            if (
                normalizeNumber(item.optString("GrupoTributarioId")) !=
                normalizeNumber(taxGroupId)
            ) return false

            val itemConsumer = normalizeBoolean(item.optString("ICMSGrupoTributarioConsumidorFinal"))
            val requestedConsumer = normalizeBoolean(consumerFinal)
            if (itemConsumer != requestedConsumer) return false

            if (
                item.optString("ICMSGrupoTributarioTipoICMSSaida").trim().uppercase() !=
                outgoingIcmsType.trim().uppercase()
            ) return false
            if (normalizeNumber(item.optString("ICMSGrupoTributarioSaida")) != "1") return false
            if (item.optString("UFDestinoUFSigla").uppercase().trim() != destinationUf.uppercase().trim()) return false
            if (item.optString("UFOrigemUFSigla").uppercase().trim() != originUf.uppercase().trim()) return false
            return true
        }

        fun findByBranch(candidateBranch: String): JSONObject? {
            for (index in 0 until groups.length()) {
                val item = groups.optJSONObject(index) ?: continue
                val itemBranch = normalizeNumber(
                    item.optString("stRamoAtividadeId").trim().ifBlank { "0" }
                )
                if (matches(item) && itemBranch == candidateBranch) return item
            }
            return null
        }

        val normalizedBranch = normalizeNumber(branchId)
        val selected = findByBranch(normalizedBranch) ?: findByBranch("0")
        return selected?.let { item ->
            TaxRates(
                valueOrZero(item, "ICMSGrupoTributarioPerICMSProduto"),
                valueOrZero(item, "ICMSGrupoTributarioPerReducao"),
                valueOrZero(item, "ICMSGrupoTributarioMVA"),
                valueOrZero(item, "ICMSGrupoTributarioPerICMS"),
                valueOrZero(item, "ICMSGrupoTributarioPerFCP")
            )
        }
    }

    private fun normalizeBoolean(value: String): String = when (value.trim().uppercase()) {
        "1", "TRUE", "S", "SIM" -> "1"
        else -> "0"
    }

    private fun normalizeNumber(value: String): String {
        val normalized = value.trim().replace(",", ".")
        if (normalized.isBlank() || normalized == "null") return "0"
        return normalized.toDoubleOrNull()?.toLong()?.toString()
            ?: normalized
    }

    private fun valueOrZero(item: JSONObject, key: String): String =
        item.opt(key)?.let { value ->
            if (value == JSONObject.NULL) "0"
            else value.toString().trim().replace(",", ".").ifBlank { "0" }
        } ?: "0"

    private fun extractArray(root: JSONObject): JSONArray {
        root.optJSONArray("value")?.let { return it }
        root.optJSONArray("results")?.let { return it }
        root.optJSONObject("d")?.let { envelope ->
            envelope.optJSONArray("results")?.let { return it }
            envelope.optJSONArray("value")?.let { return it }
        }
        root.optJSONObject("data")?.let { envelope ->
            envelope.optJSONArray("results")?.let { return it }
            envelope.optJSONArray("value")?.let { return it }
        }
        return JSONArray()
    }
}
