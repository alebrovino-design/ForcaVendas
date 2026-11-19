package com.example.forcavendas

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrderDraftServiceTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "order_draft_test_${UUID.randomUUID()}"
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(preferencesName, mode)
        }
        preferences = context.getSharedPreferences("forca_vendas", Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        OrderDraftService.endSending(101)
        baseContext.deleteSharedPreferences(preferencesName)
    }

    private fun draft(id: Long, erpId: Long = 0): JSONObject =
        JSONObject().put(
            "sdtPedidoAppVenda",
            JSONObject().put("PedidoMobId", id).put("PedidoERPId", erpId)
        )

    @Test
    fun removesOnlyPendingOrderAndPreservesOtherData() {
        val data = JSONArray().put(draft(101)).put(draft(102, 55)).put("outro dado")
        preferences.edit().putString("order_drafts_json", data.toString()).commit()

        assertEquals(OrderDraftService.RemoveResult.REMOVED, OrderDraftService.removePending(context, 101))
        val remaining = JSONArray(preferences.getString("order_drafts_json", "[]"))
        assertEquals(2, remaining.length())
        assertEquals(102L, remaining.getJSONObject(0).getJSONObject("sdtPedidoAppVenda").getLong("PedidoMobId"))
        assertEquals("outro dado", remaining.getString(1))
        assertFalse(OrderDraftService.beginSending(context, 101))
    }

    @Test
    fun sentAndInFlightOrdersCannotBeRemoved() {
        val data = JSONArray().put(draft(101)).put(draft(102, 55))
        preferences.edit().putString("order_drafts_json", data.toString()).commit()

        assertEquals(OrderDraftService.RemoveResult.ALREADY_SENT, OrderDraftService.removePending(context, 102))
        assertTrue(OrderDraftService.beginSending(context, 101))
        assertEquals(OrderDraftService.RemoveResult.SENDING, OrderDraftService.removePending(context, 101))
        OrderDraftService.endSending(101)
        assertEquals(OrderDraftService.RemoveResult.REMOVED, OrderDraftService.removePending(context, 101))
    }

    @Test
    fun malformedDataIsNotOverwritten() {
        preferences.edit().putString("order_drafts_json", "invalid").commit()

        try {
            OrderDraftService.removePending(context, 101)
            throw AssertionError("Dados inválidos deveriam impedir a exclusão")
        } catch (_: JSONException) {
            assertEquals("invalid", preferences.getString("order_drafts_json", null))
        }
    }
}
