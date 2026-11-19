# 🔐 Correção Crítica - Token ERP API

**Data:** 13 de Novembro de 2026  
**Status:** ✅ CORRIGIDO E COMPILADO

---

## 🚨 Problema Identificado

### O que estava errado?
Na implementação anterior, o token da API ERP estava sendo **confundido** com o `session_signature` local:

```kotlin
❌ ERRADO:
val token = preferences.getString("session_signature", "")
// session_signature = "user_id|license" (string local, não é token ERP)
```

Isso resultava em:
- ❌ Token ERP sendo **substituído** pelo código do usuário
- ❌ Autenticação falhando na API
- ❌ Chamadas retornando erro 401/403

---

## ✅ Solução Implementada

### 1. **Criado arquivo `ERPConfig.kt`**
Centralized configuration para gerenciar o token ERP:

```kotlin
object ERPConfig {
    // Token ERP real (não confundir com session_signature)
    private const val DEFAULT_ERP_TOKEN = 
        "<TOKEN_ERP>"
    
    fun getERPToken(context: Context): String
    fun setERPToken(context: Context, token: String): Unit
    fun clearERPToken(context: Context): Unit
    fun isValidToken(token: String): Boolean
}
```

### 2. **Atualizado `ERPOrderDownloadService.kt`**

```kotlin
✅ CORRETO:
suspend fun downloadOrders(
    context: Context,
    representanteId: String = "",
    erpToken: String = ""  // Parâmetro explícito
): Result {
    val token = if (erpToken.isNotEmpty()) {
        erpToken
    } else {
        ERPConfig.getERPToken(context)  // Busca do config correto
    }
    
    // ⚠️ Validação: token NÃO é substituído por user_id
    if (!ERPConfig.isValidToken(token)) {
        return Result(success = false, ...)
    }
    
    // API call com token CORRETO
    val url = URL("$BASE_URL?Parametrostegaceptoken=$token&Representanteid=$repId")
}
```

### 3. **Atualizado `OrdersFragment.kt`**

```kotlin
private fun baixarComProgressao(representanteId: String) {
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        // Usa ERPConfig para obter token (não session_signature)
        val result = ERPOrderDownloadService.downloadOrders(
            requireContext(), 
            representanteId
            // erpToken será obtido automaticamente via ERPConfig
        )
    }
}
```

---

## 📋 Diferenças Críticas

| Item | Antes ❌ | Depois ✅ |
|------|---------|---------|
| **Token Source** | `session_signature` (local) | `ERPConfig` (ERP auth) |
| **Token Value** | `"user_id\|license"` | `"dSJQ4qUlSwdNs3T/Oipn..."` |
| **Validação** | Nenhuma | `isValidToken()` check |
| **Armazenamento** | SharedPreferences (confuso) | `erp_api_token` key (claro) |
| **Segurança** | Misturava contextos | Separação clara |

---

## 🔑 Como Funciona Agora

### 1️⃣ **Usuário clica em "Baixar Pedidos"**
```
OrdersFragment.baixarPedidosDoERP()
```

### 2️⃣ **Progressão de download inicia**
```
OrdersFragment.baixarComProgressao(representanteId)
```

### 3️⃣ **Serviço obtém token CORRETO**
```
ERPOrderDownloadService.downloadOrders()
  └─ ERPConfig.getERPToken(context)  ← Token ERP real
```

### 4️⃣ **API é chamada com tokens CORRETOS**
```
GET https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda
    ?Parametrostegaceptoken=<TOKEN_ERP>
    &Representanteid=10418
```

**Sem confundir com:**
- ❌ `session_signature` = "10418|license_key" (contexto local)
- ❌ `user_id` = "10418" (apenas identificador)

---

## 🛡️ Segurança

### ✅ Boas Práticas Implementadas
1. **Separação de contextos**: Token ERP ≠ Session signature
2. **Validação explícita**: `isValidToken()` antes de usar
3. **Logs reduzidos**: Não logamos token completo (apenas "[TOKEN_REDACTED]")
4. **Configuração centralizada**: `ERPConfig` é única fonte da verdade

