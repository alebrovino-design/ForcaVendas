# ⚡ Teste Rápido - 5 Minutos

## 🚀 Passo 1: Preparar (1 min)

```bash
# Abrir emulador
# Ou conectar dispositivo Android real via USB
```

## 📱 Passo 2: Instalar (1 min)

```bash
cd D:\Projeto\ForcaVendas

# Instalar APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

✅ Esperado: "Success"

## 🧪 Passo 3: Testar Interface (2 min)

1. Abrir app "Força de Vendas"
2. Login com usuário válido
3. Ir para aba "Pedidos"
4. Procurar botão "⬇️ Baixar Pedidos do ERP"

**Verificar:**
- [ ] Botões estão em 2 linhas? (✏️ Editar | 🗑️ Remover na linha 1)
- [ ] Segunda linha tem 📄 PDF | 📦 JSON?
- [ ] Pedidos têm cores diferentes? (🟢 verde=ERP, 🟠 laranja=draft)
- [ ] Emojis aparecem em todos os campos?

## 🔽 Passo 4: Baixar Pedidos (1 min)

1. Clicar botão "⬇️ Baixar Pedidos do ERP"
2. Aguardar resposta

**Esperado:**
- ✅ "X pedidos sincronizados com sucesso" → OK!
- ❌ "HTTP 401" → Token inválido (ver DEBUG_GUIDE.md)
- ❌ "Timeout" → Sem internet (ver DEBUG_GUIDE.md)

## 📊 Ver Logs (se necessário)

```bash
adb logcat | grep "ERPOrderDownload"
```

**Procurar por:**
```
D/ERPOrderDownload: HTTP Response Code: 200
D/ERPOrderDownload: Encontrados 15 pedidos
D/ERPOrderDownload: Pedidos salvos no banco de dados
```

## ✅ Conclusão

Se tudo passou:
- ✅ Layout OK
- ✅ Cores OK
- ✅ Download OK
- ✅ **PRONTO PARA PRODUÇÃO** 🎉

Se tiver problema:
- Consultar `DEBUG_GUIDE.md` (tem soluções para 10+ cenários)

---

**Tempo total:** ~5 minutos
