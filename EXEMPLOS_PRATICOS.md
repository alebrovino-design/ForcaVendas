# 📖 Exemplos Práticos - Download de Pedidos do ERP

## Exemplo 1: Usar ERPOrderLocalRepository Diretamente

### Salvar Pedidos
```kotlin
val context = this  // Activity ou Fragment
val repository = ERPOrderLocalRepository(context)

val jsonArray = JSONArray(apiResponse)  // Resposta da API
repository.saveOrders("10420", jsonArray)  // Salva para o representante 10420
```

### Carregar Todos os Pedidos
```kotlin
val repository = ERPOrderLocalRepository(requireContext())
val allOrders = repository.loadAll()

allOrders.forEach { order ->
    Log.d("DEBUG", "Pedido ${order.erpId} - ${order.clientName} - R$ ${order.total}")
}
// Output:
// Pedido 67890 - Cliente ABC - R$ 1905.60
// Pedido 67891 - Cliente XYZ - R$ 3450.00
// ...
```

### Filtrar por Cliente
```kotlin
val clientOrders = repository.loadByClient("999")
Log.d("DEBUG", "Cliente 999 tem ${clientOrders.size} pedidos no histórico")
// Output: Cliente 999 tem 3 pedidos no histórico
```

### Buscar Pedido Específico
```kotlin
val order = repository.findByErpId(67890L)
if (order != null) {
    Log.d("DEBUG", "Encontrado: ${order.clientName}")
    Log.d("DEBUG", "Status: ${order.status}")
} else {
    Log.d("DEBUG", "Pedido não encontrado")
}
```

### Contar Pedidos
```kotlin
val total = repository.count()
val faturados = repository.countByStatus("FATURADO")
val pendentes = repository.countByStatus("AGUARDANDO FATURAMENTO")

Log.d("DEBUG", "Total: $total")
Log.d("DEBUG", "Faturados: $faturados")
Log.d("DEBUG", "Pendentes: $pendentes")
```

### Verificar Última Sincronização
```kotlin
val lastSync = repository.getLastSyncTimestamp("10420")
if (lastSync > 0L) {
    val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm")
        .format(java.util.Date(lastSync))
    Log.d("DEBUG", "Última sincronização: $date")
} else {
    Log.d("DEBUG", "Nunca foi sincronizado")
}
```

---

## Exemplo 2: Usar ERPOrderDownloadService Diretamente

### Download Simples
```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

// Em um Fragment ou Activity com lifecycleScope
lifecycleScope.launch(Dispatchers.IO) {
    val result = ERPOrderDownloadService.downloadOrders(
        context = requireContext(),
        representanteId = "10420"
    )
    
    launch(Dispatchers.Main) {  // Voltar para main thread
        if (result.success) {
            Log.d("DEBUG", result.message)
            Toast.makeText(
                requireContext(),
                "${result.ordersCount} pedidos sincronizados",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Log.e("DEBUG", result.error ?: "Erro desconhecido")
            Toast.makeText(
                requireContext(),
                result.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
```

### Download com Representante Automático
```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    // Se não passar representanteId, usa SessionManager.currentUserId()
    val result = ERPOrderDownloadService.downloadOrders(requireContext())
    
    launch(Dispatchers.Main) {
        val message = if (result.success) {
            "✓ ${result.message}"
        } else {
            "✗ ${result.message}\n${result.error}"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
```

---

## Exemplo 3: Converter ERPOrder para Draft (Para Exibição)

### Código Atual em OrdersFragment
```kotlin
private fun ERPOrderLocalRepository.ERPOrder.toOrderDraft(): OrderDraftService.Draft {
    return OrderDraftService.Draft(
        mobileId = this.mobileId,
        erpId = this.erpId,
        clientId = this.clientId,
        clientName = this.clientName,
        regionId = this.regionId,
        createdAt = this.emissionDate,  // Usa emission_date
        total = this.total,
        status = this.status,
        json = this.json
    )
}

// Uso:
val erpOrder = repository.findByErpId(67890L) ?: return
val draft = erpOrder.toOrderDraft()
Log.d("DEBUG", "Convertido para Draft: ${draft.clientName}")
```

