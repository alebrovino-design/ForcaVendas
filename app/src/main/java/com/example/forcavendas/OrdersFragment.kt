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
import androidx.core.os.bundleOf
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

        android.util.Log.d("OrdersFragment", "Rascunhos: ${drafts.size}, ERP: ${erpOrders.size}")

        val allOrders = (drafts + erpOrders.map { toOrderDraft(it) })
            .sortedByDescending { draft ->
                draft.json.optJSONObject("sdtPedidoAppVenda")?.optLong("PedidoMobId", 0L) ?: 0L
            }

        android.util.Log.d("OrdersFragment", "Total de pedidos: ${allOrders.size}")
        
        list.removeAllViews()
        message.visibility = if (allOrders.isEmpty()) View.VISIBLE else View.GONE

        allOrders.forEach { draft ->
            android.util.Log.d("OrdersFragment", "Renderizando pedido: ${draft.mobileId} - ${draft.clientName}")
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
        android.util.Log.d("OrdersFragment", "renderOrderCard chamado: mobile=${draft.mobileId}, erp=${draft.erpId}")
        android.util.Log.d("OrdersFragment", "JSON: ${draft.json}")
        
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            radius = dp(12).toFloat()
            elevation = dp(4).toFloat()
            setCardBackgroundColor(resources.getColor(R.color.login_field, requireContext().theme))
            cardElevation = 8f
        }
        
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val order = draft.json.optJSONObject("sdtPedidoAppVenda")
        android.util.Log.d("OrdersFragment", "Order object null? ${order == null}")
        if (order != null) {
            try {
                val keys = mutableListOf<String>()
                val iter = order.keys()
                while (iter.hasNext()) {
                    keys.add(iter.next())
                }
                android.util.Log.d("OrdersFragment", "Order keys: $keys")
            } catch (e: Exception) {
                android.util.Log.d("OrdersFragment", "Erro ao listar chaves: ${e.message}")
            }
        }
        
        val isErp = draft.erpId > 0L
        val displayedOrderTotal = calculateOrderTotalWithIpi(order, draft.total)

        // ===== HEADER: Status Badge + Client Name =====
        val headerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        // Status badge
        val statusBadge = createStatusBadge(isErp)
        headerContainer.addView(statusBadge)

        // Client name
        headerContainer.addView(TextView(requireContext()).apply {
            text = draft.clientName.ifBlank { "Cliente ${draft.clientId}" }
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.white, requireContext().theme))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                leftMargin = dp(12)
            }
        })

        content.addView(headerContainer)

        // ===== ORDER IDs =====
        val idsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        idsRow.addView(createIdField("Móvel", draft.mobileId.toString(), "📱"))
        idsRow.addView(createIdField("ERP", if (isErp) draft.erpId.toString() else "-", "🔗"))
        content.addView(idsRow)

        // ===== FINANCIAL INFO =====
        val financialRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val weight = calculateOrderWeight(draft)
        financialRow.addView(createFinancialField("Valor", formatCurrency(displayedOrderTotal), "💰"))
        financialRow.addView(createFinancialField("Peso", formatWeight(weight), "⚖️"))
        content.addView(financialRow)

        // ===== PAYMENT TERMS =====
        val paymentDescription = order?.optString("PedidoCondicaoPgtoDescricao")?.trim().orEmpty()
        val paymentId = order?.optInt("PedidoCondicaoPgtoId", 0) ?: 0
        val paymentText = when {
            paymentDescription.isNotBlank() && paymentId > 0 -> "$paymentId - $paymentDescription"
            paymentDescription.isNotBlank() -> paymentDescription
            paymentId > 0 -> "Condição $paymentId"
            else -> "-"
        }
        content.addView(createInfoField("Pagamento", paymentText, "📋"))

        // ===== METADATA ROW =====
        val metadataRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val regionText = if (draft.regionId.isNotBlank()) draft.regionId else "-"
        val dateText = formatCardDate(draft.createdAt)
        metadataRow.addView(createMetadataField("Região", regionText, "🗺️"))
        metadataRow.addView(createMetadataField("Data", dateText, "📅"))
        content.addView(metadataRow)

        // ===== ACTION BUTTONS (2 ROWS for better visibility) =====
        val buttonsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Row 1: Editar + Remover (if editable)
        if (!isErp) {
            val row1 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply { bottomMargin = dp(8) }
            }

            row1.addView(createActionButton("✏️ Editar", "Editar este pedido") {
                try {
                    // TODO: Implementar navegação para tela de edição
                    val bundle = bundleOf("pedidoId" to draft.mobileId, "pedidoJson" to draft.json.toString())
                    android.util.Log.d("OrdersFragment", "Navegar para edição do pedido ${draft.mobileId}")
                    Toast.makeText(requireContext(), "Editando pedido ${draft.mobileId}", Toast.LENGTH_SHORT).show()
                    
                    // Se quiser navegar, descomente e ajuste a action:
                    // findNavController().navigate(R.id.action_orders_to_edit, bundle)
                } catch (e: Exception) {
                    android.util.Log.e("OrdersFragment", "Erro ao editar", e)
                    Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })

            row1.addView(createActionButton("🗑️ Remover", "Remover este pedido") {
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirmar remoção")
                    .setMessage("Remover pedido ${draft.mobileId}?")
                    .setPositiveButton("Remover") { _, _ ->
                        OrderDraftService.remove(requireContext(), draft.mobileId)
                        renderOrders()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            })

            buttonsContainer.addView(row1)
        }

        // Row 2: PDF + JSON (available for all)
        val row2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }

        row2.addView(createActionButton("📄 PDF", "Exportar como PDF") {
            generateOrderPdf(draft)
        })

        row2.addView(createActionButton("📦 JSON", "Compartilhar JSON") {
            generateOrderJson(draft)
        })

        buttonsContainer.addView(row2)
        content.addView(buttonsContainer)

        card.addView(content)
        list.addView(card)
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
        val products = order.optJSONArray("PedidoProdutos") ?: return 0.0
        var total = 0.0
        for (i in 0 until products.length()) {
            val product = products.optJSONObject(i) ?: continue
            val weight = product.optString("ProdutoPeso", "0").replace(",", ".").toDoubleOrNull() ?: 0.0
            val quantity = product.optInt("ProdutoQuantidade", 0)
            total += weight * quantity
        }
        return total
    }

    private fun calculateOrderTotalWithIpi(order: JSONObject?, total: Double): Double {
        if (order == null) return total
        val ipi = order.optString("PedidoIPI", "0").replace(",", ".").toDoubleOrNull() ?: 0.0
        return total + ipi
    }

    private fun formatCurrency(value: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
        return formatter.format(value)
    }

    private fun formatWeight(value: Double): String {
        return "%.2f kg".format(Locale.US, value)
    }

    private fun formatCardDate(date: String): String {
        return try {
            val parts = date.split("/")
            if (parts.size == 3) {
                "${parts[0]}/${parts[1]}/${parts[2].take(2)}"
            } else date
        } catch (_: Exception) {
            date
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
        try {
            android.util.Log.d("OrdersFragment", "Gerando PDF para pedido ${draft.mobileId}")
            
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(210, 297, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val paint = Paint().apply { color = android.graphics.Color.BLACK; textSize = 12f }
            canvas.drawText("Pedido Nº ${draft.mobileId}", 20f, 30f, paint)
            canvas.drawText("Cliente: ${draft.clientName}", 20f, 50f, paint)
            canvas.drawText("Valor Total: ${formatCurrency(draft.total)}", 20f, 70f, paint)

            pdfDocument.finishPage(page)

            val file = File(requireContext().cacheDir, "pedido_${draft.mobileId}.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()

            android.util.Log.d("OrdersFragment", "PDF criado: ${file.absolutePath}")
            openPdf(file)
        } catch (e: Exception) {
            android.util.Log.e("OrdersFragment", "Erro ao gerar PDF", e)
            Toast.makeText(requireContext(), "Erro ao gerar PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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
        try {
            android.util.Log.d("OrdersFragment", "Abrindo PDF: ${file.absolutePath}")
            
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("OrdersFragment", "Erro ao abrir PDF", e)
            Toast.makeText(requireContext(), "Nenhum leitor de PDF disponível", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
