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
        val json: JSONObject
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "forca_vendas_erp_orders.db", null, 1) {
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
            db.execSQL("DROP TABLE IF EXISTS erp_orders")
            db.execSQL("DROP TABLE IF EXISTS erp_sync_metadata")
            onCreate(db)
        }
    }

    private val database = Database(context.applicationContext)

    fun saveOrders(representanteId: String, orders: JSONArray): Int {
        val db = database.writableDatabase
        var savedCount = 0
        db.beginTransaction()
        try {
            for (index in 0 until orders.length()) {
                val responseItem = parseObjectValue(orders.opt(index)) ?: continue
                val item = JSONObject(findOrderPayload(responseItem).toString())
                removeDuplicateInstallments(item)
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
        val db = database.readableDatabase
        val result = mutableListOf<ERPOrder>()

        db.rawQuery(
            """
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date, json_data
            FROM erp_orders
            ORDER BY synced_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    result.add(ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return result
    }

    fun loadByClient(clientId: String): List<ERPOrder> {
        val db = database.readableDatabase
        val result = mutableListOf<ERPOrder>()

        db.rawQuery(
            """
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date, json_data
            FROM erp_orders
            WHERE client_id = ?
            ORDER BY synced_at DESC
            """.trimIndent(),
            arrayOf(clientId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    result.add(ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData
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
            SELECT mobile_id, erp_id, client_id, client_name, region_id, status, total, emission_date, json_data
            FROM erp_orders
            WHERE erp_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(erpId.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                try {
                    val jsonData = JSONObject(cursor.getString(8))
                    ERPOrder(
                        mobileId = cursor.getLong(0),
                        erpId = cursor.getLong(1),
                        clientId = cursor.getString(2),
                        clientName = cursor.getString(3),
                        regionId = cursor.getString(4),
                        status = cursor.getString(5),
                        total = cursor.getDouble(6),
                        emissionDate = cursor.getString(7),
                        json = jsonData
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
