package com.example.forcavendas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

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

    fun saveOrders(representanteId: String, orders: JSONArray) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (index in 0 until orders.length()) {
                val item = orders.getJSONObject(index)
                val mobileId = item.optLong("PedidoMobId", 0L)
                val erpId = item.optLong("PedidoERPId", 0L)

                if (mobileId == 0L || erpId == 0L) continue

                val values = ContentValues().apply {
                    put("mobile_id", mobileId)
                    put("erp_id", erpId)
                    put("client_id", item.optString("PedidoEntidadeId"))
                    put("client_name", item.optString("PedidoEntidadeNome").ifBlank {
                        item.optString("PedidoEntidadeNomeFantasia")
                    })
                    put("region_id", item.optString("PedidoEntidadeRegiaoid"))
                    put("status", item.optString("PedidoStatus"))
                    put("total", item.optDouble("PedidoVlrTotal", 0.0))
                    put("emission_date", item.optString("PedidoDtEmissao"))
                    put("json_data", JSONObject().apply {
                        put("sdtPedidoAppVenda", item)
                    }.toString())
                    put("synced_at", System.currentTimeMillis())
                }

                db.insertWithOnConflict(
                    "erp_orders",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            updateSyncMetadata(db, representanteId, orders.length())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
