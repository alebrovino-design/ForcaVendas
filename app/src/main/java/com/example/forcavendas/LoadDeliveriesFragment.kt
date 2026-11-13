package com.example.forcavendas

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentLoadDeliveriesBinding
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class LoadDeliveriesFragment : Fragment() {
    private var binding: FragmentLoadDeliveriesBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewBinding = FragmentLoadDeliveriesBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val loadId = arguments?.getString("load_id").orEmpty()
        val description = arguments?.getString("load_description").orEmpty()

        binding?.deliveriesBack?.setOnClickListener { findNavController().navigateUp() }
        binding?.deliveriesTitle?.text = "Entregas da carga $loadId"
        binding?.deliveriesSyncButton?.setOnClickListener { synchronizeDeliveries(loadId) }

        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val userId = preferences.getString("user_id", "").orEmpty()
        val cached = preferences.getString("deliveries_synced_json_$userId", null)
        if (!cached.isNullOrBlank()) {
            runCatching { showDeliveries(JSONArray(cached), loadId) }
        } else {
            binding?.deliveriesMessage?.text =
                if (description.isBlank()) "Nenhuma entrega sincronizada." else "$description\n\nNenhuma entrega sincronizada."
        }
        synchronizeDeliveries(loadId)
    }

    private fun synchronizeDeliveries(loadId: String) {
        binding?.deliveriesSyncButton?.isEnabled = false
        binding?.deliveriesProgress?.visibility = View.VISIBLE
        binding?.deliveriesMessage?.text = "Sincronizando entregas..."

        thread {
            try {
                val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
                val userId = preferences.getString("user_id", "").orEmpty()
                if (userId.isBlank()) throw IllegalStateException("Usuário não informado")

                val token = ERPConfig.getERPToken(requireContext())
                
                Log.d("LoadDeliveriesFragment", "Buscando entregas para usuário: $userId")
                Log.d("LoadDeliveriesFragment", "Token: ${token.take(20)}...")

                val endpoint =
                    "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntregaCargaRepAPPVenda" +
                        "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}" +
                        "&Representanteid=${URLEncoder.encode(userId, "UTF-8")}"
                
                Log.d("LoadDeliveriesFragment", "Conectando a: https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntregaCargaRepAPPVenda")
                
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                
                val responseCode = connection.responseCode
                Log.d("LoadDeliveriesFragment", "HTTP Response Code: $responseCode")
                
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.d("LoadDeliveriesFragment", "Response length: ${response.length} bytes")
                
                connection.disconnect()
                
                if (responseCode !in 200..299) throw IllegalStateException("Erro HTTP $responseCode: $response")

                val deliveries = JSONArray(response)
                Log.d("LoadDeliveriesFragment", "Encontradas ${deliveries.length()} entregas")
                
                preferences.edit()
                    .putString("deliveries_synced_json_$userId", response)
                    .putInt("deliveries_synced_count_$userId", deliveries.length())
                    .putLong("deliveries_synced_at_$userId", System.currentTimeMillis())
                    .apply()

                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.deliveriesProgress?.visibility = View.GONE
                    binding?.deliveriesSyncButton?.isEnabled = true
                    showDeliveries(deliveries, loadId)
                }
            } catch (exception: Exception) {
                Log.e("LoadDeliveriesFragment", "Erro ao sincronizar entregas", exception)
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.deliveriesProgress?.visibility = View.GONE
                    binding?.deliveriesSyncButton?.isEnabled = true
                    binding?.deliveriesMessage?.text =
                        "Falha ao sincronizar: ${exception.message}"
                }
            }
        }
    }

    private fun showDeliveries(deliveries: JSONArray, loadId: String) {
        val list = binding?.deliveriesList ?: return
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val userId = preferences.getString("user_id", "").orEmpty()
        list.removeAllViews()

        val filtered = mutableListOf<JSONObject>()
        for (index in 0 until deliveries.length()) {
            val delivery = deliveries.getJSONObject(index)
            val deliveryLoadId = firstValue(
                delivery,
                "CargaId", "CargaID", "CargaCargaId", "cargaId", "Carga"
            )
            val deliveryRepresentative = firstValue(
                delivery,
                "RepresentanteId", "Representanteid", "UsuarioId", "Usuarioid", "usuarioId"
            )
            val belongsToLoad = deliveryLoadId.isBlank() || deliveryLoadId.trim() == loadId.trim()
            val belongsToRepresentative =
                deliveryRepresentative.isBlank() || deliveryRepresentative.trim() == userId.trim()
            if (belongsToLoad && belongsToRepresentative) filtered.add(delivery)
        }

        if (filtered.isEmpty()) {
            binding?.deliveriesMessage?.text = "Nenhuma entrega encontrada para esta carga."
            return
        }

        binding?.deliveriesMessage?.text =
            "${filtered.size} entrega${if (filtered.size == 1) "" else "s"} encontrada${if (filtered.size == 1) "" else "s"}"

        filtered.forEach { delivery ->
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

            content.addView(fieldRow(listOf(
                DeliveryField("Nro. Pedido", firstValue(delivery, "Pedidoid", "PedidoId", "PedidoID")),
                DeliveryField("Nro. NF", firstValue(delivery, "nfnro", "NfNro", "NFNro", "NfNumero")),
                DeliveryField("Ordem", firstValue(delivery, "ordem", "OrdemId", "OrdemEntrega"))
            )))
            content.addView(fieldRow(listOf(
                DeliveryField("Código Cliente", firstValue(delivery, "ClienteId", "ClienteID", "clienteId")),
                DeliveryField("Nome Cliente", firstValue(delivery, "ClienteNome", "clienteNome"), 1.4f)
            )))
            content.addView(fieldRow(listOf(
                DeliveryField("Fantasia", firstValue(delivery, "ClienteNomefantasia", "ClienteNomeFantasia", "NomeFantasia")),
                DeliveryField("Telefone", firstValue(delivery, "fone", "Fone", "Telefone"))
            )))
            content.addView(fieldView("Endereço", firstValue(delivery, "endereco", "Endereco", "Endereço")))
            content.addView(fieldRow(listOf(
                DeliveryField("Bairro", firstValue(delivery, "bairro", "Bairro")),
                DeliveryField("Cidade", firstValue(delivery, "cidade", "Cidade"))
            )))
            content.addView(fieldView("Peso", formatWeight(firstValue(delivery, "peso", "Peso"))))
            card.addView(content)
            list.addView(card)
        }
    }

    private data class DeliveryField(
        val label: String,
        val value: String,
        val weight: Float = 1f
    )

    private fun fieldView(label: String, value: String): LinearLayout =
        fieldRow(listOf(DeliveryField(label, value, 1f)))

    private fun fieldRow(fields: List<DeliveryField>): LinearLayout =
        LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL

            fields.forEach { field ->
                val column = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, field.weight)
                        .apply { marginEnd = dp(8) }
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

    private fun firstValue(json: JSONObject, vararg names: String): String {
        names.forEach { name ->
            val value = json.optString(name).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun formatWeight(value: String): String {
        val weight = value.replace(",", ".").toDoubleOrNull() ?: return value.ifBlank { "Não informado" }
        return java.lang.String.format(java.util.Locale("pt", "BR"), "%,.3f kg", weight)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