---

## Exemplo 4: Renderizar Pedidos Unificados

### Combinar Rascunhos + ERP
```kotlin
private fun renderOrders() {
    val drafts = OrderDraftService.load(requireContext())
    val erpOrders = ERPOrderLocalRepository(requireContext()).loadAll()
    
    // Combinar
    val allOrders = (drafts + erpOrders.map { it.toOrderDraft() })
        .sortedByDescending {
            it.json.optJSONObject("sdtPedidoAppVenda")?.optLong("PedidoMobId", 0L) ?: 0L
        }
    
    Log.d("DEBUG", "Total de pedidos a renderizar: ${allOrders.size}")
    Log.d("DEBUG", "  - Rascunhos: ${drafts.size}")
    Log.d("DEBUG", "  - Do ERP: ${erpOrders.size}")
    
    // Renderizar cada um
    allOrders.forEach { draft ->
        renderOrderCard(list, draft)
    }
}
```

### Renderizar apenas Rascunhos
```kotlin
private fun renderOnlyDrafts() {
    val drafts = OrderDraftService.load(requireContext())
    drafts.forEach { draft ->
        renderOrderCard(list, draft)
    }
}
```

### Renderizar apenas ERP Orders
```kotlin
private fun renderOnlyERPOrders() {
    val erpOrders = ERPOrderLocalRepository(requireContext()).loadAll()
    val drafts = erpOrders.map { it.toOrderDraft() }
    drafts.forEach { draft ->
        renderOrderCard(list, draft)
    }
}
```

---

## Exemplo 5: Detectar Tipo de Pedido

### Verificar se é ERP Order
```kotlin
val draft = /* algum pedido */
val order = draft.json.optJSONObject("sdtPedidoAppVenda")
val isERPOrder = order?.optLong("PedidoERPId", 0L) ?: 0L > 0L

if (isERPOrder) {
    Log.d("DEBUG", "Este é um pedido do ERP (já enviado)")
} else {
    Log.d("DEBUG", "Este é um rascunho (ainda não enviado)")
}
```

### Usando ERPOrderDownloadService.isErpOrder
```kotlin
val draft = /* algum pedido */
val isERP = ERPOrderDownloadService.isErpOrder(draft)

if (isERP) {
    // Mostrar apenas botões de visualização
    showButton("PDF")
    showButton("JSON")
} else {
    // Mostrar botões de edição
    showButton("Alterar")
    showButton("PDF")
    showButton("JSON")
}
```

---

## Exemplo 6: Tratar Erros de Download

