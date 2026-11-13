# 🔍 DEBUG: Cargas Sincronizadas mas Não Aparecem

## Instalação
```
APK: app-debug-v8-DEBUG.apk
Versão: v8
```

## 📱 Passos para Testar

### 1️⃣ Abra o Logcat
```bash
# Terminal / PowerShell:
adb logcat LoadsFragment:D *:S
```

### 2️⃣ Abra o app → Aba "Cargas"

### 3️⃣ Clique em "Sincronizar Cargas"

## 🔍 Procure por Estes Logs (em ordem)

### Se FUNCIONOU (dados baixados):
```
LoadsFragment: Buscando cargas para usuário: 10418
LoadsFragment: Token: dSJQ4qUlSwdNs3T/OipnOo8F...
LoadsFragment: Conectando a: https://erpgnisn.specialnutri.com.br/...
LoadsFragment: HTTP Response Code: 200
LoadsFragment: Response length: 1234 bytes
LoadsFragment: Encontradas 5 cargas
LoadsFragment: Primeiro item: {"CargaId":"123","CargaDescricao":"..."}
LoadsFragment: Chaves do primeiro item: [CargaId, CargaDescricao, CargaStatus, ...]
```

### Se FALHOU (erro HTTP):
```
LoadsFragment: HTTP Response Code: 404  (ou 401, 500, etc)
LoadsFragment: Erro ao buscar cargas: ...
```

### Renderização (deve aparecer depois do download):
```
LoadsFragment: showLoads chamado com 5 cargas
LoadsFragment: Renderizando 5 cargas
LoadsFragment: Adicionando card para carga: 123 (descrição: Carga XYZ)
LoadsFragment: ✅ Card adicionado à lista para carga: 123
LoadsFragment: Adicionando card para carga: 124 (descrição: Carga ABC)
LoadsFragment: ✅ Card adicionado à lista para carga: 124
...
LoadsFragment: ✅ Total de cargas renderizadas: 5
```

## 🚨 Cenários Possíveis

### Cenário 1: Logs mostram HTTP 200 + "Encontradas 5 cargas" MAS nada aparece
**Problema:** Cache offline pode estar corrompido ou estrutura de dados está diferente

**Solução:**
1. Limpe os dados do app: `Settings → Apps → ForcaVendas → Storage → Clear`
2. Teste novamente
3. Se continuar, a estrutura JSON da API pode estar diferente do esperado

### Cenário 2: Logs mostram HTTP 401 ou 403
**Problema:** Token ou Representante ID errado

**Solução:**
1. Verifique o ID do usuário logado
2. Verifique se o token em ERPConfig.kt está correto
3. Teste manualmente a URL no navegador/Postman com seus dados

### Cenário 3: Logs mostram HTTP 404
**Problema:** Endpoint não encontrado no ERP

**Solução:**
1. Verifique a URL do endpoint (dpCargaRepresentanteAppVenda)
2. Pode ser que o ERP mudou o endpoint

### Cenário 4: Logs mostram que renderizou MAS sem dados
**Problema:** Cache vazio ou dados não estão sendo carregados do offline

**Solução:**
1. Verifique SharedPreferences com comando:
```bash
adb shell "run-as com.example.forcavendas cat /data/data/com.example.forcavendas/shared_prefs/forca_vendas.xml | grep loads_synced"
```

2. Se mostrar algo como:
```xml
<string name="loads_synced_json">[{"CargaId":"123",...}]</string>
```
Então os dados estão lá mas não estão sendo renderizados.

## 📊 Checklist

- [ ] APK v8-DEBUG instalado
- [ ] Logcat aberto com filtro LoadsFragment
- [ ] Clicou em "Sincronizar Cargas"
- [ ] Aguardou a conexão completar
- [ ] Viu os logs de HTTP 200
- [ ] Viu os logs de "Encontradas X cargas"
- [ ] Viu os logs de renderização (Card adicionado)
- [ ] Verificou se cards aparecem na tela

## 💡 Dicas de Debug

1. **Se suspeita de cache corrompido:**
```bash
adb shell pm clear com.example.forcavendas
```

2. **Se quer resetar dados offline:**
```bash
adb shell run-as com.example.forcavendas rm -f /data/data/com.example.forcavendas/shared_prefs/*
```

3. **Se quer ver EXATAMENTE o que a API retorna:**
Na linha 88 do LoadsFragment, o log mostra: `"Primeiro item: $firstLoad"`
Copie esse JSON e analise a estrutura (compare com os campos que o código tenta ler)

## 🎯 Próximas Ações Após Investigação

1. **Se HTTP 200 + dados renderizados:** ✅ Problema resolvido!
2. **Se HTTP erro:** Precisa investigar credenciais/token/endpoint
3. **Se dados vazios:** Pode ser problema de estrutura JSON da API
4. **Se renderiza mas sem dados:** SharedPreferences pode estar corrompido

Compartilhe os logs do Logcat aqui para que eu possa ver exatamente o que está acontecendo! 🔍
