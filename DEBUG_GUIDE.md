# 🔍 Guia de Debugging - ERP Order Download

Este guia ajuda a diagnosticar problemas com o download de pedidos do ERP.

---

## 📱 Passo 1: Ativar Logcat

### Via Android Studio:
```
1. Conectar dispositivo ou abrir emulador
2. Menu: View → Tool Windows → Logcat
3. Filtrar por: "ERPOrderDownload" (TAG)
```

### Via Terminal (adb):
```bash
adb logcat | grep "ERPOrderDownload"
```

---

## 🧪 Passo 2: Testar API Manualmente

### Com cURL (Terminal):
```bash
curl -v "https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda?Parametrostegaceptoken=<TOKEN_ERP>&Representanteid=10418"
```

**Analisar resposta:**
- `< HTTP/1.1 200` → Sucesso
- `< HTTP/1.1 401` → Token inválido
- `< HTTP/1.1 403` → Sem permissão
- `< HTTP/1.1 404` → Endpoint não encontrado
- `< HTTP/1.1 500` → Erro no servidor

### Com Postman:
1. GET `https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda`
2. Params:
   - `Parametrostegaceptoken`: token ERP
   - `Representanteid`: ID do usuário
3. Headers:
   - `Content-Type: application/json`
   - `Accept: application/json`
4. Clique "Send"

---

## 📋 Passo 3: Verificar Logs no App

### Esperado no sucesso:
```
D/ERPOrderDownload: Iniciando download para representante: 10418
D/ERPOrderDownload: Conectando a: https://erpgnisn.specialnutri.com.br/specialnutri/rest/dpPedidoVendaRepAPPVenda
D/ERPOrderDownload: HTTP Response Code: 200
D/ERPOrderDownload: Response length: 5432 bytes
D/ERPOrderDownload: HTTP 200 OK - Processando resposta
D/ERPOrderDownload: Encontrados 15 pedidos
D/ERPOrderDownload: Pedidos salvos no banco de dados
```

### Esperado em erro 401:
```
E/ERPOrderDownload: HTTP 401 - Autenticação falhou. Verifique token e representante ID
```

### Esperado em timeout:
```
E/ERPOrderDownload: Timeout na conexão com ERP
W/System.err: java.net.SocketTimeoutException: timeout
```

---

## 🔐 Passo 4: Verificar Configurações

### Abrir arquivo de código:
**`app/src/main/java/com/example/forcavendas/ERPConfig.kt`**

```kotlin
private const val DEFAULT_ERP_TOKEN = "<TOKEN_ERP>"
```

✅ **Verificar:**
- [ ] Token não é vazio
- [ ] Token tem mais de 10 caracteres
- [ ] Token começa com `dSJQ...`
- [ ] Não há espaços extras no final

### Versão do Android:
```kotlin
// app/build.gradle.kts
android {
    compileSdk = 34
    minSdk = 21
    targetSdk = 34
}
```

✅ **Verificar:** minSdk ≥ 21 (suporta TLS 1.2)

---

## 🌐 Passo 5: Testar Conectividade

### No emulador:
```bash
# Abrir terminal do emulador
adb shell ping google.com

# Se falhar, fazer:
adb shell settings put global http_proxy <proxy>
adb shell settings delete global http_proxy  # remover proxy
```

### No dispositivo:
- Abrir Settings → WiFi
- Confirmar que está conectado
- Testar: abrir navegador e acessar google.com

---

## 🐛 Passo 6: Cenários de Erro Comuns

### ❌ Erro: "HTTP 401 - Autenticação falhou"

**Causas possíveis:**
1. Token ERP expirou ou é inválido
2. Representante ID incorreto
3. Usuário não tem permissão nesse ERP

**Solução:**
```kotlin
// Em ERPConfig.kt, verificar:
ERPConfig.setERPToken(context, "NOVO_TOKEN_AQUI")
```

### ❌ Erro: "Servidor ERP não respondeu"

**Causas possíveis:**
1. Internet desconectada
2. Servidor ERP offline
3. Firewall bloqueando conexão

**Solução:**
```bash
# Testar conectividade
ping erpgnisn.specialnutri.com.br

# Se não responde, o servidor está offline
# Aguardar e tentar novamente
```

### ❌ Erro: "Nenhum pedido encontrado"

**Causas possíveis:**
1. Representante não tem pedidos no ERP
2. API retorna array vazio `[]`
3. JSON tem estrutura diferente