### Implementação Completa com Erros
```kotlin
private fun downloadComTratamentoDeErros() {
    val representanteId = SessionManager.currentUserId(requireContext())
    
    if (representanteId.isEmpty()) {
        Toast.makeText(requireContext(), "Usuário não autenticado", Toast.LENGTH_SHORT).show()
        return
    }
    
    val progressDialog = AlertDialog.Builder(requireContext())
        .setTitle("Sincronizando...")
        .setMessage("Por favor, aguarde")
        .setCancelable(false)
        .show()
    
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        val result = ERPOrderDownloadService.downloadOrders(
            requireContext(),
            representanteId
        )
        
        launch(Dispatchers.Main) {
            progressDialog.dismiss()
            
            when {
                result.success && result.ordersCount > 0 -> {
                    // Sucesso com pedidos
                    showSuccessDialog("Sucesso!", 
                        "${result.ordersCount} pedidos sincronizados",
                        onOK = { renderOrders() }
                    )
                }
                result.success && result.ordersCount == 0 -> {
                    // Sucesso sem novos pedidos
                    Toast.makeText(
                        requireContext(),
                        "Nenhum novo pedido encontrado",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                result.error?.contains("401") == true -> {
                    // Erro de autenticação
                    showErrorDialog("Erro de Autenticação",
                        "Sua sessão expirou. Por favor, faça login novamente.",
                        onOK = { /* navegar para login */ }
                    )
                }
                result.error?.contains("timeout") == true -> {
                    // Timeout
                    showErrorDialog("Conexão Lenta",
                        "A requisição demorou muito. Tente novamente.",
                        onOK = { /* retry */ }
                    )
                }
                else -> {
                    // Erro genérico
                    showErrorDialog("Erro",
                        "${result.message}\n\n${result.error ?: "Erro desconhecido"}",
                        onOK = { }
                    )
                }
            }
        }
    }
}

private fun showSuccessDialog(title: String, message: String, onOK: () -> Unit) {
    AlertDialog.Builder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK") { _, _ -> onOK() }
        .show()
}

private fun showErrorDialog(title: String, message: String, onOK: () -> Unit) {
    AlertDialog.Builder(requireContext())
        .setTitle(title)
        .setIcon(android.R.drawable.ic_dialog_alert)
        .setMessage(message)
        .setPositiveButton("OK") { _, _ -> onOK() }
        .show()
}
```

---

## Exemplo 7: Limpeza de Dados

### Limpar Tudo (Reset Completo)
```kotlin
val repository = ERPOrderLocalRepository(requireContext())
val userId = SessionManager.currentUserId(requireContext())

// Remove todos os pedidos e metadata de sincronização
repository.clear(userId)

Log.d("DEBUG", "Dados de sincronização limpos para usuário $userId")
```

### Deletar Pedido Individual
```kotlin
val repository = ERPOrderLocalRepository(requireContext())

// Remove pedido específico
repository.delete(mobileId = 1224217L)

Log.d("DEBUG", "Pedido 1224217 removido do histórico")
```

### Deletar Tudo
```kotlin
val repository = ERPOrderLocalRepository(requireContext())

// Remove todos os pedidos (cuidado!)
repository.deleteAll()

Log.d("DEBUG", "Todos os pedidos do histórico foram removidos")
```

---

## Exemplo 8: Integração com AutoSyncManager

### Adicionar Sincronização Automática
```kotlin
// Em AutoSyncManager.kt - adicionar ao método syncOncePerDay()

private suspend fun syncOncePerDay() {
    Log.d(TAG, "Sincronizando dados...")
    
    try {
        // Sincronizar pedidos para enviar (existe)
        runCatching { OrderSyncService.sendPending(context) }
            .onFailure { Log.w(TAG, "Erro ao enviar pedidos", it) }
        
        // ✨ NOVO: Sincronizar histórico do ERP
        val representanteId = SessionManager.currentUserId(context)
        runCatching { 
            ERPOrderDownloadService.downloadOrders(context, representanteId)
        }.onSuccess { result ->
            if (result.success) {
                Log.d(TAG, "Sincronização automática: ${result.ordersCount} pedidos")
            }
        }.onFailure { 
            Log.w(TAG, "Erro ao baixar histórico", it) 
        }
        
    } catch (e: Exception) {
        Log.e(TAG, "Erro em syncOncePerDay", e)
    }
}
```

---

## Exemplo 9: Migrar de SharedPreferences para SQLite

### Script de Migração
```kotlin
fun migrateToSQLite(context: Context) {
    val preferences = context.getSharedPreferences("forca_vendas", 0)
    val oldData = preferences.getString("erp_orders_json", "[]") ?: "[]"
    
    if (oldData == "[]") {
        Log.d("DEBUG", "Nenhum dado antigo para migrar")
        return
    }
    
    try {
        val jsonArray = JSONArray(oldData)
        val repository = ERPOrderLocalRepository(context)
        val userId = SessionManager.currentUserId(context)
        
        // Salvar em SQLite
        repository.saveOrders(userId, jsonArray)
        
        // Remover do SharedPreferences (opcional)
        preferences.edit()
            .remove("erp_orders_json")
            .apply()
        
        Log.d("DEBUG", "Migração concluída: ${jsonArray.length()} pedidos")
    } catch (e: Exception) {
        Log.e("DEBUG", "Erro na migração", e)
    }
}
```

