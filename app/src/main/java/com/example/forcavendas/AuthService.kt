package com.example.forcavendas

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AuthService {
    private const val AUTH_URL =
        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/pprcautenciarusuariorest"

    data class AuthResult(
        val success: Boolean,
        val message: String,
        val userId: String? = null
    )

    fun authenticate(username: String, password: String): AuthResult {
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(AUTH_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject()
                .put("UsuarioLogin", username)
                .put("Senha", password)
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                AuthResult(false, "Erro HTTP $responseCode")
            } else {
                val json = JSONObject(response)
                val success = json.optBoolean("Resultado", false)
                val message = json.optString("Mensagem", "")
                val userId = json.optString("UsuarioId", "").ifBlank { null }
                AuthResult(success, message, userId)
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            AuthResult(false, "Resposta inválida da API")
        } finally {
            connection?.disconnect()
        }
    }
}