**Verificar resposta:**
```bash
# Usar cURL com -s e pipar para jq (ou similar)
curl -s "https://erpgnisn.specialnutri.com.br/..." | jq '.' | head -50
```

**Procurar por:**
- Array vazio: `[]` → OK, nenhum pedido
- JSON inválido: erro parsing
- Estrutura diferente: `{value: [...]}` ou `{d: [...]}`

### ❌ Erro: "Timeout"

**Causas possíveis:**
1. Servidor muito lento
2. Muitos pedidos na resposta
3. Conexão instável

**Solução em código:**
```kotlin
// Em ERPOrderDownloadService.kt
val connection = ... as HttpURLConnection
connection.connectTimeout = 60000  // aumentar para 60s
connection.readTimeout = 60000
```

---

## 📊 Passo 7: Validar Estrutura da Resposta

### Resposta esperada:
```json
[
  {
    "PedidoERPId": 12345,
    "PedidoMobId": 98765,
    "ClienteId": 555,
    "ClienteNome": "Supermercado XYZ",
    "RegioId": 10,
    "DataEmissao": "2024-01-15",
    "Total": 1500.00,
    "Status": "Processado",
    "Peso": 45.5,
    "ItensQtd": 25,
    "JSON": "{...JSON serializado...}"
  },
  ...
]
```

### Ou com estrutura envolvida:
```json
{
  "value": [
    { "PedidoERPId": 12345, ... },
    { "PedidoERPId": 12346, ... }
  ]
}
```

✅ **Verificar:** Qual estrutura a API retorna e adaptar em `extractOrders()` se necessário.

---

## 🔄 Passo 8: Teste de Anti-duplicação

### Primeiro download:
```
1. Clicar "⬇️ Baixar Pedidos do ERP"
2. ✅ Resultado: "5 pedidos sincronizados"
3. Verificar banco de dados:
   adb shell sqlite3 /data/data/com.example.forcavendas/databases/orders.db "SELECT COUNT(*) FROM erp_orders;"
   Resultado esperado: 5
```

### Segundo download (mesmo representante):
```
1. Clicar novamente "⬇️ Baixar Pedidos do ERP"
2. ✅ Resultado: "0 pedidos sincronizados" OU "5 pedidos já existem"
3. Verificar banco:
   adb shell sqlite3 /data/data/com.example.forcavendas/databases/orders.db "SELECT COUNT(*) FROM erp_orders;"
   Resultado esperado: 5 (não duplicou)
```

### Se duplicou:
- Verificar `lastSyncTimestamp` em `erp_sync_metadata`
- Verificar lógica em `ERPOrderLocalRepository.saveOrders()`
- Limpar dados:
  ```sql
  DELETE FROM erp_orders;
  DELETE FROM erp_sync_metadata;
  ```

---

## 🧹 Passo 9: Limpar Cache e Dados

Se tudo falhar, fazer reset completo:

### Via Android Studio:
```
1. Device Manager → Selecionar emulador
2. Wipe Data
3. Rebuild APK e reinstalar
```

### Via Terminal:
```bash
adb uninstall com.example.forcavendas
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Via App:
```
1. Settings → Apps → Força de Vendas
2. Clear Cache
3. Clear Storage
4. Force Stop
5. Reabrir app
```

---

## 📞 Suporte Avançado

Se nenhum passo acima resolver:

### 1. Coletar informações:
```bash
# Salvar logs completos
adb logcat -d > logs.txt

# Salvar versão do sistema
adb shell getprop ro.build.version.release > android_version.txt

# Salvar dados do app
adb backup -f backup.ab com.example.forcavendas
```

### 2. Verificar manifesto:
```xml
<!-- AndroidManifest.xml -->
<!-- Confirmar permissões: -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 3. Testar em múltiplos emuladores:
```bash
# Android 6.0 (API 23)
# Android 8.0 (API 26) - com Google Play Services
# Android 12.0 (API 31) - nativo
```

---

## 🎯 Checklist de Debugging

- [ ] Logcat mostrando TAG "ERPOrderDownload"
- [ ] cURL testando API manualmente
- [ ] Token verificado em ERPConfig.kt
- [ ] Representante ID correto
- [ ] Conectividade de internet OK
- [ ] Servidor ERP respondendo (HTTP 200)
- [ ] Resposta JSON válida
- [ ] Estrutura de resposta conhecida
- [ ] Banco de dados salvando pedidos
- [ ] Anti-duplicação funcionando
- [ ] UI mostrando pedidos corretamente

---

**Última atualização:** 13/11/2026  
**Autor:** Copilot SDK