---

## Exemplo 10: Query Customizada (Futuro)

### Busca Avançada (Para Implementar)
```kotlin
// Estrutura preparada para futuros filtros
fun searchOrders(
    clientId: String? = null,
    status: String? = null,
    fromDate: String? = null,
    toDate: String? = null
): List<ERPOrder> {
    val db = database.readableDatabase
    
    val whereConditions = mutableListOf<String>()
    val args = mutableListOf<String>()
    
    if (!clientId.isNullOrEmpty()) {
        whereConditions.add("client_id = ?")
        args.add(clientId)
    }
    if (!status.isNullOrEmpty()) {
        whereConditions.add("status = ?")
        args.add(status)
    }
    if (!fromDate.isNullOrEmpty()) {
        whereConditions.add("synced_at >= ?")
        args.add(fromDate)
    }
    if (!toDate.isNullOrEmpty()) {
        whereConditions.add("synced_at <= ?")
        args.add(toDate)
    }
    
    val where = whereConditions.joinToString(" AND ")
    
    db.rawQuery(
        "SELECT * FROM erp_orders WHERE $where ORDER BY synced_at DESC",
        args.toTypedArray()
    ).use { cursor ->
        // ... retornar resultados
    }
}
```

---

## 🔗 Conexões com Outras Funcionalidades

### OrderSyncService (Envio)
```
Pedido Rascunho
    ↓
[Clica em Sincronizar Pedidos] ← OrdersFragment.sincronizarPedidosPendentes()
    ↓
OrderSyncService.sync(order) ← Envia para ERP
    ↓
Se sucesso: OrderDraftService.markSent(mobileId, erpId)
    ↓
Pedido agora tem ERP ID > 0
    ↓
Status muda para "ENVIADO AO ERP"
```

### ERPOrderDownloadService (Download)
```
[Clica em Atualizar Histórico] ← OrdersFragment.baixarPedidosDoERP()
    ↓
ERPOrderDownloadService.downloadOrders() ← Baixa do ERP
    ↓
ERPOrderLocalRepository.saveOrders() ← Salva em SQLite
    ↓
renderOrders() recarrega
    ↓
Novo pedido ERP aparece na lista
```

---

## 📋 Checklist de Uso

Ao usar a nova funcionalidade:

- [ ] Testar download com internet
- [ ] Testar sem conexão
- [ ] Verificar se dados são persistentes
- [ ] Validar que não duplica pedidos
- [ ] Confirmar que não edita ERP orders
- [ ] Testar sincronização manual
- [ ] Verificar logs no logcat
- [ ] Confirmar Toast messages
- [ ] Testar com múltiplos usuários
- [ ] Validar performance

---

## 🐛 Debugging

### Logs Importantes
```kotlin
// Ativar debugging
Log.d("ERPOrderDownload", "Iniciando download...")
Log.d("ERPOrderDownload", "Resposta: $response")
Log.e("ERPOrderDownload", "Erro:", exception)

// Ver no logcat
adb logcat | grep "ERPOrderDownload"
```

### Verificar Banco de Dados
```bash
# Via adb shell
adb shell
run-as com.example.forcavendas
cd /data/data/com.example.forcavendas/databases/
sqlite3 forca_vendas_erp_orders.db
> SELECT COUNT(*) FROM erp_orders;
> SELECT * FROM erp_sync_metadata;
```

---

_Exemplos Práticos - Versão 1.0_
_Última atualização: 2026-11-12_
