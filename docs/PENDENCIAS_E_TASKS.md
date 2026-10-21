# Pendências e tasks

Acompanhamento da update de **eventos por território** (plano completo em [PLANO_EVENTOS_TERRITORIOS_V1.md](PLANO_EVENTOS_TERRITORIOS_V1.md)) e das pendências que ficaram das updates anteriores.

Legenda: `[x]` feito · `[ ]` a fazer · `[~]` feito, falta validar no jogo

---

## Estado atual

| Fase | Entrega | Versão | Estado |
|---|---|---|---|
| T0 | API do CityWorld (reflexão) e comando `where` | Events 0.22.0 | `[x]` validada e aprovada no jogo |
| T1 | Metrô: gás no túnel e estação-abrigo | Events 0.25.0 | `[~]` teste manual no CityWorld pendente (branch `FSantiago75/T1-T2`) |
| T2 | Cavernas: bolsões de gás natural e mineiros perdidos | Events 0.25.0 | `[~]` teste manual no CityWorld pendente (branch `FSantiago75/T1-T2`) |
| T3 | Esgotos: preferência de ninhos e gás, esconderijo de contrabandista | Events 0.23.0 | `[x]` aprovada no jogo |
| T3+ | Esconderijos habitados (kits de móveis) | Events 0.23.x | `[x]` etapas 1 a 5 aprovadas no jogo, na branch `KaiD3v/T3-T4` |
| T4 | Superfície: queda de helicóptero | Events 0.24.0 | `[x]` aprovada no jogo |

---

## T0: API do CityWorld e comando `where`

- [x] `CityWorldSites`: única porta para `me.daddychurchill.CityWorld.api.CityWorldAPI`, por reflexão (sem o CityWorld, tudo responde "nada aqui")
  - `streetLevel`, `lotClass`, `subway` (estação, linhas, altura das plataformas), `inSubway`, `findLots`, `fullInfo`
  - `territory`: `SURFACE`, `METRO_STATION`, `METRO_TUNNEL`, `MINE`, `BUNKER`, `SEWER`, `CAVE`
- [x] `/lazarusevents where`: território, lote, profundidade, metrô, estação mais próxima e a descrição do próprio CityWorld
- [x] GameTest: sem CityWorld, todas as consultas respondem vazio sem erro; superfície e caverna pelo céu aberto
- [x] **Validado no jogo** com `/lazarusevents where`:
  - [x] rua (esperado `SURFACE`)
  - [x] bilheteria e plataforma de uma estação (`METRO_STATION`)
  - [x] túnel entre estações (`METRO_TUNNEL`)
  - [x] esgoto, cisterna e porão (`SEWER`)
  - [x] mina do CityWorld (`MINE`) e bunker (`BUNKER`)
  - [x] caverna funda fora de mina (`CAVE`)
- [x] Conferir se `findLots("SubwayStationLot")` acha a estação mais próxima e se o tempo de resposta é aceitável
- [x] Conferir se o limite de 16 blocos abaixo da rua separa bem esgoto de caverna; ajustar `CityWorldSites.SHALLOW` se preciso

**Riscos conhecidos:**
- Esgotos não são lotes na API: a classificação é só por profundidade.
- Os nomes das classes de lote (`MineEntranceLot`, `BunkerLot`, `GravelMineLot`, `SubwayStationLot`) vêm do jar 6.3.0; uma atualização do CityWorld pode mudá-los.

---

## T1: metrô

### T1a: gás no túnel
- [~] Achar um trecho de túnel (`inSubway` e não estação) a 30–80 blocos do jogador
- [~] Volume do gás seguindo o túnel (até cerca de 60 blocos), com as duas pontas como saída
- [~] Reaproveitar a Z3: máscara, filtro, hazmat, NPCs fugindo, barril médico no meio
- [~] Fala de rádio: *"Military band: chemical leak in the metro, line closed"*
- [~] Comando `test metro_gas near` dentro de um túnel
- [x] GameTest: gás cobre um corredor montado na arena e só ele

### T1b: estação-abrigo caída
- [~] Achar uma plataforma (`findLots("SubwayStationLot")` e `subway().eastWestFloorY / northSouthFloorY`)
- [~] Montar barracas (lã e cerca), camas, papelão, lixo, quadro de avisos e diário no atril
- [~] 4 a 6 infectados civis entre as barracas; 2 ou 3 baús `smallairdrop` do tier atual
- [~] Só uma das plataformas da estação
- [~] Comando `test metro_shelter near` dentro de uma estação
- [x] GameTest: barracas, diário e loot numa plataforma montada

