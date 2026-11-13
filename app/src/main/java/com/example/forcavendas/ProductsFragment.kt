package com.example.forcavendas

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentProductsBinding
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ProductsFragment : Fragment() {
    private var binding: FragmentProductsBinding? = null
    private var products = listOf<Product>()
    private var selectedProductGroupId = ""
    private var displayedCount = 0
    private val pageSize = 30

    private data class Product(
        val code: String,
        val description: String,
        val netWeight: String,
        val grossWeight: String,
        val photoUrl: String,
        val productGroupId: String,
        val taxGroupId: String,
        val salesBrandId: String,
        val familyCommissionPercent: String
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewBinding = FragmentProductsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.productsBack?.setOnClickListener { findNavController().navigateUp() }
        val searchHandler = Handler(Looper.getMainLooper())
        var searchRunnable: Runnable? = null
        binding?.productsSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let(searchHandler::removeCallbacks)
                searchRunnable = Runnable {
                    if (!isAdded) return@Runnable
                    showFilteredProducts(s?.toString().orEmpty(), append = false)
                }
                searchHandler.postDelayed(searchRunnable!!, 180)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding?.productsLoadMore?.setOnClickListener {
            showFilteredProducts(binding?.productsSearch?.text?.toString().orEmpty(), append = true)
        }
        setupProductGroupFilter()
        loadProducts()
    }

    private fun setupProductGroupFilter() {
        val groups = ProductGroupService.load(requireContext())
        val labels = mutableListOf("Todos os grupos")
        val ids = mutableListOf("")
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            val id = group.optString("GrupoProdutoId")
            val description = group.optString("GrupoProdutoDescricao")
            val sigla = group.optString("GrupoProdutoSigla")
            if (id.isBlank()) continue
            ids.add(id)
            labels.add(
                if (sigla.isBlank()) description else "$description ($sigla)"
            )
        }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            labels
        )
        binding?.productsGroupFilter?.setAdapter(adapter)
        val savedGroupId = requireContext().getSharedPreferences("forca_vendas", 0)
            .getString("products_last_group_id", "") ?: ""
        val savedPosition = ids.indexOf(savedGroupId).takeIf { it >= 0 } ?: 0
        selectedProductGroupId = ids[savedPosition]
        binding?.productsGroupFilter?.setText(labels[savedPosition], false)
        binding?.productsGroupFilter?.setOnItemClickListener { _, _, position, _ ->
            selectedProductGroupId = ids.getOrElse(position) { "" }
            requireContext().getSharedPreferences("forca_vendas", 0).edit()
                .putString("products_last_group_id", selectedProductGroupId)
                .apply()
            showFilteredProducts(binding?.productsSearch?.text?.toString().orEmpty(), append = false)
        }
    }

    private fun loadProducts() {
        binding?.productsProgress?.visibility = View.VISIBLE
        binding?.productsMessage?.visibility = View.GONE

        thread {
            val records = ProductLocalRepository(requireContext()).getAll()
            val loaded = records.map { record ->
                Product(
                    code = record.code,
                    description = record.description,
                    netWeight = record.netWeight,
                    grossWeight = record.grossWeight,
                    photoUrl = record.photoUrl,
                    productGroupId = record.productGroupId,
                    taxGroupId = record.taxGroupId,
                    salesBrandId = record.salesBrandId,
                    familyCommissionPercent = record.familyCommissionPercent
                )
            }

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                products = loaded
                binding?.productsProgress?.visibility = View.GONE
                if (loaded.isEmpty()) {
                    binding?.productsMessage?.visibility = View.VISIBLE
                    binding?.productsMessage?.text =
                        "Nenhum produto sincronizado. Acesse Sincronização para baixar os dados."
                } else {
                    binding?.productsMessage?.visibility = View.GONE
                    showFilteredProducts("", append = false)
                }
            }
        }
    }

    private fun filterProducts(search: String): List<Product> {
        val normalized = search.trim().lowercase()
        return products.filter {
            val matchesGroup = selectedProductGroupId.isBlank() ||
                it.productGroupId == selectedProductGroupId
            val matchesSearch = normalized.isBlank() ||
                it.code.lowercase().contains(normalized) ||
                it.description.lowercase().contains(normalized)
            matchesGroup && matchesSearch
        }
    }

    private fun showFilteredProducts(search: String, append: Boolean) {
        if (!append) {
            displayedCount = 0
            binding?.productsList?.removeAllViews()
        }
        val filtered = filterProducts(search)
        val nextCount = (displayedCount + pageSize).coerceAtMost(filtered.size)
        binding?.productsList?.removeAllViews()
        displayedCount = nextCount
        showProducts(filtered.take(nextCount))
        binding?.productsLoadMore?.visibility =
            if (displayedCount < filtered.size) View.VISIBLE else View.GONE
    }

    private fun showProducts(items: List<Product>) {
        val list = binding?.productsList ?: return
        list.removeAllViews()

        if (items.isEmpty()) {
            binding?.productsMessage?.visibility = View.VISIBLE
            binding?.productsMessage?.text = if (products.isEmpty())
                "Nenhum produto encontrado." else "Nenhum produto corresponde à pesquisa."
            return
        }
        binding?.productsMessage?.visibility = View.GONE

        items.forEach { product ->
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                radius = dp(14).toFloat()
                setCardBackgroundColor(resources.getColor(R.color.login_field, requireContext().theme))
            }

            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val photo = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(100), dp(100))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(android.R.drawable.ic_menu_gallery)
                tag = product.photoUrl
            }

            val content = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(12) }
                orientation = LinearLayout.VERTICAL
            }

            content.addView(productText("Código", product.code, true))
            content.addView(productText("Descrição", product.description, false))
            content.addView(productText("Peso líquido", formatWeight(product.netWeight), false))
            content.addView(productText("Peso bruto", formatWeight(product.grossWeight), false))
            val priceItems = PriceTableItemService.findByProduct(requireContext(), product.code)
            val firstPrice = priceItems.firstOrNull()
            if (firstPrice != null) {
                val price = firstPrice.optString("ItemtabelaVlrVenda").replace(".", ",")
                val region = firstPrice.optString("RegiaoDescricao")
                content.addView(productText("Preço de venda", "R$ $price", true))
                if (region.isNotBlank()) {
                    content.addView(productText("Região / tabela", region, false))
                }
            } else {
                content.addView(productText("Preço de venda", "Não sincronizado", false))
            }

            row.addView(photo)
            row.addView(content)
            card.addView(row)
            list.addView(card)
            loadPhoto(product.photoUrl, photo)
        }
    }

    private fun productText(label: String, value: String, bold: Boolean): TextView =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            text = "$label: ${value.ifBlank { "Não informado" }}"
            setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun formatWeight(weight: String): String =
        weight.ifBlank { "Não informado" }.let { if (it == "Não informado") it else "$it kg" }

    private fun loadPhoto(url: String, imageView: ImageView) {
        if (url.isBlank()) return
        thread {
            try {
                val bitmap = ProductPhotoCacheService.download(requireContext(), url)
                Handler(Looper.getMainLooper()).post {
                    if (isAdded && imageView.tag == url && bitmap != null) imageView.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
                // Keep the placeholder when the product photo is unavailable.
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
