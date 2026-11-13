package com.example.forcavendas

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object ClientCreateService {
    private const val CREATE_URL =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/postClienteAppVenda"
    private const val TOKEN =
        "dSJQ4qUlSwdNs3T/OipnOo8F0UOVuFHC7TCRjr9DcmEbMpCc1gwhUAYDj3M82WOQbHKRFAwSr5i8lNsBcQyVg=="

    fun create(client: JSONObject): String {
        val endpoint = CREATE_URL +
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
            "sdtClienteAppVenda",
            client
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

    fun wasRegistered(response: String): Boolean {
        if (response.isBlank()) return false
        val normalized = response.trim()
        return try {
            val root = JSONObject(normalized)
            evaluateRegistrationResult(root)
        } catch (_: Exception) {
            try {
                val array = JSONArray(normalized)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    if (item != null && evaluateRegistrationResult(item)) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun evaluateRegistrationResult(result: JSONObject): Boolean {
        val isError = result.optBoolean("isError", true)
        if (!isError) return true
        val message = result.optString("outmessage", "")
        val normalized = message.lowercase()
            .replace("á", "a")
            .replace("ã", "a")
        return normalized.contains("cnpj") && normalized.contains("ja cadastrad")
    }

    fun savePending(context: Context, client: JSONObject, reason: String = "Erro não informado") {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val pending = try {
            JSONArray(preferences.getString("pending_clients_json", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        pending.put(JSONObject()
            .put("client", client)
            .put("reason", reason)
            .put("created_at", System.currentTimeMillis()))
        preferences.edit()
            .putString("pending_clients_json", pending.toString())
            .apply()
    }

    fun pendingCount(context: Context): Int = try {
        JSONArray(
            context.getSharedPreferences("forca_vendas", 0)
                .getString("pending_clients_json", "[]") ?: "[]"
        ).length()
    } catch (_: Exception) {
        0
    }

    fun sendPending(context: Context): Int {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val pending = try {
            JSONArray(preferences.getString("pending_clients_json", "[]") ?: "[]")
        } catch (_: Exception) {
            return 0
        }
        val remaining = JSONArray()
        var sent = 0

        for (index in 0 until pending.length()) {
            val entry = pending.optJSONObject(index) ?: continue
            val client = entry.optJSONObject("client")
                ?: entry.takeIf { it.has("EntidadeCPFCNPJ") }
                ?: continue
            try {
                val response = create(client)
                if (wasRegistered(response)) {
                    sent++
                } else {
                    val reason = try {
                        val result = JSONObject(response.trim())
                        result.optString("outmessage", "ERP não confirmou o cadastro")
                    } catch (_: Exception) {
                        "ERP não confirmou o cadastro: ${response.take(300)}"
                    }
                    remaining.put(entryWithReason(entry, client, reason))
                }
            } catch (exception: Exception) {
                remaining.put(entryWithReason(
                    entry,
                    client,
                    exception.message?.ifBlank { "Erro ao enviar o cliente" }
                        ?: "Erro ao enviar o cliente"
                ))
            }
        }

        preferences.edit()
            .putString("pending_clients_json", remaining.toString())
            .apply()
        return sent
    }

    private fun entryWithReason(entry: JSONObject, client: JSONObject, reason: String): JSONObject =
        JSONObject()
            .put("client", client)
            .put("reason", reason)
            .put("created_at", entry.optLong("created_at", System.currentTimeMillis()))

    fun removePending(context: Context, index: Int) {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val pending = try {
            JSONArray(preferences.getString("pending_clients_json", "[]") ?: "[]")
        } catch (_: Exception) {
            return
        }
        val remaining = JSONArray()
        for (itemIndex in 0 until pending.length()) {
            if (itemIndex != index) remaining.put(pending.opt(itemIndex))
        }
        preferences.edit().putString("pending_clients_json", remaining.toString()).apply()
    }

    fun updatePending(context: Context, index: Int, client: JSONObject) {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val pending = try {
            JSONArray(preferences.getString("pending_clients_json", "[]") ?: "[]")
        } catch (_: Exception) {
            return
        }
        val entry = pending.optJSONObject(index)
        val clientEntry = if (entry != null && entry.has("client")) entry else JSONObject()
            .put("created_at", System.currentTimeMillis())
        clientEntry.put("client", client)
        clientEntry.put("reason", "Cadastro alterado pelo usuário; aguardando novo envio")
        pending.put(index, clientEntry)
        preferences.edit().putString("pending_clients_json", pending.toString()).apply()
    }
}
