package com.example.forcavendas

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object OrderSyncService {
    private const val SYNC_URL =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/postPedidoAppVenda"
    private const val TOKEN =
        "dSJQ4qUlSwdNs3T/OipnOo8F0UOVuFHC7TCRjr9DcmEbMpCc1gwhUAYDj3M82WOQbHKRFAwSr5i8lNsBcQyVg=="

    fun sync(pedido: JSONObject): String {
        val endpoint = SYNC_URL +
            "?Parametrostegaceptoken=" +
            URLEncoder.encode(TOKEN, "UTF-8")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject().put(
            "sdtPedidoAppVenda",
            pedido
        ).toString()

        return try {
            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw Exception("Erro HTTP $responseCode${if (response.isBlank()) "" else ": $response"}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    fun wasSynchronized(response: String): Boolean {
        if (response.isBlank()) return false
        val normalized = response.trim()
        return try {
            val root = JSONObject(normalized)
            val isError = root.optBoolean("isError", true)
            !isError
        } catch (_: Exception) {
            false
        }
    }

    fun sendPending(context: Context): Int {
        val drafts = OrderDraftService.load(context)
        var sent = 0

        for (draft in drafts) {
            if (draft.erpId > 0L) continue
            
            try {
                val order = draft.json.optJSONObject("sdtPedidoAppVenda") ?: continue
                val response = sync(order)
                if (wasSynchronized(response)) {
                    val erpId = extractErpId(response)
                    if (erpId > 0L) {
                        OrderDraftService.markSent(context, draft.mobileId, erpId)
                        sent++
                    }
                }
            } catch (_: Exception) {
                // continua tentando com os proximos
            }
        }

        return sent
    }

    private fun extractErpId(response: String): Long {
        return try {
            val root = JSONObject(response.trim())
            root.optLong("PedidoERPId", 0L)
        } catch (_: Exception) {
            0L
        }
    }
}
