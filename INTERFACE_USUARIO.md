# Interface de Usuário - Pedidos

## Tela de Pedidos Após Implementação

```
┌────────────────────────────────────────────┐
│ ← [PEDIDOS]              [🔄][↻]           │
├────────────────────────────────────────────┤
│                                            │
│ ┌──────────────────────────────────────┐  │
│ │ Código Mobile: 1224217      ✏️        │  │
│ │ Código ERP: -                        │  │
│ │ Status: RASCUNHO                     │  │
│ │                                      │  │
│ │ Cliente ABC Importadora              │  │
│ │ Valor: R$ 1.905,60                   │  │
│ │ Peso: 250.000 kg                     │  │
│ │                                      │  │
│ │ [Alterar pedido] [Gerar PDF] [JSON]  │  │
│ └──────────────────────────────────────┘  │
│                                            │
│ ┌──────────────────────────────────────┐  │
│ │ Código Mobile: 1224218               │  │
│ │ Código ERP: 67890                    │  │
│ │ Status: FATURADO                     │  │
│ │                                      │  │
│ │ Cliente XYZ Distribuidora             │  │
│ │ Valor: R$ 3.450,00                   │  │
│ │ Peso: 125.500 kg                     │  │
│ │                                      │  │
│ │ [Gerar PDF] [JSON] [Baixar...]       │  │
│ └──────────────────────────────────────┘  │
│                                            │
│ ┌──────────────────────────────────────┐  │
│ │ Código Mobile: 1224219               │  │
│ │ Código ERP: 67891                    │  │
│ │ Status: AGUARDANDO FATURAMENTO       │  │
│ │                                      │  │
│ │ Cliente DEF Varejo LTDA              │  │
│ │ Valor: R$ 890,50                     │  │
│ │ Peso: 45.200 kg                      │  │
│ │                                      │  │
│ │ [Gerar PDF] [JSON]                   │  │
│ └──────────────────────────────────────┘  │
│                                            │
│ 📊 Total de Pedidos: 35                    │
│    - Rascunhos: 20                         │
│    - No ERP: 15                            │
│                                            │
└────────────────────────────────────────────┘
```

---

## Botões de Ação

### 1. Botão Sincronizar (🔄)
**Localização**: Top Bar - Posição esquerda
**Ação**: Sincroniza pedidos **pendentes** com ERP
**Fluxo**:
```
Clique → Dialog confirmação
         ↓
      Sincronizar → Progress dialog
                   ↓
               Resultado (sucesso/erro)
                   ↓
              Lista atualizada
```

### 2. Botão Atualizar Histórico (↻)
**Localização**: Top Bar - Posição direita
**Ação**: Baixa pedidos **já processados** do ERP
**Fluxo**:
```
Clique → Dialog confirmação
         ↓
      Baixar → Progress dialog ("Conectando...")
             ↓
         API retorna pedidos
             ↓
         Salva em SQLite
             ↓
        Resultado com contagem
             ↓
        Lista atualizada
```

---

## Dialog de Confirmação - Sincronizar Rascunhos

```
╔════════════════════════════════════════╗
║ SINCRONIZAR PEDIDOS                   ║
╠════════════════════════════════════════╣
║                                        ║
║  Deseja sincronizar 5 pedido(s)       ║
║  com o ERP?                           ║
║                                        ║
║  Os pedidos serão enviados para       ║
║  processamento no servidor.           ║
║                                        ║
║                                        ║
║        [NÃO]        [SIM]             ║
╚════════════════════════════════════════╝
```

---

## Dialog de Confirmação - Baixar Histórico

```
╔════════════════════════════════════════╗
║ BAIXAR PEDIDOS DO ERP                 ║
╠════════════════════════════════════════╣
║                                        ║
║  Deseja baixar os pedidos que         ║
║  já foram enviados ao ERP?            ║
║                                        ║
║  Isso sincronizará seu histórico      ║
║  com os pedidos já processados.       ║
║                                        ║
║                                        ║
║      [CANCELAR]      [BAIXAR]         ║
╚════════════════════════════════════════╝
```

---

## Dialog de Progresso - Durante Download

```
╔════════════════════════════════════════╗
║ BAIXANDO PEDIDOS                      ║
╠════════════════════════════════════════╣
║                                        ║
║  Conectando ao servidor ERP...        ║
║                                        ║
║  Por favor, aguarde...                ║
║                                        ║
║  ⏳ [===================>  ] 60%      ║
║                                        ║
║  Não é possível cancelar              ║
║                                        ║
╚════════════════════════════════════════╝
```

