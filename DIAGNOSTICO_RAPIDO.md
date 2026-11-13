# 🆘 Diagnóstico Rápido - Pedidos não aparecem

## ⚡ Se NÃO está vendo os pedidos sincronizados

### Passo 1: Reinstalar APK v4 (com melhor logging)

```bash
adb uninstall com.example.forcavendas
adb install D:\Projeto\ForcaVendas\app\build\outputs\apk\debug\app-debug.apk
```

### Passo 2: Ver os logs (abrir em DOIS terminais)

**Terminal 1:**
```bash
adb logcat -c
adb logcat | grep -E "ERPOrder|OrdersFragment"
```

**Terminal 2:**
```bash
# Aqui você usa o app (não precisa digitar nada)
```

### Passo 3: Reproduzir (no app no emulador/dispositivo)

1. Login
2. Ir para "Pedidos"
3. Clicar "⬇️ Baixar Pedidos do ERP"
4. Observar logs no Terminal 1

---

## 📊 Analisar Resultado dos Logs

### Cenário A: Vê "HTTP Response Code: 200"

```
D/ERPOrderDownload: HTTP Response Code: 200
D/ERPOrderDownload: Response length: 5432 bytes
D/ERPOrderDownload: Encontrados 15 pedidos
D/ERPOrderRepository: Salvando 15 pedidos
```

✅ **Download OK!**

**Próximo passo:** Ver **Cenário C** abaixo

---

### Cenário B: Vê erro HTTP (401, 403, 404, 500)

```
D/ERPOrderDownload: HTTP 401 - Autenticação falhou
```

❌ **Problema na API**

**Solução:**
- Verificar token em `ERPConfig.kt`
- Verificar Representante ID (user_id)
- Ver [DEBUG_GUIDE.md](DEBUG_GUIDE.md) para detalhes

---

### Cenário C: HTTP 200 mas pedidos não aparecem

**Procurar no log:**
```
D/OrdersFragment: Rascunhos: 0, ERP: 5
D/OrdersFragment: Total de pedidos: 5
D/OrdersFragment: Renderizando pedido: 98765 - NOME CLIENTE
```

✅ Se aparecer → Pedidos estão sendo renderizados!

**Verificar:**
```
D/OrdersFragment: Order object null? false
D/OrdersFragment: Order keys: [...]
```

✅ Se `Order object null? false` → JSON está OK!

---

## 🔧 Se Tiver Problema de Dados Vazios

**Ver log:**
```
D/ERPOrderRepository: Primeiro pedido: {JSON AQUI}
D/ERPOrderRepository: Pedido 67890: NOME CLIENTE (R$ valor)
```

❌ Se aparece `-` (hífen) ou "Desconhecido"  
→ Campo está vindo vazio da API

**Solução:**
1. Copiar o JSON do log
2. Verificar se tem as chaves:
   - `PedidoMobId`
   - `PedidoERPId`
   - `PedidoEntidadeNome`
   - `PedidoVlrTotal`

3. Se falta chaves → API retorna estrutura diferente

---

## 📱 Verificar Banco de Dados Diretamente

```bash
# Ver se tem pedidos salvos
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT COUNT(*) FROM erp_orders;"

# Ver primeiros 3 pedidos
adb shell sqlite3 /data/data/com.example.forcavendas/databases/forca_vendas_erp_orders.db "SELECT mobile_id, client_name, total FROM erp_orders LIMIT 3;"
```

✅ Se aparecem dados → Banco OK!  
❌ Se `COUNT=0` → Não foram salvos

---

## 🔄 Testes dos Botões

### Teste 1: Botão PDF (📄)
- Clicar em "📄 PDF"
- Log esperado: `Gerando PDF para pedido 98765`
- Esperado: Abrir PDF ou perguntar com qual app abrir

### Teste 2: Botão JSON (📦)
- Clicar em "📦 JSON"
- Log esperado: `Compartilhando JSON para pedido 98765`
- Esperado: Abrir menu de compartilhar

### Teste 3: Botão Editar (✏️) [Apenas rascunhos]
- Clicar em "✏️ Editar"
- Log esperado: `Navegar para edição do pedido 98765`
- Esperado: Toast "Editando pedido 98765"

### Teste 4: Botão Remover (🗑️) [Apenas rascunhos]
- Clicar em "🗑️ Remover"
- Esperado: Diálogo de confirmação
- Clicar "Remover"
- Esperado: Pedido desaparece da lista

---

## 🎯 Se Tudo Funcionar

Parabéns! ✅

**Status:**
- ✅ Download de pedidos ERP funciona
- ✅ Salvamento em BD funciona
- ✅ Renderização funciona
- ✅ Botões funcionam

**Próximos passos:**
1. Testar segunda sincronização (deve reconhecer como duplicata)
2. Testar com múltiplos pedidos
3. Testar em emulador/dispositivo diferente

---

## 📞 Se Ainda Não Funcionar

1. **Coletar logs:**
```bash
adb logcat -d > logs_debug.txt
```

2. **Ver qual é o primeiro erro nos logs:**
```
adb logcat | head -50
```

3. **Procurar por "ERROR" ou "Exception":**
```bash
adb logcat | grep -i "error\|exception"
```

4. **Consultar documentação:**
   - [DEBUGGING_PEDIDOS_NAO_APARECEM.md](DEBUGGING_PEDIDOS_NAO_APARECEM.md) - Análise completa
   - [DEBUG_GUIDE.md](DEBUG_GUIDE.md) - Troubleshooting geral

---

## 📋 Checklist Rápido

- [ ] APK v4 instalado
- [ ] Logcat aberto
- [ ] Pedidos baixados (HTTP 200)
- [ ] Logs mostram "Encontrados X pedidos"
- [ ] Banco tem dados (COUNT > 0)
- [ ] Pedidos aparecem na tela
- [ ] Campos mostram valores (não "-")
- [ ] Botões PDF/JSON funcionam
- [ ] Botão Editar funciona (Toast)
- [ ] Botão Remover funciona

---

**Status:** v4 pronta com logging avançado
