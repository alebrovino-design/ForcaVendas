# Sincronização de Pedidos com ERP - Força Vendas

## Overview
Foi adicionada a funcionalidade de sincronização de pedidos digitados com o ERP da Special Nutri. Agora os representantes podem:
1. **Enviar pedidos**: Sincronizar pedidos pendentes com o ERP
2. **Visualizar histórico**: Acompanhar o status dos pedidos já enviados

## Alterações Realizadas

### 1. Novo Serviço: OrderSyncService.kt
**Localização**: `app/src/main/java/com/example/forcavendas/OrderSyncService.kt`

Serviço responsável pela sincronização de pedidos com a API do ERP. Funcionalidades:
- `sync(pedido: JSONObject)`: Envia um pedido individual para o ERP
- `wasSynchronized(response: String)`: Verifica se o pedido foi sincronizado com sucesso
- `sendPending(context: Context)`: Sincroniza todos os pedidos pendentes
- `extrairErpId(response: String)`: Extrai o ID gerado pelo ERP na resposta

**Endpoint da API**: `https://erpgnisn.specialnutri.com.br/specialnutri/rest/postPedidoAppVenda`

**Token**: Usa o mesmo token de segurança configurado em `ClientCreateService`

### 2. Atualização do OrdersFragment.kt
**Localização**: `app/src/main/java/com/example/forcavendas/OrdersFragment.kt`

Novos métodos adicionados:
- `sincronizarPedidosPendentes()`: Exibe diálogo confirmando sincronização
- `sincronizarPedidosComProgressao()`: Processa a sincronização com feedback em tempo real
- `atualizarHistorico()`: Preparado para futura funcionalidade de histórico
- `extrairErpId()`: Auxiliar para extrair ID do ERP da resposta

### 3. Atualização do Layout: fragment_orders.xml
**Localização**: `app/src/main/res/layout/fragment_orders.xml`

Adicionados dois novos botões no header:
- **Botão Sincronizar** (`orders_sync`): Ícone de sincronização
- **Botão Atualizar Histórico** (`orders_refresh`): Ícone de atualizar

Estes botões são posicionados no topo do fragmento para fácil acesso.

## Como Funciona

### Exclusão de pedidos pendentes

Na tela de Pedidos, o botão **Excluir pedido** aparece para pedidos locais sem
confirmação do ERP. A exclusão exige confirmação e remove o pedido somente do
aparelho; pedidos confirmados ou em envio não podem ser excluídos.

Após erro de conexão ou tempo limite, o ERP pode ter recebido o pedido mesmo
sem confirmar ao aplicativo. Consulte o ERP antes de excluir ou reenviar.

### Fluxo de Sincronização

```
1. Usuário clica em "Sincronizar Pedidos"
   ↓
2. Sistema conta quantos pedidos estão pendentes (PedidoERPId == 0)
   ↓
3. Se houver pendentes, exibe diálogo confirmando operação
   ↓
4. Para cada pedido pendente:
   - Envia um POST com o JSON no formato `{"sdtPedidoAppVenda": <pedido>}`
   - Exige confirmação `isError: false` e um ID positivo (`PedidoERPId` ou `PedidoId`) na resposta
   - Só então marca o pedido como enviado e grava o ID do ERP
   - Se falhar ou a resposta não trouxer o ID, continua com o próximo pedido e informa a falha
   ↓
5. Ao final, exibe quantos pedidos foram confirmados pelo ERP e detalha as falhas
   ↓
6. Atualiza a lista de pedidos mostrando os novos status
```

### Estrutura do JSON Enviado

