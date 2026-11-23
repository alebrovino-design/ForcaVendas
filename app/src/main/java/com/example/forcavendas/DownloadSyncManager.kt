package com.example.forcavendas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

internal fun runDownloadSteps(
    steps: List<Pair<String, () -> Unit>>,
    onStart: (Int, Int, String) -> Unit,
    onFailure: (String, Exception) -> Unit
): List<String> {
    val failures = mutableListOf<String>()
    steps.forEachIndexed { index, (name, action) ->
        onStart(index + 1, steps.size, name)
        try {
            action()
        } catch (exception: Exception) {
            failures += name
            onFailure(name, exception)
        }
    }
    return failures
}

internal object DownloadSyncManager {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: (() -> Unit)? = null
    var isRunning = false
        private set
    var message = "Atualiza os dados do ERP sem enviar clientes ou pedidos."
        private set

    fun attach(onChange: () -> Unit) {
        listener = onChange
        onChange()
    }

    fun detach() {
        listener = null
    }

    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext
        val userId = appContext.getSharedPreferences("forca_vendas", 0)
            .getString("user_id", "").orEmpty()
        val steps: List<Pair<String, () -> Unit>> = listOf(
            "Produtos" to { AutoSyncManager.syncProducts(appContext) },
            "Grupos de produtos" to { ProductGroupService.sync(appContext) },
            "Tabela de preços" to { PriceTableItemService.sync(appContext) },
            "Empresas" to { AutoSyncManager.syncCompanies(appContext) },
            "Meta" to { AutoSyncManager.syncTarget(appContext, userId) },
            "Clientes" to { AutoSyncManager.syncClients(appContext, userId) },
            "Financeiro" to { AutoSyncManager.syncFinancial(appContext, userId) },
            "Bonificação" to { BonusConfigurationService.sync(appContext) },
            "Cargas" to { AutoSyncManager.syncLoads(appContext, userId) },
            "Tipos de operação" to { AutoSyncManager.syncOrderOperations(appContext) },
            "Grupos tributários" to { TaxGroupService.sync(appContext) },
            "Condições de pagamento" to { AutoSyncManager.syncPaymentConditions(appContext) }
        )
        isRunning = true
        message = "Iniciando sincronização geral; aguarde outras buscas em andamento..."
        listener?.invoke()
        thread {
            val failures = synchronized(AutoSyncManager.downloadLock) {
                runDownloadSteps(
                    steps,
                    onStart = { index, total, name ->
                        handler.post {
                            message = "Baixando $name ($index/$total)..."
                            listener?.invoke()
                        }
                    },
                    onFailure = { name, exception ->
                        Log.e("DownloadSyncManager", "Falha ao sincronizar $name", exception)
                    }
                )
            }
            handler.post {
                isRunning = false
                message = if (failures.isEmpty()) {
                    "Sincronização geral concluída."
                } else {
                    "Falha ao sincronizar: ${failures.joinToString(", ")}. Verifique a conexão e tente novamente."
                }
                listener?.invoke()
            }
        }
    }
}
