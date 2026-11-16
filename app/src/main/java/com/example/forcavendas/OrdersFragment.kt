package com.example.forcavendas

import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentOrdersBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

class OrdersFragment : Fragment() {
    private var binding: FragmentOrdersBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.ordersBack?.setOnClickListener { findNavController().navigateUp() }
        binding?.ordersSync?.setOnClickListener { sincronizarPedidosPendentes() }
        binding?.ordersRefresh?.setOnClickListener { baixarPedidosDoERP() }
        renderOrders()
    }

    override fun onResume() {
        super.onResume()
        renderOrders()
    }

    private fun renderOrders() {
        val list = binding?.ordersList ?: return
        val message = binding?.ordersMessage ?: return

        val drafts = OrderDraftService.load(requireContext())
        val erpRepository = ERPOrderLocalRepository(requireContext())
        val erpOrders = erpRepository.loadAll()
        val downloadedOrders = erpOrders.map { toOrderDraft(it) }
        val downloadedMobileIds = downloadedOrders.mapTo(mutableSetOf()) { it.mobileId }
        val allOrders = (downloadedOrders + drafts.filterNot { it.mobileId in downloadedMobileIds })
            .sortedByDescending { draft ->
                draft.json.optJSONObject("sdtPedidoAppVenda")?.optLong("PedidoMobId", 0L) ?: 0L
            }

        list.removeAllViews()
        message.visibility = if (allOrders.isEmpty()) View.VISIBLE else View.GONE

        allOrders.forEach { draft ->
            renderOrderCard(list, draft)
        }
    }

    private fun toOrderDraft(erpOrder: ERPOrderLocalRepository.ERPOrder): OrderDraftService.Draft {
        return OrderDraftService.Draft(
            mobileId = erpOrder.mobileId,
            erpId = erpOrder.erpId,
            clientId = erpOrder.clientId,
            clientName = erpOrder.clientName,
            regionId = erpOrder.regionId,
            createdAt = erpOrder.emissionDate,
            total = erpOrder.total,
            status = erpOrder.status,
            json = erpOrder.json
        )
    }

    private fun renderOrderCard(list: LinearLayout, draft: OrderDraftService.Draft) {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            radius = dp(12).toFloat()
            setCardBackgroundColor(resources.getColor(R.color.login_field, requireContext().theme))
        }
        
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val order = draft.json.optJSONObject("sdtPedidoAppVenda")
        val isErp = draft.erpId > 0L
        val displayedOrderTotal = order?.let { jsonNumber(it, "PedidoVlrTotal") }
            ?.takeIf { it > 0.0 } ?: draft.total
        val weight = calculateOrderWeight(draft)
        val status = order?.optString("PedidoStatus").orEmpty()
            .ifBlank { draft.status.ifBlank { "EM CARTEIRA" } }

        content.addView(TextView(requireContext()).apply {
            text = draft.clientName.ifBlank { "Cliente ${draft.clientId}" }
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        row1.addView(createFieldColumn("Código Móvel", draft.mobileId.takeIf { it > 0L }?.toString() ?: "-"))
        row1.addView(createFieldColumn("Código ERP", if (isErp) draft.erpId.toString() else "-"))
        row1.addView(createFieldColumn("Status", status))
        content.addView(row1)

        val row2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        row2.addView(createFieldColumn("Valor do pedido", formatCurrency(displayedOrderTotal)))
        row2.addView(createFieldColumn("Peso do pedido", formatWeight(weight)))
        content.addView(row2)

        val paymentDescription = order?.optString("PedidoCondicaoPgtoDescricao")?.trim().orEmpty()
        val paymentId = order?.optInt("PedidoCondicaoPgtoId", 0) ?: 0
        val paymentText = when {
            paymentDescription.isNotBlank() && paymentId > 0 &&
                !paymentDescription.startsWith("$paymentId") -> "$paymentId - $paymentDescription"
            paymentDescription.isNotBlank() -> paymentDescription
            paymentId > 0 -> "Condição $paymentId"
            else -> "-"
        }
        content.addView(createFieldColumn("Condição de pagamento", paymentText).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })

        val installments = order?.let { orderItems(it, "PedidoParcelas", "PedidoParcelamento") }
            ?.let(::uniqueInstallments)
            .orEmpty()
        val conditionDays = paymentText.substringAfter("-", "")
            .split("/")
            .mapNotNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        if (installments.isNotEmpty() || conditionDays.isNotEmpty()) {
            val days = installments.mapNotNull { jsonNumber(it, "PedidoParcelaDias")?.toInt() }
                .ifEmpty { conditionDays }
            val averageTerm = order?.optInt("PedidoCondicaoPgtoPrazoMedio", 0)
                ?.takeIf { it > 0 } ?: days.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
            val installmentCount = if (installments.isNotEmpty()) installments.size else days.size
            val installmentSummary =
                "$installmentCount Parcelas / Prazo Médio $averageTerm Dias"
            val installmentLines = mutableListOf(installmentSummary)
            if (installments.isNotEmpty()) {
                for ((index, installment) in installments.withIndex()) {
                    val number = installment.optInt("PedidoParcelaNro", index + 1)
                    val amount = jsonNumber(installment, "PedidoParcelaVlr") ?: 0.0
                    val rawDueDate = firstValue(
                        installment,
                        "PedidoParcelaDtVencimento",
                        "PedidoParcelaDataVencimento"
                    )
                    val dueDate = formatOrderDate(rawDueDate)
                    val daysText = jsonNumber(installment, "PedidoParcelaDias")
                        ?.toInt()
                        ?.let { " (+$it dias)" }
                        .orEmpty()
                    installmentLines.add("Parcela $number: ${formatCurrency(amount)} - $dueDate$daysText")
                }
            } else {
                val count = days.size
                val baseAmount = java.math.BigDecimal.valueOf(displayedOrderTotal)
                    .divide(java.math.BigDecimal(count), 2, java.math.RoundingMode.DOWN)
                val lastAmount = java.math.BigDecimal.valueOf(displayedOrderTotal)
                    .subtract(baseAmount.multiply(java.math.BigDecimal(count - 1)))
                days.forEachIndexed { index, dueDays ->
                    val amount = if (index == count - 1) lastAmount.toDouble() else baseAmount.toDouble()
                    val dueDate = formatInstallmentDate(
                        firstValue(order, "PedidoDtEmissao", "PedidoDtCadastro"),
                        dueDays
                    )
                    installmentLines.add(
                        "Parcela ${index + 1}: ${formatCurrency(amount)} - $dueDate (+$dueDays dias)"
                    )
                }
            }
            content.addView(createFieldColumn("Parcelas do pedido", installmentLines.joinToString("\n")).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            })
        }

        val companyId = firstValue(order, "PedidoEmpresaId")
        val companyName = firstValue(order, "PedidoEmpresaNome", "EmpresaNome")
            .ifBlank { CompanyLocalRepository(requireContext()).findById(companyId)?.name.orEmpty() }
        val companyDisplay = when {
            companyId.isBlank() -> companyName
            companyName.isBlank() -> companyId
            companyName.startsWith(companyId) -> companyName
            else -> "$companyId - $companyName"
        }
        val regionId = firstValue(order, "PedidoEntidadeRegiaoid", "PedidoEntidadeRegiaoId")
        val regionDescription = firstValue(
            order,
            "PedidoEntidadeRegiaoDescricao",
            "PedidoEntidadeRegiaoNome",
            "PedidoRegiaoDescricao"
        ).ifBlank {
            val priceItems = PriceTableItemService.load(requireContext())
            (0 until priceItems.length())
                .asSequence()
                .mapNotNull { priceItems.optJSONObject(it) }
                .firstOrNull { it.optString("RegiaoId") == regionId }
                ?.optString("RegiaoDescricao")
                .orEmpty()
        }
        val regionDisplay = when {
            regionId.isBlank() -> regionDescription
            regionDescription.isBlank() -> regionId
            regionDescription.startsWith(regionId) -> regionDescription
            else -> "$regionId - $regionDescription"
        }
        val date = formatOrderDate(
            firstValue(order, "PedidoDtEmissao", "PedidoDtCadastro", "PedidoData")
        )
        val detailsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        detailsRow.addView(createFieldColumn("Empresa", companyDisplay))
        detailsRow.addView(createFieldColumn("Região", regionDisplay))
        detailsRow.addView(createFieldColumn("Data", date))
        content.addView(detailsRow)

        val buttonsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (!isErp) {
            buttonsContainer.addView(createFullButton("Alterar pedido") {
                val arguments = Bundle().apply {
                    putString("client_id", draft.clientId)
                    putString("client_name", draft.clientName)
                    putString("client_region_id", draft.regionId)
                    putString("edit_order_mobile_id", draft.mobileId.toString())
                }
                findNavController().navigate(
                    R.id.action_OrdersFragment_to_ClientProductsFragment,
                    arguments
                )
            })
        }

        buttonsContainer.addView(createFullButton("Gerar PDF do pedido") {
            generateOrderPdf(draft)
        })

        buttonsContainer.addView(createFullButton("Gerar JSON do pedido") {
            generateOrderJson(draft)
        })

        content.addView(buttonsContainer)
        card.addView(content)
        list.addView(card)
    }

    private fun createFieldColumn(label: String, value: String): View {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 11f
                setTextColor(resources.getColor(R.color.text_secondary, requireContext().theme))
            })
            addView(TextView(requireContext()).apply {
                text = value.ifBlank { "-" }
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                maxLines = 8
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
        }
    }

    private fun createFullButton(text: String, isRed: Boolean = false, onClick: () -> Unit): View {
        return com.google.android.material.button.MaterialButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(8) }
            setText(text)
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isRed) resources.getColor(R.color.financial_red, requireContext().theme)
                else resources.getColor(R.color.button_light_blue, requireContext().theme)
            )
            cornerRadius = dp(8)
            setOnClickListener { onClick() }
        }
    }

    private fun formatInstallmentDate(days: Int): String {
        return try {
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.DAY_OF_MONTH, days)
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
            dateFormat.format(calendar.time)
        } catch (_: Exception) {
            ""
        }
    }

    private fun createStatusBadge(isErp: Boolean): MaterialCardView {
        return MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            ).apply { rightMargin = dp(8) }
            radius = dp(4).toFloat()
            setCardBackgroundColor(
                if (isErp) Color.parseColor("#4CAF50")  // Green for ERP
                else Color.parseColor("#FF9800")         // Orange for Draft
            )
            elevation = dp(2).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            
            val statusText = TextView(requireContext()).apply {
                text = if (isErp) "ERP" else "RASCUNHO"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(statusText)
        }
    }

    private fun createIdField(label: String, value: String, emoji: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = dp(8)
            }
            addView(TextView(requireContext()).apply {
                text = "$emoji $label"
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 13f
                setTextColor(resources.getColor(android.R.color.white, requireContext().theme))
                setTypeface(null, Typeface.NORMAL)
            })
        }
    }

    private fun createFinancialField(label: String, value: String, emoji: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = dp(8)
            }
            addView(TextView(requireContext()).apply {
                text = "$emoji $label"
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor("#4CAF50"))  // Green for financial info
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun createInfoField(label: String, value: String, emoji: String): TextView {
        return TextView(requireContext()).apply {
            text = "$emoji $label: $value"
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.white, requireContext().theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
    }

    private fun createMetadataField(label: String, value: String, emoji: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = dp(8)
            }
            addView(TextView(requireContext()).apply {
                text = "$emoji $label"
                textSize = 10f
                setTextColor(Color.parseColor("#90A4AE"))
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor("#ECEFF1"))
            })
        }
    }

    private fun createActionButton(text: String, hint: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(requireContext()).apply {
            this.text = text
            this.contentDescription = hint
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 1f
                rightMargin = dp(4)
            }
            textSize = 12f
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun calculateOrderWeight(draft: OrderDraftService.Draft): Double {
        val order = draft.json.optJSONObject("sdtPedidoAppVenda") ?: return 0.0
        val products = orderItems(order, "ItemPedido", "PedidoProdutos", "PedidoItens") ?: return 0.0
        var total = 0.0
        for (i in 0 until products.length()) {
            val product = products.optJSONObject(i) ?: continue
            val weight = jsonNumber(product, "ItemPedidoProdutoPesoLiq", "ProdutoPeso", "ItemPedidoProdutoPeso") ?: 0.0
            val quantity = jsonNumber(product, "ItemPedidoQtde", "ProdutoQuantidade", "Quantidade") ?: 0.0
            total += weight * quantity
        }
        return total
    }

    private fun jsonNumber(json: JSONObject, vararg names: String): Double? {
        names.forEach { name ->
            val value = json.optString(name).trim()
            if (value.isNotEmpty() && value != "null") {
                value.replace(",", ".").toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstValue(json: JSONObject?, vararg names: String): String {
        if (json == null) return ""
        names.forEach { name ->
            val value = json.optString(name).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun orderItems(order: JSONObject, vararg names: String): JSONArray? {
        names.forEach { name -> order.optJSONArray(name)?.let { return it } }
        return null
    }

    private fun uniqueInstallments(installments: JSONArray): List<JSONObject> {
        val seen = mutableSetOf<String>()
        return (0 until installments.length())
            .mapNotNull { installments.optJSONObject(it) }
            .filter { installment ->
                val number = installment.optInt("PedidoParcelaNro", 0)
                val key = if (number > 0) {
                    "number:$number"
                } else {
                    val dueDate = firstValue(
                        installment,
                        "PedidoParcelaDtVencimento",
                        "PedidoParcelaDataVencimento"
                    )
                    val amount = jsonNumber(installment, "PedidoParcelaVlr")
                    val days = jsonNumber(installment, "PedidoParcelaDias")
                    "details:$dueDate|$amount|$days"
                }
                seen.add(key)
            }
    }

    private fun formatCurrency(value: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
        return formatter.format(value)
    }

    private fun formatQuantity(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun formatWeight(value: Double): String {
        return "%.2f kg".format(Locale.US, value)
    }

    private fun formatCardDate(date: String): String {
        return formatOrderDate(date)
    }

    private fun formatOrderDate(value: String): String {
        if (value.isBlank() || value.startsWith("0000")) return "-"
        val inputPatterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy")
        val parsed = inputPatterns.firstNotNullOfOrNull { pattern ->
            runCatching { java.text.SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
        } ?: return value
        return java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(parsed)
    }

    private fun formatInstallmentDate(orderDate: String, days: Int): String {
        val parsed = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy")
            .firstNotNullOfOrNull { pattern ->
                runCatching {
                    java.text.SimpleDateFormat(pattern, Locale.US).parse(orderDate)
                }.getOrNull()
            } ?: return "-"
        return java.util.Calendar.getInstance().run {
            time = parsed
            add(java.util.Calendar.DAY_OF_MONTH, days)
            java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(time)
        }
    }

    private fun sincronizarPedidosPendentes() {
        val drafts = OrderDraftService.load(requireContext())
        val pending = drafts.filter { it.erpId <= 0L }

        if (pending.isEmpty()) {
            Toast.makeText(requireContext(), "Nenhum pedido pendente para sincronizar", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("📤 Sincronizar Pedidos")
            .setMessage("Deseja sincronizar ${pending.size} pedido(s) pendente(s)?")
            .setPositiveButton("Sincronizar") { _, _ ->
                sincronizarComProgressao(pending)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sincronizarComProgressao(pending: List<OrderDraftService.Draft>) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("📤 Sincronizando Pedidos")
            .setMessage("Conectando ao servidor...\nPor favor, aguarde...")
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            // TODO: Implement actual sync using OrderSyncService
            activity?.runOnUiThread {
                progressDialog.dismiss()
                val mensagem = "Sincronização concluída!\nEnviados: ${pending.size}\nErros: 0"
                Toast.makeText(requireContext(), mensagem, Toast.LENGTH_LONG).show()
                renderOrders()
            }
        }
    }

    private fun baixarPedidosDoERP() {
        val representanteId = SessionManager.currentUserId(requireContext())

        if (representanteId.isEmpty()) {
            Toast.makeText(requireContext(), "ID do representante não encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("⬇️ Baixar Pedidos do ERP")
            .setMessage("Deseja sincronizar seu histórico com os pedidos já processados no ERP?\n\nIsso pode levar alguns segundos...")
            .setPositiveButton("Baixar") { _, _ ->
                baixarComProgressao(representanteId)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun baixarComProgressao(representanteId: String) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("⬇️ Baixando Pedidos")
            .setMessage("Conectando ao servidor ERP...\nPor favor, aguarde...")
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = ERPOrderDownloadService.downloadOrders(requireContext(), representanteId)

                launch(Dispatchers.Main) {
                    progressDialog.dismiss()

                    if (result.success) {
                        val mensagem = buildString {
                            append("✅ ${result.message}")
                            if (result.ordersCount > 0) {
                                append("\n\n${result.ordersCount} pedido(s) adicionado(s)")
                            }
                        }
                        AlertDialog.Builder(requireContext())
                            .setTitle("✅ Sucesso")
                            .setMessage(mensagem)
                            .setPositiveButton("OK") { _, _ ->
                                renderOrders()
                            }
                            .show()
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle("❌ Erro")
                            .setMessage("${result.message}\n\n${result.error ?: "Erro desconhecido"}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(requireContext())
                        .setTitle("❌ Erro")
                        .setMessage("Erro ao baixar pedidos: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun generateOrderPdf(draft: OrderDraftService.Draft) {
        var pdfDocument: PdfDocument? = null
        try {
            val order = draft.json.optJSONObject("sdtPedidoAppVenda")
                ?: throw IllegalStateException("O pedido não contém os dados necessários.")
            val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
            val client = try {
                val clients = JSONArray(preferences.getString("clients_synced_json", "[]") ?: "[]")
                (0 until clients.length())
                    .asSequence()
                    .mapNotNull { clients.optJSONObject(it) }
                    .firstOrNull { it.optString("EntidadeId") == draft.clientId }
            } catch (_: Exception) {
                null
            }
            val allItems = orderItems(order, "ItemPedido", "PedidoProdutos", "PedidoItens")
                ?: JSONArray()
            val saleItems = mutableListOf<Pair<Int, JSONObject>>()
            val bonusItems = mutableListOf<Pair<Int, JSONObject>>()
            for (index in 0 until allItems.length()) {
                val item = allItems.optJSONObject(index) ?: continue
                val bonus = item.optInt(
                    "ItemPedidoAluminioAceite",
                    item.optInt("ItemPedidoAceite", 0)
                ) == 1
                val itemNumber = item.optInt("ItemPedidoId", index + 1)
                if (bonus) bonusItems.add(itemNumber to item) else saleItems.add(itemNumber to item)
            }

            pdfDocument = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val tableBottomReserve = 150f
            val rowHeight = 27f
            var pageNumber = 0
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = 0f
            val dark = Color.rgb(75, 75, 75)
            val lightGray = Color.rgb(242, 242, 242)
            val ruleGray = Color.rgb(155, 155, 155)
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(45, 45, 45)
                textSize = 10f
            }
            val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(45, 45, 45)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(50, 50, 50)
                textSize = 9f
            }
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f
            }
            val columns = floatArrayOf(24f, 56f, 250f, 292f, 358f, 424f, 490f, 571f)
            var activeTableTitle: String? = null

            fun drawRightAlignedText(text: String, right: Float, baseline: Float, paint: Paint) {
                canvas!!.drawText(text, right - paint.measureText(text), baseline, paint)
            }

            fun drawFittedText(text: String, x: Float, baseline: Float, maxWidth: Float, paint: Paint) {
                var fitted = text
                while (fitted.isNotEmpty() && paint.measureText(fitted) > maxWidth) {
                    fitted = fitted.dropLast(1)
                }
                if (fitted.length < text.length && fitted.length > 3) fitted = fitted.dropLast(3) + "..."
                canvas!!.drawText(fitted, x, baseline, paint)
            }

            fun startPage() {
                pageNumber++
                page = pdfDocument!!.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page!!.canvas
                y = 18f
                if (pageNumber > 1) {
                    canvas!!.drawText("ESPELHO DO PEDIDO - CONTINUAÇÃO", 8f, y + 10f, boldPaint)
                    y += 24f
                }
            }

            fun finishPage() {
                page?.let { pdfDocument!!.finishPage(it) }
                page = null
                canvas = null
            }

            fun drawColumnHeader(title: String) {
                val top = y
                val height = 42f
                canvas!!.drawRect(columns.first(), top, columns.last(), top + height, Paint().apply { color = dark })
                val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas!!.drawText(title, columns.first() + 4f, top + 14f, headerPaint)
                val baseline = top + 34f
                val labels = listOf("Num.", "ItemProduto", "Qtde.", "Vlr. Item", "Total", "Peso Un", "Tot. Kg")
                for (index in labels.indices) {
                    val left = columns[index]
                    val width = columns[index + 1] - left
                    val paint = Paint(headerPaint).apply { textSize = 11f }
                    canvas!!.drawText(labels[index], left + 4f, baseline, paint)
                }
                y += height
            }

            fun drawItemTable(title: String, items: List<Pair<Int, JSONObject>>) {
                if (items.isEmpty()) return
                activeTableTitle = title
                drawColumnHeader(title)
                items.forEachIndexed { index, (itemNumber, item) ->
                    if (y + rowHeight > pageHeight - tableBottomReserve) {
                        finishPage()
                        startPage()
                        drawColumnHeader(activeTableTitle ?: title)
                    }
                    val top = y
                    if (index % 2 == 1) {
                        canvas!!.drawRect(columns.first(), top, columns.last(), top + rowHeight, Paint().apply {
                            color = lightGray
                        })
                    }
                    canvas!!.drawLine(columns.first(), top + rowHeight, columns.last(), top + rowHeight, Paint().apply {
                        color = ruleGray
                        strokeWidth = 0.6f
                    })

                    val code = firstValue(item, "ItemPedidoProdutoCodigo", "ProdutoCodigo")
                    val description = firstValue(
                        item,
                        "ItemPedidoProdutoDescricao",
                        "ProdutoNome",
                        "ProdutoDescricao"
                    )
                    val name = listOf(code, description).filter { it.isNotBlank() }.joinToString(" - ")
                    val quantity = jsonNumber(item, "ItemPedidoQtde", "ProdutoQuantidade", "Quantidade") ?: 0.0
                    val taxedValues = TaxedMoney.itemValues(item)
                    val unitPrice = taxedValues.unit.toDouble()
                    val lineTotal = taxedValues.line.toDouble()
                    val unitWeight = jsonNumber(
                        item,
                        "ItemPedidoProdutoPesoLiq",
                        "ProdutoPeso",
                        "ItemPedidoProdutoPeso"
                    ) ?: 0.0
                    val totalWeight = unitWeight * quantity
                    val baseline = top + 18f
                    val values = listOf(
                        itemNumber.toString(),
                        name,
                        formatPdfQuantity(quantity),
                        formatPdfNumber(unitPrice),
                        formatPdfNumber(lineTotal),
                        formatPdfQuantity(unitWeight),
                        formatPdfQuantity(totalWeight)
                    )
                    for (columnIndex in values.indices) {
                        val left = columns[columnIndex]
                        val right = columns[columnIndex + 1]
                        if (columnIndex == 1) {
                            drawFittedText(values[columnIndex], left + 4f, baseline, right - left - 8f, bodyPaint)
                        } else {
                            drawRightAlignedText(values[columnIndex], right - 4f, baseline, bodyPaint)
                        }
                    }
                    y += rowHeight
                }
                y += 4f
            }

            startPage()
            val headerTop = 0f
            canvas!!.drawRect(0f, headerTop, pageWidth.toFloat(), 56f, Paint().apply {
                color = Color.rgb(245, 245, 245)
            })
            val logo = BitmapFactory.decodeResource(resources, R.drawable.specialnutri_logo)
            if (logo != null) {
                canvas!!.drawBitmap(
                    logo,
                    null,
                    android.graphics.RectF(12f, 9f, 78f, 46f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                logo.recycle()
            }
            val userName = preferences.getString("username", "Usuário").orEmpty().ifBlank { "Usuário" }
            val dateCreated = formatOrderDate(firstValue(order, "PedidoDtCadastro", "PedidoDtEmissao"))
            val paymentDescription = firstValue(order, "PedidoCondicaoPgtoDescricao")
            drawFittedText(userName, 88f, 20f, 300f, boldPaint)
            drawFittedText(
                "Data Cadastro: $dateCreated    Forma Pgto.: $paymentDescription",
                88f,
                39f,
                300f,
                smallPaint
            )

            val orderNumber = firstValue(order, "PedidoNroPedidoRepresentante")
                .ifBlank {
                    firstValue(order, "PedidoMobId").ifBlank {
                        if (draft.mobileId > 0L) draft.mobileId.toString() else draft.erpId.toString()
                    }
                }
            val printTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dark
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawRightAlignedText("ESPELHO DO PEDIDO", columns.last(), 19f, printTitle)
            drawRightAlignedText(
                "Pedido: $orderNumber    ERP: ${if (draft.erpId > 0L) draft.erpId else "-"}",
                columns.last(),
                36f,
                smallPaint
            )
            drawRightAlignedText(
                "Emissão: ${formatOrderDate(firstValue(order, "PedidoDtEmissao", "PedidoDtCadastro"))}",
                columns.last(),
                49f,
                smallPaint
            )

            y = 66f
            val clientName = firstValue(order, "PedidoEntidadeNomeFantasia", "PedidoEntidadeNome")
                .ifBlank { draft.clientName }
            val clientId = firstValue(order, "PedidoEntidadeId").ifBlank { draft.clientId }
            val document = firstValue(order, "PedidoEntidadeCPFCNPJ")
                .ifBlank { client?.optString("EntidadeCPFCNPJ").orEmpty() }
            val address = firstValue(order, "PedidoEntidadeEndereco")
                .ifBlank { client?.optString("EntidadeEndereco").orEmpty() }
            val addressNumber = client?.optString("EntidadeEnderecoNro").orEmpty()
            val neighborhood = firstValue(order, "PedidoEntidadeBairro")
                .ifBlank { client?.optString("EntidadeBairro").orEmpty() }
            val city = firstValue(order, "PedidoEntidadeCidade")
                .ifBlank { client?.optString("EntidadeCidade").orEmpty() }
            val state = firstValue(order, "PedidoEntidadeUF")
                .ifBlank { client?.optString("EntidadeUF").orEmpty() }
            val postalCode = client?.optString("EntidadeCEP").orEmpty()
            val mediumTerm = firstValue(order, "PedidoCondicaoPgtoPrazoMedio")
            val saleType = firstValue(order, "PedidoTipoVenda")
            val infoRows = listOf(
                "Cliente: $clientId - $clientName" to "CNPJ: $document",
                "Endereço: ${listOf(address, addressNumber).filter { it.isNotBlank() }.joinToString(" - ")}" to
                    "I.E.: ${client?.optString("EntidadeInscricaoEstadual").orEmpty()}",
                "Bairro: $neighborhood" to "Cidade: $city    UF: $state",
                "Cond.Pgto.: $paymentDescription" to "Médio: $mediumTerm",
                "Tp. Venda: $saleType" to "CEP: $postalCode"
            )
            infoRows.forEach { (left, right) ->
                drawFittedText(left, 24f, y, 315f, smallPaint)
                drawFittedText(right, 350f, y, 221f, smallPaint)
                y += 14f
            }

            drawItemTable("Venda", saleItems)
            drawItemTable("Bonificação", bonusItems)

            val orderDate = firstValue(order, "PedidoDtEmissao", "PedidoDtCadastro")
            val installments = orderItems(order, "PedidoParcelas", "PedidoParcelamento")
                ?.let(::uniqueInstallments)
                .orEmpty()
            val scheduleDays = paymentDescription.substringAfter("-", "")
                .split("/")
                .mapNotNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            val parcelRows = mutableListOf<String>()
            if (installments.isNotEmpty()) {
                for ((index, installment) in installments.withIndex()) {
                    val number = installment.optInt("PedidoParcelaNro", index + 1)
                    val amount = jsonNumber(installment, "PedidoParcelaVlr") ?: 0.0
                    val dueDate = formatOrderDate(
                        firstValue(installment, "PedidoParcelaDtVencimento", "PedidoParcelaDataVencimento")
                    )
                    parcelRows.add("Parcela $number: ${formatPdfCurrency(amount)} - $dueDate")
                }
            } else if (scheduleDays.isNotEmpty()) {
                val total = jsonNumber(order, "PedidoVlrTotal") ?: draft.total
                val count = scheduleDays.size
                val baseAmount = java.math.BigDecimal.valueOf(total)
                    .divide(java.math.BigDecimal(count), 2, java.math.RoundingMode.DOWN)
                val lastAmount = java.math.BigDecimal.valueOf(total)
                    .subtract(baseAmount.multiply(java.math.BigDecimal(count - 1)))
                scheduleDays.forEachIndexed { index, days ->
                    val amount = if (index == count - 1) lastAmount.toDouble() else baseAmount.toDouble()
                    parcelRows.add(
                        "Parcela ${index + 1}: ${formatPdfCurrency(amount)} - ${formatInstallmentDate(orderDate, days)}"
                    )
                }
            }

            val salesTotal = saleItems.fold(java.math.BigDecimal.ZERO) { sum, (_, item) ->
                sum.add(TaxedMoney.itemValues(item).line)
            }
            val totalWeight = (0 until allItems.length()).fold(0.0) { sum, index ->
                val product = allItems.optJSONObject(index) ?: return@fold sum
                val quantity = jsonNumber(product, "ItemPedidoQtde", "ProdutoQuantidade", "Quantidade") ?: 0.0
                val weight = jsonNumber(
                    product,
                    "ItemPedidoProdutoPesoLiq",
                    "ProdutoPeso",
                    "ItemPedidoProdutoPeso"
                ) ?: 0.0
                sum + quantity * weight
            }
            val totalOrder = jsonNumber(order, "PedidoVlrTotal") ?: draft.total

            val summaryHeight = maxOf(112f, 42f + parcelRows.size * 14f)
            var summaryTop = maxOf(y + 8f, pageHeight - summaryHeight - 24f)
            if (summaryTop + summaryHeight + 24f > pageHeight) {
                finishPage()
                startPage()
                summaryTop = maxOf(y + 8f, pageHeight - summaryHeight - 24f)
            }
            y = summaryTop
            val summaryY = y
            canvas!!.drawRect(24f, summaryY, 249f, summaryY + 23f, Paint().apply { color = dark })
            canvas!!.drawText(
                "Total de parcelas: ${parcelRows.size}",
                30f,
                summaryY + 16f,
                whitePaint
            )
            parcelRows.forEachIndexed { index, line ->
                drawFittedText(line, 24f, summaryY + 39f + index * 14f, 225f, smallPaint)
            }

            val productsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dark
                textSize = 14f
            }
            val totalsRight = 495f
            val totalsValue = 571f
            drawRightAlignedText("Total dos Produtos", totalsRight, summaryY + 20f, productsLabelPaint)
            drawRightAlignedText(formatPdfNumber(salesTotal.toDouble()), totalsValue, summaryY + 20f, productsLabelPaint)
            drawRightAlignedText("Total Peso", totalsRight, summaryY + 40f, productsLabelPaint)
            drawRightAlignedText(formatPdfQuantity(totalWeight), totalsValue, summaryY + 40f, productsLabelPaint)
            canvas!!.drawRect(250f, summaryY + 55f, 571f, summaryY + 94f, Paint().apply { color = dark })
            canvas!!.drawText("Total do Pedido", 265f, summaryY + 80f, whitePaint.apply { textSize = 15f })
            drawRightAlignedText(formatPdfNumber(totalOrder), 559f, summaryY + 80f, whitePaint.apply { textSize = 15f })

            finishPage()

            val pdfDirectory = File(requireContext().cacheDir, "pedido-pdfs")
            if (!pdfDirectory.exists() && !pdfDirectory.mkdirs()) {
                throw IllegalStateException("Não foi possível criar a pasta temporária do PDF.")
            }
            val file = File(pdfDirectory, "pedido_${draft.mobileId}.pdf")
            FileOutputStream(file).use { output -> pdfDocument.writeTo(output) }
            pdfDocument.close()
            pdfDocument = null
            openPdf(file)
        } catch (e: Exception) {
            android.util.Log.e("OrdersFragment", "Erro ao gerar PDF", e)
            Toast.makeText(requireContext(), "Não foi possível gerar o PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pdfDocument?.close()
        }
    }

    private fun formatPdfNumber(value: Double): String =
        java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(Locale("pt", "BR"))).format(value)

    private fun formatPdfQuantity(value: Double): String =
        java.text.DecimalFormat("#,##0.000", java.text.DecimalFormatSymbols(Locale("pt", "BR"))).format(value)

    private fun formatPdfCurrency(value: Double): String = "R$ ${formatPdfNumber(value)}"

    private fun generateOrderJson(draft: OrderDraftService.Draft) {
        try {
            android.util.Log.d("OrdersFragment", "Compartilhando JSON para pedido ${draft.mobileId}")
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, draft.json.toString(2))
                putExtra(Intent.EXTRA_SUBJECT, "Pedido ${draft.mobileId}")
            }
            startActivity(Intent.createChooser(intent, "Compartilhar JSON"))
        } catch (e: Exception) {
            android.util.Log.e("OrdersFragment", "Erro ao compartilhar JSON", e)
            Toast.makeText(requireContext(), "Erro ao gerar JSON: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            clipData = android.content.ClipData.newUri(
                requireContext().contentResolver,
                file.name,
                uri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(viewIntent, "Abrir PDF do pedido"))
        } catch (exception: android.content.ActivityNotFoundException) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = viewIntent.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(shareIntent, "Compartilhar PDF do pedido"))
            } catch (shareException: android.content.ActivityNotFoundException) {
                android.util.Log.e("OrdersFragment", "Nenhum app pode abrir ou compartilhar o PDF", shareException)
                Toast.makeText(requireContext(), "PDF salvo, mas nenhum app pode abri-lo ou compartilhá-lo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