---

## Dialog de Resultado - Sucesso

```
╔════════════════════════════════════════╗
║ ✓ SUCESSO                             ║
╠════════════════════════════════════════╣
║                                        ║
║  15 pedidos sincronizados com sucesso ║
║                                        ║
║  Total de pedidos no histórico:       ║
║  • Rascunhos: 20                      ║
║  • Processados: 15                    ║
║                                        ║
║            [OK]                       ║
╚════════════════════════════════════════╝
```

---

## Dialog de Resultado - Erro

```
╔════════════════════════════════════════╗
║ ✗ ERRO                                ║
╠════════════════════════════════════════╣
║                                        ║
║  Erro ao conectar com o servidor      ║
║                                        ║
║  HTTP 401 - Token inválido            ║
║                                        ║
║  Por favor, faça login novamente      ║
║  e tente sincronizar.                 ║
║                                        ║
║            [OK]                       ║
╚════════════════════════════════════════╝
```

---

## Card de Pedido - Tipo Rascunho

```
┌──────────────────────────────────────┐
│ • Código Mobile: 1224217             │
│ • Código ERP: -                      │
│ • Status: RASCUNHO                   │
│                                      │
│ Cliente ABC Importadora              │
│                                      │
│ Valor do pedido: R$ 1.905,60         │
│ Peso do pedido: 250.000 kg           │
│                                      │
│ [BOTÃO] Alterar pedido               │
│ [BOTÃO] Gerar PDF do pedido          │
│ [BOTÃO] Gerar JSON do pedido         │
│                                      │
└──────────────────────────────────────┘

Características:
✓ Botão "Alterar pedido" visível
✓ Pode ser editado
✓ Pode ser deletado
✓ ERP ID mostra "-" (não enviado)
✓ Status é "RASCUNHO"
```

---

## Card de Pedido - Tipo ERP (Histórico)

```
┌──────────────────────────────────────┐
│ • Código Mobile: 1224218             │
│ • Código ERP: 67890                  │
│ • Status: FATURADO                   │
│                                      │
│ Cliente XYZ Distribuidora             │
│                                      │
│ Valor do pedido: R$ 3.450,00         │
│ Peso do pedido: 125.500 kg           │
│                                      │
│ [BOTÃO] Gerar PDF do pedido          │
│ [BOTÃO] Gerar JSON do pedido         │
│                                      │
│ Alteração bloqueada após o envio     │
│ ao ERP.                              │
│                                      │
└──────────────────────────────────────┘

Características:
✗ Botão "Alterar pedido" NÃO visível
✗ Não pode ser editado
✗ Não pode ser deletado
✓ ERP ID mostra número (processado)
✓ Status é do ERP (FATURADO, etc)
✓ Apenas leitura (PDF/JSON)
```

---

## Fluxo de Interação - Sincronizar Rascunhos

```
REPRESENTANTE
     |
     | Clica em [🔄] "Sincronizar"
     ↓
App Verifica:
├─ Há rascunhos pendentes?
│  ├─ SIM → Exibe dialog
│  └─ NÃO → Toast "Nenhum pendente"
│
└─ Dialog: "Sincronizar 5 pedido(s)?"
     |
     | Clica em [SIM]
     ↓
Para cada rascunho (thread background):
├─ Preparar JSON do pedido
├─ Enviar para API ERP
├─ Validar resposta (sucesso?)
│  ├─ SIM → Extrair ERP ID
│  │         └─ Atualizar status
│  └─ NÃO → Contar erro
│
└─ Após terminar todos:
     ├─ Voltar para main thread
     ├─ Exibir resultado:
     │  "5 enviados | 0 erros"
     ├─ Fechar dialog
     └─ Recarregar lista (renderOrders)
        └─ Rascunhos agora mostram ERP ID
           Status = "ENVIADO AO ERP"
```

---

## Fluxo de Interação - Baixar Histórico

