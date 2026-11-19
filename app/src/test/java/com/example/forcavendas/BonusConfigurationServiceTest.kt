package com.example.forcavendas

import org.junit.Assert.assertEquals
import org.junit.Test

class BonusConfigurationServiceTest {
    @Test
    fun toleranceIsCalculatedOnSoldQuantity() {
        assertEquals(30.0, BonusConfigurationService.calculateAllowedBonus(100.0, 10.0, 20.0), 0.000001)
        assertEquals(6.75, BonusConfigurationService.calculateAllowedBonus(45.0, 12.5, 2.5), 0.000001)
    }

    @Test
    fun zeroBonusOrToleranceStillUsesSoldQuantity() {
        assertEquals(5.0, BonusConfigurationService.calculateAllowedBonus(100.0, 0.0, 5.0), 0.000001)
        assertEquals(10.0, BonusConfigurationService.calculateAllowedBonus(100.0, 10.0, 0.0), 0.000001)
        assertEquals(0.0, BonusConfigurationService.calculateAllowedBonus(0.0, 10.0, 20.0), 0.000001)
    }

    @Test
    fun cartBalanceCanBeNegativeWhenBonusExceedsBase() {
        assertEquals(-200.0, BonusConfigurationService.calculateBonifiableAmount(100.0, 10000.0, 12.0, 10.0), 0.000001)
        assertEquals(200.0, BonusConfigurationService.calculateBonifiableAmount(100.0, 10000.0, 8.0, 10.0), 0.000001)
        assertEquals(0.0, BonusConfigurationService.calculateBonifiableAmount(100.0, 10000.0, 10.0, 10.0), 0.000001)
    }

    @Test
    fun cartBalanceUsesAverageSoldPrice() {
        assertEquals(-150.0, BonusConfigurationService.calculateBonifiableAmount(100.0, 7500.0, 12.0, 10.0), 0.000001)
        assertEquals(0.0, BonusConfigurationService.calculateBonifiableAmount(0.0, 0.0, 0.0, 10.0), 0.000001)
    }
}
