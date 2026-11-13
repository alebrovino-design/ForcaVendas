# 🔍 Debugging - Pedidos ERP não Aparecem

## 🚀 Passo 1: Instalar APK e Executar Teste

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c  # Limpar logs anteriores
adb logcat | grep -E "ERPOrder|OrdersFragment"
```

## 📱 Passo 2: Reproduzir o Erro

1. Abrir app "Força de Vendas"
2. Login com seu usuário
3. Ir para aba "Pedidos"
4. Clicar "⬇️ Baixar Pedidos do ERP"
5. Aguardar download

## 📊 Passo 3: Analisar Logs

### A. Logs de Download

**Procurar por:**
```
D/ERPOrderDownload: HTTP Response Code: 200
D/ERPOrderDownload: Response length: XXXXX bytes
D/ERPOrderDownload: Encontrados X pedidos
```

✅ Se aparecer → Download OK!  
❌ Se não aparecer → Problema na API

### B. Logs de Salvamento

**Procurar por:**
```
D/ERPOrderRepository: Salvando X pedidos
D/ERPOrderRepository: Primeiro pedido: {JSON aqui}
D/ERPOrderRepository: Pedido 12345: Nome Cliente (R$ valor)
D/ERPOrderRepository: Transação bem-sucedida
```

✅ Se aparecer → Salvamento OK!  
❌ Se não aparecer → Problema no banco de dados

### C. Logs de Renderização

**Procurar por:**
```
D/OrdersFragment: Rascunhos: X, ERP: Y
D/OrdersFragment: Total de pedidos: X
D/OrdersFragment: Renderizando pedido: 12345 - Nome Cliente
D/OrdersFragment: renderOrderCard chamado: mobile=12345, erp=67890
D/OrdersFragment: Order object null? false
D/OrdersFragment: Order keys: [...]
```

✅ Se aparecer → Renderização tentando processar!  
❌ Se `Order object null? true` → Problema no JSON

---

## 🐛 Problema 1: Pedidos não baixam (HTTP error)

**Sintomas:**
- Mensagem de erro na tela
- Logs mostram HTTP 401, 403, 404 ou 500

**Solução:**
```bash
# Ver erro específico
adb logcat | grep "ERPOrderDownload: HTTP"

# Exemplos:
# HTTP 401 → Token inválido
# HTTP 403 → Sem permissão
# HTTP 404 → Endpoint errado
# HTTP 500 → Servidor offline
```

→ Ver [DEBUG_GUIDE.md](DEBUG_GUIDE.md) para soluções

---

## 🐛 Problema 2: Pedidos baixam (HTTP 200) mas não aparecem

**Sintomas:**
- Mensagem "X pedidos sincronizados com sucesso"
- Mas nenhum pedido aparece na tela

**Checklist:**

### A. Verificar se dados foram salvos no BD

```bash
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db ".tables"
# Output esperado: erp_orders erp_sync_metadata

adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT COUNT(*) FROM erp_orders;"
# Output esperado: X (número de pedidos)

# Ver dados específicos
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT mobile_id, erp_id, client_name, total FROM erp_orders LIMIT 5;"
```

✅ Se aparecem dados → Banco OK!  
❌ Se COUNT=0 → Não foram salvos

### B. Verificar JSON

```bash
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT json_data FROM erp_orders LIMIT 1;" > /tmp/pedido.json

# Ver arquivo (pode ser longo)
cat /tmp/pedido.json | jq . | head -50
```

✅ Se JSON tem `sdtPedidoAppVenda` → OK!  
❌ Se vazio ou malformado → Problema no parsing

### C. Verificar se está carregando do BD

```bash
# Logs mostram "Rascunhos: 0, ERP: X"?
adb logcat | grep "Rascunhos:"
```

✅ Se `ERP: 5` → Está carregando!  
❌ Se `ERP: 0` → Não está lendo o BD

---

## 🐛 Problema 3: Dados carregam mas fields ficam vazios

**Sintomas:**
- Pedido aparece (card renderizado)
- Mas campos mostram "-" ou vazios

**Causa provável:** JSON não tem chaves esperadas

**Solução:**

1. Ver logs do first pedido:
```bash
adb logcat | grep "Primeiro pedido:"
```

2. Copiar o JSON e analisar em um JSON formatter

3. Procurar pelas chaves:
```
PedidoMobId
PedidoERPId
PedidoEntidadeNome
PedidoVlrTotal
PedidoDtEmissao
PedidoStatus
```

❌ Se falta chaves → API retorna estrutura diferente  
✅ Se tem chaves → Problema no parsing ou no template

---

## 📋 Quick Checklist

```
[ ] APK v4 compilou? (look for BUILD SUCCESSFUL)
[ ] Download retornou HTTP 200?
[ ] Logs mostram "Encontrados X pedidos"?
[ ] Banco de dados tem dados (COUNT > 0)?
[ ] JSON tem estrutura correta (sdtPedidoAppVenda existe)?
[ ] Logs mostram "Renderizando pedido..."?
[ ] Order object não é null?
[ ] Card aparece na tela?
[ ] Campos mostram valores (não "-")?
[ ] Botões PDF/JSON funcionam?
```

---

## 🔍 Analyze Passo-a-passo

### 1. Download OK?
```bash
adb logcat | grep "HTTP Response Code: 200"
```

### 2. Salvamento OK?
```bash
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT COUNT(*) FROM erp_orders;"
```

### 3. Renderização OK?
```bash
adb logcat | grep "Renderizando pedido:"
```

### 4. JSON OK?
```bash
adb logcat | grep "Order object null"
```

---

## 💡 Se Ainda Tiver Erro

1. **Copiar logs completos:**
```bash
adb logcat -d > logs_completos.txt
# Enviar arquivo para análise
```

2. **Salvar estado do banco:**
```bash
adb shell "sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db '.dump erp_orders' | head -20" > pedidos_banco.txt
```

3. **Verificar permissões:**
```bash
adb shell pm dump com.example.forcavendas | grep permission
```

---

## ✅ Se Tudo Funciona!

🎉 Então o problema foi resolvido!

**Próximas ações:**
1. [ ] Testar PDF (clicar botão "📄 PDF")
2. [ ] Testar JSON (clicar botão "📦 JSON")
3. [ ] Testar Editar (clicar botão "✏️ Editar")
4. [ ] Testar Remover (clicar botão "🗑️ Remover")

---

**Última atualização:** 13/11/2026
