package com.example.forcavendas

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.AdapterView
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentClientProductsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AlertDialog
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private class OperationNotFoundException(
    val diagnosticJson: String,
    message: String
) : Exception(message)

class ClientProductsFragment : Fragment() {
    private var binding: FragmentClientProductsBinding? = null
    private var demonstrativeSummary: BonusSummaryService.Summary? = null
    private var demonstrativeLoading = false
    private var demonstrativeVisible = true
    private var products = listOf<ProductWithPrice>()
    private var clientId = ""
    private var regionId = ""
    private var selectedProductGroupId = ""
    private var displayedCount = 0
    private var currentFilteredProducts = listOf<ProductWithPrice>()
    private var cartItems = listOf<ClientCartService.CartItem>()
    private var editOrderMobileId = ""
    private val pageSize = 30
    private var pendingFinalizeItems: List<ClientCartService.CartItem>? = null
    private var pendingFinalizeCondition: PaymentConditionService.Condition? = null
    private var pendingFinalizeSaleType: String = "BO"
    private var pendingFinalizeCompany: CompanyLocalRepository.Company? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val items = pendingFinalizeItems
        val condition = pendingFinalizeCondition
        val company = pendingFinalizeCompany
        pendingFinalizeItems = null
        pendingFinalizeCondition = null
        val saleType = pendingFinalizeSaleType
        pendingFinalizeCompany = null
        if (items != null && condition != null && company != null) {
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (!granted) {
                Toast.makeText(
                    requireContext(),
                    "Localização não autorizada. O pedido será salvo sem coordenadas.",
                    Toast.LENGTH_LONG
                ).show()
            }
            saveOrderDraft(items, condition, saleType, getCurrentLocation(), company)
        }
    }

    private data class ProductWithPrice(
        val code: String,
        val description: String,
        val photoUrl: String,
        val price: String,
        val productGroupId: String
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewBinding = FragmentClientProductsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.clientProductsBack?.setOnClickListener { findNavController().navigateUp() }
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        demonstrativeVisible = preferences.getBoolean("bonus_demonstrative_visible", true)
        fun updateSearchVisibility() {
            binding?.clientProductsSearchLayout?.visibility = if (demonstrativeVisible) View.VISIBLE else View.GONE
        }
        updateSearchVisibility()
        binding?.clientProductsDemonstrativeToggle?.setOnClickListener {
            demonstrativeVisible = !demonstrativeVisible
            if (!demonstrativeVisible) {
                binding?.clientProductsSearch?.let { search ->
                    if (search.hasFocus()) {
                        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                            ?.hideSoftInputFromWindow(search.windowToken, 0)
                        search.clearFocus()
                    }
                }
            }
            preferences.edit().putBoolean("bonus_demonstrative_visible", demonstrativeVisible).apply()
            updateSearchVisibility()
            updateBonusSummary()
        }
        var clientName = arguments?.getString("client_name").orEmpty()
        clientId = arguments?.getString("client_id").orEmpty()
        regionId = arguments?.getString("client_region_id").orEmpty()
        editOrderMobileId = arguments?.getString("edit_order_mobile_id").orEmpty()
        if (editOrderMobileId.isNotBlank()) {
            val draft = OrderDraftService.load(requireContext())
                .firstOrNull { it.mobileId.toString() == editOrderMobileId && it.erpId <= 0L }
            if (draft == null) {
                Toast.makeText(requireContext(), "O rascunho não foi encontrado ou já foi enviado ao ERP.", Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
                return
            }
            val order = draft.json.optJSONObject("sdtPedidoAppVenda")
            clientId = clientId.ifBlank { draft.clientId }
            clientName = clientName.ifBlank { draft.clientName }
            regionId = regionId.ifBlank { draft.regionId }
            restoreDraftCart(draft)
            if (order != null) {
                clientId = clientId.ifBlank { order.optString("PedidoEntidadeId") }
                regionId = regionId.ifBlank { order.optString("PedidoEntidadeRegiaoid") }
            }
        }
        binding?.clientProductsClient?.text =
            if (clientName.isBlank()) "Código ERP: $clientId" else "$clientName - Código ERP: $clientId"

        val searchHandler = Handler(Looper.getMainLooper())
        var searchRunnable: Runnable? = null
        binding?.clientProductsSearch?.addTextChangedListener(object : TextWatcher {
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
        binding?.clientProductsCartButton?.setOnClickListener { showCart() }
        binding?.clientProductsGenerateOrderButton?.setOnClickListener { generateOrderDraft() }
        binding?.clientProductsLoadMore?.setOnClickListener {
            showFilteredProducts(
                binding?.clientProductsSearch?.text?.toString().orEmpty(),
                append = true
            )
        }
        updateCartButton()
        updateBonusSummary()
        setupProductGroupFilter()
        loadProducts(regionId)
    }

    private fun restoreDraftCart(draft: OrderDraftService.Draft) {
        val order = draft.json.optJSONObject("sdtPedidoAppVenda") ?: return
        val items = order.optJSONArray("ItemPedido")
            ?: order.optJSONArray("PedidoProdutos")
            ?: order.optJSONArray("PedidoItens")
            ?: return
        val localProducts = ProductLocalRepository(requireContext()).getAll().associateBy { it.code }
        val priceItems = PriceTableItemService.load(requireContext())
        val restored = linkedMapOf<String, ClientCartService.CartItem>()

        fun number(item: org.json.JSONObject, vararg names: String): Double {
            names.forEach { name ->
                val value = item.optString(name).trim()
                if (value.isNotBlank() && value != "null") {
                    value.replace(",", ".").toDoubleOrNull()?.let { return it }
                }
            }
            return 0.0
        }

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val productCode = listOf(
                "ItemPedidoProdutoCodigo",
                "ProdutoCodigo",
                "ItemPedidoProdutoId"
            ).firstNotNullOfOrNull { key ->
                item.optString(key).takeIf { it.isNotBlank() && it != "null" }
            } ?: continue
            val localProduct = localProducts[productCode]
            val existing = restored[productCode]
            val quantity = number(item, "ItemPedidoQtde", "ProdutoQuantidade", "Quantidade")
            val isBonus = number(item, "ItemPedidoAceite", "ItemPedidoAluminioAceite") > 0.0
            val price = item.optString("ItemPedidoVlrOriginal")
                .ifBlank { item.optString("ItemPedidoVrlUnitario") }
                .ifBlank {
                    (0 until priceItems.length())
                        .asSequence()
                        .mapNotNull { priceItems.optJSONObject(it) }
                        .firstOrNull {
                            it.optString("ItemtabelaProdutoCodigo") == productCode &&
                                (regionId.isBlank() || it.optString("RegiaoId") == regionId)
                        }
                        ?.optString("ItemtabelaVlrVenda")
                        .orEmpty()
                }
            restored[productCode] = ClientCartService.CartItem(
                productCode = productCode,
                description = item.optString("ItemPedidoProdutoDescricao")
                    .ifBlank { localProduct?.description.orEmpty() },
                photoUrl = item.optString("ItemPedidoProdutoFoto")
                    .ifBlank { localProduct?.photoUrl.orEmpty() },
                price = price,
                soldQuantity = (existing?.soldQuantity ?: 0.0) + if (isBonus) 0.0 else quantity,
                bonusQuantity = (existing?.bonusQuantity ?: 0.0) + if (isBonus) quantity else 0.0,
                productGroupId = item.optString("ItemPedidoProdutoGrupoId")
                    .ifBlank { localProduct?.productGroupId.orEmpty() }
            )
        }
        ClientCartService.save(requireContext(), draft.clientId, restored.values.toList())
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
            labels.add(if (sigla.isBlank()) description else "$description ($sigla)")
        }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            labels
        )
        binding?.clientProductsGroupFilter?.setAdapter(adapter)
        val savedGroupId = requireContext().getSharedPreferences("forca_vendas", 0)
            .getString("client_products_last_group_id", "") ?: ""
        val savedPosition = ids.indexOf(savedGroupId).takeIf { it >= 0 } ?: 0
        selectedProductGroupId = ids[savedPosition]
        binding?.clientProductsGroupFilter?.setText(labels[savedPosition], false)
        binding?.clientProductsGroupFilter?.setOnItemClickListener { _, _, position, _ ->
            selectedProductGroupId = ids.getOrElse(position) { "" }
            requireContext().getSharedPreferences("forca_vendas", 0).edit()
                .putString("client_products_last_group_id", selectedProductGroupId)
                .apply()
            showFilteredProducts(
                binding?.clientProductsSearch?.text?.toString().orEmpty(),
                append = false
            )
        }
    }

    private fun loadProducts(regionId: String) {
        thread {
            val productRepository = ProductLocalRepository(requireContext())
            val priceItems = PriceTableItemService.load(requireContext())
            val loaded = mutableListOf<ProductWithPrice>()
            val productsByCode = productRepository.getAll().associateBy { it.code }
            val loadedCodes = mutableSetOf<String>()
            cartItems = ClientCartService.load(requireContext(), clientId)

            for (index in 0 until priceItems.length()) {
                val item = priceItems.optJSONObject(index) ?: continue
                if (regionId.isNotBlank() && item.optString("RegiaoId") != regionId) continue

                val code = item.optString("ItemtabelaProdutoCodigo")
                val product = productsByCode[code] ?: continue
                if (!loadedCodes.add(code)) continue
                loaded.add(
                    ProductWithPrice(
                        code = code,
                        description = product.description,
                        photoUrl = product.photoUrl,
                        price = item.optString("ItemtabelaVlrVenda"),
                        productGroupId = product.productGroupId
                    )
                )
            }

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                products = loaded
                if (loaded.isEmpty()) {
                    binding?.clientProductsMessage?.visibility = View.VISIBLE
                    binding?.clientProductsMessage?.text =
                        if (regionId.isBlank()) "Cliente sem região cadastrada." else
                            "Nenhum produto com preço encontrado para esta região."
                } else {
                    binding?.clientProductsMessage?.visibility = View.GONE
                    showFilteredProducts(
                        binding?.clientProductsSearch?.text?.toString().orEmpty(),
                        append = false
                    )
                }
            }
        }
    }

    private fun filterProducts(search: String): List<ProductWithPrice> {
        val normalized = search.trim().lowercase()
        return products.filter { product ->
            val matchesGroup = selectedProductGroupId.isBlank() ||
                product.productGroupId == selectedProductGroupId
            val matchesSearch = normalized.isBlank() ||
                product.code.lowercase().contains(normalized) ||
                product.description.lowercase().contains(normalized)
            matchesGroup && matchesSearch
        }
    }

    private fun showFilteredProducts(search: String, append: Boolean) {
        if (!append) {
            displayedCount = 0
            binding?.clientProductsList?.removeAllViews()
        }
        currentFilteredProducts = filterProducts(search)
        val nextCount = (displayedCount + pageSize).coerceAtMost(currentFilteredProducts.size)
        val page = currentFilteredProducts.take(nextCount)
        binding?.clientProductsList?.removeAllViews()
        displayedCount = page.size
        showProducts(page)
        binding?.clientProductsLoadMore?.visibility =
            if (displayedCount < currentFilteredProducts.size) View.VISIBLE else View.GONE
        binding?.clientProductsMessage?.visibility =
            if (currentFilteredProducts.isEmpty()) View.VISIBLE else View.GONE
        if (currentFilteredProducts.isEmpty()) {
            binding?.clientProductsMessage?.text = "Nenhum produto corresponde aos filtros."
        }
    }

    private fun showProducts(items: List<ProductWithPrice>) {
        val list = binding?.clientProductsList ?: return
        list.removeAllViews()
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
            content.addView(productText("Produto", product.description, false))
            content.addView(productText("Valor", formatCurrency(product.price), true))
            val cartItem = cartItems
                .firstOrNull { it.productCode == product.code }

            val quantities = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                orientation = LinearLayout.VERTICAL
            }
            fun quantityField(label: String, value: Double): Pair<LinearLayout, TextInputEditText> {
                val layout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).also {
                        it.topMargin = dp(2)
                        it.bottomMargin = dp(2)
                    }
                }
                layout.addView(TextView(requireContext()).apply {
                    text = label
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                })
                val controls = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val inputLayout = TextInputLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    this.hint = ""
                    minimumWidth = dp(72)
                }
                val input = TextInputEditText(layout.context).apply {
                    setText(formatQuantity(value))
                    gravity = android.view.Gravity.CENTER
                    setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                    minimumHeight = dp(48)
                    minimumWidth = dp(72)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                inputLayout.addView(input)

                fun quantityButton(text: String, amount: Double): com.google.android.material.button.MaterialButton =
                    com.google.android.material.button.MaterialButton(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                            marginStart = dp(2)
                            marginEnd = dp(2)
                        }
                        this.text = text
                        textSize = 18f
                        isAllCaps = false
                        insetTop = 0
                        insetBottom = 0
                        setPadding(0, 0, 0, 0)
                        setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            resources.getColor(R.color.button_light_blue, requireContext().theme)
                        )
                        cornerRadius = dp(8)
                        setOnClickListener {
                            val current = input.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                            val updated = (current + amount).coerceAtLeast(0.0)
                            input.setText(formatQuantity(updated))
                            input.setSelection(input.text?.length ?: 0)
                        }
                    }

                controls.addView(quantityButton("−", -1.0))
                controls.addView(inputLayout)
                controls.addView(quantityButton("+", 1.0))
                layout.addView(controls)
                return layout to input
            }
            val sold = quantityField("Qtd. venda", cartItem?.soldQuantity ?: 0.0)
            val bonus = quantityField("Qtd. bonificação", cartItem?.bonusQuantity ?: 0.0)
            quantities.addView(sold.first)
            quantities.addView(bonus.first)
            
            // Adicionar informações de bonificação
            val soldQuantityForLimit = groupSoldQuantity(product.productGroupId, cartItem?.soldQuantity ?: 0.0, product.code)
            val limit = BonusConfigurationService.getBonusLimit(requireContext(), regionId, product.productGroupId, soldQuantityForLimit)
            val otherBonusInGroup = cartItems
                .filter { it.productGroupId == product.productGroupId && it.productCode != product.code }
                .sumOf { it.bonusQuantity }
            
            if (limit != null) {
                val bonusInfoText = TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.text_secondary, requireContext().theme))
                    text = "Saldo: ${formatQuantity((limit.allowedBonus - otherBonusInGroup).coerceAtLeast(0.0))} unid. | " +
                        "Base ${formatQuantity(limit.bonusPercent)}% + tolerância ${formatQuantity(limit.extraPercent)}% sobre a venda"
                }
                quantities.addView(bonusInfoText)
            }
            
            content.addView(quantities)

            val addButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply { topMargin = dp(6) }
                text = if (cartItem == null) "Adicionar ao carrinho" else "Atualizar carrinho"
                isAllCaps = false
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.button_light_blue, requireContext().theme)
                )
                cornerRadius = dp(10)
                setOnClickListener {
                    val soldQuantity = sold.second.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    val bonusQuantity = bonus.second.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    if (soldQuantity < 0.0 || bonusQuantity < 0.0) {
                        Toast.makeText(requireContext(), "As quantidades não podem ser negativas.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val limit = BonusConfigurationService.getBonusLimit(
                        requireContext(),
                        regionId,
                        product.productGroupId,
                        groupSoldQuantity(product.productGroupId, soldQuantity, product.code)
                    )
                    if (bonusQuantity > 0.0 && limit == null) {
                        Toast.makeText(
                            requireContext(),
                            "Não existe bonificação configurada para esta região e grupo.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    val otherBonusQuantity = cartItems
                        .filter { it.productGroupId == product.productGroupId && it.productCode != product.code }
                        .sumOf { it.bonusQuantity }
                    val remainingBonusQuantity =
                        (limit?.allowedBonus ?: 0.0) - otherBonusQuantity
                    if (limit != null && bonusQuantity > remainingBonusQuantity + 0.000001) {
                        val allowed = formatQuantity((limit.allowedBonus - otherBonusQuantity).coerceAtLeast(0.0))
                        Toast.makeText(
                            requireContext(),
                            "Bonificação máxima restante do grupo: $allowed unidade(s). Base ${formatQuantity(limit.bonusPercent)}% + tolerância ${formatQuantity(limit.extraPercent)}% sobre a venda.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    ClientCartService.addOrUpdate(
                        requireContext(),
                        clientId,
                        ClientCartService.CartItem(
                            productCode = product.code,
                            description = product.description,
                            photoUrl = product.photoUrl,
                            price = product.price,
                            soldQuantity = soldQuantity,
                            bonusQuantity = bonusQuantity,
                            productGroupId = product.productGroupId
                        )
                    )
                    updateCartButton()
                    text = "Atualizar carrinho"
                    Toast.makeText(requireContext(), "Produto atualizado no carrinho.", Toast.LENGTH_SHORT).show()
                }
            }
            content.addView(addButton)
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
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun formatQuantity(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun updateCartButton() {
        cartItems = if (clientId.isBlank()) emptyList() else
            ClientCartService.load(requireContext(), clientId)
        val count = cartItems.size
        binding?.clientProductsCartButton?.text = "Carrinho ($count)"
        binding?.clientProductsGenerateOrderButton?.visibility =
            if (count > 0) View.VISIBLE else View.GONE
        updateBonusSummary()
    }

    private fun groupSoldQuantity(
        productGroupId: String,
        proposedSoldQuantity: Double,
        proposedProductCode: String
    ): Double =
        cartItems
            .filter { it.productGroupId == productGroupId && it.productCode != proposedProductCode }
            .sumOf { it.soldQuantity } + proposedSoldQuantity

    private fun updateBonusSummary() {
        val container = binding?.clientProductsBonusSummary ?: return
        val cartBonusAmount = cartItems.sumOf { item ->
            val price = item.price.replace(",", ".").toDoubleOrNull() ?: 0.0
            price * item.bonusQuantity
        }
        val cartBonifiableAmount = calculateCartBonifiableAmount()
        val displayedBonifiableAmount = java.math.BigDecimal.valueOf(cartBonifiableAmount)
            .setScale(2, java.math.RoundingMode.HALF_EVEN)
        binding?.clientProductsBonusPanel?.visibility = View.VISIBLE
        container.removeAllViews()
        binding?.clientProductsDemonstrativeToggle?.setText(
            if (demonstrativeVisible) R.string.hide_bonus_demonstrative
            else R.string.show_bonus_demonstrative
        )

        val demonstrativeMetrics = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            visibility = if (demonstrativeVisible) View.VISIBLE else View.GONE
        }
        val userId = SessionManager.currentUserId(requireContext())
        val history = demonstrativeSummary
            ?: BonusSummaryService.loadCached(requireContext(), userId)
            ?: BonusSummaryService.Summary(0.0, 0.0, 0.0)
        if (demonstrativeSummary == null && !demonstrativeLoading && userId.isNotBlank()) {
            demonstrativeLoading = true
            val appContext = requireContext().applicationContext
            kotlin.concurrent.thread {
                val loaded = runCatching { BonusSummaryService.load(appContext, userId) }.getOrNull()
                Handler(Looper.getMainLooper()).post {
                    demonstrativeLoading = false
                    if (loaded != null && isAdded && binding != null) {
                        demonstrativeSummary = loaded
                        updateBonusSummary()
                    }
                }
            }
        }
        demonstrativeMetrics.addView(createBonusMetric(
            "Saldo (demonstrativo)",
            formatCurrency(history.balance.toString()),
            R.color.text_primary
        ))
        demonstrativeMetrics.addView(createBonusMetric(
            "A bonificar (demonstrativo)",
            formatCurrency(history.toBonify.toString()),
            R.color.achieved_blue
        ))
        demonstrativeMetrics.addView(createBonusMetric(
            "Bonificado (demonstrativo)",
            formatCurrency(history.bonified.toString()),
            R.color.financial_red_text
        ))
        container.addView(demonstrativeMetrics)

        val cartMetrics = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
        }
        cartMetrics.addView(createBonusMetric(
            "Bonificado no carrinho",
            formatCurrency(cartBonusAmount.toString()),
            R.color.financial_red_text
        ))
        cartMetrics.addView(createBonusMetric(
            "Bonificar no carrinho",
            formatCurrency(displayedBonifiableAmount.toPlainString()),
            if (displayedBonifiableAmount.signum() < 0) R.color.financial_red_text else R.color.achieved_blue
        ))
        container.addView(cartMetrics)
    }

    private fun calculateCartBonifiableAmount(): Double {
        var configuredAmount = 0.0

        cartItems
            .filter { it.productGroupId.isNotBlank() }
            .groupBy { it.productGroupId }
            .forEach { (groupId, groupItems) ->
                val soldQuantity = groupItems.sumOf { it.soldQuantity }
                val currentBonusQuantity = groupItems.sumOf { it.bonusQuantity }
                val limit = BonusConfigurationService.getBonusLimit(
                    requireContext(),
                    regionId,
                    groupId,
                    soldQuantity
                ) ?: return@forEach

                val soldAmount = groupItems.sumOf { item ->
                    val price = item.price.replace(",", ".").toDoubleOrNull() ?: 0.0
                    price * item.soldQuantity
                }
                configuredAmount += BonusConfigurationService.calculateBonifiableAmount(
                    soldQuantity,
                    soldAmount,
                    currentBonusQuantity,
                    limit.bonusPercent
                )
            }

        return configuredAmount
    }

    private fun createBonusMetric(
        label: String,
        value: String,
        valueColor: Int,
        fullWidth: Boolean = false
    ): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = if (fullWidth) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            radius = dp(10).toFloat()
            setCardBackgroundColor(resources.getColor(R.color.login_background, requireContext().theme))
        }
        val content = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        content.addView(TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
        })
        content.addView(TextView(requireContext()).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resources.getColor(valueColor, requireContext().theme))
        })
        card.addView(content)
        return card
    }

    private fun showCart() {
        val items = ClientCartService.load(requireContext(), clientId)
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), "Carrinho vazio.", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        items.forEach { item ->
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                orientation = LinearLayout.VERTICAL
            }
            row.addView(TextView(requireContext()).apply {
                text = "${item.description}\nCódigo: ${item.productCode}"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
            })
            row.addView(TextView(requireContext()).apply {
                text = "Venda: ${formatQuantity(item.soldQuantity)}  |  Bonificação: ${formatQuantity(item.bonusQuantity)}"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, requireContext().theme))
            })
            val remove = com.google.android.material.button.MaterialButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { topMargin = dp(6) }
                text = "Remover do carrinho"
                isAllCaps = false
                setTextColor(resources.getColor(R.color.white, requireContext().theme))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.financial_red, requireContext().theme)
                )
                cornerRadius = dp(8)
                setOnClickListener {
                    ClientCartService.remove(requireContext(), clientId, item.productCode)
                    updateCartButton()
                    showFilteredProducts(
                        binding?.clientProductsSearch?.text?.toString().orEmpty(),
                        append = false
                    )
                    Toast.makeText(requireContext(), "Item removido.", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(remove)
            container.addView(row)
        }
        val scroll = androidx.core.widget.NestedScrollView(requireContext()).apply {
            addView(container)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Carrinho")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun generateOrderDraft() {
        try {
            val items = ClientCartService.load(requireContext(), clientId)
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "Adicione produtos antes de finalizar o pedido.", Toast.LENGTH_SHORT).show()
                return
            }

            var saleTotal = 0.0
            val discountPercent = getClientDiscountPercent()
            val company = getClientCompany()
            val clientsJson = requireContext().getSharedPreferences("forca_vendas", 0)
                .getString("clients_synced_json", "[]") ?: "[]"
            val clientJson = try {
                org.json.JSONArray(clientsJson).let { array ->
                    (0 until array.length()).map { array.getJSONObject(it) }
                        .firstOrNull { it.optString("EntidadeId") == clientId }
                }
            } catch (_: Exception) { null }
            val clientUf = clientJson?.optString("EntidadeUF").orEmpty()
            val activityOperationId = clientJson?.optString("EntidadeRamoAtividadeId").orEmpty()
            val operation = if (company != null) {
                OrderOperationLocalRepository(requireContext()).find(
                    companyId = company.id,
                    activityOperationId = activityOperationId,
                    clientUf = clientUf,
                    companyUf = company.uf
                )
            } else null
            val productsByCode = ProductLocalRepository(requireContext()).getAll().associateBy { it.code }
            items.forEach { item ->
                val tableUnitPrice = item.price.replace(",", ".").toDoubleOrNull() ?: 0.0
                val unitPrice = if (discountPercent > 0.0 && discountPercent < 100.0) {
                    java.math.BigDecimal.valueOf(tableUnitPrice)
                        .multiply(
                            java.math.BigDecimal.ONE.subtract(
                                java.math.BigDecimal.valueOf(discountPercent)
                                    .divide(java.math.BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP)
                            )
                        )
                        .setScale(4, java.math.RoundingMode.HALF_UP)
                        .toDouble()
                } else {
                    tableUnitPrice
                }
                val saleAmount = java.math.BigDecimal.valueOf(unitPrice)
                    .multiply(java.math.BigDecimal.valueOf(item.soldQuantity))
                    .setScale(2, java.math.RoundingMode.DOWN)
                val ipiPercent = productsByCode[item.productCode]?.ipiPercent
                    ?.replace(",", ".")
                    ?.toDoubleOrNull()
                    ?: 0.0
                val itemIpiPercent = if (
                    operation?.natopIpi.equals("T", ignoreCase = true) &&
                    ipiPercent > 0.0
                ) ipiPercent else 0.0
                val taxRates = if (company != null && operation != null) {
                    TaxGroupService.find(
                        context = requireContext(),
                        companyId = company.id,
                        taxGroupId = productsByCode[item.productCode]?.taxGroupId.orEmpty(),
                        consumerFinal = clientJson?.optString("EntidadeConsumidorFinal").orEmpty(),
                        outgoingIcmsType = operation.natopIcms,
                        destinationUf = clientUf,
                        originUf = company.uf,
                        activityOperationId = activityOperationId
                    )
                } else null
                val taxedItem = org.json.JSONObject().apply {
                    put("ItemPedidoQtde", item.soldQuantity)
                    put("ItemPedidoVrlUnitario", unitPrice)
                    put("ItemPedidoVlrOriginal", tableUnitPrice)
                    put("ItemPedidoVltTotal", saleAmount.toDouble())
                    put("ItemPedidoPerIPI", itemIpiPercent)
                    put("ItemPedidoTipoIPI", operation?.natopIpi.orEmpty())
                    put("ItemPedidoTipoICMS", operation?.natopIcms.orEmpty())
                    put("ItemPedidoPerICMSProduto", taxRates?.perIcmsProduct ?: "0")
                    put("ItemPedidoPerReducaoICMS", taxRates?.perReduction ?: "0")
                    put("ItemPedidoPerMVA", taxRates?.mva ?: "0")
                    put("ItemPedidoPerICMSEstado", taxRates?.perIcmsState ?: "0")
                }
                saleTotal = java.math.BigDecimal.valueOf(saleTotal)
                    .add(TaxedMoney.itemValues(taxedItem).line)
                    .setScale(2, java.math.RoundingMode.DOWN)
                    .toDouble()
            }
            val orderTotal = saleTotal
            if (!orderTotal.isFinite() || orderTotal < 0.0) {
                Toast.makeText(requireContext(), "Não foi possível calcular o total do pedido.", Toast.LENGTH_LONG).show()
                return
            }
            showPaymentConditionDialog(items, orderTotal)
        } catch (exception: Exception) {
            Log.e("ClientProductsFragment", "Erro ao abrir finalização do pedido", exception)
            Toast.makeText(requireContext(), "Não foi possível abrir a finalização do pedido.", Toast.LENGTH_LONG).show()
        }
    }

    private fun getSavedOfflinePayment(): Pair<String, String> {
        val mobileId = editOrderMobileId.toLongOrNull() ?: return Pair("", "")
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val drafts = try {
            org.json.JSONArray(preferences.getString("order_drafts_json", "[]") ?: "[]")
        } catch (_: Exception) {
            return Pair("", "")
        }
        for (index in 0 until drafts.length()) {
            val order = drafts.optJSONObject(index)?.optJSONObject("sdtPedidoAppVenda")
                ?: continue
            if (order.optLong("PedidoMobId", 0L) == mobileId) {
                return Pair(
                    order.optString("PedidoCondicaoPgtoId"),
                    order.optString("PedidoTipoVenda")
                )
            }
        }
        return Pair("", "")
    }

    private fun showPaymentConditionDialog(
        items: List<ClientCartService.CartItem>,
        orderTotal: Double
    ) {
        val savedPaymentBeforeValidation = getSavedOfflinePayment()
        val allConditions = PaymentConditionService.load(requireContext())
        val available = allConditions
            .filter { orderTotal >= it.minimumValue }
            .sortedWith(
                compareBy<PaymentConditionService.Condition> {
                    it.description.lowercase(java.util.Locale("pt", "BR"))
                }.thenBy { it.minimumValue }
            )
            .toMutableList()
        if (savedPaymentBeforeValidation.first.isNotBlank() &&
            available.none { it.id == savedPaymentBeforeValidation.first }
        ) {
            allConditions.firstOrNull { it.id == savedPaymentBeforeValidation.first }
                ?.let { available.add(0, it) }
        }

        if (available.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Nenhuma condição de pagamento disponível para o valor deste pedido. Sincronize as condições de pagamento.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = available.map { condition ->
            val parcelLabel = if (condition.installments == 1) "1 parcela" else "${condition.installments} parcelas"
            "${condition.description} - $parcelLabel"
        }
        var selectedIndex = 0
        val savedPayment = getSavedOfflinePayment()
        val savedConditionIndex = available.indexOfFirst { condition ->
            savedPayment.first.isNotBlank() && condition.id == savedPayment.first
        }.takeIf { it >= 0 } ?: 0
        selectedIndex = savedConditionIndex

        val company = getClientCompany()
        if (company == null) {
            Toast.makeText(
                requireContext(),
                "A empresa vinculada ao cliente não foi encontrada. Sincronize as empresas e os clientes.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        container.addView(TextView(requireContext()).apply {
            text = "Pedido: ${formatCurrency(orderTotal.toString())}"
            setTypeface(typeface, Typeface.BOLD)
        })

        val spinner = Spinner(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(12) }
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            setSelection(savedConditionIndex)
        }
        container.addView(spinner)

        val saleTypes = listOf(
            "BO - BOLETO",
            "VI - VISTA",
            "PA - PAGAMENTO ANTECIPADO",
            "CQ - CHEQUE",
            "DI - DINHEIRO",
            "TR - TRANSFERENCIA",
            "PX - DEPÓSITO"
        )
        val savedSaleType = savedPayment.second
        var selectedSaleType = savedSaleType.ifBlank { "BO" }
        val saleTypeSpinner = Spinner(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(12) }
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                saleTypes
            )
            val savedSaleTypePosition = saleTypes.indexOfFirst {
                it.substringBefore(" -").trim() == savedSaleType
            }.takeIf { it >= 0 } ?: 0
            setSelection(savedSaleTypePosition)
        }
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSaleType = saleTypes[position].substringBefore(" -").trim()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        container.addView(saleTypeSpinner)

        val installmentsView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(installmentsView)

        fun updateInstallmentsPreview() {
            val condition = available[selectedIndex]
            val installmentCount = condition.installments.coerceAtLeast(1)
            val total = java.math.BigDecimal.valueOf(orderTotal).setScale(2, java.math.RoundingMode.HALF_UP)
            val installmentValue = total.divide(
                java.math.BigDecimal(installmentCount),
                2,
                java.math.RoundingMode.DOWN
            )
            val lastValue = total.subtract(installmentValue.multiply(java.math.BigDecimal(installmentCount - 1)))
            val dateFormatter = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR"))
            val preview = buildString {
                append("${installmentCount} Parcela${if (installmentCount == 1) "" else "s"}")
                append(" / Prazo Medio ${condition.averageTerm} Dia${if (condition.averageTerm == 1) "" else "s"}\n")
                for (index in 1..installmentCount) {
                    val value = if (index == installmentCount) lastValue else installmentValue
                    val dueDays = condition.dueDays.getOrElse(index - 1) {
                        condition.averageTerm
                    }.coerceAtLeast(0)
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.DAY_OF_MONTH, dueDays)
                    append("${index}ª ${formatCurrency(value.toPlainString())} - ${dateFormatter.format(calendar.time)} (+$dueDays dias)")
                    if (index < installmentCount) append("\n")
                }
            }
            installmentsView.text = preview
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedIndex = position
                updateInstallmentsPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateInstallmentsPreview()

        AlertDialog.Builder(requireContext())
            .setTitle("Condição de pagamento")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar") { _, _ ->
                finalizeOrder(items, available[selectedIndex], selectedSaleType, company)
            }
            .show()
    }

    private fun getClientCompany(): CompanyLocalRepository.Company? {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val clientsJson = preferences.getString("clients_synced_json", "[]") ?: "[]"
        val clientId = arguments?.getString("client_id").orEmpty()
        val clientJson = try {
            org.json.JSONArray(clientsJson).let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }
                    .firstOrNull { it.optString("EntidadeId") == clientId }
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val companyId = listOf(
            "EmpresaClienteId",
            "EntidadeEmpresaId",
            "EntidadeEmpresaERPId",
            "EmpresaId",
            "EntidadeEmpresa"
        )
            .asSequence()
            .map { clientJson.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "0" }
            ?: return null

        return CompanyLocalRepository(requireContext()).findById(companyId)
    }

    private fun getClientDiscountPercent(): Double {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        return try {
            val clients = org.json.JSONArray(
                preferences.getString("clients_synced_json", "[]") ?: "[]"
            )
            val client = (0 until clients.length())
                .map { clients.getJSONObject(it) }
                .firstOrNull { it.optString("EntidadeId") == clientId }
            client?.optString("EntidadePerDescoCliente")
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?.coerceIn(0.0, 100.0) ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun finalizeOrder(
        items: List<ClientCartService.CartItem>,
        paymentCondition: PaymentConditionService.Condition,
        saleType: String,
        company: CompanyLocalRepository.Company
    ) {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation || hasCoarseLocation) {
            saveOrderDraft(items, paymentCondition, saleType, getCurrentLocation(), company)
        } else {
            pendingFinalizeItems = items
            pendingFinalizeCondition = paymentCondition
            pendingFinalizeSaleType = saleType
            pendingFinalizeCompany = company
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun getCurrentLocation(): String {
        val locationManager = requireContext().getSystemService(
            android.content.Context.LOCATION_SERVICE
        ) as? LocationManager ?: return ""

        val hasFineLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) return ""

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val location = providers.asSequence()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        return location?.let { "${it.latitude.toString()},${it.longitude.toString()}" }.orEmpty()
    }

    private fun saveOrderDraft(
        items: List<ClientCartService.CartItem>,
        paymentCondition: PaymentConditionService.Condition,
        saleType: String,
        location: String,
        company: CompanyLocalRepository.Company
    ) {
        try {
            saveOrderDraftSafely(items, paymentCondition, saleType, location, company)
        } catch (exception: Exception) {
            Log.e("ClientProductsFragment", "Erro ao finalizar pedido", exception)
            if (exception is OperationNotFoundException) {
                showOperationNotFoundDialog(exception.diagnosticJson)
                return
            }
            Toast.makeText(
                requireContext(),
                "Não foi possível finalizar o pedido: ${exception.message ?: "erro inesperado"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showOperationNotFoundDialog(diagnosticJson: String) {
        val prettyJson = try {
            org.json.JSONObject(diagnosticJson).toString(2)
        } catch (_: Exception) {
            diagnosticJson
        }
        val scroll = androidx.core.widget.NestedScrollView(requireContext())
        val text = TextView(requireContext()).apply {
            text = prettyJson
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        scroll.addView(text)

        AlertDialog.Builder(requireContext())
            .setTitle("Tipo de operação não encontrado")
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Copiar JSON") { _, _ ->
                val clipboard = requireContext().getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE
                ) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Diagnóstico da operação fiscal",
                        prettyJson
                    )
                )
                Toast.makeText(requireContext(), "JSON copiado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveOrderDraftSafely(
        items: List<ClientCartService.CartItem>,
        paymentCondition: PaymentConditionService.Condition,
        saleType: String,
        location: String,
        company: CompanyLocalRepository.Company
    ) {
        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        val draftsJson = preferences.getString("order_drafts_json", "[]") ?: "[]"
        val drafts = try { org.json.JSONArray(draftsJson) } catch (_: Exception) { org.json.JSONArray() }
        val draftItems = org.json.JSONArray()
        val productsByCode = ProductLocalRepository(requireContext()).getAll().associateBy { it.code }
        val clientsJson = preferences.getString("clients_synced_json", "[]") ?: "[]"
        val clientJson = try {
            org.json.JSONArray(clientsJson).let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }
                    .firstOrNull { it.optString("EntidadeId") == clientId }
            }
        } catch (_: Exception) { null }
        var itemSequence = 1
        var saleTotal = 0.0
        val clientDiscountPercent = clientJson?.optString("EntidadePerDescoCliente")
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 100.0) ?: 0.0

        fun addItem(item: ClientCartService.CartItem, quantity: Double, accepted: Boolean) {
            if (quantity <= 0.0) return
            val tableUnitPrice = item.price.replace(",", ".").toDoubleOrNull() ?: 0.0
            val calculatedUnitPrice = if (accepted) {
                tableUnitPrice
            } else if (clientDiscountPercent <= 0.0 || clientDiscountPercent >= 100.0) {
                tableUnitPrice
            } else {
                val discountFactor = java.math.BigDecimal.ONE.subtract(
                    java.math.BigDecimal.valueOf(clientDiscountPercent)
                        .divide(java.math.BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP)
                )
                java.math.BigDecimal.valueOf(tableUnitPrice)
                    .multiply(discountFactor)
                    .setScale(4, java.math.RoundingMode.HALF_UP)
                    .toDouble()
            }
            val lineOriginal = tableUnitPrice
            val lineTotal = java.math.BigDecimal.valueOf(calculatedUnitPrice)
                .multiply(java.math.BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.DOWN)
                .toDouble()
            val product = productsByCode[item.productCode]
            val clientUf = clientJson?.optString("EntidadeUF").orEmpty()
            val activityOperationId = clientJson?.optString("EntidadeRamoAtividadeId").orEmpty()
            val operation = OrderOperationLocalRepository(requireContext()).find(
                companyId = company.id,
                activityOperationId = activityOperationId,
                clientUf = clientUf,
                companyUf = company.uf
            )
            if (operation == null) {
                val operationStateType =
                    if (clientUf.uppercase().trim() == company.uf.uppercase().trim()) "D" else "F"
                val specificUfSearch = org.json.JSONObject().apply {
                    put("TipoOperacaoPedidoSomenteUF", clientUf)
                    put("TipoOperacaoPedidoTipoEstado", operationStateType)
                    put("RamoAtividadeOperacaoId", activityOperationId)
                }
                val specificUfFallbackRamo = org.json.JSONObject().apply {
                    put("TipoOperacaoPedidoSomenteUF", clientUf)
                    put("TipoOperacaoPedidoTipoEstado", operationStateType)
                    put("RamoAtividadeOperacaoId", "0")
                }
                val emptyUfFallbackRamo = org.json.JSONObject().apply {
                    put("TipoOperacaoPedidoSomenteUF", "")
                    put("TipoOperacaoPedidoTipoEstado", operationStateType)
                    put("RamoAtividadeOperacaoId", "0")
                }
                val diagnosticJson = org.json.JSONObject().apply {
                    put("erro", "Tipo de operação não encontrado")
                    put("PedidoEmpresaId", company.id)
                    put("EntidadeRamoAtividadeId", activityOperationId)
                    put("EntidadeUF", clientUf)
                    put("EmpresaUF", company.uf)
                    put("TipoOperacaoPedidoTipoEstadoExecutado", operationStateType)
                    put("filtrosComuns", org.json.JSONObject().apply {
                        put("PedidoEmpresaId", company.id)
                        put("TipoOperacaoPedidoEmpresaId", company.id)
                        put("EntidadeRamoAtividadeId", activityOperationId)
                        put("RamoAtividadeOperacaoId", activityOperationId)
                    })
                    put("buscasExecutadas", org.json.JSONArray().apply {
                        put(specificUfSearch)
                        put(specificUfFallbackRamo)
                        put(emptyUfFallbackRamo)
                    })
                }.toString()
                throw OperationNotFoundException(
                    diagnosticJson,
                    "Tipo de operação não encontrado. JSON de diagnóstico disponível no diálogo."
                )
            }
            val consumerFinal = clientJson?.optString("EntidadeConsumidorFinal").orEmpty()
            val taxRates = TaxGroupService.find(
                context = requireContext(),
                companyId = company.id,
                taxGroupId = product?.taxGroupId.orEmpty(),
                consumerFinal = consumerFinal,
                outgoingIcmsType = operation?.natopIcms.orEmpty(),
                destinationUf = clientUf,
                originUf = company.uf,
                activityOperationId = activityOperationId
            )
            val ipiPercent = product?.ipiPercent
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?: 0.0
            val itemIpiPercent = if (
                operation?.natopIpi.equals("T", ignoreCase = true) &&
                ipiPercent > 0.0
            ) {
                ipiPercent
            } else {
                0.0
            }
            val draftItem = org.json.JSONObject().apply {
                put("ItemPedidoId", itemSequence++)
                put("ItemPedidoPedidoMobId", 0)
                put("ItemPedidoPedidoMobCancelado", 0)
                put("ItemPedidoPedidoMobRegiaoId", 0)
                put("ItemPedidoProdutoId", product?.erpId?.toIntOrNull() ?: 0)
                put("ItemPedidoProdutoCodigo", item.productCode)
                put("ItemPedidoProdutoDescricao", item.description)
                put("ItemPedidoProdutoGrupoId", item.productGroupId.toIntOrNull() ?: 0)
                put("ItemPedidoProdutoFoto", item.photoUrl)
                put("ItemPedidoProdutoPesoLiq", product?.netWeight?.replace(",", ".")?.toDoubleOrNull() ?: 0.0)
                put("ItemPedidoQtde", quantity)
                put("ItemPedidoAceite", if (accepted) 1 else 0)
                put("ItemPedidoVlrOriginal", lineOriginal)
                put("ItemPedidoVlrBruto", lineOriginal)
                put("ItemPedidoVrlUnitario", calculatedUnitPrice)
                put("ItemPedidoVltTotal", lineTotal)
                put("ItemPedidoPerIPI", itemIpiPercent)
                put("ItemPedidoTipoICMS", operation?.natopIcms.orEmpty())
                put("ItemPedidoTipoIPI", operation?.natopIpi.orEmpty())
                put("ItemPedidoPerICMSProduto", taxRates?.perIcmsProduct ?: "0")
                put("ItemPedidoPerReducaoICMS", taxRates?.perReduction ?: "0")
                put("ItemPedidoPerMVA", taxRates?.mva ?: "0")
                put("ItemPedidoPerICMSEstado", taxRates?.perIcmsState ?: "0")
                put("ItemPedidoPerFCP", taxRates?.perFcp ?: "0")
                put(
                    "ItemPedidoTipoOperacaoPedId",
                    if (accepted) operation?.bonusOperationId.orEmpty()
                    else operation?.erpId.orEmpty()
                )
                put("ItemPedidoRevestimentoId", 999999)
                put("ItemPedidoTipoRevestimentoId", 1)
                put("ItemPedidoAluminioAceite", if (accepted) 1 else 0)
                put("ItemPedidoProdutoCombo", 0)
                for (field in listOf(
                    "ItemPedidoPerDescComercial", "ItemPedidoPerDescPromocional",
                    "ItemPedidoPerDescOutros1", "ItemPedidoPerDescOutros2",
                    "ItemPedidoPerDescOutros3", "ItemPedidoPerDescOutros4",
                    "ItemPedidoPerDescExtra", "ItemPedidoPerAcrescimo1",
                    "ItemPedidoPerAcrescimo2", "ItemPedidoPerFreteCIF",
                    "ItemPedidoPerFreteFOB", "ItemPedidoPerFreteComp",
                    "ItemPedidoPerVerbaPropaganda", "ItemPedidoPerBonificacao",
                    "ItemPedidoPerDescFinanceiro", "ItemPedidoPerComissao"
                )) put(field, 0)
                put("ItemPedidoPersonalizaProdutoPessoaId", 0)
            }
            draftItems.put(draftItem)
            if (!accepted) {
                saleTotal = java.math.BigDecimal.valueOf(saleTotal)
                    .add(TaxedMoney.itemValues(draftItem).line)
                    .setScale(2, java.math.RoundingMode.DOWN)
                    .toDouble()
            }
        }

        items.forEach { item ->
            addItem(item, item.soldQuantity, accepted = false)
            addItem(item, item.bonusQuantity, accepted = true)
        }

        val userId = preferences.getString("user_id", "").orEmpty()
        val license = preferences.getString("license", "").orEmpty()
        val username = preferences.getString("username", "Usuário") ?: "Usuário"
        val now = java.util.Date()
        val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val editingMobileId = editOrderMobileId.toLongOrNull()
        val mobileId = editingMobileId ?: run {
            val mobileCodeFormatter =
                java.text.SimpleDateFormat("ddMMHHmm", java.util.Locale("pt", "BR"))
            val usedMobileIds = mutableSetOf<Long>()
            for (index in 0 until drafts.length()) {
                val existingOrder = drafts.optJSONObject(index)
                    ?.optJSONObject("sdtPedidoAppVenda")
                if (existingOrder != null) {
                    usedMobileIds.add(existingOrder.optLong("PedidoMobId", 0L))
                }
            }
            val calendar = java.util.Calendar.getInstance()
            var generated: Long
            do {
                generated = mobileCodeFormatter.format(calendar.time).toLongOrNull() ?: 0L
                if (generated !in usedMobileIds) break
                calendar.add(java.util.Calendar.MINUTE, 1)
            } while (true)
            generated
        }
        val clientName = arguments?.getString("client_name").orEmpty()
            .ifBlank { clientJson?.optString("EntidadeNomeRazao").orEmpty() }
        val fantasyName = clientJson?.optString("EntidadeNomeFantasia").orEmpty()
        val cnpjCpf = clientJson?.optString("EntidadeCPFCNPJ").orEmpty()
        val orderDate = dateFormatter.format(now)
        val orderTotal = saleTotal
        val installmentCount = paymentCondition.installments.coerceAtLeast(1)
        val installmentTotal = java.math.BigDecimal.valueOf(orderTotal).setScale(2, java.math.RoundingMode.HALF_UP)
        val installmentBase = installmentTotal.divide(
            java.math.BigDecimal(installmentCount),
            2,
            java.math.RoundingMode.DOWN
        )
        val installmentLast = installmentTotal.subtract(
            installmentBase.multiply(java.math.BigDecimal(installmentCount - 1))
        )
        val installmentDateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val installmentsJson = org.json.JSONArray()
        var existingErpId = 0L
        var existingStatus = "EM CARTEIRA"
        if (editingMobileId != null) {
            var erpId = 0L
            for (index in 0 until drafts.length()) {
                val existingOrder = drafts.optJSONObject(index)
                    ?.optJSONObject("sdtPedidoAppVenda")
                if (existingOrder?.optLong("PedidoMobId", 0L) == editingMobileId) {
                    erpId = existingOrder.optLong("PedidoERPId", 0L)
                    existingErpId = erpId
                    existingStatus = if (erpId > 0L) "ENVIADO AO ERP"
                    else existingOrder.optString("PedidoStatus", "EM CARTEIRA")
                    break
                }
            }
        }
        for (index in 1..installmentCount) {
            val dueDays = paymentCondition.dueDays.getOrElse(index - 1) {
                paymentCondition.averageTerm
            }.coerceAtLeast(0)
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.DAY_OF_MONTH, dueDays)
            val installmentValue = if (index == installmentCount) installmentLast else installmentBase
            installmentsJson.put(org.json.JSONObject().apply {
                put("PedidoParcelaNro", index)
                put("PedidoParcelaVlr", installmentValue.toString())
                put("PedidoParcelaDtVencimento", installmentDateFormatter.format(calendar.time))
                put("PedidoParcelaDias", dueDays)
            })
        }
        val pedido = org.json.JSONObject().apply {
            put("PedidoMobId", mobileId)
            put("PedidoERPId", existingErpId)
            put("PedidoLicencaId", license.toIntOrNull() ?: 1)
            put("PedidoEmpresaId", company.id.toIntOrNull() ?: 0)
            put("PedidoEmpresaNome", company.name)
            put("PedidoEntidadeMobId", 0)
            put("PedidoEntidadeId", clientId.toIntOrNull() ?: 0)
            put("PedidoEntidadeNome", clientName)
            put("PedidoEntidadeNomeRazao", clientJson?.optString("EntidadeNomeRazao").orEmpty())
            put("PedidoTipoFrete", "")
            put("PedidoEntidadeNomeFantasia", fantasyName)
            put("PedidoEntidadeRegiaoid", regionId.toIntOrNull() ?: 0)
            put("PedidoEntidadeCPFCNPJ", cnpjCpf)
            put("PedidoPercDesconto", 0)
            put("PedidoDtEmissao", orderDate)
            put("PedidoDtCadastro", orderDate)
            put("PedidoRepresentanteMobId", 0)
            put("PedidoRepresentanteId", userId.toIntOrNull() ?: 0)
            put("PedidoRepresentanteNome", username)
            put("PedidoObs", "")
            put("PedidoFaturado", false)
            put("PedidoCancelado", 0)
            put("PedidoDtFaturamento", "0000-00-00")
            put("PedidoStatus", existingStatus)
            put("PedidoCondicaoPgtoId", paymentCondition.id.toIntOrNull() ?: 0)
            put("PedidoCondicaoPgtoDescricao", paymentCondition.description)
            put("PedidoCondicaoPgtoPrazoMedio", paymentCondition.averageTerm)
            put("PedidoTipoVenda", saleType)
            put("PedidoParcelas", installmentsJson)
            put("PedidoTipoPedidoId", 10)
            put("PedidoVlrTotal", orderTotal)
            put("PedidoLocation", location)
            put("PedidoPrecisao", "100.00")
            put("PedidoNroPedidoRepresentante", "")
            put("PedidoNroPedidoCliente", "")
            put("DataCustoId", 0)
            put("PedidoPerDescontoNF", 0)
            put("ItemPedido", draftItems)
        }
        val draft = org.json.JSONObject().apply {
            put("sdtPedidoAppVenda", pedido)
        }
        if (editingMobileId != null) {
            val updatedDrafts = org.json.JSONArray()
            for (index in 0 until drafts.length()) {
                val existing = drafts.optJSONObject(index) ?: continue
                val existingOrder = existing.optJSONObject("sdtPedidoAppVenda")
                if (existingOrder?.optLong("PedidoMobId", 0L) != editingMobileId) {
                    updatedDrafts.put(existing)
                }
            }
            updatedDrafts.put(draft)
            preferences.edit().putString("order_drafts_json", updatedDrafts.toString()).apply()
        } else {
            drafts.put(draft)
            preferences.edit().putString("order_drafts_json", drafts.toString()).apply()
        }
        ClientCartService.save(requireContext(), clientId, emptyList())
        updateCartButton()
        Toast.makeText(requireContext(), "Pedido salvo como rascunho offline.", Toast.LENGTH_LONG).show()
        try {
            val navController = findNavController()
            val returnedToOrders = editOrderMobileId.isNotBlank() &&
                navController.popBackStack(R.id.OrdersFragment, false)
            if (!returnedToOrders) navController.navigate(R.id.OrdersFragment)
        } catch (exception: Exception) {
            Log.e("ClientProductsFragment", "Pedido salvo, mas não foi possível abrir Pedidos", exception)
            Toast.makeText(
                requireContext(),
                "Pedido salvo, mas ocorreu um erro ao abrir Pedidos.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun formatCurrency(value: String): String {
        val number = value.replace(",", ".").toDoubleOrNull()
        return number?.let {
            java.text.NumberFormat.getCurrencyInstance(java.util.Locale("pt", "BR")).format(it)
        } ?: value
    }

    private fun loadPhoto(url: String, imageView: ImageView) {
        if (url.isBlank()) return
        thread {
            try {
                val bitmap = ProductPhotoCacheService.download(requireContext(), url)
                Handler(Looper.getMainLooper()).post {
                    if (isAdded && imageView.tag == url && bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