```json
{
  "sdtPedidoAppVenda": {
    "PedidoMobId": 1224217,
    "PedidoERPId": 0,
    "PedidoLicencaId": 1,
    "PedidoEmpresaId": 1,
    "PedidoEntidadeMobId": 9130,
    "PedidoEntidadeId": 42382,
    "PedidoEntidadeNome": "",
    "PedidoEntidadeNomeFantasia": "",
    "PedidoEntidadeRegiaoid": 194,
    "PedidoEntidadeCPFCNPJ": "17538662000112",
    "PedidoPercDesconto": 0,
    "PedidoDtEmissao": "2026-11-09",
    "PedidoDtCadastro": "2026-11-09",
    "PedidoRepresentanteMobId": 8496,
    "PedidoRepresentanteId": 10420,
    "PedidoStatus": "EM CARTEIRA",
    "PedidoCondicaoPgtoId": 5,
    "PedidoCondicaoPgtoPrazoMedio": 7,
    "PedidoTipoVenda": "BO",
    "PedidoTipoPedidoId": 10,
    "PedidoVlrTotal": 1905.6,
    "ItemPedido": [
      {
        "ItemPedidoId": 1,
        "ItemPedidoProdutoId": 183,
        "ItemPedidoProdutoCodigo": "050054",
        "ItemPedidoQtde": 20,
        "ItemPedidoVlrOriginal": 95.28,
        "ItemPedidoVlrBruto": 1905.6,
        "ItemPedidoVrlUnitario": 95.28,
        "ItemPedidoVltTotal": 1905.6,
        ...
      }
    ]
  }
}
```

No JSON enviado, os campos `ItemPedidoPerICMSProduto`, `ItemPedidoPerReducaoICMS`,
`ItemPedidoPerMVA`, `ItemPedidoPerICMSEstado` e `ItemPedidoTipoOperacaoPedId`
vão como números, sem aspas. `PedidoEmpresaNome`, `ItemPedidoTipoIPI` e
`ItemPedidoPerFCP` e `PedidoParcelas` são omitidos do envio; os dados locais do pedido são mantidos.

## Status do Pedido

Após sincronização com sucesso, o pedido muda de status:
- **Antes**: `RASCUNHO` ou vazio (PedidoERPId == 0)
- **Depois**: `ENVIADO AO ERP` (PedidoERPId > 0)

O ID do ERP é exibido na lista de pedidos para referência.

O ERP pode retornar, por exemplo, `{"PedidoId":"11019","isError":false,"outmsg":null}`.
Nesse formato, `PedidoId` é salvo como o ID ERP do pedido.

## Tratamento de Erros

- Se o envio falhar para um pedido, o sistema continua tentando os próximos
- Erros de rede ou HTTP são capturados e contabilizados
- Uma resposta HTTP bem-sucedida, sem confirmação `isError: false` e sem ID positivo, não é exibida como pedido sincronizado
- Em caso de timeout, o ERP pode ter processado o pedido sem a resposta chegar ao app; verifique o ERP antes de reenviar
- O usuário recebe o resumo das confirmações e das falhas
- Pedidos não enviados continuam disponíveis para tentar novamente

## Integração com AutoSyncManager

A sincronização pode ser integrada ao `AutoSyncManager` para sincronização automática diária:

```kotlin
// No método syncOncePerDay(), adicionar:
runCatching { OrderSyncService.sendPending(context) }
```

## Testes Recomendados

1. ✅ Sincronizar um pedido pendente
2. ✅ Sincronizar múltiplos pedidos em sequência
3. ✅ Verificar se o status muda para "ENVIADO AO ERP"
4. ✅ Testar comportamento sem conexão de rede
5. ✅ Verificar se o ID do ERP é armazenado corretamente
6. ✅ Tentar sincronizar pedido já enviado (deve ser ignorado)

## Futuras Melhorias

1. **Histórico de Pedidos**: Baixar lista de pedidos já enviados do ERP para visualização
2. **Status em Tempo Real**: Atualizar status de pedidos baseado em retorno do ERP
3. **Sincronização Automática**: Integrar com AutoSyncManager para sincronização periódica
4. **Notificações**: Avisar usuário quando há pedidos pendentes
5. **Retry Inteligente**: Retry automático para pedidos com falha temporária

## Debugging

Para ativar logs de debug, adicione logs em `OrderSyncService.kt`:

```kotlin
Log.d("OrderSync", "Enviando pedido ${order.optLong("PedidoMobId")}")
Log.d("OrderSync", "Resposta: $response")
```

## Compatibilidade

- Mínimo SDK: API 24 (Android 7.0)
- Target SDK: Igual ao projeto
- Dependências: Sem dependências novas adicionadas
