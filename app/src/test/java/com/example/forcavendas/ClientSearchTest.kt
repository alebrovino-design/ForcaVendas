package com.example.forcavendas

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientSearchTest {
    private val client = Client(
        id = "123",
        companyId = "456",
        consumerFinal = false,
        cnpjCpf = "12.345.678/0001-90",
        fantasyName = "Agro Bom",
        reasonName = "Fazenda Exemplo Ltda",
        address = "Avenida das Flores",
        addressNumber = "98",
        neighborhood = "Centro",
        postalCode = "00000-000",
        city = "São Paulo",
        state = "SP",
        openBalance = "0",
        overdueBalance = "0",
        clientDiscount = "0",
        regionId = "1"
    )

    @Test
    fun findsCityFantasyAndAddressIgnoringCase() {
        listOf(" SÃO PAULO ", "agro bom", "AVENIDA DAS FLORES", "flores 98", "centro").forEach { query ->
            assertEquals(listOf(client), filterClients(listOf(client), query))
        }
    }

    @Test
    fun preservesExistingSearchAndRejectsNonMatches() {
        listOf("123", "456", "12.345.678", "exemplo", "").forEach { query ->
            assertEquals(listOf(client), filterClients(listOf(client), query))
        }
        assertEquals(emptyList<Client>(), filterClients(listOf(client), "Campinas"))
    }
}
