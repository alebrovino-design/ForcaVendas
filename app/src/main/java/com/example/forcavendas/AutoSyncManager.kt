package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object AutoSyncManager {
    private const val PREFERENCES_NAME = "forca_vendas"
    private const val LAST_DAY_KEY = "auto_sync_last_day"
    private val TOKEN =
        BuildConfig.ERP_TEST_TOKEN

    fun syncOncePerDay(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (preferences.getString(LAST_DAY_KEY, "") == today) return

        val userId = preferences.getString("user_id", "") ?: ""
        if (userId.isBlank()) return

        preferences.edit().putString(LAST_DAY_KEY, today).apply()

        thread {
            runCatching { syncTarget(context, userId) }
            runCatching { syncClients(context, userId) }
            runCatching { syncFinancial(context, userId) }
            runCatching { syncProducts(context) }
            runCatching { ProductGroupService.sync(context.applicationContext) }
            runCatching { PriceTableItemService.sync(context.applicationContext) }
            runCatching { BonusConfigurationService.sync(context) }
            runCatching { TaxGroupService.sync(context.applicationContext) }
            runCatching { syncLoads(context, userId) }
            runCatching { syncDeliveries(context, userId) }
            runCatching { syncCompanies(context) }
            runCatching { syncOrderOperations(context) }
            runCatching { syncPaymentConditions(context) }
        }
    }

    private fun syncTarget(context: Context, userId: String) {
        val month = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
        val targetData = TargetService.load(userId, month)
        TargetService.save(context, userId, month, targetData)
    }

    private fun syncClients(context: Context, userId: String) {
        val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntidadeRepAPPVenda" +
            "?Usuarioid=${URLEncoder.encode(userId, "UTF-8")}" +
            "&Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        val clients = JSONArray(response)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("clients_synced_json", response)
            .putInt("clients_synced_count", clients.length())
            .putLong("clients_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncFinancial(context: Context, userId: String) {
        val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpMovFinanceiroRepAPPVenda" +
            "?Usuarioid=${URLEncoder.encode(userId, "UTF-8")}" +
            "&Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        val movements = JSONArray(response)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("financial_synced_json", response)
            .putInt("financial_synced_count", movements.length())
            .putLong("financial_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncProducts(context: Context) {
        val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpProdutoAPPVenda" +
            "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        val json = JSONArray(response)
        val products = mutableListOf<ProductLocalRepository.ProductRecord>()
        for (index in 0 until json.length()) {
            val item = json.getJSONObject(index)
            products.add(
                ProductLocalRepository.ProductRecord(
                    code = item.optString("ProdutoCodigo"),
                    erpId = item.optString("ProdutoId").ifBlank { item.optString("ProdutoERPId") },
                    description = item.optString("ProdutoDescricao"),
                    netWeight = item.optString("ProdutoPesoLiquido"),
                    grossWeight = item.optString("ProdutoPesoBruto"),
                    photoUrl = item.optString("ProdutocaminhoFoto"),
                    productGroupId = item.optString("ProdutoGrupoProdutoId"),
                    taxGroupId = item.optString("ProdutoGrupoTributarioId"),
                    salesBrandId = item.optString("ProdutoMarcaVendaId"),
                    familyCommissionPercent = item.optString("ProdutoPercComissaoFamilia"),
                    ipiPercent = item.optString("ProdutoPercIPI")
                )
            )
        }
        ProductLocalRepository(context).replaceAll(products)
        ProductPhotoCacheService.prefetch(context.applicationContext, products.map { it.photoUrl })
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putLong("products_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncLoads(context: Context, userId: String) {
        val endpoint =
            "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCargaRepresentanteAppVenda" +
                "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}" +
                "&Usuarioid=${URLEncoder.encode(userId, "UTF-8")}"
        val response = downloadJson(endpoint)
        val loads = JSONArray(response)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("loads_synced_json", response)
            .putInt("loads_synced_count", loads.length())
            .putLong("loads_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncDeliveries(context: Context, userId: String) {
        val endpoint =
            "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntregaCargaRepAPPVenda" +
                "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}" +
                "&Usuarioid=${URLEncoder.encode(userId, "UTF-8")}"
        val response = downloadJson(endpoint)
        val deliveries = JSONArray(response)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("deliveries_synced_json_$userId", response)
            .putInt("deliveries_synced_count_$userId", deliveries.length())
            .putLong("deliveries_synced_at_$userId", System.currentTimeMillis())
            .apply()
    }

    private fun syncOrderOperations(context: Context) {
        val endpoint =
            "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpTipoOpPedidoCompletaAPPVenda" +
                "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        val operations = OrderOperationLocalRepository.extractOperations(response)
        OrderOperationLocalRepository(context).replaceAll(operations)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("order_operations_synced_json", response)
            .putInt("order_operations_synced_count", operations.length())
            .putLong("order_operations_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncCompanies(context: Context) {
        val endpoint =
            "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEmpresaAPPVenda" +
                "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        val companies = JSONArray(response)
        CompanyLocalRepository(context).replaceAll(companies)
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString("companies_synced_json", response)
            .putInt("companies_synced_count", companies.length())
            .putLong("companies_synced_at", System.currentTimeMillis())
            .apply()
    }

    private fun syncPaymentConditions(context: Context) {
        val endpoint =
            "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCondicaoPgtoAPPVendaVlrMin" +
                "?Parametrostegaceptoken=${URLEncoder.encode(TOKEN, "UTF-8")}"
        val response = downloadJson(endpoint)
        PaymentConditionService.save(context.applicationContext, response)
    }

    private fun downloadJson(endpoint: String): String {
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
            if (responseCode !in 200..299) throw IllegalStateException("Erro HTTP $responseCode")
            response
        } finally {
            connection.disconnect()
        }
    }
}
