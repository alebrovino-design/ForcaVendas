# Correções v5 - Entregas e Pedidos do ERP

## 🔧 Problemas Identificados e Corrigidos

### 1. **LoadDeliveriesFragment.kt** - Entregas não aparecendo
**Problema:** 
- O token estava hardcoded como field privado
- O parâmetro da API era `Usuarioid` em vez de `Representanteid` (inconsistent com a API real)
- Faltava logging para debug

**Correção:**
```kotlin
// ANTES
private val token = "<TOKEN_ERP>"
val endpoint = "...&Usuarioid=${URLEncoder.encode(userId, "UTF-8")}"

// DEPOIS
val token = ERPConfig.getERPToken(requireContext())
val endpoint = "...&Representanteid=${URLEncoder.encode(userId, "UTF-8")}"
```

**Adições:**
- ✅ Import de `android.util.Log`
- ✅ Logging detalhado: usuário ID, token (primeiros 20 chars), HTTP response code, tamanho da resposta, contagem de entregas
- ✅ Mensagem de erro melhorada: `"Falha ao sincronizar: ${exception.message}"` em vez de genérica

### 2. **Centralização de Token**
Ambos os fragments agora usam:
```kotlin
val token = ERPConfig.getERPToken(requireContext())
```

Benefícios:
- ✅ Single source of truth
- ✅ Facilita futura autenticação dinâmica
- ✅ Segurança (preparado para EncryptedSharedPreferences)

## 📡 API Endpoints Verificados

### Pedidos (OrdersFragment)
```
GET https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda
?Parametrostegaceptoken={token}
&Representanteid={userId}
```
✅ Corrigido em session anterior

### Cargas/Loads (LoadsFragment)
```
GET https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpCargaRepAPPVenda
?Parametrostegaceptoken={token}
&Representanteid={userId}
```
✅ Corrigido em session anterior

### Entregas/Deliveries (LoadDeliveriesFragment) - NOVO
```
GET https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpEntregaCargaRepAPPVenda
?Parametrostegaceptoken={token}
&Representanteid={userId}
```
✅ Corrigido agora

## 🧪 Logging Adicionado

### LoadDeliveriesFragment
```
LoadDeliveriesFragment: Buscando entregas para usuário: {userId}
LoadDeliveriesFragment: Token: {primeiros 20 chars}...
LoadDeliveriesFragment: Conectando a: https://erpgnisn.specialnutri.com.br/...
LoadDeliveriesFragment: HTTP Response Code: {code}
LoadDeliveriesFragment: Response length: {bytes} bytes
LoadDeliveriesFragment: Encontradas {count} entregas
LoadDeliveriesFragment: Erro ao sincronizar entregas (se falhar)
```

## 📦 APK Gerado
- **Versão:** app-debug-v5.apk
- **Tamanho:** 7,25 MB
- **Build Status:** ✅ SUCCESS (36s)
- **Localização:** `D:\Projeto\ForcaVendas\app-debug-v5.apk`

## 🧩 Arquivos Modificados
1. `LoadDeliveriesFragment.kt` - Correção de parâmetro e centralização de token

## ✅ Próximos Passos para Testes

1. Instalar APK em device/emulator
2. Fazer login com as credenciais do representante
3. Acessar aba "Entregas" (LoadDeliveriesFragment)
4. Clicar em "Sincronizar Entregas" e verificar:
   - ✅ Progresso aparece
   - ✅ Entregas são listadas
   - ✅ Logs mostram HTTP 200 e contagem correta
   - ✅ Dados aparecem nos campos do card

4. Verificar também:
   - Aba "Pedidos" - Sincronizar Pedidos (deve funcionar como antes)
   - Aba "Cargas" - Sincronizar Cargas (corrigida em v4)
   - Abrir PDF e Editar de um pedido

## 🔍 Debug via Logcat
```bash
# Terminal do Android Studio ou via adb:
adb logcat | grep -E "LoadDeliveriesFragment|LoadsFragment|OrdersFragment|ERPOrder"

# Filtros específicos:
adb logcat LoadDeliveriesFragment:D LoadsFragment:D OrdersFragment:D ERPOrderLocalRepository:D *:S
```

## 📝 Resumo das Mudanças de Código

### LoadDeliveriesFragment.kt
- **Linhas 1-20:** Adicionado import `android.util.Log`
- **Linhas 25:** Removido field privado hardcoded `token`
- **Linhas 60-68:** Adicionado centralização de token via `ERPConfig.getERPToken()`
- **Linhas 70-78:** Mudança de `Usuarioid` para `Representanteid` + logging de conexão
- **Linhas 84-92:** Adicionado logging de response code, tamanho e contagem
- **Linhas 107:** Mensagem de erro melhorada com `exception.message`

## 🚀 Esperado
Agora que os três endpoints (pedidos, cargas, entregas) estão usando corretamente:
1. Parâmetro `Representanteid` (não `Usuarioid`)
2. Token centralizado em `ERPConfig`
3. Logging detalhado para debug

As entregas deverão aparecer normalmente na tela!
