package com.example.forcavendas

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import com.example.forcavendas.databinding.FragmentClientsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL

class ClientsFragment : Fragment() {
    private var binding: FragmentClientsBinding? = null
    private var clients = listOf<Client>()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private data class Client(
        val id: String,
        val companyId: String,
        val consumerFinal: Boolean,
        val cnpjCpf: String,
        val fantasyName: String,
        val reasonName: String,
        val address: String,
        val addressNumber: String,
        val neighborhood: String,
        val postalCode: String,
        val city: String,
        val state: String,
        val openBalance: String,
        val overdueBalance: String,
        val clientDiscount: String,
        val regionId: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentClientsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.clientsBack?.setOnClickListener { findNavController().navigateUp() }
        binding?.clientsAdd?.setOnClickListener { showAddClientDialog() }
        binding?.clientsSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let(searchHandler::removeCallbacks)
                val query = s?.toString().orEmpty()
                searchRunnable = Runnable {
                    if (!isAdded) return@Runnable
                    showClients(filterClients(query))
                }
                searchHandler.postDelayed(searchRunnable!!, 180)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadClients()
    }

    private fun loadClients() {
        binding?.clientsMessage?.visibility = View.GONE
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val cachedResponse = preferences.getString("clients_synced_json", null)
        if (cachedResponse.isNullOrBlank()) {
            binding?.clientsProgress?.visibility = View.VISIBLE
            binding?.clientsMessage?.visibility = View.VISIBLE
            binding?.clientsMessage?.text =
                "Nenhum cliente sincronizado. Acesse Sincronização."
            binding?.clientsProgress?.visibility = View.GONE
            return
        }

        thread {
            try {
                val loaded = parseClients(cachedResponse)
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.clientsProgress?.visibility = View.GONE
                    clients = loaded
                    showClients(clients)
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.clientsProgress?.visibility = View.GONE
                    binding?.clientsMessage?.visibility = View.VISIBLE
                    binding?.clientsMessage?.text = "Não foi possível carregar os clientes sincronizados."
                }
            }
        }
    }

    private fun parseClients(response: String): List<Client> {
        val json = JSONArray(response)
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            Client(
                id = item.optString("EntidadeId"),
                companyId = listOf(
                    "EmpresaClienteId",
                    "EntidadeEmpresaId",
                    "EntidadeEmpresaERPId",
                    "EmpresaId",
                    "EntidadeEmpresa"
                ).firstNotNullOfOrNull { field ->
                    item.optString(field).trim().takeIf { it.isNotBlank() && it != "0" }
                }.orEmpty(),
                consumerFinal = item.optInt("EntidadeConsumidorFinal", 0) == 1,
                cnpjCpf = item.optString("EntidadeCPFCNPJ").takeUnless { it.isBlank() } ?: "",
                fantasyName = item.optString("EntidadeNomeFantasia").takeUnless { it.isBlank() } ?: "",
                reasonName = item.optString("EntidadeNomeRazao").takeUnless { it.isBlank() } ?: "",
                address = item.optString("EntidadeEndereco").takeUnless { it.isBlank() } ?: "",
                addressNumber = item.optString("EntidadeEnderecoNro").takeUnless { it.isBlank() || it == "." } ?: "",
                neighborhood = item.optString("EntidadeBairro").takeUnless { it.isBlank() || it == "." } ?: "",
                postalCode = formatCep(item.optString("EntidadeCepCod")),
                city = item.optString("EntidadeCidade").takeUnless { it.isBlank() } ?: "",
                state = item.optString("EntidadeUF").takeUnless { it.isBlank() } ?: "",
                openBalance = item.optString("EntidadeVlrTituloAberto").takeUnless { it.isBlank() } ?: "0",
                overdueBalance = item.optString("EntidadeVlrTituloAtraso").takeUnless { it.isBlank() } ?: "0",
                clientDiscount = item.optString("EntidadePerDescoCliente").takeUnless { it.isBlank() } ?: "0",
                regionId = item.optString("EntidadeRegiaoId").takeUnless { it.isBlank() } ?: "0"
            )
        }
    }

    private fun filterClients(search: String): List<Client> {
        val normalized = search.trim().lowercase()
        if (normalized.isBlank()) return clients
        return clients.filter {
            it.id.lowercase().contains(normalized) ||
                it.companyId.lowercase().contains(normalized) ||
                it.cnpjCpf.lowercase().contains(normalized) ||
                it.fantasyName.lowercase().contains(normalized) ||
                it.reasonName.lowercase().contains(normalized)
        }
    }

    private fun showClients(items: List<Client>) {
        val list = binding?.clientsList ?: return
        list.removeAllViews()

        if (items.isEmpty()) {
            binding?.clientsMessage?.visibility = View.VISIBLE
            binding?.clientsMessage?.text =
                if (clients.isEmpty()) "Nenhum cliente encontrado." else "Nenhum cliente corresponde à pesquisa."
            return
        }
        binding?.clientsMessage?.visibility = View.GONE

        val visibleItems = items.take(100)
        visibleItems.forEach { client ->
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                radius = dp(14).toFloat()
                setCardBackgroundColor(resources.getColor(R.color.login_field, requireContext().theme))
            }

            val content = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            val addressNumber = client.addressNumber.takeUnless { it.isBlank() || it == "." }
                ?.let { " $it" }.orEmpty()
            content.addView(clientFieldRow(listOf(
                ClientField(
                    if (client.consumerFinal) "Código ERP - Consumidor" else "Código ERP",
                    client.id,
                    1f
                ),
                ClientField(
                    "Código da empresa",
                    client.companyId.ifBlank { "-" },
                    1f
                ),
                ClientField("CNPJ/CPF", client.cnpjCpf, 1f)
            )))
            content.addView(clientFieldRow(listOf(
                ClientField("Razão social", client.reasonName, 1f),
                ClientField("Nome fantasia", client.fantasyName, 1f)
            )))
            content.addView(clientFieldView(
                "Endereço",
                "${client.address}${addressNumber}${if (client.neighborhood.isBlank()) "" else " - ${client.neighborhood}"}"
            ))
            content.addView(clientFieldRow(listOf(
                ClientField("Cidade", client.city, 1f),
                ClientField("UF", client.state, 0.35f),
                ClientField("CEP", client.postalCode, 0.65f)
            )))
            content.addView(clientFieldRow(listOf(
                ClientField("Valor em aberto", formatCurrency(client.openBalance), 1f),
                ClientField("Valor em atraso", formatCurrency(client.overdueBalance), 1f),
                ClientField("Desconto do cliente", formatPercent(client.clientDiscount), 1f)
            )))

            val buttons = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
                orientation = LinearLayout.HORIZONTAL
            }

            val hasOverdueBalance = client.overdueBalance.replace(",", ".")
                .toDoubleOrNull()?.let { it > 0.0 } ?: false
            buttons.addView(clientButton("Consultar produtos", client.id, {
                val arguments = Bundle().apply {
                    putString("client_id", client.id)
                    putString("client_name", client.fantasyName.ifBlank { client.reasonName })
                    putString("client_region_id", client.regionId)
                }
                findNavController().navigate(R.id.action_ClientsFragment_to_ClientProductsFragment, arguments)
            }, hasOverdueBalance))
            buttons.addView(clientButton("Financeiro", client.id, {
                val arguments = Bundle().apply { putString("client_id", client.id) }
                findNavController().navigate(R.id.action_ClientsFragment_to_FinancialFragment, arguments)
            }, hasOverdueBalance))
            content.addView(buttons)
            card.addView(content)
            list.addView(card)
        }
    }

    private data class ClientField(val label: String, val value: String, val weight: Float)

    private fun clientFieldView(label: String, value: String): LinearLayout =
        clientFieldRow(listOf(ClientField(label, value, 1f)))

    private fun clientFieldRow(fields: List<ClientField>): LinearLayout =
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
                    text = field.label
                    textSize = 11f
                    setTextColor(resources.getColor(R.color.text_secondary, requireContext().theme))
                })
                column.addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                    text = field.value.ifBlank { "Não informado" }
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                })
                addView(column)
            }
        }

    private fun clientButton(
        label: String,
        clientId: String,
        onClick: () -> Unit,
        hasOverdueBalance: Boolean = false
    ): MaterialButton = MaterialButton(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = if (label == "Consultar produtos") dp(6) else 0
            marginStart = if (label == "Financeiro") dp(6) else 0
        }
        text = label
        isAllCaps = false
        val color = when {
            label == "Financeiro" && hasOverdueBalance -> R.color.financial_red
            label == "Financeiro" -> R.color.financial_green
            label == "Consultar produtos" -> R.color.button_light_blue
            else -> R.color.login_button
        }
        setBackgroundColor(resources.getColor(color, requireContext().theme))
        backgroundTintList = ColorStateList.valueOf(resources.getColor(color, requireContext().theme))
        if (label == "Consultar produtos") {
            setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
        } else {
            setTextColor(resources.getColor(R.color.white, requireContext().theme))
        }
        cornerRadius = dp(10)
        setOnClickListener {
            if (label == "Consultar produtos" && hasOverdueBalance) {
                Toast.makeText(
                    requireContext(),
                    "Operação não permitida para esse cliente!",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            onClick()
        }
    }

    private fun showAddClientDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        fun field(label: String): TextInputEditText {
            val layout = TextInputLayout(requireContext()).apply {
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                hint = label
            }
            return TextInputEditText(layout.context).also {
                layout.addView(it)
                container.addView(layout)
                if (!label.contains("Email", ignoreCase = true) &&
                    !label.contains("CNPJ", ignoreCase = true) &&
                    !label.contains("CPF", ignoreCase = true) &&
                    !label.contains("Telefone", ignoreCase = true) &&
                    !label.contains("Celular", ignoreCase = true) &&
                    !label.contains("DD", ignoreCase = true) &&
                    !label.contains("desconto", ignoreCase = true)
                ) {
                    it.filters = arrayOf(InputFilter.AllCaps())
                }
            }
        }

        fun sideBySide(firstLabel: String, secondLabel: String): Pair<TextInputEditText, TextInputEditText> {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            fun item(label: String): Pair<TextInputLayout, TextInputEditText> {
                val layout = TextInputLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    hint = label
                }
                val input = TextInputEditText(layout.context)
                layout.addView(input)
                if (!firstLabel.contains("desconto", ignoreCase = true) &&
                    !firstLabel.contains("Telefone", ignoreCase = true) &&
                    !firstLabel.contains("Celular", ignoreCase = true) &&
                    !firstLabel.contains("DD", ignoreCase = true) &&
                    !secondLabel.contains("desconto", ignoreCase = true)
                ) {
                    input.filters = arrayOf(InputFilter.AllCaps())
                }
                return layout to input
            }
            val first = item(firstLabel)
            val second = item(secondLabel)
            first.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            second.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            row.addView(first.first)
            row.addView(second.first)
            container.addView(row)
            return first.second to second.second
        }

        val cnpj = field("CNPJ/CPF")
        val razao = field("Razão social")
        val fantasia = field("Nome fantasia")
        val ieAndDiscount = sideBySide("Inscrição estadual", "% desconto do cliente")
        val ie = ieAndDiscount.first
        val discount = ieAndDiscount.second
        discount.setText("0")
        discount.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        val cep = field("CEP")
        cep.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        cep.filters = arrayOf(InputFilter.LengthFilter(9))
        addCepMask(cep)
        val endereco = field("Endereço")
        val numberAndComplement = sideBySide("Número", "Complemento")
        val numero = numberAndComplement.first
        val complemento = numberAndComplement.second
        val bairro = field("Bairro")
        val cityAndUf = sideBySide("Cidade", "UF")
        val cidade = cityAndUf.first
        val uf = cityAndUf.second
        val dd = field("DD")
        val fone = field("Telefone")
        val celular = field("Celular")
        val emailNfe = field("Email para NFe")
        val email = field("Email")

        val values = listOf(
            field("EntidadeVlrLimite").apply { setText("0"); isEnabled = false },
            field("EntidadeVlrTituloAtraso").apply { setText("0"); isEnabled = false },
            field("EntidadeVlrTituloAberto").apply { setText("0"); isEnabled = false }
        )

        cnpj.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val digits = s?.toString()?.replace(Regex("\\D"), "").orEmpty()
                if (digits.length == 14) lookupCnpj(digits) { data ->
                    if (!isAdded) return@lookupCnpj
                    razao.setText(data.optString("razao_social"))
                    fantasia.setText(data.optString("nome_fantasia"))
                    ie.setText(data.optString("inscricao_estadual"))
                    cep.setText(formatCep(data.optString("cep")))
                    endereco.setText(data.optString("endereco"))
                    numero.setText(data.optString("numero"))
                    complemento.setText(data.optString("complemento"))
                    bairro.setText(data.optString("bairro"))
                    cidade.setText(data.optString("cidade"))
                    uf.setText(data.optString("uf"))
                    dd.setText(data.optString("dd"))
                    fone.setText(data.optString("fone"))
                    celular.setText(data.optString("celular"))
                    email.setText(data.optString("email"))
                    emailNfe.setText(data.optString("email"))
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        cnpj.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val digits = cnpj.text?.toString()?.replace(Regex("\\D"), "").orEmpty()
                if (digits.length == 14) lookupCnpj(digits) { data ->
                    if (!isAdded) return@lookupCnpj
                    razao.setText(data.optString("razao_social"))
                    fantasia.setText(data.optString("nome_fantasia"))
                    ie.setText(data.optString("inscricao_estadual"))
                    cep.setText(formatCep(data.optString("cep")))
                    endereco.setText(data.optString("endereco"))
                    numero.setText(data.optString("numero"))
                    complemento.setText(data.optString("complemento"))
                    bairro.setText(data.optString("bairro"))
                    cidade.setText(data.optString("cidade"))
                    uf.setText(data.optString("uf"))
                    dd.setText(data.optString("dd"))
                    fone.setText(data.optString("fone"))
                    celular.setText(data.optString("celular"))
                    email.setText(data.optString("email"))
                    emailNfe.setText(data.optString("email"))
                }
            }
        }

        val scroll = ScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(520)
            )
            addView(container)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Incluir cliente")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cpfCnpj = cnpj.text?.toString()?.replace(Regex("\\D"), "").orEmpty()
                if (cpfCnpj.length !in listOf(11, 14)) {
                    Toast.makeText(requireContext(), "Informe um CPF ou CNPJ válido.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val razaoSocial = razao.text.toString().trim()
                if (razaoSocial.isBlank()) {
                    Toast.makeText(requireContext(), "Informe a razão social.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                val userId = preferences.getString("user_id", "") ?: ""
                val licenseId = preferences.getString("license", "") ?: ""
                val discountValue = discount.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val client = JSONObject()
                    .put("EntidadeMobId", 0)
                    .put("EntidadeId", 0)
                    .put("EntidadeNomeRazao", razaoSocial)
                    .put("EntidadeNomeFantasia", fantasia.text.toString().trim())
                    .put("EntidadeCPFCNPJ", cpfCnpj)
                    .put("EntidadeInscEstadual", ie.text.toString().trim())
                    .put("EntidadeTipoClienteId", 1)
                    .put("EntidadeRegiaoId", 0)
                    .put("EntidadeRegiaoERPId", 0)
                    .put("EntidadePerDescoCliente", discountValue)
                    .put("EntidadeRamoAtividadeId", 0)
                    .put("EntidadeSuframa", "")
                    .put("EntidadeLivreComercio", false)
                    .put("EntidadeTipoLivreComercio", "")
                    .put("EntidadeCepCod", formatCep(cep.text.toString()))
                    .put("EntidadeEndereco", endereco.text.toString().trim())
                    .put("EntidadeEnderecoNro", numero.text.toString().trim())
                    .put("EntidadeEndComp", complemento.text.toString().trim())
                    .put("EntidadeBairro", bairro.text.toString().trim())
                    .put("EntidadeCidade", cidade.text.toString().trim())
                    .put("EntidadeUF", uf.text.toString().trim().uppercase())
                    .put("EntidadeFone", fone.text.toString().trim())
                    .put("EntidadeDD", dd.text.toString().trim())
                    .put("EntidadeEmailNFE", emailNfe.text.toString().trim())
                    .put("EntidadeEmail", email.text.toString().trim())
                    .put("EntidadeVlrLimite", 0)
                    .put("EntidadeVlrTituloAtraso", 0)
                    .put("EntidadeVlrTituloAberto", 0)
                    .put("EntidadeDescricaoCarteira", "")
                    .put("EntidadeLicencaId", licenseId)
                    .put("EntidadeRepresentanteId", userId)
                    .put("EntidadeSupervisorId", 0)
                    .put("EntidadeTipoEmpresa", false)
                    .put("EntidadeTipoRepresentante", false)
                    .put("EntidadeTipoCliente", true)
                    .put("EntidadeEmpresaMatrizId", 0)
                    .put("EntidadeVlrPacelaMinima", 0)
                    .put("EntidadeMotorista", 0)
                    .put("EntidadeCelular", celular.text.toString().trim())

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                thread {
                    try {
                        val response = ClientCreateService.create(client)
                        if (!ClientCreateService.wasRegistered(response)) {
                            throw Exception("Retorno ERP: ${response.ifBlank { "resposta não informada" }}")
                        }
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            dialog.dismiss()
                            Toast.makeText(requireContext(), "Cliente cadastrado ou já existente no ERP.", Toast.LENGTH_LONG).show()
                            loadClients()
                        }
                    } catch (exception: Exception) {
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            ClientCreateService.savePending(
                                requireContext().applicationContext,
                                client,
                                exception.message?.ifBlank { "Erro ao enviar o cliente" }
                                    ?: "Erro ao enviar o cliente"
                            )
                            sendClientErrorEmail(
                                exception.message ?: "Erro desconhecido ao enviar o cliente",
                                client
                            )
                            Toast.makeText(
                                requireContext(),
                                "Cliente salvo offline. Ele será enviado na sincronização manual de pendentes. " +
                                    "Um email com o JSON foi preparado para ti@specialnutri.com.br.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun lookupCnpj(cnpj: String, onResult: (JSONObject) -> Unit) {
        thread {
            try {
                val connection = URL("https://publica.cnpj.ws/cnpj/$cnpj")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ForcaVendas/1.0 Android")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (responseCode !in 200..299 || response.isBlank()) return@thread

                val root = JSONObject(response)
                val establishment = root.optJSONObject("estabelecimento")
                    ?: root.optJSONObject("establishment")
                    ?: JSONObject()
                val address = establishment.optJSONObject("endereco")
                    ?: establishment.optJSONObject("address")
                    ?: establishment
                val cityObject = address.optJSONObject("cidade")
                    ?: address.optJSONObject("city")
                    ?: JSONObject()
                val stateObject = address.optJSONObject("estado")
                    ?: address.optJSONObject("state")
                    ?: JSONObject()

                val inscriptions = establishment.optJSONArray("inscricoes_estaduais")
                val inscription = if (inscriptions != null && inscriptions.length() > 0) {
                    inscriptions.getJSONObject(0).optString(
                        "inscricao_estadual",
                        inscriptions.getJSONObject(0).optString("number", "")
                    )
                } else establishment.optString("inscricao_estadual", "")

                val phone = listOfNotNull(
                    establishment.optString("ddd1").takeIf { it.isNotBlank() },
                    establishment.optString("telefone1").takeIf { it.isNotBlank() }
                ).joinToString(" ").trim()

                val mapped = JSONObject()
                    .put("razao_social", uppercaseAvailable(root.optString("razao_social"), root.optString("nome_razao_social")))
                    .put("nome_fantasia", uppercaseAvailable(establishment.optString("nome_fantasia"), establishment.optString("nome_fantasia")))
                    .put("inscricao_estadual", inscription)
                    .put("cep", address.optString("cep"))
                    .put("endereco", listOfNotNull(
                        address.optString("tipo_logradouro").takeIf { it.isNotBlank() },
                        address.optString("logradouro").takeIf { it.isNotBlank() }
                    ).joinToString(" ").uppercase(Locale("pt", "BR")))
                    .put("numero", address.optString("numero"))
                    .put("complemento", address.optString("complemento").uppercase(Locale("pt", "BR")))
                    .put("bairro", firstAvailable(address.optString("bairro"), address.optString("bairro_nome")).uppercase(Locale("pt", "BR")))
                    .put("cidade", uppercaseAvailable(
                        cityObject.optString("nome"),
                        address.optString("cidade"),
                        address.optString("municipio")
                    ))
                    .put("uf", firstAvailable(stateObject.optString("sigla"), address.optString("uf")))
                    .put("dd", establishment.optString("ddd1"))
                    .put("fone", establishment.optString("telefone1"))
                    .put("celular", establishment.optString("telefone2"))
                    .put("email", firstAvailable(establishment.optString("email"), root.optString("email")))

                activity?.runOnUiThread { if (isAdded) onResult(mapped) }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (isAdded) Toast.makeText(requireContext(), "Não foi possível consultar o CNPJ.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun firstAvailable(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun uppercaseAvailable(vararg values: String): String =
        firstAvailable(*values).uppercase(Locale("pt", "BR"))

    private fun sendClientErrorEmail(error: String, client: JSONObject) {
        val report = JSONObject()
            .put("error", error)
            .put("sdtClienteAppVenda", client)
            .toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("ti@specialnutri.com.br"))
            putExtra(Intent.EXTRA_SUBJECT, "Erro ao enviar cliente do Força de Vendas")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            startActivity(Intent.createChooser(intent, "Enviar relatório por email"))
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                "Nenhum aplicativo de email disponível para enviar o relatório.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openPlaceholder(title: String) {
        val arguments = Bundle().apply { putString("screen_title", title) }
        findNavController().navigate(
            R.id.PlaceholderFragment,
            arguments
        )
    }

    private fun formatCurrency(value: String): String {
        val amount = value.replace(",", ".").toDoubleOrNull() ?: return "R$ 0,00"
        return NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(amount)
    }

    private fun formatPercent(value: String): String {
        val percent = value.replace(",", ".").toDoubleOrNull() ?: return "0%"
        return "${String.format(Locale("pt", "BR"), "%.3f", percent).trimEnd('0').trimEnd(',')}%"
    }

    private fun formatCep(value: String): String {
        val digits = value.replace(Regex("\\D"), "").take(8)
        return when {
            digits.length <= 5 -> digits
            else -> "${digits.substring(0, 5)}-${digits.substring(5)}"
        }
    }

    private fun addCepMask(input: TextInputEditText) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                input.removeTextChangedListener(this)
                val formatted = formatCep(editable?.toString().orEmpty())
                input.setText(formatted)
                input.setSelection(formatted.length)
                input.addTextChangedListener(this)
            }
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        searchRunnable?.let(searchHandler::removeCallbacks)
        binding = null
        super.onDestroyView()
    }
}
