# 🧪 Quick Testing Checklist - Força de Vendas ERP Download

## Installation
```bash
adb install D:\Projeto\ForcaVendas\app-debug.apk
```

---

## Smoke Test (5 minutes)

### Basic Launch
- [ ] App launches
- [ ] No immediate crashes
- [ ] Login screen appears

### Navigation
- [ ] Login with test credentials
- [ ] Navigate to "Pedidos" (Orders)
- [ ] Orders screen loads

### UI Elements
- [ ] Order list visible
- [ ] "↻ Atualizar Histórico" button visible
- [ ] Order cards display correctly

---

## ERP Download Test (10 minutes) ⭐ **CRITICAL**

### Pre-Test
- [ ] Logged in with representative ID 10418 (or valid ID)
- [ ] Internet connection active
- [ ] No existing downloaded orders

### Download Flow
1. [ ] Click "↻ Atualizar Histórico"
2. [ ] Confirmation dialog appears: "Deseja baixar os pedidos que já foram enviados ao ERP?"
3. [ ] Click "Baixar"
4. [ ] Progress dialog shows: "Conectando ao servidor ERP..."
5. [ ] Wait for response (5-15 seconds typically)
6. [ ] Success dialog appears with count
   - Expected: "Sincronização concluída!\n\nN pedido(s) adicionado(s) ao histórico"
7. [ ] Orders list updates with downloaded orders
8. [ ] Downloaded orders show status as "Processado" or similar

### Anti-Duplication Test
1. [ ] Click "↻ Atualizar Histórico" again
2. [ ] Confirm download
3. [ ] Observe that no new duplicates are added
4. [ ] Sync metadata timestamp updates (internal verification)

### Error Handling Test
1. [ ] Turn off internet
2. [ ] Click "↻ Atualizar Histórico"
3. [ ] Error dialog should appear: "Erro ao conectar ao servidor"
4. [ ] Click OK to dismiss
5. [ ] Turn on internet
6. [ ] Retry download should work

---

## UI/UX Verification

### Order Card Display
- [ ] Mobile ID (código mobile) shows correctly
- [ ] ERP ID (código ERP) shows `-` before download, displays number after
- [ ] Client name displays
- [ ] Order value displays in BRL currency
- [ ] Status field shows "Rascunho" or "Processado"

### Order Actions
- [ ] "✎ Editar" button visible for draft orders only
- [ ] "ⓧ Remover" button visible for draft orders only
- [ ] "⬇ PDF" button visible on all orders
- [ ] "⬌ JSON" button visible on all orders

### Downloaded Order Differences
- [ ] Editar/Remover buttons hidden for downloaded orders
- [ ] Status clearly marked as non-editable
- [ ] Visual distinction from draft orders (color/style)

---

## Database Verification

### Persistent Storage
1. [ ] Close app completely
2. [ ] Reopen app
3. [ ] Navigate to Orders
4. [ ] Downloaded orders still visible
5. [ ] No data loss on restart

### Concurrent Orders
- [ ] Mix of draft orders and downloaded orders displays
- [ ] No conflicts or overlaps
- [ ] Sorting by mobile ID works correctly

---

## Performance Tests

### Large Order List (100+ orders)
- [ ] App remains responsive
- [ ] Scrolling is smooth
- [ ] No lag when loading list
- [ ] Memory usage reasonable

### Network Conditions
- [ ] Works on 4G/LTE
- [ ] Works on WiFi
- [ ] Handles slow connections gracefully
- [ ] Timeout handling works (>30s waits)

---

## Logging

### Check Logcat
```bash
adb logcat | findstr "ERP"
```

Look for:
- [ ] `ERPOrderDownloadService: Starting download for representante_id: 10418`
- [ ] `ERPOrderDownloadService: API response received`
- [ ] `ERPOrderLocalRepository: Saving N orders to database`
- [ ] `ERPOrderLocalRepository: Sync completed at timestamp: XXXX`

### No Errors Expected
- [ ] No NullPointerException
- [ ] No ClassNotFoundException
- [ ] No SQLiteException
- [ ] No NetworkException (only expected on offline)

---

## Device Compatibility

Test on (if available):
- [ ] Android 12+ (modern device)
- [ ] Android 10-11 (mid-range)
- [ ] Android 8-9 (older device)
- [ ] Emulator (Android API 24+)

---

## Known Issues (To Ignore)

- ⚠️ "Editar" button shows "Editar não implementado" toast
  - **Expected**: This is a TODO for Phase 2
  - **Action**: No action needed

- ⚠️ Deprecation warnings in Gradle
  - **Expected**: Java Locale deprecation warnings
  - **Action**: No action needed, doesn't affect app

---

## Test Results Log

```
Date: _________________
Tester: _________________
Device Model: _________________
Android Version: _________________
App Version: app-debug.apk

✓ Smoke Test: PASS / FAIL
✓ ERP Download: PASS / FAIL
✓ Anti-Duplication: PASS / FAIL
✓ Error Handling: PASS / FAIL
✓ UI/UX: PASS / FAIL
✓ Database Persistence: PASS / FAIL
✓ Performance: PASS / FAIL

Issues Found:
1. ____________________________
2. ____________________________
3. ____________________________

Notes:
_________________________________
_________________________________
_________________________________

Overall: PASS / FAIL
```

---

## Success Criteria

✅ **PASS** if:
1. App installs without errors
2. ERP orders download successfully
3. No duplicates on second download
4. Orders persist after app restart
5. All error cases handled gracefully

❌ **FAIL** if:
1. App crashes on download
2. Duplicates appear on second sync
3. Orders lost after restart
4. UI becomes unresponsive

---

**⏱️ Estimated Testing Time: 25-30 minutes**

**📝 Report to:** Development Team
**🔗 APK Location:** `D:\Projeto\ForcaVendas\app-debug.apk`
