# Plano: eventos por território, V1 enxuta

## 1. Objetivo

Dar a cada território do mapa um motivo para ser explorado e uma identidade própria: **metrô, cavernas, esgotos e superfície**. Esta V1 escolhe **um ou dois eventos por área**, os que mais mudam a experiência com o menor custo, e deixa o resto no backlog.

Regras da V1:
- Reaproveitar o que já existe: o gás e as máscaras (Z3), os ninhos (Z1), a montagem de cômodos, o rádio, os diários e o loot do dyairdrop.
- Mirar nos lugares certos usando a **API do CityWorld**, em vez de adivinhar espaços fechados.
- **Minerar não pode ser vanilla:** a área de cavernas recebe um perigo sistêmico, e não só um evento raro.
- Nada de estruturas grandes novas: a cena é montada sobre o que o CityWorld já gera.

## 2. Base técnica: a API do CityWorld (fase T0)

O `cityworld-6.3.0` tem uma API pública, `me.daddychurchill.CityWorld.api.CityWorldAPI`:

| Método | Uso no plano |
|---|---|
| `isSubway(level, pos)` / `subwayAt(level, pos)` → `SubwayInfo` | Saber se a posição é metrô, se é estação (`station()`), e a altura das plataformas de cada linha (`eastWestFloorY`, `northSouthFloorY`) |
| `findLots(level, pos, lotClass, radius, max)` | Achar lotes por tipo: `SubwayStationLot`, `MineEntranceLot`, `BunkerLot`, `GravelMineLot` |
| `lotAt(level, pos)` → `LotInfo` | O tipo do lote onde o jogador está (`lotClass`, `subway`, `streetLevel`) |
| `streetLevel(level)` | A altura da rua, para medir a profundidade |

**Integração:** dependência **opcional, por reflexão**. Sem o CityWorld, os eventos de território ficam desligados e o resto do mod funciona igual. Uma classe `CityWorldSites` concentra todas as chamadas.

**Debug:** um comando `/lazarusevents where` mostra o que a API diz sobre a posição do jogador: lote, metrô, estação, profundidade. É a primeira coisa a validar no jogo, antes de qualquer evento.

**Esgotos:** não são lotes na API; fazem parte das ruas. Ficam identificados pela profundidade: abaixo da rua, acima do metrô e dentro de um espaço fechado. A T0 confirma isso no jogo com o comando `where`.

## 3. Metrô (fase T1)

**Identidade:** estações grandes e escuras, túneis longos com trilho, água parada e spawners. Foi onde muita gente se refugiou no começo.

### T1a: gás no túnel
- Uma **zona de infecção** (Z3) num trecho de túnel entre estações, achado pela API (`isSubway` e fora de estação).
- O volume é o túnel em si: um trecho de até cerca de 60 blocos de comprimento, com as duas pontas como saída.
- Tudo o que a Z3 já faz: máscara, filtro, hazmat, NPCs fugindo, barril médico no meio do trecho.
- No rádio: *"Military band: chemical leak in the metro, line closed"*.
- **Motivo para ir:** os túneis viram atalhos de risco entre bairros, com loot médico no meio.

### T1b: estação-abrigo caída
- A plataforma de uma estação (achada com `findLots("SubwayStationLot")`) vira o **acampamento do começo do surto**:
  - 4 a 6 "barracas" (lã e cerca), camas improvisadas, caixas de papelão e lixo;
  - um quadro de avisos com bilhetes (placas) e um **diário** num atril contando o fim do abrigo;
  - de 4 a 6 infectados "civis" entre as barracas, que acordam com barulho (Stealth);
  - 2 ou 3 baús de loot civil (`smallairdrop` do tier atual).
- Persistente até ser saqueada, como os esconderijos.
- **Motivo para ir:** muito loot civil reunido num só lugar, e lore.

## 4. Cavernas e minas (fase T2)

**Identidade:** com o Hardcore True Darkness, o escuro já é total. Descer deve ser uma expedição, com riscos próprios.

### T2a: bolsões de gás natural (perigo sistêmico da mineração)
- Ao quebrar pedra ou minério **abaixo de Y 0** (ajustável), há uma chance pequena de abrir um **bolsão de gás**: uma nuvem cinza-esverdeada que ocupa a galeria em volta por alguns minutos.
- Sem máscara: tontura, fraqueza e sufocamento (dano leve); sem infecção, porque não é o vírus.
- **Fogo explode o bolsão:** tocha na mão, fogueira, isqueiro ou tiro dentro dele ou uma tocha colocada causam uma explosão destrutiva (potência 5, dano multiplicado por 4). Um aviso de som (chiado) dá ao jogador alguns segundos.
- Limite de um bolsão por jogador a cada certo tempo, para não cansar.
- Reaproveita o sistema de células e exposição do gás, com outro efeito.
- **Motivo:** muda a mineração de vez: máscara e lanterna em vez de tocha, e cuidado ao cavar fundo.

