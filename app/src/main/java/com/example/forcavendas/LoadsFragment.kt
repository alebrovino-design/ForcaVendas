package com.example.forcavendas

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentLoadsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

class LoadsFragment : Fragment() {
    private var binding: FragmentLoadsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewBinding = FragmentLoadsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.loadsBack?.setOnClickListener { findNavController().navigateUp() }
        loadLoads()
    }

    private fun loadLoads() {
        binding?.loadsProgress?.visibility = View.VISIBLE
        binding?.loadsMessage?.visibility = View.GONE
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val cached = preferences.getString("loads_synced_json", null)
        if (!cached.isNullOrBlank()) {
            try {
                val loads = try {
                    JSONArray(cached)
                } catch (e: Exception) {
                    val obj = JSONObject(cached)
                    obj.optJSONArray("value") ?: obj.optJSONArray("d") ?: obj.optJSONArray("results") ?: JSONArray()
                }
                showLoads(loads)
                binding?.loadsProgress?.visibility = View.GONE
            } catch (_: Exception) {
            }
        }

        thread {
            try {
                val userId = preferences.getString("user_id", "") ?: ""
                val token = ERPConfig.getERPToken(requireContext())
                val endpoint = "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCargaRepresentanteAppVenda" +
                    "?Parametrostegaceptoken=${URLEncoder.encode(token, "UTF-8")}" +
                    "&Representanteid=${URLEncoder.encode(userId, "UTF-8")}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (responseCode !in 200..299) {
                    throw Exception("Erro HTTP $responseCode")
                }

                val loads = try {
                    JSONArray(response)
                } catch (e: Exception) {
                    // Try to extract array from object
                    val obj = JSONObject(response)
                    obj.optJSONArray("value") ?: obj.optJSONArray("d") ?: obj.optJSONArray("results") ?: JSONArray()
                }
                
                Handler(Looper.getMainLooper()).post {
                    if (isAdded) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Cargas recebidas: ${loads.length()}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                preferences.edit()
                    .putString("loads_synced_json", response)
                    .putInt("loads_synced_count", loads.length())
                    .putLong("loads_synced_at", System.currentTimeMillis())
                    .apply()
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.loadsProgress?.visibility = View.GONE
                    showLoads(loads)
                }
            } catch (exception: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    binding?.loadsProgress?.visibility = View.GONE
                    binding?.loadsMessage?.visibility = View.VISIBLE
                    binding?.loadsMessage?.text = "Não foi possível consultar as cargas."
                }
            }
        }
    }

    private fun showLoads(loads: JSONArray) {
        val list = binding?.loadsList ?: return
        list.removeAllViews()

        if (loads.length() == 0) {
            binding?.loadsMessage?.visibility = View.VISIBLE
            binding?.loadsMessage?.text = "Nenhuma carga encontrada."
            return
        }

        val sortedLoads = mutableListOf<JSONObject>()
        for (index in 0 until loads.length()) {
            sortedLoads.add(loads.getJSONObject(index))
        }
        sortedLoads.sortWith { first, second ->
            val firstId = first.optString("CargaId").trim().toLongOrNull()
            val secondId = second.optString("CargaId").trim().toLongOrNull()
            when {
                firstId != null && secondId != null -> secondId.compareTo(firstId)
                firstId != null -> -1
                secondId != null -> 1
                else -> second.optString("CargaId").compareTo(first.optString("CargaId"))
            }
        }

        for (load in sortedLoads) {
            val loadId = load.optString("CargaId")
            val description = load.optString("CargaDescricao")
            val status = load.optString("CargaStatus")
            val forecastDate = formatDate(load.optString("CargaDtPrevisao"))
            val billingDate = formatDate(load.optString("CargaDtFaturamento"))
            val weight = load.optString("Peso")

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

            content.addView(fieldRow(
                listOf(
                    LoadField("Código da carga", loadId, 1f),
                    LoadField("Data previsão", forecastDate, 1f),
                    LoadField("Dt. faturamento", billingDate, 1f)
                )
            ))
            content.addView(fieldView("Descrição", description))
            content.addView(fieldRow(
                listOf(
                    LoadField("Status", status, 1f),
                    LoadField("Peso", formatWeight(weight), 1f)
                )
            ))

            val button = MaterialButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                ).apply { topMargin = dp(12) }
                text = "Ver entregas da carga"
                isAllCaps = false
                setBackgroundColor(resources.getColor(R.color.login_button, requireContext().theme))
                cornerRadius = dp(10)
                setOnClickListener {
                    val arguments = Bundle().apply {
                        putString("load_id", loadId)
                        putString("load_description", description)
                    }
                    findNavController().navigate(
                        R.id.action_LoadsFragment_to_LoadDeliveriesFragment,
                        arguments
                    )
                }
            }
            content.addView(button)
            card.addView(content)
            list.addView(card)
        }
    }

    private data class LoadField(val label: String, val value: String, val weight: Float)

    private fun fieldView(label: String, value: String): LinearLayout =
        fieldRow(listOf(LoadField(label, value, 1f)))

    private fun fieldRow(fields: List<LoadField>): LinearLayout =
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

    private fun formatDate(value: String): String {
        if (value.isBlank() || value.startsWith("0000")) return "Não informada"
        val date = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
        ).firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(value)
            } catch (_: Exception) {
                null
            }
        } ?: return value
        return SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(date)
    }

    private fun formatWeight(value: String): String {
        val weight = value.replace(",", ".").toDoubleOrNull() ?: return "Não informado"
        return String.format(Locale("pt", "BR"), "%,.3f kg", weight)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
