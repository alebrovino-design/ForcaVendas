package com.example.forcavendas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class OrderOperationLocalRepository(context: Context) {
    data class Operation(
        val erpId: String,
        val natopId: String,
        val natopIpi: String,
        val natopIcms: String,
        val bonusOperationId: String
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "forca_vendas_order_operations.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE order_operations (
                    erp_id TEXT NOT NULL,
                    description TEXT NOT NULL,
                    company_id TEXT,
                    activity_operation_id TEXT,
                    free_trade INTEGER,
                    natop_classification TEXT,
                    natop_id TEXT,
                    natop_cofins TEXT,
                    natop_ipi TEXT,
                    natop_pis TEXT,
                    natop_icms TEXT,
                    only_state TEXT,
                    suframa INTEGER,
                    state_type TEXT,
                    free_trade_type TEXT,
                    bonus_operation_id TEXT
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS order_operations")
            onCreate(db)
        }
    }

    private val database = Database(context.applicationContext)

    companion object {
        fun extractOperations(response: String): JSONArray {
            val trimmed = response.trim()
            if (trimmed.startsWith("[")) return JSONArray(trimmed)

            val root = JSONObject(trimmed)
            listOf("value", "results", "data").forEach { key ->
                val array = root.optJSONArray(key)
                if (array != null) return array
            }
            val d = root.opt("d")
            when (d) {
                is JSONArray -> return d
                is JSONObject -> {
                    listOf("results", "value", "data").forEach { key ->
                        val array = d.optJSONArray(key)
                        if (array != null) return array
                    }
                }
            }
            return JSONArray()
        }
    }

    fun replaceAll(json: JSONArray) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("order_operations", null, null)
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val values = ContentValues().apply {
                    put("erp_id", item.optString("TipoOperacaoPedidoERPId"))
                    put("description", item.optString("TipoOperacaoPedidoDescricao"))
                    put("company_id", item.optString("TipoOperacaoPedidoEmpresaId"))
                    put("activity_operation_id", item.optString("RamoAtividadeOperacaoId"))
                    put("free_trade", item.optInt("TipoOperacaoPedidoLivreComercio"))
                    put("natop_classification", item.optString("TipoOperacaoPedidoNatopClassificacao"))
                    put("natop_id", item.optString("TipoOperacaoPedidoNatopId"))
                    put("natop_cofins", item.optString("TipoOperacaoPedidoNatopTIPOCOFINS"))
                    put("natop_ipi", item.optString("TipoOperacaoPedidoNatopTIPOIPI"))
                    put("natop_pis", item.optString("TipoOperacaoPedidoNatopTIPOPIS"))
                    put("natop_icms", item.optString("TipoOperacaoPedidoNatopTipoICMS"))
                    put("only_state", item.optString("TipoOperacaoPedidoSomenteUF"))
                    put("suframa", item.optInt("TipoOperacaoPedidoSuframa"))
                    put("state_type", item.optString("TipoOperacaoPedidoTipoEstado"))
                    put("free_trade_type", item.optString("TipoOperacaoPedidoTipoLivreComercio"))
                    put("bonus_operation_id", item.optString("TipoOperacaoPedidoTipoOpBoniId"))
                }
                db.insertWithOnConflict(
                    "order_operations",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(): Int {
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM order_operations",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun find(
        companyId: String,
        activityOperationId: String,
        clientUf: String,
        companyUf: String
    ): Operation? {
        val normalizedClientUf = clientUf.uppercase().trim()
        val normalizedCompanyUf = companyUf.uppercase().trim()
        val stateType = if (normalizedClientUf == normalizedCompanyUf) "D" else "F"
        val db = database.readableDatabase

        fun query(
            activityId: String,
            whereUf: String,
            ufValue: String?
        ): Operation? {
            val args = mutableListOf(companyId, activityId, stateType)
            if (ufValue != null) args.add(ufValue)
            return db.rawQuery(
                """
                SELECT erp_id, natop_id, natop_ipi, natop_icms, bonus_operation_id
                FROM order_operations
                WHERE CAST(TRIM(COALESCE(company_id, '')) AS INTEGER) = CAST(TRIM(?) AS INTEGER)
                  AND CAST(TRIM(COALESCE(activity_operation_id, '')) AS INTEGER) = CAST(TRIM(?) AS INTEGER)
                  AND UPPER(TRIM(COALESCE(state_type, ''))) = UPPER(TRIM(?))
                  AND $whereUf
                ORDER BY erp_id
                LIMIT 1
                """.trimIndent(),
                args.toTypedArray()
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Operation(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4)
                )
            }
        }

        return if (normalizedClientUf.isNotBlank()) {
            query(
                activityOperationId,
                "UPPER(TRIM(COALESCE(only_state, ''))) = UPPER(TRIM(?))",
                normalizedClientUf
            )
                ?: query(
                    "0",
                    "UPPER(TRIM(COALESCE(only_state, ''))) = UPPER(TRIM(?))",
                    normalizedClientUf
                )
                ?: query(
                    activityOperationId,
                    "TRIM(COALESCE(only_state, '')) = ''",
                    null
                )
                ?: query("0", "TRIM(COALESCE(only_state, '')) = ''", null)
        } else {
            query(activityOperationId, "TRIM(COALESCE(only_state, '')) = ''", null)
                ?: query("0", "TRIM(COALESCE(only_state, '')) = ''", null)
        }
    }
}