```
REPRESENTANTE
     |
     | Clica em [↻] "Atualizar Histórico"
     ↓
App Verifica:
├─ Está autenticado?
│  ├─ SIM → Prosseguir
│  └─ NÃO → Toast "Login necessário"
│
└─ Dialog: "Sincronizar histórico?"
     |
     | Clica em [BAIXAR]
     ↓
Obter credenciais (SessionManager):
├─ user_id = "10420"
├─ session_signature = "10420|License123"
└─ Validar preenchimento

Fazer requisição (thread background):
├─ URL: https://erpgnisn.specialnutri.com.br/...
├─ GET com ?Parametrostegaceptoken=...
│         &Representanteid=10420
│
├─ Resposta HTTP?
│  ├─ 200 OK → Continuar
│  ├─ 401 Unauthorized → Erro autenticação
│  ├─ 500 Error → Erro servidor
│  └─ Timeout → Erro conexão
│
└─ Se 200 OK:
    ├─ Parse JSON array
    ├─ Validar estrutura (15 pedidos)
    └─ Salvar em SQLite (transação):
        ├─ erp_orders: INSERT/UPDATE
        └─ erp_sync_metadata: UPDATE

Volta para main thread:
├─ Fechar progress dialog
├─ Exibir resultado:
│  "✓ 15 pedidos sincronizados"
├─ Chamar renderOrders()
└─ Lista atualizada:
    ├─ 20 Rascunhos
    └─ 15 Pedidos do ERP (NOVO)
```

---

## Estados Possíveis da Tela

### 1. Estado Vazio
```
┌────────────────────────────────────────┐
│ ← [PEDIDOS]              [🔄][↻]       │
├────────────────────────────────────────┤
│                                        │
│                                        │
│            Você não tem                │
│            pedidos no histórico.       │
│                                        │
│            Clique em [↻] para          │
│            sincronizar com o ERP.      │
│                                        │
│                                        │
└────────────────────────────────────────┘
```

### 2. Estado Com Rascunhos
```
┌────────────────────────────────────────┐
│ ← [PEDIDOS]              [🔄][↻]       │
├────────────────────────────────────────┤
│                                        │
│ [20 pedidos rascunho]                  │
│                                        │
│ Total: 20 rascunhos                    │
│ Status: Nenhum enviado ao ERP          │
│                                        │
│ Ação: Clique em [🔄] para enviar       │
│                                        │
└────────────────────────────────────────┘
```

### 3. Estado Híbrido (Rascunho + ERP)
```
┌────────────────────────────────────────┐
│ ← [PEDIDOS]              [🔄][↻]       │
├────────────────────────────────────────┤
│                                        │
│ [20 pedidos rascunho + 15 do ERP]      │
│                                        │
│ Total: 35 pedidos                      │
│  • 20 Rascunhos (pendentes)            │
│  • 15 Processados no ERP               │
│                                        │
│ Ações:                                 │
│ • [🔄] Enviar rascunhos                │
│ • [↻] Atualizar histórico              │
│                                        │
└────────────────────────────────────────┘
```

### 4. Estado Com Apenas ERP
```
┌────────────────────────────────────────┐
│ ← [PEDIDOS]              [🔄][↻]       │
├────────────────────────────────────────┤
│                                        │
│ [15 pedidos do ERP]                    │
│                                        │
│ Total: 15 pedidos processados          │
│ Status: Todos já faturados             │
│                                        │
│ Ação: Clique em [↻] para atualizar     │
│       o histórico com novos pedidos    │
│                                        │
└────────────────────────────────────────┘
```

---

## Atalhos de Teclado (Futuro)

| Atalho | Ação |
|--------|------|
| Swipe direita | Voltar |
| Swipe esquerda | Próximo pedido |
| Long press | Menu contexto |
| Double tap | Gerar PDF |

---

## Toast Messages

```
✓ Sucesso:
  "15 pedidos sincronizados com sucesso"

⚠ Aviso:
  "Nenhum pedido pendente para sincronizar"
  "ID do representante não encontrado"

✗ Erro:
  "Erro ao conectar com ERP"
  "Token não disponível - Faça login"
  "Erro ao extrair dados da resposta"
```

---

## Notas Sobre a UI

1. **Reutilização de Código**
   - `renderOrderCard()` renderiza tanto drafts quanto ERP orders
   - Estrutura unificada facilita manutenção

2. **Indicadores Visuais**
   - Status diferencia pedidos novos (rascunho) de processados
   - ERP ID mostra "-" para não enviados, número para enviados

3. **Acessibilidade**
   - Botões com descrição clara
   - Diálogos confirmam ações destrutivas
   - Mensagens de erro específicas

4. **Performance**
   - Progress dialog não bloqueia cancelamento total
   - Thread background evita janela congelada
   - Renderização incremental (lazy loading)

5. **Experiência do Usuário**
   - Dialog progressivo fornece feedback
   - Toast confirma ações rápidas
   - Lista recarrega automaticamente após sucesso
