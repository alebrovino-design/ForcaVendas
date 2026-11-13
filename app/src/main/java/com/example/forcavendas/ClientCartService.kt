package com.example.forcavendas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ClientCartService {
    private const val PREFERENCES_NAME = "forca_vendas"

    data class CartItem(
        val productCode: String,
        val description: String,
        val photoUrl: String,
        val price: String,
        val soldQuantity: Double,
        val bonusQuantity: Double,
        val productGroupId: String
    )

    fun key(clientId: String) = "client_cart_$clientId"

    fun load(context: Context, clientId: String): List<CartItem> {
        val json = context.getSharedPreferences(PREFERENCES_NAME, 0)
            .getString(key(clientId), "[]") ?: "[]"
        val items = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val result = mutableListOf<CartItem>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result.add(
                CartItem(
                    productCode = item.optString("productCode"),
                    description = item.optString("description"),
                    photoUrl = item.optString("photoUrl"),
                    price = item.optString("price"),
                    soldQuantity = item.optDouble("soldQuantity", 0.0),
                    bonusQuantity = item.optDouble("bonusQuantity", 0.0),
                    productGroupId = item.optString("productGroupId")
                )
            )
        }
        return result
    }

    fun save(context: Context, clientId: String, items: List<CartItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("productCode", item.productCode)
                put("description", item.description)
                put("photoUrl", item.photoUrl)
                put("price", item.price)
                put("soldQuantity", item.soldQuantity)
                put("bonusQuantity", item.bonusQuantity)
                put("productGroupId", item.productGroupId)
            })
        }
        context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putString(key(clientId), array.toString())
            .apply()
    }

    fun addOrUpdate(context: Context, clientId: String, item: CartItem) {
        val items = load(context, clientId).filterNot { it.productCode == item.productCode }.toMutableList()
        if (item.soldQuantity > 0.0 || item.bonusQuantity > 0.0) items.add(item)
        save(context, clientId, items)
    }

    fun remove(context: Context, clientId: String, productCode: String) {
        save(context, clientId, load(context, clientId).filterNot { it.productCode == productCode })
    }
}