### ⚠️ Próximos Passos (Phase 2)
- [ ] Implementar OAuth2 ou similar para token seguro
- [ ] Armazenar token em EncryptedSharedPreferences
- [ ] Refresh token automático
- [ ] Audit logging de chamadas API

---

## 📝 Código Completo - Fluxo Correto

### **ERPConfig.kt** (novo)
```kotlin
object ERPConfig {
    private const val DEFAULT_ERP_TOKEN = 
        "<TOKEN_ERP>"
    
    fun getERPToken(context: Context): String {
        val preferences = context.getSharedPreferences("forca_vendas", 0)
        return preferences.getString("erp_api_token", DEFAULT_ERP_TOKEN) 
            ?: DEFAULT_ERP_TOKEN
    }
}
```

### **ERPOrderDownloadService.kt** (corrigido)
```kotlin
suspend fun downloadOrders(
    context: Context,
    representanteId: String = "",
    erpToken: String = ""
): Result = withContext(Dispatchers.IO) {
    val repId = SessionManager.currentUserId(context)  // ← ID do usuário
    
    // ✅ CORRETO: Token ERP, não session_signature
    val token = if (erpToken.isNotEmpty()) {
        erpToken
    } else {
        ERPConfig.getERPToken(context)
    }
    
    val url = URL("$BASE_URL?Parametrostegaceptoken=$token&Representanteid=$repId")
    // API call...
}
```

### **OrdersFragment.kt** (corrigido)
```kotlin
private fun baixarComProgressao(representanteId: String) {
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        // Automático: usa ERPConfig.getERPToken()
        val result = ERPOrderDownloadService.downloadOrders(
            requireContext(), 
            representanteId
        )
        // ...
    }
}
```

---

## 🧪 Verificação

### Como confirmar que está funcionando?

```bash
# 1. Instalar novo APK
adb install D:\Projeto\ForcaVendas\app-debug.apk

# 2. Habilitar logging
adb logcat -c
adb logcat | findstr "ERP"

# 3. Clicar em "Baixar Pedidos"
# Deverá ver:
# D ERPOrderDownload: Iniciando download para representante: 10418
# D ERPOrderDownload: URL: https://...?Parametrostegaceptoken=[TOKEN_REDACTED]&Representanteid=10418

# 4. Se sucesso:
# D ERPOrderDownload: Download bem-sucedido: N pedidos

# ✅ Se erro HTTP 401/403:
# Significa token ainda não está correto - verificar valor em ERPConfig
```

---

## 📊 Build Information

```
Status: ✅ BUILD SUCCESSFUL
Build Time: 34 seconds
APK Size: 7.24 MB
Files Modified:
  - ERPOrderDownloadService.kt (refatorado)
  - OrdersFragment.kt (simplificado)
  - ERPConfig.kt (novo)

Warnings: 2 (Locale deprecation - harmless)
Errors: 0
```

---

## 🎯 Resumo da Correção

| Aspecto | Antes | Depois |
|--------|-------|--------|
| Token ERP | ❌ Confundido com session_signature | ✅ Separado em ERPConfig |
| Validação | ❌ Sem validação | ✅ `isValidToken()` obrigatório |
| Segurança | ❌ Potencial confusão de credenciais | ✅ Separação clara de contextos |
| Manutenção | ❌ Código espalhado | ✅ Centralizado em ERPConfig |
| Testabilidade | ❌ Difícil mockar token | ✅ Fácil injetar token em testes |

---

## ✅ Status Final

**Problema:** ❌ Token ERP sendo substituído por user_id  
**Solução:** ✅ Separação clara com ERPConfig + validação  
**Teste:** ✅ Build successful  
**Próximo Passo:** Testar download real com API

---

**APK:** `D:\Projeto\ForcaVendas\app-debug.apk` (v2 - atualizado)  
**Pronto para teste!** 🚀