**Aceite T1:** gás no túnel e estação-abrigo aparecem nos lugares certos, com `where` confirmando o território.

---

## T2: cavernas e minas

### T2a: bolsões de gás natural
- [~] Ao quebrar pedra ou minério abaixo de Y 0: chance de cerca de 1 em 400 de abrir um bolsão; no máximo um a cada 10 min por jogador (configurável)
- [~] Nuvem cinza-esverdeada na galeria por alguns minutos; sem máscara: tontura, fraqueza e dano leve, sem infecção
- [~] Fogo dentro do bolsão (tocha na mão/colocada, fogueira, tiro) explode destruindo blocos; potência 5 e dano ×4, com chiado de três segundos antes (requisito revisado após teste)
- [x] GameTest: abertura forçada, explosão com fogo, máscara protege

### T2b: mineiros perdidos
- [~] Achar mina do CityWorld (`findLots("MineEntranceLot")` ou território `MINE`)
- [~] Carrinhos, trilhos, lanternas apagadas; 3 a 5 mineiros infectados de capacete e picareta
- [~] Baú com minério extraído e ferramentas; diário do colapso da mina
- [x] GameTest: montagem numa galeria da arena

**Aceite T2:** minerar fundo deixa de ser vanilla (máscara e lanterna fazem diferença) e as minas do CityWorld têm um motivo para ir.

---

## T3: esgotos

- [x] **T3a:** ninhos (Z1) e zonas de gás (Z3) procuram primeiro um esgoto em 60% das vezes (`CityWorldSites.findSewer`, `SEWER_PREFERENCE`)
- [x] **T3b:** esconderijo de contrabandista (`smuggler_den`): grades e uma porta nas pontas do túnel, barris de armas, remédio e bebida, 3 ou 4 saqueadores, pagamento político aos Wardens ao limpar
  - só em trechos com formato de túnel (até 10 passagens para fora); porta militar do Apocalypse Now, que abre com a mão
- [x] GameTest: recusa fora de esgoto, montagem num túnel, guardas e pagamento
- [~] **Validar no jogo:**
  - [x] `/lazarusevents test smuggler_den near` dentro de um esgoto: grades, porta, barris e guardas
  - [ ] `/lazarusevents test smuggler_den natural` acha um esgoto sozinho
  - [ ] ninhos e zonas de gás naturais aparecendo em esgotos

---

## T3+: esconderijos habitados

