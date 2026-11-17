package com.example.forcavendas

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL

object OrderSyncService {
    private const val SYNC_URL =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/postPedidoAppVenda"
    private const val TOKEN =
        "dSJQ4qUlSwdNs3T/OipnOo8F0UOVuFHC7TCRjr9DcmEbMpCc1gwhUAYDj3M82WOQbHKRFAwSr5i8lNsBcQyVg=="

    data class SyncFailure(
        val mobileId: Long,
        val message: String,
        val requestJson: String? = null
    )

    data class SendResult(val sentCount: Int, val failures: List<SyncFailure>)

    fun sync(pedido: JSONObject): String {
        return postRequest(buildRequestJson(pedido))
    }

    private fun buildRequestJson(pedido: JSONObject): String {
        return JSONObject()
            .put("sdtPedidoAppVenda", prepareOrderForErp(pedido))
            .toString()
    }

    private fun postRequest(body: String): String {
        val endpoint = SYNC_URL +
            "?Parametrostegaceptoken=" +
            URLEncoder.encode(TOKEN, "UTF-8")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

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
        return sendPending(context, OrderDraftService.load(context)).sentCount
    }

    fun sendPending(context: Context, drafts: List<OrderDraftService.Draft>): SendResult {
        var sent = 0
        val failures = mutableListOf<SyncFailure>()

        for (draft in drafts) {
            if (draft.erpId > 0L) continue

            if (draft.mobileId <= 0L) {
                failures.add(SyncFailure(draft.mobileId, "Identificador móvel do pedido inválido."))
                continue
            }
            val order = draft.json.optJSONObject("sdtPedidoAppVenda")
            if (order == null) {
                failures.add(SyncFailure(draft.mobileId, "O pedido não contém os dados para envio."))
                continue
            }

            val requestJson = try {
                buildRequestJson(order)
            } catch (exception: Exception) {
                failures.add(
                    SyncFailure(
                        draft.mobileId,
                        exception.message?.take(300).orEmpty().ifBlank {
                            "Não foi possível gerar o JSON do pedido."
                        }
                    )
                )
                continue
            }

            val response = try {
                postRequest(requestJson)
            } catch (exception: Exception) {
                failures.add(
                    SyncFailure(
                        draft.mobileId,
                        timeoutMessage(exception) ?: exception.message?.take(300).orEmpty()
                            .ifBlank { "Falha ao enviar ao ERP." },
                        requestJson
                    )
                )
                continue
            }

            if (!wasSynchronized(response)) {
                failures.add(SyncFailure(draft.mobileId, responseMessage(response), requestJson))
                continue
            }

            val erpId = extractErpId(response)
            if (erpId <= 0L) {
                failures.add(
                    SyncFailure(
                        draft.mobileId,
                        "O ERP respondeu sem um PedidoERPId válido. Verifique no ERP antes de reenviar para evitar duplicidade.",
                        requestJson
                    )
                )
                continue
            }

            OrderDraftService.markSent(context, draft.mobileId, erpId)
            sent++
        }

        return SendResult(sent, failures)
    }

    private fun extractErpId(response: String): Long {
        return try {
            val root = JSONObject(response.trim())
            findErpId(root).takeIf { it > 0L } ?: positiveLong(root.opt("PedidoId"))
        } catch (_: org.json.JSONException) {
            0L
        }
    }

    private fun positiveLong(value: Any?): Long {
        val parsed = when (value) {
            is Number -> value.toString().toLongOrNull()
            is String -> value.trim().toLongOrNull()
            else -> null
        } ?: return 0L
        return parsed.takeIf { it > 0L } ?: 0L
    }

    private fun timeoutMessage(exception: Throwable): String? {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is SocketTimeoutException ||
                cause.message?.contains("timeout", ignoreCase = true) == true
            ) {
                return "Tempo limite esgotado. O ERP pode ter integrado o pedido sem conseguir retornar a confirmação; verifique no ERP antes de reenviar."
            }
            cause = cause.cause
        }
        return null
    }

    private fun findErpId(value: Any?): Long {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val nested = value.opt(key)
                    if (key.filter(Char::isLetterOrDigit).equals("PedidoERPId", ignoreCase = true)) {
                        val id = when (nested) {
                            is Number -> nested.toString().toLongOrNull()
                            is String -> nested.trim().toLongOrNull()
                            else -> null
                        }
                        if (id != null && id > 0L) return id
                    }
                    val nestedId = findErpId(nested)
                    if (nestedId > 0L) return nestedId
                }
                0L
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val id = findErpId(value.opt(index))
                    if (id > 0L) return id
                }
                0L
            }
            else -> 0L
        }
    }

    private fun responseMessage(response: String): String {
        return try {
            val root = JSONObject(response.trim())
            listOf("outmsg", "outmessage", "message", "error", "description")
                .firstNotNullOfOrNull { key ->
                    root.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
                }
                ?: "O ERP não confirmou a inclusão do pedido."
        } catch (_: org.json.JSONException) {
            response.trim().take(300).ifBlank { "O ERP não confirmou a inclusão do pedido." }
        }
    }

    private fun prepareOrderForErp(order: JSONObject): JSONObject {
        val outgoingOrder = JSONObject(order.toString())
        outgoingOrder.remove("PedidoEmpresaNome")
        outgoingOrder.remove("PedidoParcelas")

        val items = outgoingOrder.optJSONArray("ItemPedido") ?: return outgoingOrder
        val decimalFields = listOf(
            "ItemPedidoPerICMSProduto",
            "ItemPedidoPerReducaoICMS",
            "ItemPedidoPerMVA",
            "ItemPedidoPerICMSEstado"
        )
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            item.remove("ItemPedidoTipoIPI")
            item.remove("ItemPedidoPerFCP")
            for (field in decimalFields) {
                item.put(field, decimalValue(item.opt(field), field))
            }
            item.put(
                "ItemPedidoTipoOperacaoPedId",
                integerValue(item.opt("ItemPedidoTipoOperacaoPedId"), "ItemPedidoTipoOperacaoPedId")
            )
        }
        return outgoingOrder
    }

    private fun decimalValue(value: Any?, field: String): BigDecimal {
        val raw = value.asNumericText()
        if (raw.isBlank()) return BigDecimal.ZERO
        return raw.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$field deve conter um número.")
    }

    private fun integerValue(value: Any?, field: String): Long {
        val raw = value.asNumericText()
        if (raw.isBlank()) return 0L
        return raw.toLongOrNull()
            ?: throw IllegalArgumentException("$field deve conter um número inteiro.")
    }

    private fun Any?.asNumericText(): String = when {
        this == null || this === JSONObject.NULL -> ""
        this is Number || this is String -> toString().trim().replace(',', '.')
        else -> throw IllegalArgumentException("Valor numérico inválido.")
    }
}
