# 🔐 Correção de Token ERP - README

## Problema
Token ERP estava sendo substituído por `session_signature` (user_id|license):
```
❌ ANTES: Parametrostegaceptoken=10418|license_key
✅ DEPOIS: Parametrostegaceptoken=dSJQ4qUlSwdNs3T/OipnOo8...
```

## Solução
Criado `ERPConfig.kt` para gerenciar token ERP centralizadamente.

## Arquivos Modificados
1. **ERPConfig.kt** (novo) - Gerenciamento de token
2. **ERPOrderDownloadService.kt** - Usa ERPConfig.getERPToken()
3. **OrdersFragment.kt** - Chamada simplificada

## Build Status
- ✅ BUILD SUCCESSFUL (34s)
- ✅ 0 errors, 2 warnings (harmless)
- ✅ APK: 7.24 MB

## Teste Rápido
```bash
adb install D:\Projeto\ForcaVendas\app-debug.apk
# Clicar "↻ Atualizar Histórico" → Deve baixar pedidos sem erro
```

## Documentação Completa
- TOKEN_FIX_REPORT.md - Detalhes técnicos
- VERIFICACAO_TOKEN.md - Checklist de teste
- SUMARIO_CONSOLIDADO_CORRECAO.md - Visão geral

---
**Status:** ✅ PRONTO PARA TESTE COM API REAL
