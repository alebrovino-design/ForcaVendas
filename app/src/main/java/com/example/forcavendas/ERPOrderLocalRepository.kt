package com.example.forcavendas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

class ERPOrderLocalRepository(context: Context) {
    data class ERPOrder(
        val mobileId: Long,
        val erpId: Long,
        val clientId: String,
        val clientName: String,
        val regionId: String,
        val status: String,
        val total: Double,
        val emissionDate: String,
        val json: JSONObject,
        val cachedWeight: Double,
        val cachedCompanyName: String,
        val cachedRegionDescription: String
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "forca_vendas_erp_orders.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE erp_orders (
                    mobile_id INTEGER PRIMARY KEY,
                    erp_id INTEGER NOT NULL,
                    client_id TEXT NOT NULL,
                    client_name TEXT NOT NULL,
                    region_id TEXT,
                    status TEXT,
                    total REAL,
                    emission_date TEXT,
                    json_data TEXT NOT NULL,
                    cached_weight REAL,
                    cached_company_name TEXT,
                    cached_region_description TEXT,
                    synced_at INTEGER,
                    created_at INTEGER DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
            
            db.execSQL("""
                CREATE TABLE erp_sync_metadata (
                    id INTEGER PRIMARY KEY,
                    representante_id TEXT NOT NULL UNIQUE,
                    last_sync_timestamp INTEGER,
                    last_sync_count INTEGER,
                    updated_at INTEGER DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE erp_orders ADD COLUMN cached_weight REAL")
                db.execSQL("ALTER TABLE erp_orders ADD COLUMN cached_company_name TEXT")
                db.execSQL("ALTER TABLE erp_orders ADD COLUMN cached_region_description TEXT")
            }
        }
    }

    private val appContext = context.applicationContext
    private val database = Database(appContext)

    fun saveOrders(representanteId: String, orders: JSONArray): Int {
        val db = database.writableDatabase
        val companyNames = CompanyLocalRepository(appContext).getAll()
            .associate { it.id to it.name }
        val regionNames = loadRegionNames()
        val operationIpiTypes = OrderOperationLocalRepository(appContext).loadIpiTypes()
            .mapKeys { normalizeOperationId(it.key) }
        var savedCount = 0
        db.beginTransaction()
        try {
            for (index in 0 until orders.length()) {
                val responseItem = parseObjectValue(orders.opt(index)) ?: continue
                val item = JSONObject(findOrderPayload(responseItem).toString())
                removeDuplicateInstallments(item)
                restoreMissingIpiTypes(item, operationIpiTypes)
                val responseMobileId = firstLongInOrder(
                    item,
                    "PedidoMobId",
                    "PedidoMobileId"
                ).takeIf { it > 0L } ?: firstLongInOrder(
                    responseItem,
                    "PedidoMobId",
                    "PedidoMobileId"
                )
                val erpId = firstLongInOrder(
                    item,
                    "PedidoERPId",
                    "PedidoErpId"
                ).takeIf { it > 0L } ?: firstLongInOrder(
                    responseItem,
                    "PedidoERPId",
                    "PedidoErpId"
                )
                val mobileId = responseMobileId.takeIf { it > 0L }
                    ?: erpId.takeIf { it > 0L }?.unaryMinus()
                    ?: 0L

                if (mobileId == 0L || erpId == 0L) {
                    if (index == 0) {
                        android.util.Log.w(
                            "ERPOrderRepository",
                            "Registro sem IDs válidos. Campos recebidos: ${describeFields(responseItem)}"
                        )
                    }
                    continue
                }

                val clientId = firstString(item, "PedidoEntidadeId")
                val clientName = firstString(
                    item,
                    "PedidoEntidadeNome",
                    "PedidoEntidadeNomeRazao",
                    "PedidoEntidadeNomeFantasia"
                )
                val regionId = firstString(
                    item,
                    "PedidoEntidadeRegiaoid",
                    "PedidoEntidadeRegiaoId"
                )
                val status = firstString(item, "PedidoStatus").ifBlank { "Processado" }
                val total = firstDouble(item, "PedidoVlrTotal", "PedidoValorTotal")
                val emissionDate = firstString(item, "PedidoDtEmissao", "PedidoDtCadastro")
                val companyName = firstString(item, "PedidoEmpresaNome", "EmpresaNome")
                    .ifBlank { companyNames[firstString(item, "PedidoEmpresaId")].orEmpty() }
                val regionDescription = firstString(
                    item,
                    "PedidoEntidadeRegiaoDescricao",
                    "PedidoEntidadeRegiaoNome",
                    "PedidoRegiaoDescricao"
                ).ifBlank { regionNames[regionId].orEmpty() }

                val values = ContentValues().apply {
                    put("mobile_id", mobileId)
                    put("erp_id", erpId)
                    put("client_id", clientId)
                    put("client_name", clientName)
                    put("region_id", regionId)
                    put("status", status)
                    put("total", total)
                    put("emission_date", emissionDate)
                    put("json_data", JSONObject().apply {
                        put("sdtPedidoAppVenda", item)
                    }.toString())
                    put("cached_weight", calculateOrderWeight(item))
                    put("cached_company_name", companyName)
                    put("cached_region_description", regionDescription)
                    put("synced_at", System.currentTimeMillis())
                }

                val rowId = db.insertWithOnConflict(
                    "erp_orders",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (rowId != -1L) savedCount++
            }

            updateSyncMetadata(db, representanteId, orders.length())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return savedCount
    }

    private fun removeDuplicateInstallments(order: JSONObject) {
        listOf("PedidoParcelas", "PedidoParcelamento").forEach { name ->
            val key = findKey(order, name) ?: return@forEach
            val installments = order.optJSONArray(key) ?: return@forEach
            val seen = mutableSetOf<String>()
            val uniqueInstallments = JSONArray()

            for (index in 0 until installments.length()) {
                val value = installments.opt(index)
                val installment = parseObjectValue(value)
                if (installment == null) {
                    uniqueInstallments.put(value)
                    continue
                }

                val number = firstLong(installment, "PedidoParcelaNro")
                val identity = if (number > 0L) {
                    "number:$number"
                } else {
                    val dueDate = firstString(
                        installment,
                        "PedidoParcelaDtVencimento",
                        "PedidoParcelaDataVencimento"
                    )
                    val amount = firstString(installment, "PedidoParcelaVlr")
                    val days = firstString(installment, "PedidoParcelaDias")
                    "details:$dueDate|$amount|$days"
                }
                if (seen.add(identity)) uniqueInstallments.put(value)
            }

            if (uniqueInstallments.length() != installments.length()) {
                order.put(key, uniqueInstallments)
            }
        }
    }

    private fun restoreMissingIpiTypes(
        order: JSONObject,
        operationIpiTypes: Map<String, String>
    ) {
        val items = firstArray(order, "ItemPedido", "PedidoProdutos", "PedidoItens") ?: return
        val orderOperationId = firstString(
            order,
            "PedidoTipoOperacaoPedId",
            "PedidoTipoOperacaoERPId",
            "PedidoTipoOperacaoId"
        )

        for (index in 0 until items.length()) {
            val originalValue = items.opt(index)
            val item = parseObjectValue(originalValue) ?: continue
            if (firstString(item, "ItemPedidoTipoIPI").isNotBlank()) continue

            val operationId = firstString(
                item,
                "ItemPedidoTipoOperacaoPedId",
                "ItemPedidoTipoOperacaoId"
            ).ifBlank { orderOperationId }
            if (operationId.isBlank()) continue

            val ipiType = operationIpiTypes[normalizeOperationId(operationId)] ?: continue
            val ipiKey = findKey(item, "ItemPedidoTipoIPI") ?: "ItemPedidoTipoIPI"
            item.put(ipiKey, ipiType)
            if (originalValue !is JSONObject) items.put(index, item)
        }
    }

    private fun normalizeOperationId(value: String): String {
        val trimmed = value.trim()
        return runCatching {
            BigDecimal(trimmed.replace(",", ".")).stripTrailingZeros().toPlainString()
        }.getOrDefault(trimmed)
    }

    private fun findOrderPayload(value: JSONObject, depth: Int = 0): JSONObject {
        if (depth >= 4) return value

        var best = value
        var bestScore = orderIdScore(value)
        val keys = listOf("sdtPedidoAppVenda", "JSON", "Pedido", "PedidoVenda")

        for (key in keys) {
            val nested = parseObject(value, key) ?: continue
            val candidate = findOrderPayload(nested, depth + 1)
            val candidateScore = orderIdScore(candidate)
            if (candidateScore >= bestScore && candidateScore > 0) {
                best = candidate
                bestScore = candidateScore
            }
        }
        return best
    }

    private fun orderIdScore(json: JSONObject): Int {
        var score = 0
        if (firstLong(json, "PedidoMobId", "PedidoMobileId") > 0L) score++
        if (firstLong(json, "PedidoERPId", "PedidoErpId") > 0L) score++
        return score
    }

    private fun firstLongInOrder(json: JSONObject, vararg names: String, depth: Int = 0): Long {
        val direct = firstLong(json, *names)
        if (direct > 0L || depth >= 4) return direct

        listOf("sdtPedidoAppVenda", "JSON", "Pedido", "PedidoVenda").forEach { key ->
            val nested = parseObject(json, key) ?: return@forEach
            val value = firstLongInOrder(nested, *names, depth = depth + 1)
            if (value > 0L) return value
        }
        return direct
    }

    private fun parseObject(json: JSONObject, key: String): JSONObject? {
        val actualKey = findKey(json, key) ?: return null
        return parseObjectValue(json.opt(actualKey))
    }

    private fun parseObjectValue(value: Any?): JSONObject? {
        return when (value) {
            is JSONObject -> value
            is String -> value.trim().takeIf { it.startsWith("{") }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            else -> null
        }
    }

    private fun describeFields(json: JSONObject, prefix: String = "", depth: Int = 0): List<String> {
        val fields = mutableListOf<String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            fields.add(path)
            if (depth < 3 && normalizeKey(key) in setOf(
                    "sdtpedidoappvenda",
                    "json",
                    "pedido",
                    "pedidovenda"
                )
            ) {
                parseObject(json, key)?.let {
                    fields.addAll(describeFields(it, path, depth + 1))
                }
            }
        }
        return fields
    }

    private fun findKey(json: JSONObject, name: String): String? {
        val normalizedName = normalizeKey(name)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (normalizeKey(key) == normalizedName) return key
        }
        return null
    }

    private fun normalizeKey(value: String): String =
        value.filter(Char::isLetterOrDigit).lowercase()

    private fun firstString(json: JSONObject, vararg names: String): String {
        names.forEach { name ->
            val key = findKey(json, name) ?: return@forEach
            val value = json.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun firstLong(json: JSONObject, vararg names: String): Long {
        names.forEach { name ->
            val key = findKey(json, name) ?: return@forEach
            val value = json.opt(key)
            val parsed = when (value) {
                is Number -> value.toString().toLongOrNull()
                    ?: runCatching { BigDecimal(value.toString()).longValueExact() }.getOrNull()
                is String -> value.trim().toLongOrNull()
                    ?: runCatching { BigDecimal(value.trim()).longValueExact() }.getOrNull()
                else -> null
            }
            parsed?.let { return it }
        }
        return 0L
    }

    private fun firstDouble(json: JSONObject, vararg names: String): Double {
        names.forEach { name ->
            val key = findKey(json, name) ?: return@forEach
            val value = json.optString(key).trim()
            value.replace(",", ".").toDoubleOrNull()?.let { return it }
        }
        return 0.0
    }

    private fun calculateOrderWeight(order: JSONObject): Double {
        val products = firstArray(order, "ItemPedido", "PedidoProdutos", "PedidoItens")
            ?: return 0.0
        var weight = 0.0
        for (index in 0 until products.length()) {
            val product = parseObjectValue(products.opt(index)) ?: continue
            val productWeight = firstDouble(
                product,
                "ItemPedidoProdutoPesoLiq",
                "ProdutoPeso",
                "ItemPedidoProdutoPeso"
            )
            val quantity = firstDouble(
                product,
                "ItemPedidoQtde",
                "ProdutoQuantidade",
                "Quantidade"
            )
            weight += productWeight * quantity
        }
        return weight
    }

    private fun firstArray(json: JSONObject, vararg names: String): JSONArray? {
        names.forEach { name ->
            val key = findKey(json, name) ?: return@forEach
            json.optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun loadRegionNames(): Map<String, String> {
        val items = PriceTableItemService.load(appContext)
        return (0 until items.length())
            .mapNotNull { items.optJSONObject(it) }
            .mapNotNull { item ->
                val id = item.optString("RegiaoId")
                val name = item.optString("RegiaoDescricao")
                if (id.isBlank() || name.isBlank()) null else id to name
            }
            .toMap()
    }

    private fun updateSyncMetadata(db: SQLiteDatabase, representanteId: String, syncCount: Int) {
        val metadata = ContentValues().apply {
            put("representante_id", representanteId)
            put("last_sync_timestamp", System.currentTimeMillis())
            put("last_sync_count", syncCount)
            put("updated_at", System.currentTimeMillis())
        }

        db.insertWithOnConflict(
            "erp_sync_metadata",
            null,
            metadata,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun loadAll(): List<ERPOrder> {
        val db = database.writableDatabase
        val result = mutableListOf<ERPOrder>()
        val cacheUpdates = mutableListOf<Pair<Long, ContentValues>>()
        var companyNames: Map<String, String>? = null
        var regionNames: Map<String, String>? = null

        db.rawQuery(
            """
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date,
                json_data, cached_weight, cached_company_name, cached_region_description
            FROM erp_orders
            ORDER BY synced_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    val order = parseObject(jsonData, "sdtPedidoAppVenda") ?: jsonData
                    val cachedWeight = if (cursor.isNull(9)) {
                        calculateOrderWeight(order)
                    } else {
                        cursor.getDouble(9)
                    }
                    val cachedCompanyName = if (cursor.isNull(10)) {
                        firstString(order, "PedidoEmpresaNome", "EmpresaNome").ifBlank {
                            val companyId = firstString(order, "PedidoEmpresaId")
                            if (companyId.isBlank()) {
                                ""
                            } else {
                                if (companyNames == null) {
                                    companyNames = CompanyLocalRepository(appContext).getAll()
                                        .associate { it.id to it.name }
                                }
                                companyNames?.get(companyId).orEmpty()
                            }
                        }
                    } else {
                        cursor.getString(10).orEmpty()
                    }
                    val cachedRegionDescription = if (cursor.isNull(11)) {
                        firstString(
                            order,
                            "PedidoEntidadeRegiaoDescricao",
                            "PedidoEntidadeRegiaoNome",
                            "PedidoRegiaoDescricao"
                        ).ifBlank {
                            val regionId = firstString(
                                order,
                                "PedidoEntidadeRegiaoid",
                                "PedidoEntidadeRegiaoId"
                            )
                            if (regionId.isBlank()) {
                                ""
                            } else {
                                if (regionNames == null) regionNames = loadRegionNames()
                                regionNames?.get(regionId).orEmpty()
                            }
                        }
                    } else {
                        cursor.getString(11).orEmpty()
                    }
                    if (cursor.isNull(9) || cursor.isNull(10) || cursor.isNull(11)) {
                        cacheUpdates.add(
                            cursor.getLong(0) to ContentValues().apply {
                                put("cached_weight", cachedWeight)
                                put("cached_company_name", cachedCompanyName)
                                put("cached_region_description", cachedRegionDescription)
                            }
                        )
                    }
                    result.add(ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData,
                        cachedWeight = cachedWeight,
                        cachedCompanyName = cachedCompanyName,
                        cachedRegionDescription = cachedRegionDescription
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (cacheUpdates.isNotEmpty()) {
            db.beginTransaction()
            try {
                cacheUpdates.forEach { (mobileId, values) ->
                    db.update(
                        "erp_orders",
                        values,
                        "mobile_id = ?",
                        arrayOf(mobileId.toString())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        return result
    }

    fun loadByClient(clientId: String): List<ERPOrder> {
        val db = database.readableDatabase
        val result = mutableListOf<ERPOrder>()

        db.rawQuery(
            """
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date,
                json_data, cached_weight, cached_company_name, cached_region_description
            FROM erp_orders
            WHERE client_id = ?
            ORDER BY synced_at DESC
            """.trimIndent(),
            arrayOf(clientId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    val order = parseObject(jsonData, "sdtPedidoAppVenda") ?: jsonData
                    result.add(ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData,
                        cachedWeight = if (cursor.isNull(9)) calculateOrderWeight(order) else cursor.getDouble(9),
                        cachedCompanyName = cursor.getString(10).orEmpty()
                            .ifBlank { firstString(order, "PedidoEmpresaNome", "EmpresaNome") },
                        cachedRegionDescription = cursor.getString(11).orEmpty().ifBlank {
                            firstString(
                                order,
                                "PedidoEntidadeRegiaoDescricao",
                                "PedidoEntidadeRegiaoNome",
                                "PedidoRegiaoDescricao"
                            )
                        }
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return result
    }

    fun findByErpId(erpId: Long): ERPOrder? {
        val db = database.readableDatabase

        db.rawQuery(
            """
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date,
                json_data, cached_weight, cached_company_name, cached_region_description
            FROM erp_orders
            WHERE erp_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(erpId.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    val order = parseObject(jsonData, "sdtPedidoAppVenda") ?: jsonData
                    ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData,
                        cachedWeight = if (cursor.isNull(9)) calculateOrderWeight(order) else cursor.getDouble(9),
                        cachedCompanyName = cursor.getString(10).orEmpty()
                            .ifBlank { firstString(order, "PedidoEmpresaNome", "EmpresaNome") },
                        cachedRegionDescription = cursor.getString(11).orEmpty().ifBlank {
                            firstString(
                                order,
                                "PedidoEntidadeRegiaoDescricao",
                                "PedidoEntidadeRegiaoNome",
                                "PedidoRegiaoDescricao"
                            )
                        }
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    fun count(): Int {
        val db = database.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM erp_orders", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun countByStatus(status: String): Int {
        val db = database.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM erp_orders WHERE status = ?",
            arrayOf(status)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getLastSyncTimestamp(representanteId: String): Long {
        val db = database.readableDatabase
        db.rawQuery(
            "SELECT last_sync_timestamp FROM erp_sync_metadata WHERE representante_id = ? LIMIT 1",
            arrayOf(representanteId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun delete(mobileId: Long) {
        val db = database.writableDatabase
        db.delete("erp_orders", "mobile_id = ?", arrayOf(mobileId.toString()))
    }

    fun deleteAll() {
        val db = database.writableDatabase
        db.delete("erp_orders", null, null)
    }

    fun clear(representanteId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("erp_orders", null, null)
            db.delete("erp_sync_metadata", "representante_id = ?", arrayOf(representanteId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