### T2b: mineiros perdidos
- Numa mina do CityWorld (`findLots("MineEntranceLot")`, ou a galeria de mina mais próxima), um **acampamento de mineração abandonado**:
  - carrinhos, trilhos e lanternas apagadas;
  - 3 a 5 **mineiros infectados** de capacete e picareta (zumbis com equipamento);
  - um baú com minério já extraído (ferro, carvão, ouro, às vezes diamante) e ferramentas;
  - um diário do colapso da mina.
- **Motivo para ir:** minério pronto sem precisar cavar, com uma luta no escuro.

## 5. Esgotos (fase T3)

**Identidade:** úmido, apertado e com som abafado. É o território dos ninhos e do contrabando.

### T3a: os esgotos como casa dos ninhos e do gás
- Os ninhos (Z1) e as zonas de gás (Z3) passam a **preferir esgotos**: peso maior quando o espaço encontrado está abaixo da rua e acima do metrô.
- Sem evento novo: só ajuste de onde os existentes aparecem.

### T3b: esconderijo de contrabandista
- Um trecho do esgoto fechado com **porta de ferro** e grades, com caixas de bebida, remédio, munição e uma arma, guardado por 3 ou 4 saqueadores.
- Aproveita o esconderijo de gangue em prédio (cômodo, guardas, pagamento ao limpar) com outra decoração e loot.
- **Motivo para ir:** o loot de gangue mais rico do mapa, escondido debaixo da cidade.

## 5b. Esconderijos habitados (fase T3+)

**Problema:** os esconderijos (gangue em prédio, covil do Hollow, contrabandista) são cômodos com barris avulsos, uma grade e uma porta. Não parecem lugares onde alguém vive.

**Princípios** (das referências de interiores do Minecraft): móveis em **conjuntos**, espaço com **função** e **sujeira de vida**.

