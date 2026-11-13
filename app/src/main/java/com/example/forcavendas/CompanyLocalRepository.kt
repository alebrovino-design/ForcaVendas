package com.example.forcavendas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class CompanyLocalRepository(context: Context) {
    data class Company(
        val id: String,
        val name: String,
        val uf: String
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "forca_vendas_companies.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE companies (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    uf TEXT NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS companies")
            onCreate(db)
        }
    }

    private val database = Database(context.applicationContext)

    fun replaceAll(json: JSONArray) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("companies", null, null)
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val values = ContentValues().apply {
                    put("id", item.optString("EmpresaId"))
                    put("name", item.optString("EmpresaNomeInterno"))
                    put("uf", item.optString("EmpresaUF").uppercase())
                }
                db.insertWithOnConflict(
                    "companies",
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

    fun getAll(): List<Company> {
        val companies = mutableListOf<Company>()
        database.readableDatabase.rawQuery(
            "SELECT id, name, uf FROM companies ORDER BY id",
            null
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val ufIndex = cursor.getColumnIndexOrThrow("uf")
            while (cursor.moveToNext()) {
                companies.add(
                    Company(
                        cursor.getString(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(ufIndex)
                    )
                )
            }
        }
        return companies
    }

    fun findById(id: String): Company? =
        getAll().firstOrNull { it.id == id }
}
