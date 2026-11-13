package com.example.forcavendas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProductLocalRepository(context: Context) {
    data class ProductRecord(
        val code: String,
        val erpId: String,
        val description: String,
        val netWeight: String,
        val grossWeight: String,
        val photoUrl: String,
        val productGroupId: String,
        val taxGroupId: String,
        val salesBrandId: String,
        val familyCommissionPercent: String,
        val ipiPercent: String
    )

    private class Database(context: Context) : SQLiteOpenHelper(context, "forca_vendas.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE products (
                    code TEXT PRIMARY KEY,
                    product_id TEXT,
                    description TEXT NOT NULL,
                    net_weight TEXT,
                    gross_weight TEXT,
                    photo_url TEXT,
                    product_group_id TEXT,
                    tax_group_id TEXT,
                    sales_brand_id TEXT,
                    family_commission_percent TEXT,
                    ipi_percent TEXT
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS products")
                onCreate(db)
                return
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE products ADD COLUMN ipi_percent TEXT")
            }
        }
    }

    private val database = Database(context.applicationContext)

    fun replaceAll(products: List<ProductRecord>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("products", null, null)
            products.forEach { product ->
                val values = ContentValues().apply {
                    put("code", product.code)
                    put("product_id", product.erpId)
                    put("description", product.description)
                    put("net_weight", product.netWeight)
                    put("gross_weight", product.grossWeight)
                    put("photo_url", product.photoUrl)
                    put("product_group_id", product.productGroupId)
                    put("tax_group_id", product.taxGroupId)
                    put("sales_brand_id", product.salesBrandId)
                    put("family_commission_percent", product.familyCommissionPercent)
                    put("ipi_percent", product.ipiPercent)
                }
                db.insertWithOnConflict("products", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAll(): List<ProductRecord> {
        val products = mutableListOf<ProductRecord>()
        database.readableDatabase.query(
            "products", null, null, null, null, null, "description ASC"
        ).use { cursor ->
            val code = cursor.getColumnIndexOrThrow("code")
            val productId = cursor.getColumnIndexOrThrow("product_id")
            val description = cursor.getColumnIndexOrThrow("description")
            val netWeight = cursor.getColumnIndexOrThrow("net_weight")
            val grossWeight = cursor.getColumnIndexOrThrow("gross_weight")
            val photoUrl = cursor.getColumnIndexOrThrow("photo_url")
            val productGroupId = cursor.getColumnIndexOrThrow("product_group_id")
            val taxGroupId = cursor.getColumnIndexOrThrow("tax_group_id")
            val salesBrandId = cursor.getColumnIndexOrThrow("sales_brand_id")
            val familyCommissionPercent = cursor.getColumnIndexOrThrow("family_commission_percent")
            val ipiPercent = cursor.getColumnIndexOrThrow("ipi_percent")

            while (cursor.moveToNext()) {
                products.add(
                    ProductRecord(
                        code = cursor.getString(code),
                        erpId = cursor.getString(productId).orEmpty(),
                        description = cursor.getString(description),
                        netWeight = cursor.getString(netWeight).orEmpty(),
                        grossWeight = cursor.getString(grossWeight).orEmpty(),
                        photoUrl = cursor.getString(photoUrl).orEmpty(),
                        productGroupId = cursor.getString(productGroupId).orEmpty(),
                        taxGroupId = cursor.getString(taxGroupId).orEmpty(),
                        salesBrandId = cursor.getString(salesBrandId).orEmpty(),
                        familyCommissionPercent = cursor.getString(familyCommissionPercent).orEmpty(),
                        ipiPercent = cursor.getString(ipiPercent).orEmpty()
                    )
                )
            }
        }
        return products
    }

    fun count(): Int {
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM products", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
