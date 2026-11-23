package com.example.forcavendas

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.example.forcavendas.databinding.FragmentSyncBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SyncFragment : Fragment() {
    private var binding: FragmentSyncBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewBinding = FragmentSyncBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.syncBack?.setOnClickListener { findNavController().navigateUp() }
        updateProductStatus()
        updateProductGroupStatus()
        updatePriceTableStatus()
        updateTargetStatus()
        updateClientsStatus()
        updateFinancialStatus()
        updateBonusConfigurationStatus()
        updateLoadsStatus()
        updateOrderOperationsStatus()
        updateCompaniesStatus()
        updateTaxGroupsStatus()
        updatePaymentConditionsStatus()
        binding?.syncProductsButton?.setOnClickListener { syncProducts() }
        binding?.syncProductGroupsButton?.setOnClickListener { syncProductGroups() }
        binding?.syncPriceTableButton?.setOnClickListener { syncPriceTableItems() }
        binding?.syncTargetButton?.setOnClickListener { syncTarget() }
        binding?.syncClientsButton?.setOnClickListener { syncClients() }
        binding?.syncPendingClientsListButton?.setOnClickListener { showPendingClients() }
        binding?.syncPendingClientsButton?.setOnClickListener { syncPendingClientsToErp() }
        binding?.syncFinancialButton?.setOnClickListener { syncFinancial() }
        binding?.syncBonusConfigurationButton?.setOnClickListener { syncBonusConfiguration() }
        binding?.syncLoadsButton?.setOnClickListener { syncLoads() }
        binding?.syncOrderOperationsButton?.setOnClickListener { syncOrderOperations() }
        binding?.syncCompaniesButton?.setOnClickListener { syncCompanies() }
        binding?.syncTaxGroupsButton?.setOnClickListener { syncTaxGroups() }
        binding?.syncPaymentConditionsButton?.setOnClickListener { syncPaymentConditions() }
        binding?.syncAllButton?.setOnClickListener {
            if (syncButtons().any { !it.isEnabled }) {
                Toast.makeText(requireContext(), "Aguarde a sincronização em andamento.", Toast.LENGTH_SHORT).show()
            } else {
                DownloadSyncManager.start(requireContext())
            }
        }
        DownloadSyncManager.attach { updateGeneralSyncStatus() }
    }

    private fun syncButtons(): List<View> {
        val views = binding ?: return emptyList()
        return listOf(
            views.syncProductsButton, views.syncProductGroupsButton, views.syncPriceTableButton,
            views.syncCompaniesButton, views.syncTargetButton, views.syncClientsButton,
            views.syncPendingClientsButton, views.syncFinancialButton, views.syncBonusConfigurationButton,
            views.syncLoadsButton, views.syncOrderOperationsButton, views.syncTaxGroupsButton,
            views.syncPaymentConditionsButton
        )
    }

    private fun updateGeneralSyncStatus() {
        val views = binding ?: return
        val running = DownloadSyncManager.isRunning
        views.syncAllButton.isEnabled = !running
        views.syncAllProgress.visibility = if (running) View.VISIBLE else View.GONE
        views.syncAllStatus.text = DownloadSyncManager.message
        syncButtons().forEach { it.isEnabled = !running }
        if (!running) {
            updateProductStatus()
            updateProductGroupStatus()
            updatePriceTableStatus()
            updateCompaniesStatus()
            updateTargetStatus()
            updateClientsStatus()
            updateFinancialStatus()
            updateBonusConfigurationStatus()
            updateLoadsStatus()
            updateOrderOperationsStatus()
            updateTaxGroupsStatus()
            updatePaymentConditionsStatus()
        }
    }

    private fun updateProductStatus() {
        val count = ProductLocalRepository(requireContext()).count()
        binding?.syncProductsStatus?.text = if (count == 0) {
            "Nenhum produto sincronizado"
        } else {
            "$count produtos disponíveis para consulta offline"
        }
    }

    private fun updateProductGroupStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("product_groups_synced_count", 0)
        val syncedAt = preferences.getLong("product_groups_synced_at", 0L)
        binding?.syncProductGroupsStatus?.text = when {
            count == 0 -> "Nenhum grupo de produto sincronizado"
            syncedAt == 0L -> "$count grupos disponíveis para filtro offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count grupos disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun syncProductGroups() {
        binding?.syncProductGroupsButton?.isEnabled = false
        binding?.syncProductGroupsStatus?.text = "Sincronizando grupos de produtos..."
        thread {
            try {
                val count = ProductGroupService.sync(requireContext().applicationContext)
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncProductGroupsButton?.isEnabled = true
                    updateProductGroupStatus()
                    Toast.makeText(
                        requireContext(),
                        "$count grupos de produtos sincronizados.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (exception: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncProductGroupsButton?.isEnabled = true
                    binding?.syncProductGroupsStatus?.text =
                        exception.message?.ifBlank { "Falha ao sincronizar grupos." }
                            ?: "Falha ao sincronizar grupos."
                }
            }
        }
    }

    private fun updatePriceTableStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("price_table_items_synced_count", 0)
        val syncedAt = preferences.getLong("price_table_items_synced_at", 0L)
        binding?.syncPriceTableStatus?.text = when {
            count == 0 -> "Nenhum item da tabela de preço sincronizado"
            syncedAt == 0L -> "$count itens disponíveis para consulta offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count itens disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun syncPriceTableItems() {
        binding?.syncPriceTableButton?.isEnabled = false
        binding?.syncPriceTableStatus?.text = "Sincronizando itens da tabela de preço..."
        thread {
            try {
                val count = PriceTableItemService.sync(requireContext().applicationContext)
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncPriceTableButton?.isEnabled = true
                    updatePriceTableStatus()
                    Toast.makeText(
                        requireContext(),
                        "$count itens da tabela de preço sincronizados.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (exception: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncPriceTableButton?.isEnabled = true
                    binding?.syncPriceTableStatus?.text =
                        exception.message?.ifBlank { "Falha ao sincronizar tabela de preço." }
                            ?: "Falha ao sincronizar tabela de preço."
                }
            }
        }
    }
 
    private fun updateTargetStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val month = preferences.getString("target_month", null)
        val target = preferences.getString("target_weight", null)?.toDoubleOrNull()
        val achieved = preferences.getString("target_achieved", null)?.toDoubleOrNull()
        val syncedAt = preferences.getLong("target_synced_at", 0L)

        binding?.syncTargetStatus?.text = when {
            month.isNullOrBlank() || target == null || achieved == null ->
                "Meta ainda não sincronizada"
            syncedAt == 0L ->
                "Meta sincronizada localmente - mês $month"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                val targetText = formatWeight(target)
                val achievedText = formatWeight(achieved)
                "Meta: $targetText | Atingido: $achievedText | Atualizado em $formattedDate"
            }
        }
    }

        private fun updateFinancialStatus() {
            val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
            val count = preferences.getInt("financial_synced_count", 0)
            val syncedAt = preferences.getLong("financial_synced_at", 0L)

            binding?.syncFinancialStatus?.text = when {
                count == 0 -> "Nenhum dado financeiro sincronizado"
                syncedAt == 0L -> "$count movimentos disponíveis para consulta offline"
                else -> {
                    val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                        .format(Date(syncedAt))
                    "$count movimentos disponíveis | Atualizado em $formattedDate"
                }
            }
        }

        private fun syncFinancial() {
            binding?.syncFinancialButton?.isEnabled = false
            binding?.syncFinancialProgress?.visibility = View.VISIBLE
            binding?.syncFinancialStatus?.text = "Baixando dados financeiros..."

            thread {
                try {
                    val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                    val userId = preferences.getString("user_id", "") ?: ""
                    if (userId.isBlank()) throw Exception("Código do representante não disponível")

                    val token = BuildConfig.ERP_TEST_TOKEN
                    val endpoint =
                        "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpMovFinanceiroRepAPPVenda" +
                            "?Usuarioid=${URLEncoder.encode(userId, "UTF-8")}" +
                            "&Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}"
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json")
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000

                    val responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()

                    if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

                    val movements = JSONArray(response)
                    preferences.edit()
                        .putString("financial_synced_json", response)
                        .putInt("financial_synced_count", movements.length())
                        .putLong("financial_synced_at", System.currentTimeMillis())
                        .apply()

                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        binding?.syncFinancialProgress?.visibility = View.GONE
                        binding?.syncFinancialButton?.isEnabled = true
                        updateFinancialStatus()
                    }
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        binding?.syncFinancialProgress?.visibility = View.GONE
                        binding?.syncFinancialButton?.isEnabled = true
                        binding?.syncFinancialStatus?.text =
                            "Falha na sincronização do financeiro. Verifique a conexão."
                    }
                }
            }
        }

        private fun updateClientsStatus() {
            val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
            val count = preferences.getInt("clients_synced_count", 0)
            val syncedAt = preferences.getLong("clients_synced_at", 0L)
            val pending = ClientCreateService.pendingCount(requireContext())
            binding?.syncClientsStatus?.text = when {
                count == 0 -> "Nenhum cliente sincronizado"
                syncedAt == 0L -> "$count clientes disponíveis para consulta offline"
                else -> {
                    val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                        .format(Date(syncedAt))
                    "$count clientes disponíveis | Atualizado em $formattedDate"
                }
            }
            if (pending > 0) {
                binding?.syncClientsStatus?.text =
                    "${binding?.syncClientsStatus?.text}\n$pending cliente(s) aguardando envio ao ERP"
            }
            binding?.syncPendingClientsListButton?.text =
                if (pending == 0) "Ver clientes pendentes (0)"
                else "Ver clientes pendentes ($pending)"
        }

        private fun syncClients() {
            binding?.syncClientsButton?.isEnabled = false
            binding?.syncClientsProgress?.visibility = View.VISIBLE
            binding?.syncClientsStatus?.text = "Baixando clientes..."

            thread {
                try {
                    val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                    val userId = preferences.getString("user_id", "") ?: ""
                    if (userId.isBlank()) throw Exception("Código do representante não disponível")

                    val token = BuildConfig.ERP_TEST_TOKEN
                    val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntidadeRepAPPVenda" +
                        "?Usuarioid=${URLEncoder.encode(userId, "UTF-8")}" +
                        "&Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}"
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json")
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000

                    val responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()

                    if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

                    val clients = JSONArray(response)
                    preferences.edit()
                        .putString("clients_synced_json", response)
                        .putInt("clients_synced_count", clients.length())
                        .putLong("clients_synced_at", System.currentTimeMillis())
                        .apply()

                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        binding?.syncClientsProgress?.visibility = View.GONE
                        binding?.syncClientsButton?.isEnabled = true
                        updateClientsStatus()
                    }
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        binding?.syncClientsProgress?.visibility = View.GONE
                        binding?.syncClientsButton?.isEnabled = true
                        binding?.syncClientsStatus?.text =
                            "Falha na sincronização dos clientes. Verifique a conexão."
                    }
                }
            }
        }

    private fun showPendingClients() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val pendingJson = preferences.getString("pending_clients_json", "[]") ?: "[]"
        val pending = try { JSONArray(pendingJson) } catch (_: Exception) { JSONArray() }
        if (pending.length() == 0) {
            Toast.makeText(requireContext(), "Nenhum cliente aguardando envio.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = Array(pending.length()) { index ->
            val entry = pending.optJSONObject(index)
            val client = entry?.optJSONObject("client")
                ?: entry?.takeIf { it.has("EntidadeCPFCNPJ") }
            val name = client?.optString("EntidadeNomeRazao", "").orEmpty()
            val document = client?.optString("EntidadeCPFCNPJ", "").orEmpty()
            val reason = entry?.optString("reason", "Motivo não informado")
                ?: "Motivo não informado"
            "${index + 1}. $name - $document\nMotivo: $reason"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Clientes pendentes (${pending.length()})")
            .setItems(items) { _, selectedIndex ->
                showPendingClientActions(selectedIndex)
            }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showPendingClientActions(index: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Ações do cadastro pendente")
            .setItems(arrayOf("Alterar cadastro", "Excluir envio")) { _, action ->
                when (action) {
                    0 -> showPendingClientEdit(index)
                    1 -> confirmPendingClientDelete(index)
                }
            }
            .show()
    }

    private fun showPendingClientEdit(index: Int) {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val pending = try {
            JSONArray(preferences.getString("pending_clients_json", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val entry = pending.optJSONObject(index) ?: return
        val client = entry.optJSONObject("client")
            ?: entry.takeIf { it.has("EntidadeCPFCNPJ") }
            ?: return

        val fields = linkedMapOf(
            "EntidadeNomeRazao" to "Razão social",
            "EntidadeNomeFantasia" to "Nome fantasia",
            "EntidadeCPFCNPJ" to "CNPJ/CPF",
            "EntidadeInscEstadual" to "Inscrição estadual",
            "EntidadeCepCod" to "CEP",
            "EntidadeEndereco" to "Endereço",
            "EntidadeEnderecoNro" to "Número",
            "EntidadeEndComp" to "Complemento",
            "EntidadeBairro" to "Bairro",
            "EntidadeCidade" to "Cidade",
            "EntidadeUF" to "UF",
            "EntidadeDD" to "DD",
            "EntidadeFone" to "Telefone",
            "EntidadeCelular" to "Celular",
            "EntidadeEmailNFE" to "Email para NFe",
            "EntidadeEmail" to "Email"
        )
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        val inputs = mutableMapOf<String, TextInputEditText>()
        fields.forEach { (key, label) ->
            val layout = TextInputLayout(requireContext()).apply {
                hint = label
            }
            val input = TextInputEditText(layout.context).apply {
                setText(client.optString(key, ""))
            }
            layout.addView(input)
            container.addView(layout)
            inputs[key] = input
        }
        val scroll = ScrollView(requireContext()).apply {
            addView(container)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Alterar cadastro pendente")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                inputs.forEach { (key, input) ->
                    client.put(key, input.text?.toString()?.trim().orEmpty())
                }
                client.put("EntidadeUF", client.optString("EntidadeUF").uppercase())
                ClientCreateService.updatePending(requireContext(), index, client)
                updateClientsStatus()
                Toast.makeText(requireContext(), "Cadastro pendente alterado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmPendingClientDelete(index: Int) {
        val entryName = try {
            val pending = JSONArray(
                requireContext().getSharedPreferences("forca_vendas", 0)
                    .getString("pending_clients_json", "[]") ?: "[]"
            )
            val entry = pending.optJSONObject(index)
            val client = entry?.optJSONObject("client")
                ?: entry?.takeIf { it.has("EntidadeCPFCNPJ") }
            client?.optString("EntidadeNomeRazao", "cliente")
                ?: "cliente"
        } catch (_: Exception) {
            "cliente"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Excluir envio pendente")
            .setMessage("Deseja excluir $entryName da fila? O cadastro não será enviado ao ERP.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                ClientCreateService.removePending(requireContext(), index)
                updateClientsStatus()
                Toast.makeText(requireContext(), "Envio pendente excluído.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun syncPendingClientsToErp() {
        val pendingCount = ClientCreateService.pendingCount(requireContext())
        if (pendingCount == 0) {
            Toast.makeText(requireContext(), "Não há clientes pendentes.", Toast.LENGTH_SHORT).show()
            return
        }

        binding?.syncPendingClientsButton?.isEnabled = false
        binding?.syncClientsProgress?.visibility = View.VISIBLE
        binding?.syncClientsStatus?.text = "Enviando $pendingCount cliente(s) ao ERP..."

        thread {
            val sent = ClientCreateService.sendPending(requireContext().applicationContext)
            val remaining = ClientCreateService.pendingCount(requireContext())
            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                binding?.syncPendingClientsButton?.isEnabled = true
                binding?.syncClientsProgress?.visibility = View.GONE
                updateClientsStatus()
                if (remaining > 0) {
                    binding?.syncClientsStatus?.text =
                        "$sent cliente(s) enviado(s); $remaining ainda não sincronizado(s). Verifique a conexão."
                    Toast.makeText(
                        requireContext(),
                        "$remaining cliente(s) permaneceram pendentes.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "$sent cliente(s) enviado(s) ao ERP. Baixando cadastro conferido...",
                        Toast.LENGTH_LONG
                    ).show()
                    syncClients()
                }
            }
        }
    }

    private fun updateBonusConfigurationStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("bonus_configuration_synced_count", 0)
        val syncedAt = preferences.getLong("bonus_configuration_synced_at", 0L)

        binding?.syncBonusConfigurationStatus?.text = when {
            count == 0 -> "Nenhuma configuração de bonificação sincronizada"
            syncedAt == 0L -> "$count configurações disponíveis para consulta offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count configurações disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun syncBonusConfiguration() {
        binding?.syncBonusConfigurationButton?.isEnabled = false
        binding?.syncBonusConfigurationProgress?.visibility = View.VISIBLE
        binding?.syncBonusConfigurationStatus?.text = "Baixando configurações de bonificação..."

        thread {
            try {
                BonusConfigurationService.sync(requireContext())
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncBonusConfigurationProgress?.visibility = View.GONE
                    binding?.syncBonusConfigurationButton?.isEnabled = true
                    updateBonusConfigurationStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncBonusConfigurationProgress?.visibility = View.GONE
                    binding?.syncBonusConfigurationButton?.isEnabled = true
                    binding?.syncBonusConfigurationStatus?.text =
                        "Falha na sincronização das bonificações. Verifique a conexão."
                }
            }
        }
    }

    private fun updateLoadsStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("loads_synced_count", 0)
        val syncedAt = preferences.getLong("loads_synced_at", 0L)

        binding?.syncLoadsStatus?.text = when {
            count == 0 -> "Nenhuma carga sincronizada"
            syncedAt == 0L -> "$count cargas disponíveis para consulta offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count cargas disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun updateOrderOperationsStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("order_operations_synced_count", 0)
        val syncedAt = preferences.getLong("order_operations_synced_at", 0L)
        binding?.syncOrderOperationsStatus?.text = when {
            count == 0 -> "Nenhum tipo de operação sincronizado"
            syncedAt == 0L -> "$count tipos disponíveis para digitação offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count tipos disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun updateTaxGroupsStatus() {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("tax_groups_synced_count", 0)
        val syncedAt = preferences.getLong("tax_groups_synced_at", 0L)
        binding?.syncTaxGroupsStatus?.text = when {
            count == 0 -> "Nenhum grupo tributário sincronizado"
            syncedAt == 0L -> "$count grupos disponíveis para consulta offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count grupos disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun syncTaxGroups() {
        binding?.syncTaxGroupsButton?.isEnabled = false
        binding?.syncTaxGroupsProgress?.visibility = View.VISIBLE
        binding?.syncTaxGroupsStatus?.text = "Baixando grupos tributários..."

        thread {
            try {
                val count = TaxGroupService.sync(requireContext())
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncTaxGroupsProgress?.visibility = View.GONE
                    binding?.syncTaxGroupsButton?.isEnabled = true
                    updateTaxGroupsStatus()
                    Toast.makeText(
                        requireContext(),
                        "$count grupos tributários sincronizados.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (exception: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncTaxGroupsProgress?.visibility = View.GONE
                    binding?.syncTaxGroupsButton?.isEnabled = true
                    binding?.syncTaxGroupsStatus?.text =
                        exception.message?.ifBlank { "Falha ao sincronizar grupos tributários." }
                            ?: "Falha ao sincronizar grupos tributários."
                }
            }
        }
    }

    private fun updatePaymentConditionsStatus() {
        val context = requireContext()
        val count = PaymentConditionService.count(context)
        val syncedAt = PaymentConditionService.syncedAt(context)
        binding?.syncPaymentConditionsStatus?.text = when {
            count == 0 -> "Nenhuma condição de pagamento sincronizada"
            syncedAt == 0L -> "$count condições disponíveis para pagamento offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count condições disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun updateCompaniesStatus() {
        val context = requireContext()
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        val count = preferences.getInt("companies_synced_count", 0)
        val syncedAt = preferences.getLong("companies_synced_at", 0L)
        binding?.syncCompaniesStatus?.text = when {
            count == 0 -> "Nenhuma empresa sincronizada"
            syncedAt == 0L -> "$count empresas disponíveis para digitação offline"
            else -> {
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(syncedAt))
                "$count empresas disponíveis | Atualizado em $formattedDate"
            }
        }
    }

    private fun syncCompanies() {
        binding?.syncCompaniesButton?.isEnabled = false
        binding?.syncCompaniesProgress?.visibility = View.VISIBLE
        binding?.syncCompaniesStatus?.text = "Baixando empresas..."

        thread {
            try {
                val token = BuildConfig.ERP_TEST_TOKEN
                val endpoint =
                    "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEmpresaAPPVenda" +
                        "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

                val companies = JSONArray(response)
                CompanyLocalRepository(requireContext()).replaceAll(companies)
                requireContext().getSharedPreferences("forca_vendas", 0).edit()
                    .putString("companies_synced_json", response)
                    .putInt("companies_synced_count", companies.length())
                    .putLong("companies_synced_at", System.currentTimeMillis())
                    .apply()

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncCompaniesProgress?.visibility = View.GONE
                    binding?.syncCompaniesButton?.isEnabled = true
                    updateCompaniesStatus()
                    Toast.makeText(
                        requireContext(),
                        "${companies.length()} empresas sincronizadas.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (exception: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncCompaniesProgress?.visibility = View.GONE
                    binding?.syncCompaniesButton?.isEnabled = true
                    binding?.syncCompaniesStatus?.text =
                        exception.message?.ifBlank { "Falha ao sincronizar empresas." }
                            ?: "Falha ao sincronizar empresas."
                }
            }
        }
    }

    private fun syncOrderOperations() {
        binding?.syncOrderOperationsButton?.isEnabled = false
        binding?.syncOrderOperationsProgress?.visibility = View.VISIBLE
        binding?.syncOrderOperationsStatus?.text = "Baixando tipos de operação..."

        thread {
            try {
                val token = BuildConfig.ERP_TEST_TOKEN
                val endpoint =
                    "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpTipoOpPedidoCompletaAPPVenda" +
                        "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

                val operations = OrderOperationLocalRepository.extractOperations(response)
                OrderOperationLocalRepository(requireContext()).replaceAll(operations)
                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                preferences.edit()
                    .putString("order_operations_synced_json", response)
                    .putInt("order_operations_synced_count", operations.length())
                    .putLong("order_operations_synced_at", System.currentTimeMillis())
                    .apply()

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncOrderOperationsProgress?.visibility = View.GONE
                    binding?.syncOrderOperationsButton?.isEnabled = true
                    updateOrderOperationsStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncOrderOperationsProgress?.visibility = View.GONE
                    binding?.syncOrderOperationsButton?.isEnabled = true
                    binding?.syncOrderOperationsStatus?.text =
                        "Falha na sincronização dos tipos de operação."
                }
            }
        }
    }

    private fun syncPaymentConditions() {
        binding?.syncPaymentConditionsButton?.isEnabled = false
        binding?.syncPaymentConditionsProgress?.visibility = View.VISIBLE
        binding?.syncPaymentConditionsStatus?.text = "Baixando condições de pagamento..."

        thread {
            try {
                val token = BuildConfig.ERP_TEST_TOKEN
                val endpoint =
                    "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCondicaoPgtoAPPVendaVlrMin" +
                        "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

                PaymentConditionService.save(requireContext(), response)
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncPaymentConditionsProgress?.visibility = View.GONE
                    binding?.syncPaymentConditionsButton?.isEnabled = true
                    updatePaymentConditionsStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncPaymentConditionsProgress?.visibility = View.GONE
                    binding?.syncPaymentConditionsButton?.isEnabled = true
                    binding?.syncPaymentConditionsStatus?.text =
                        "Falha na sincronização das condições de pagamento."
                }
            }
        }
    }

    private fun syncLoads() {
        binding?.syncLoadsButton?.isEnabled = false
        binding?.syncLoadsProgress?.visibility = View.VISIBLE
        binding?.syncLoadsStatus?.text = "Baixando cargas..."

        thread {
            try {
                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                val userId = preferences.getString("user_id", "") ?: ""
                if (userId.isBlank()) throw Exception("Código do representante não disponível")

                val token = BuildConfig.ERP_TEST_TOKEN
                val endpoint =
                    "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCargaRepresentanteAppVenda" +
                        "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}" +
                        "&Usuarioid=${URLEncoder.encode(userId, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")
                val loads = JSONArray(response)
                preferences.edit()
                    .putString("loads_synced_json", response)
                    .putInt("loads_synced_count", loads.length())
                    .putLong("loads_synced_at", System.currentTimeMillis())
                    .apply()

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncLoadsProgress?.visibility = View.GONE
                    binding?.syncLoadsButton?.isEnabled = true
                    updateLoadsStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncLoadsProgress?.visibility = View.GONE
                    binding?.syncLoadsButton?.isEnabled = true
                    binding?.syncLoadsStatus?.text =
                        "Falha na sincronização das cargas. Verifique a conexão."
                }
            }
        }
    }

    private fun syncTarget() {
        binding?.syncTargetButton?.isEnabled = false
        binding?.syncTargetProgress?.visibility = View.VISIBLE
        binding?.syncTargetStatus?.text = "Baixando meta do mês..."

        thread {
            try {
                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                val userId = preferences.getString("user_id", "") ?: ""
                if (userId.isBlank()) throw Exception("Código do representante não disponível")

                val calendar = Calendar.getInstance()
                val month = SimpleDateFormat("yyyyMM", Locale.US).format(calendar.time)
                val targetData = TargetService.load(userId, month)
                TargetService.save(requireContext(), userId, month, targetData)

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncTargetProgress?.visibility = View.GONE
                    binding?.syncTargetButton?.isEnabled = true
                    updateTargetStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncTargetProgress?.visibility = View.GONE
                    binding?.syncTargetButton?.isEnabled = true
                    binding?.syncTargetStatus?.text =
                        "Falha na sincronização da meta. Verifique a conexão."
                }
            }
        }
    }

    private fun formatWeight(value: Double): String =
        String.format(Locale("pt", "BR"), "%,.3f kg", value)

    private fun syncProducts() {
        binding?.syncProductsButton?.isEnabled = false
        binding?.syncProgress?.visibility = View.VISIBLE
        binding?.syncProductsStatus?.text = "Baixando produtos…"

        thread {
            try {
                val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpProdutoAPPVenda" +
                    "?Parametrostegaceptoken=${URLEncoder.encode(BuildConfig.ERP_TEST_TOKEN, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (responseCode !in 200..299) throw Exception("Erro HTTP $responseCode")

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

                ProductLocalRepository(requireContext()).replaceAll(products)
                ProductPhotoCacheService.prefetch(
                    requireContext().applicationContext,
                    products.map { it.photoUrl }
                )
                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                preferences.edit().putLong("products_synced_at", System.currentTimeMillis()).apply()
                runCatching { ProductGroupService.sync(requireContext().applicationContext) }
                runCatching { PriceTableItemService.sync(requireContext().applicationContext) }

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncProgress?.visibility = View.GONE
                    binding?.syncProductsButton?.isEnabled = true
                    updateProductStatus()
                    updateProductGroupStatus()
                    updatePriceTableStatus()
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.syncProgress?.visibility = View.GONE
                    binding?.syncProductsButton?.isEnabled = true
                    binding?.syncProductsStatus?.text = "Falha na sincronização. Verifique a conexão."
                }
            }
        }
    }

    override fun onDestroyView() {
        DownloadSyncManager.detach()
        binding = null
        super.onDestroyView()
    }
}
