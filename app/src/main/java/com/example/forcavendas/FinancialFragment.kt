package com.example.forcavendas

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentFinancialBinding
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class FinancialFragment : Fragment() {
    private var binding: FragmentFinancialBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentFinancialBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.financialBack?.setOnClickListener { findNavController().navigateUp() }
        val clientId = arguments?.getString("client_id").orEmpty()
        binding?.financialTitle?.text = "Financeiro - Cliente $clientId"
        loadFinancial(clientId)
    }

    private fun loadFinancial(clientId: String) {
        binding?.financialProgress?.visibility = View.VISIBLE
        binding?.financialMessage?.visibility = View.GONE
        binding?.financialList?.removeAllViews()

        thread {
            val cached = requireContext().getSharedPreferences("forca_vendas", 0)
                .getString("financial_synced_json", null)
            val handler = Handler(Looper.getMainLooper())
            if (cached.isNullOrBlank()) {
                handler.post {
                    if (!isAdded) return@post
                    binding?.financialProgress?.visibility = View.GONE
                    binding?.financialMessage?.visibility = View.VISIBLE
                    binding?.financialMessage?.text =
                        "Nenhum dado financeiro sincronizado. Acesse Sincronização."
                }
                return@thread
            }

            val representativeId = requireContext().getSharedPreferences("forca_vendas", 0)
                .getString("user_id", "") ?: ""
            val financials = parseFinancial(
                cached,
                clientId,
                representativeId
            )

            handler.post {
                if (!isAdded) return@post
                binding?.financialProgress?.visibility = View.GONE
                showFinancial(financials)
            }
        }
    }

    private fun parseFinancial(
        response: String,
        clientId: String,
        representativeId: String
    ): List<FinancialRecord> {
        val json = JSONArray(response)
        val records = mutableListOf<FinancialRecord>()

        for (index in 0 until json.length()) {
            val item = json.getJSONObject(index)
            val personId = item.optString("stPessoaFinId")
            val representative = item.optString("RepresentanteFinId")
            if (personId != clientId) continue
            if (representativeId.isNotBlank() && representative.isNotBlank() &&
                representative != representativeId) continue

            val documentType = firstAvailable(
                item,
                listOf(
                    "MovimentoFinanceiroTipo",
                    "MovimentoFinanceiroTipoDocumento",
                    "MovimentoFinanceiroTipoMovimento",
                    "TipoDocumento"
                )
            )
            records.add(
                FinancialRecord(
                    portfolio = firstAvailable(
                        item,
                        listOf(
                            "MovimentoFinanceiroDescCarteira",
                            "MovimentoFinanceiroDescCarteiracos"
                        )
                    ),
                    documentType = when (documentType.uppercase()) {
                        "R" -> "Receber"
                        "C" -> "Cheque"
                        else -> documentType.ifBlank { "Não informado" }
                    },
                    documentNumber = item.optString("MovimentoFinanceiroDocumento"),
                    issueDate = item.optString("MovimentoFinanceiroDtEmissao"),
                    dueDate = item.optString("MovimentoFinanceiroDtVencimento"),
                    barcode = firstAvailable(
                        item,
                        listOf(
                            "MovimentoFinanceiroCodigoBarras",
                            "MovimentoFinanceiroCodBarras",
                            "MovimentoFinanceiroCodigoBarra",
                            "MovimentoFinanceiroLinhaDigitavel",
                            "CodigoBarras"
                        )
                    )
                )
            )
        }
        return records
    }

    private data class FinancialRecord(
        val portfolio: String,
        val documentType: String,
        val documentNumber: String,
        val issueDate: String,
        val dueDate: String,
        val barcode: String
    )

    data class FinancialField(
        val label: String,
        val value: String,
        val weight: Float
    )

    private fun showFinancial(records: List<FinancialRecord>) {
        val list = binding?.financialList ?: return
        list.removeAllViews()

        if (records.isEmpty()) {
            binding?.financialMessage?.visibility = View.VISIBLE
            binding?.financialMessage?.text =
                "Nenhum título financeiro encontrado para este cliente."
            return
        }

        binding?.financialMessage?.visibility = View.GONE

        records.forEach { record ->
            val overdue = isOverdue(record.dueDate)
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                radius = dp(14).toFloat()
                setCardBackgroundColor(
                    resources.getColor(
                        if (overdue) R.color.overdue_red else R.color.login_field,
                        requireContext().theme
                    )
                )
            }

            val content = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            if (overdue) {
                content.addView(financialText("Título vencido", "Vencido", true))
            }
            content.addView(financialFieldRow(
                listOf(
                    FinancialField("Carteira", record.portfolio, 1f),
                    FinancialField("Tipo", record.documentType, 1f),
                    FinancialField("Documento", record.documentNumber, 1f)
                )
            ))
            content.addView(financialFieldRow(
                listOf(
                    FinancialField("Emissão", formatDate(record.issueDate), 1f),
                    FinancialField("Vencimento", formatDate(record.dueDate), 1f)
                )
            ))
            content.addView(financialFieldRow(
                listOf(
                    FinancialField("Código de barras", record.barcode, 1f)
                )
            ))

            card.addView(content)
            list.addView(card)
        }
    }

    private fun financialText(label: String, value: String, emphasize: Boolean): TextView =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            text = "$label: ${value.ifBlank { "Não informado" }}"
            setTextColor(
                resources.getColor(
                    if (label == "Título vencido") R.color.overdue_text else R.color.text_primary,
                    requireContext().theme
                )
            )
            textSize = 14f
            if (emphasize) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun financialFieldRow(fields: List<FinancialField>): LinearLayout =
        LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL

            fields.forEach { field ->
                val column = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        field.weight
                    ).apply { marginEnd = dp(8) }
                    orientation = LinearLayout.VERTICAL
                }

                column.addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = field.label
                    textSize = 11f
                    setTextColor(
                        resources.getColor(R.color.text_secondary, requireContext().theme)
                    )
                })
                column.addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                    text = field.value.ifBlank { "Não informado" }
                    textSize = 14f
                    setTextColor(
                        resources.getColor(R.color.text_primary, requireContext().theme)
                    )
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(column)
            }
        }

    private fun isOverdue(dueDate: String): Boolean {
        if (dueDate.isBlank() || dueDate.startsWith("0000")) return false
        val date = listOf(
            "yyyy-MM-dd",
            "yyyy-MM-dd'T'HH:mm:ss",
            "dd/MM/yyyy"
        ).firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(dueDate)
            } catch (_: Exception) {
                null
            }
        } ?: return false

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        return date.before(today)
    }

    private fun formatDate(dateText: String): String {
        if (dateText.isBlank() || dateText.startsWith("0000")) {
            return "Não informado"
        }

        val parsedDate = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
        ).firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(dateText)
            } catch (_: Exception) {
                null
            }
        } ?: return dateText

        return SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(parsedDate)
    }

    private fun firstAvailable(json: org.json.JSONObject, names: List<String>): String {
        names.forEach { name ->
            val value = json.optString(name)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
