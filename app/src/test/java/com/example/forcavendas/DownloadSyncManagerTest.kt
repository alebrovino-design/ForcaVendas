package com.example.forcavendas

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSyncManagerTest {
    @Test
    fun continuesAfterFailureAndReportsEachStep() {
        val events = mutableListOf<String>()
        val steps: List<Pair<String, () -> Unit>> = listOf(
            "Produtos" to { events += "produtos" },
            "Clientes" to { throw IllegalStateException("offline") },
            "Cargas" to { events += "cargas" },
            "Meta" to { throw IllegalArgumentException("representante ausente") }
        )

        val failures = runDownloadSteps(
            steps,
            onStart = { index, total, name -> events += "$index/$total $name" },
            onFailure = { name, exception -> events += "$name: ${exception.message}" }
        )

        assertEquals(listOf("Clientes", "Meta"), failures)
        assertEquals(
            listOf(
                "1/4 Produtos", "produtos",
                "2/4 Clientes", "Clientes: offline",
                "3/4 Cargas", "cargas",
                "4/4 Meta", "Meta: representante ausente"
            ),
            events
        )
    }

    @Test
    fun reportsNoFailuresWhenAllDownloadsSucceed() {
        val failures = runDownloadSteps(
            listOf("Produtos" to {}, "Clientes" to {}),
            onStart = { _, _, _ -> },
            onFailure = { _, _ -> throw AssertionError("Falha inesperada") }
        )

        assertEquals(emptyList<String>(), failures)
    }
}