Detalhes em [PLANO_EVENTOS_TERRITORIOS_V1.md](PLANO_EVENTOS_TERRITORIOS_V1.md#5b-esconderijos-habitados-fase-t3).

- [x] **Etapa 1** (aprovada no jogo): gerador de kits (`data/lazarusevents/kits/*.json`, paleta com alternativas e sorteio, direções relativas à parede, loot do Dyairdrop), comandos `scan` e `kit`, primeiros kits (oficina, cozinha, dormitório, depósito, guarita, tenda)
  - [x] rodar `/lazarusevents scan` num esgoto, num porão e num cômodo de prédio e mandar os arquivos de `lazarus_scans/`
  - [x] revisar cada kit com `/lazarusevents kit <nome>`
- [x] **Etapa 2** (aprovada no jogo): 3 a 5 variações por função: 24 kits (oficina 4, cozinha 4, dormitório 6 com as tendas, depósito 5, guarita 3, acampamento 4, decoração 3)
- [x] **Etapa 3** (aprovada no jogo): esgoto habitado no esconderijo de contrabandista
  - limpeza do trecho; corredor de usos a partir da porta: guarita, oficina, cozinha e acampamento, dormitório, depósito (kits)
  - passagem livre da porta ao fundo, colada numa parede, para os kits ocuparem o outro lado
  - vigas de abeto descascado a cada 4 blocos, com lanterna pendurada em vigas alternadas; piso de tábuas na cozinha e no dormitório; teias altas
  - até 2 pontes de laje alagada sobre canais de 1 a 3 blocos de água
  - barris especiais: armas com o distintivo da gangue no depósito, remédio com cigarros no dormitório, suprimentos com bebida na cozinha
- [x] **Etapa 4** (aprovada no jogo): prédios com vários cômodos
  - `HideoutLayout`: junta o cômodo encontrado e até 3 vizinhos atrás de portas (gangue) ou até 2 (Hollow); limpa cada cômodo; passagens livres entre as portas de cada cômodo
  - funções pelo tamanho: o maior é a sala comum (cozinha, guarita, decoração), depois dormitório, oficina e depósito; um cômodo só recebe um pouco de tudo
  - gangue: estoque com o distintivo no depósito, placa TURF na sala comum, teias, guardas por todos os cômodos
  - Hollow: jaula num canto longe das portas, montada antes dos móveis; ossos, caveira, velas e fogueira apagada nos espaços livres
- [x] **Etapa 5** (aprovada no jogo): identidade visual por grupo
  - motor: kits com `"groups"` (só daquele grupo, usados antes dos kits de todos) e estilos em `data/lazarusevents/kit_styles/<grupo>.json` (troca de blocos por id em todo kit que o grupo monta); `rotation=front` para caveiras e estandartes
  - identidade desenhada pelo Codex ([IDENTIDADE_GRUPOS.md](IDENTIDADE_GRUPOS.md)): 6 kits e um estilo para cada grupo (gangue, contrabandistas, desertores, Hollow); revisada (estandartes da gangue viraram de parede)
  - em uso: contrabandistas no esgoto, gangue no prédio, Hollow no covil; os desertores ainda não têm esconderijo de interior (o acampamento é um modelo ao ar livre), então o estilo e os kits deles ficam prontos para quando tiverem
  - `/lazarusevents kit <nome> [grupo]` monta um kit no estilo de um grupo

---

## T4: superfície

Plano detalhado em [PLANO_T4_QUEDA_HELICOPTERO.md](PLANO_T4_QUEDA_HELICOPTERO.md); implementação pelo Codex com o prompt [PROMPT_CODEX_T4.md](PROMPT_CODEX_T4.md).

- [x] Helicóptero militar caído em blocos, hélice quebrada, fogo isolado e fogueiras de sinal sobre feno
- [x] 3 ou 4 infectados de farda, caixas `weaponairdrop` e `largeairdrop`, às vezes piloto ferido para resgatar por +3 Military
- [x] Fumaça atrai horda de 1 a 3 infectados a cada 2 minutos, até 12 vivos; fogo apaga em 1 dia e fumaça em 2
- [x] Rádio: *"Mayday, mayday, we're going down..."*
- [x] GameTest: carcaça, fogo, caixas, infectados, piloto, ciclo de vida, teto da horda e terreno com obstáculo
- [x] Validado no jogo; a busca aceita desníveis de 1 bloco (a queda nivela o terreno), o modo `near` ignora encontros vizinhos e a falha mostra os motivos

---

## Pendências de updates anteriores

| Item | Origem | Estado |
|---|---|---|
| NPCs humanos não desviam do gás antes de entrar (só sofrem e fogem depois) | Eventos V1, Z3 | Aceito como está |
| Reparo da máscara pelo filtro sem teste automático | Eventos V1, Z3 | Validado só no jogo |
| Corpo do soldado da quarentena é só um baú | Eventos V1, Z4 | Aceito como está |
| Skin "soldado de quarentena" sem ligação com a quarentena | Eventos V1, Z4 | Aberto |
| Lore das zonas e do gás (diálogos, diários e tarefa de amostra de ar) | [LORE_ZONAS_INFECCAO.md](LORE_ZONAS_INFECCAO.md) | Para uma versão futura |

### Testes instáveis (GameTests)

| Teste | Sintoma | Causa conhecida |
|---|---|---|
| `surrenderedNpcStopsFightingAndDropsNothing` | Um item vanilla aparece perto do prisioneiro morto | O Apocalypse Now solta itens fora do evento de drop |
| `smugglerDenGatesTheTunnelAndPaysWhenCleared` | Uma vez, 0 guardas contados logo depois de montar | Desconhecida; não se repetiu em duas rodadas seguidas |
| `outpostHoldsGatesAndPaysThePerimeter` | Só 2 dos 4 militares do posto aparecem | Desconhecida; acontece quando o teste anterior acabou de matar NPCs militares no mesmo lugar |
| `followOrderReachesThePlayer`, `escortDefendsOwner`, `escortFollowsWithoutCrowding` | Falhas ocasionais de movimento | Dependem do tempo da IA |

---

## Backlog (depois da V1 de territórios)

| Área | Ideias |
|---|---|
| Metrô | Trem parado no túnel, comunidade da plataforma, território do Hollow, gerador da estação, linha de carrinho |
| Cavernas | Desabamento, ninho profundo de infectados cegos, esconderijo na caverna, bunker militar com código, veio contaminado, ecos falsos |
| Esgotos | Rato-rei, enchente após chuva forte, túnel de fuga com resgate, água contaminada |
| Superfície | Comboio emboscado, horda migratória, noite agitada, condições temporárias, atirador no telhado, rastro de sobrevivente, mercado negro itinerante |