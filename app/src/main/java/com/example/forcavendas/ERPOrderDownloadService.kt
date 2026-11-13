package com.example.forcavendas

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ERPOrderDownloadService {
    private const val TAG = "ERPOrderDownload"
    private const val BASE_URL = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda"

    data class Result(
        val success: Boolean,
        val message: String,
        val ordersCount: Int = 0,
        val error: String? = null
    )

    suspend fun downloadOrders(
        context: Context,
        representanteId: String = "",
        erpToken: String = ""
    ): Result = withContext(Dispatchers.IO) {
        try {
            val repId = if (representanteId.isNotEmpty()) representanteId 
                else SessionManager.currentUserId(context)
            
            if (repId.isEmpty()) {
                Log.e(TAG, "ID do representante vazio")
                return@withContext Result(
                    success = false,
                    message = "ID do representante não disponível",
                    error = "Usuário não autenticado"
                )
            }

            val token = if (erpToken.isNotEmpty()) {
                erpToken
            } else {
                ERPConfig.getERPToken(context)
            }
            
            if (!ERPConfig.isValidToken(token)) {
                Log.e(TAG, "Token ERP inválido ou vazio")
                return@withContext Result(
                    success = false,
                    message = "Token ERP não configurado",
                    error = "Autenticação ERP incompleta"
                )
            }

            // Construir URL com parâmetros URL-encoded
            val urlString = "$BASE_URL?Parametrostegaceptoken=$token&Representanteid=$repId"
            Log.d(TAG, "Iniciando download para representante: $repId")
            Log.d(TAG, "Conectando a: $BASE_URL")
            
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "SpecialNutriScanner/1.0")
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP Response Code: $responseCode")
            
            val inputStream = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            
            val response = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.d(TAG, "Response length: ${response.length} bytes")

            return@withContext when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    Log.d(TAG, "HTTP 200 OK - Processando resposta")
                    val orders = extractOrders(response)
                    
                    if (orders.length() == 0) {
                        Log.w(TAG, "Resposta OK mas nenhum pedido encontrado")
                        Result(
                            success = true,
                            message = "Nenhum pedido encontrado no ERP",
                            ordersCount = 0
                        )
                    } else {
                        Log.d(TAG, "Encontrados ${orders.length()} pedidos")
                        val repository = ERPOrderLocalRepository(context)
                        repository.saveOrders(repId, orders)
                        Log.d(TAG, "Pedidos salvos no banco de dados")
                        
                        Result(
                            success = true,
                            message = "${orders.length()} pedidos sincronizados com sucesso",
                            ordersCount = orders.length()
                        )
                    }
                }
                401 -> {
                    Log.e(TAG, "HTTP 401 - Autenticação falhou. Verifique token e representante ID")
                    Result(
                        success = false,
                        message = "Falha de autenticação",
                        error = "Token ERP inválido ou representante ID incorreto (HTTP 401)"
                    )
                }
                403 -> {
                    Log.e(TAG, "HTTP 403 - Acesso proibido")
                    Result(
                        success = false,
                        message = "Acesso recusado pelo servidor",
                        error = "Você não tem permissão para acessar este recurso (HTTP 403)"
                    )
                }
                404 -> {
                    Log.e(TAG, "HTTP 404 - Endpoint não encontrado")
                    Result(
                        success = false,
                        message = "Endpoint ERP não encontrado",
                        error = "O servidor não encontrou o recurso (HTTP 404)"
                    )
                }
                500 -> {
                    Log.e(TAG, "HTTP 500 - Erro interno do servidor")
                    Result(
                        success = false,
                        message = "Erro interno do servidor ERP",
                        error = "Servidor ERP retornou erro 500. Tente novamente mais tarde"
                    )
                }
                else -> {
                    Log.e(TAG, "HTTP $responseCode - Erro desconhecido")
                    Result(
                        success = false,
                        message = "Erro na comunicação com ERP",
                        error = "HTTP $responseCode - ${getHttpErrorMessage(responseCode)}"
                    )
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout na conexão com ERP", e)
            Result(
                success = false,
                message = "Servidor ERP não respondeu",
                error = "Conexão expirou (timeout). Verifique sua conexão com internet"
            )
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Host não encontrado", e)
            Result(
                success = false,
                message = "Servidor ERP indisponível",
                error = "Não foi possível conectar ao servidor ERP"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante download: ${e.javaClass.simpleName}", e)
            Result(
                success = false,
                message = "Erro ao baixar pedidos do ERP",
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
    
    private fun getHttpErrorMessage(code: Int): String {
        return when (code) {
            400 -> "Requisição inválida"
            401 -> "Não autorizado"
            403 -> "Acesso proibido"
            404 -> "Não encontrado"
            500 -> "Erro interno do servidor"
            502 -> "Gateway inválido"
            503 -> "Serviço indisponível"
            504 -> "Gateway timeout"
            else -> "Erro HTTP $code"
        }
    }

    private fun extractOrders(response: String): JSONArray {
        return try {
            val trimmed = response.trim()
            
            // Se já é um array, retorna direto
            if (trimmed.startsWith("[")) return JSONArray(trimmed)

            // Tenta extrair de propriedades comuns
            val root = JSONObject(trimmed)
            listOf("value", "results", "data", "d").forEach { key ->
                val obj = root.opt(key)
                when (obj) {
                    is JSONArray -> return obj
                    is JSONObject -> {
                        val arr = obj.optJSONArray("value") ?: obj.optJSONArray("results") 
                        if (arr != null) return arr
                    }
                }
            }

            // Se não encontrou em estrutura comum, retorna o array vazio
            JSONArray()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair pedidos da resposta", e)
            JSONArray()
        }
    }

    fun isErpOrder(draft: OrderDraftService.Draft): Boolean {
        val order = draft.json.optJSONObject("sdtPedidoAppVenda") ?: return false
        val erpId = order.optLong("PedidoERPId", 0L)
        return erpId > 0L
    }
}