### Kits de móveis
- Um **kit** é um arranjo de 2×1 a 3×3 blocos, encostado numa parede e virado para o centro do cômodo, escrito em texto (paleta e camadas) em `data/lazarusevents/kits/*.json`. Aceita datapacks.
- A paleta aceita alternativas (`[a, b]`: o primeiro bloco que existir) e sorteio (`{"random": [...]}`), para funcionar sem os mods opcionais e variar a cena. Contêineres podem ter loot do Dyairdrop (`"loot": "smallairdrop"` vira a tabela do tier atual).
- As direções na paleta são relativas à parede (`facing=front|back|left|right`).
- Kits por função, de 3 a 5 variações cada:
  - **oficina:** bancada, bigorna, forno, rebolo, sucata mecânica;
  - **cozinha:** fogão ou fogueira com panela (Farmer's Delight), tábua de corte, cesto, barril de enlatados;
  - **dormitório:** camas desencontradas, baú ao pé, vela ou lanterna;
  - **depósito:** caixas empilhadas, papelão, palete, prateleira com lanterna;
  - **guarita:** sacos de areia, cadeira, rádio, lanterna;
  - **tenda:** postes, lona ou lã, cama, fogueira na frente.
- Blocos usados: vanilla, Farmer's Delight, Refurbished Furniture, Apocalypse Now, Loot Piles, Immersive Weathering.

### Espaço com função
- **Prédios:** o mapeamento atravessa portas e junta de 2 a 4 cômodos; o maior vira sala comum, os médios dormitório ou oficina, o menor depósito; a guarita fica na entrada.
- **Esgoto:** corredor de usos ao longo do túnel: grade → guarita → oficina → cozinha e tendas → dormitório → depósito → porta.
- Antes de montar, o espaço é limpo (como na quarentena) e um caminho de 1 bloco entre as saídas fica livre.

### Esgoto habitado
- **Pontes** de tábuas ou lajes sobre a água do canal, com corrimão de corda ou cerca.
- **Teto de madeira** (forro de tábuas com vigas) onde houver altura, com lanternas em corrente.
- **Estrados** de madeira na parte habitada; papéis, teias, trepadeiras e lajes rachadas.

### Identidade por grupo
| Grupo | Toque próprio |
|---|---|
| Gangue | desencontrado, sucata, bandeira com as cores da gangue, pichação |
| Contrabandistas | depósito maior, caixas militares, garrafas, cigarros |
| Desertores | camas de campanha, caixas militares, rádio, sacos de areia, lona verde |
| The Hollow | velas, ganchos com carne pendurada, ossos, altar, mais escuro |

### Loot
O loot fica dentro dos kits (baú da cama, barril da oficina, caixas do depósito); pilhas de sucata do Loot Piles são o loot pequeno; o estoque principal fica no depósito, o cômodo mais fundo.

### Ferramentas
- `/lazarusevents scan`: salva um corte em texto do lugar em volta do jogador (camadas e paleta) em `lazarus_scans/`, para desenhar os kits sobre a geometria real (largura do esgoto, canal de água, altura do teto).
- `/lazarusevents kit <nome>`: monta um kit na parede à frente do jogador, para revisar no jogo.

### Etapas
1. Gerador de kits (leitura, rotação, encaixe na parede), comandos `scan` e `kit`, primeiros kits.
2. Mais variações por função.
3. Esgoto com corredor de usos, pontes, teto e estrados, aplicado ao esconderijo de contrabandista.
4. Prédios com vários cômodos e funções, aplicados à gangue em prédio e ao covil do Hollow.
5. Paletas por grupo.

---

## 6. Superfície (fase T4)

**Identidade:** a superfície já tem muitos eventos; entra só o que dá um **destino visível de longe**.

### T4: queda de helicóptero
- Um helicóptero militar caído (montado com blocos: carcaça de ferro e concreto, hélice quebrada) **em chamas**, com uma coluna de fumaça visível de longe (partículas de fogueira).
- 3 ou 4 infectados de farda, 1 ou 2 caixas militares (`weaponairdrop` ou `largeairdrop` do tier atual) e, às vezes, um piloto ferido para resgatar (Haven ou Military).
- A fumaça atrai a horda ao longo do tempo: chegar cedo é mais seguro.
- O rádio anuncia: *"Mayday, mayday, we're going down..."*.
- **Motivo para ir:** o melhor loot militar de superfície, com um farol que se vê de longe.

## 7. Fases e versões

| Fase | Entrega | Versão | Depende de |
|---|---|---|---|
| T0 | API do CityWorld (reflexão) e comando `where` | Events 0.22.0 | — |
| T1 | Metrô: gás no túnel e estação-abrigo | Events 0.22.x | T0 |
| T2 | Cavernas: bolsões de gás natural e mineiros perdidos | Events 0.23.0 | T0 |
| T3 | Esgotos: preferência de ninhos e gás, esconderijo de contrabandista | Events 0.23.x | T0 |
| T3+ | Esconderijos habitados: kits de móveis, espaço com função, esgoto habitado | Events 0.23.x | T3 |
| T4 | Superfície: queda de helicóptero | Events 0.24.0 | — |

**Testes de cada fase:**
- **GameTests:**
  - T0: a classe de integração responde "sem CityWorld" de forma segura no ambiente de teste (que não tem cidades);
  - T1: o gás de túnel cobre um corredor montado na arena e só ele; a estação-abrigo monta barracas, diário e loot numa plataforma montada;
  - T2: quebrar pedra funda com a chance forçada abre o bolsão; fogo dentro dele explode; máscara protege;
  - T3: o esconderijo do esgoto monta porta, guardas e loot, e paga ao ser limpo;
  - T4: o helicóptero monta carcaça, fogo, caixas e infectados.
- **No jogo:**
  - `/lazarusevents where` em rua, esgoto, estação, túnel, mina e bunker;
  - o comando `test` de cada evento no lugar certo;
  - minerar fundo até abrir um bolsão, com e sem tocha.

## 8. Backlog (para depois da V1)

| Área | Ideias |
|---|---|
| Metrô | Trem parado no túnel, comunidade da plataforma, território do Hollow, gerador da estação, linha de carrinho |
| Cavernas | Desabamento, ninho profundo de infectados cegos, esconderijo na caverna, bunker militar com código, veio contaminado, ecos falsos |
| Esgotos | Rato-rei (especial do Undead Revamped), enchente após chuva forte, túnel de fuga com resgate, água contaminada |
| Superfície | Comboio emboscado, horda migratória, noite agitada, condições temporárias, atirador no telhado, rastro de sobrevivente, mercado negro itinerante |

A lore das zonas e do gás ([LORE_ZONAS_INFECCAO.md](LORE_ZONAS_INFECCAO.md)) vale para T1a e T3a; os bolsões de gás natural (T2a) são outra coisa e não carregam o vírus.

## 9. Decisões (com padrão sugerido)

1. **Dependência do CityWorld.** Padrão: opcional, por reflexão. Alternativa: dependência de compilação com o jar em `libs/`, mais simples de escrever e mais frágil quando o CityWorld atualizar.
2. **Profundidade dos bolsões de gás natural.** Padrão: abaixo de Y 0, com chance de cerca de 1 em 400 blocos quebrados e no máximo um a cada 10 minutos por jogador.
3. **O bolsão explode com fogo?** Padrão revisado após teste: sim, explosão destrutiva com potência 5 (antes 2,5), dano ×4 antes da armadura e aviso de chiado de três segundos. Este requisito substitui a proposta inicial sem destruição.
4. **A estação-abrigo ocupa uma estação inteira?** Padrão: só uma das plataformas, para não esvaziar o metrô.
5. **Ordem das fases.** Padrão: T0, T1, T2, T3, T4. Alternativa: T2 antes de T1, se a prioridade for mudar a mineração logo.
