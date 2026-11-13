package com.example.forcavendas

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

object TaxedMoney {
    data class ItemValues(val unit: BigDecimal, val line: BigDecimal)

    private fun decimal(item: JSONObject, key: String): BigDecimal {
        val value = item.optString(key, "0").trim().replace(",", ".")
        return value.toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    private fun percent(value: BigDecimal): BigDecimal =
        value.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)

    /**
     * Valor unitário e total da linha com IPI (quando tipo IPI = 'T' e percentual > 0)
     * e ICMS substituto (quando tipo ICMS = 'S').
     */
    fun itemValues(item: JSONObject): ItemValues {
        val quantity = decimal(item, "ItemPedidoQtde")
        var base = decimal(item, "ItemPedidoVrlUnitario")
        if (base.signum() <= 0) base = decimal(item, "ItemPedidoVlrOriginal")
        val lineBase = if (item.has("ItemPedidoVltTotal")) decimal(item, "ItemPedidoVltTotal")
        else base.multiply(quantity)

        val ipiPercent = decimal(item, "ItemPedidoPerIPI")
        val applyIpi = item.optString("ItemPedidoTipoIPI").trim().equals("T", ignoreCase = true) &&
            ipiPercent.signum() > 0
        val ipiFactor = if (applyIpi) BigDecimal.ONE.add(percent(ipiPercent)) else BigDecimal.ONE

        val unitWithIpi = truncate(base.multiply(ipiFactor))
        val lineWithIpi = truncate(lineBase.multiply(ipiFactor))

        var substituteIcms = BigDecimal.ZERO
        if (item.optString("ItemPedidoTipoICMS").trim().equals("S", ignoreCase = true)) {
            val stateIcmsPercent = decimal(item, "ItemPedidoPerICMSEstado")
            val reductionPercent = decimal(item, "ItemPedidoPerReducaoICMS")
            val mvaPercent = decimal(item, "ItemPedidoPerMVA")
            val productIcmsPercent = decimal(item, "ItemPedidoPerICMSProduto")

            var icms = base.multiply(percent(stateIcmsPercent))
            if (reductionPercent.signum() > 0 && icms.signum() > 0) {
                val interstatePercent = stateIcmsPercent
                    .subtract(stateIcmsPercent.multiply(reductionPercent).divide(BigDecimal("100"), 10, RoundingMode.HALF_UP))
                    .setScale(2, RoundingMode.HALF_UP)
                icms = base.multiply(percent(interstatePercent)).setScale(2, RoundingMode.HALF_UP)
            }

            val itemCalculated = base.multiply(ipiFactor)
            val substitutionBase = if (mvaPercent.signum() > 0) {
                itemCalculated.add(itemCalculated.multiply(percent(mvaPercent)))
            } else {
                itemCalculated
            }
            substituteIcms = substitutionBase.multiply(percent(productIcmsPercent))
                .setScale(2, RoundingMode.HALF_UP)
                .subtract(icms)
            if (substituteIcms.signum() < 0) substituteIcms = BigDecimal.ZERO
            substituteIcms = substituteIcms.setScale(2, RoundingMode.HALF_UP)
        }

        return ItemValues(
            unit = unitWithIpi.add(substituteIcms),
            line = lineWithIpi.add(truncate(substituteIcms.multiply(quantity)))
        )
    }

    fun truncate(value: BigDecimal): BigDecimal =
        value.setScale(2, RoundingMode.DOWN)

    fun unitWithTax(unitValue: BigDecimal, taxPercent: BigDecimal): BigDecimal =
        truncate(unitValue.multiply(BigDecimal.ONE.add(taxPercent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP))))

    fun lineWithTax(lineValue: BigDecimal, taxPercent: BigDecimal): BigDecimal =
        truncate(lineValue.multiply(BigDecimal.ONE.add(taxPercent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP))))
}
