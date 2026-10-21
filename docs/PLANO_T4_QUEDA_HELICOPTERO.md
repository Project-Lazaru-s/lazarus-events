# Plano T4: queda de helicóptero

Fase T4 do [plano de eventos por território](PLANO_EVENTOS_TERRITORIOS_V1.md): o evento de **superfície** da V1. Vai ser implementada com o Codex, usando o prompt em [PROMPT_CODEX_T4.md](PROMPT_CODEX_T4.md).

## 1. Objetivo

Dar à superfície um **destino visível de longe**: um helicóptero militar caído, ainda em chamas, cuja fumaça se vê a centenas de blocos. Chegar cedo é mais seguro; quanto mais tempo passa, mais infectados a fumaça atrai. É o melhor loot militar de superfície e um motivo para atravessar o mapa.

## 2. O que o jogador encontra

| Elemento | Detalhe |
|---|---|
| **Carcaça** | Helicóptero tombado montado em blocos (cerca de 9 × 5 × 4): fuselagem de ferro e concreto cinza/verde-escuro, cabine com vidro quebrado, cauda partida, rotor principal torto caído ao lado. Montado por código, girado conforme o local (4 rotações). |
| **Fogo e fumaça** | Fogo em blocos de carcaça (fogo de alma ou comum sobre netherrack/magma escondidos, sem espalhar), fogueiras de sinal (`campfire[signal_fire=true]` sobre fardo de feno) para a **coluna de fumaça alta**. O fogo apaga sozinho depois de cerca de 1 dia de jogo; a fumaça fica mais 1 dia. |
| **Destroços** | Cratera rasa (terra revirada, cascalho, cinzas/blocos queimados do Immersive Weathering se houver), peças espalhadas (barras de ferro, correntes, alçapões de ferro), um trilho de terra arrastada atrás da carcaça. |
| **Loot** | 1 caixa `weaponairdrop<tier>` e 1 caixa `largeairdrop<tier>` dentro ou junto da carcaça (caixas militares do Apocalypse Now com fallback para barril); às vezes uma caixa `medicalairdrop`. |
| **Infectados** | 3 ou 4 infectados de farda (zumbis com capacete/colete militar, como no posto abandonado) em volta. |
| **Piloto ferido** (40%) | Um sobrevivente militar ferido (`apocalypsenow:military_*`) esperando resgate, no padrão do evento **Rescue**: escoltar até um lugar seguro dá reputação com o **Military** (ou com o Haven, se o jogador preferir levá-lo ao refúgio, opcional). Se o piloto morrer, a tarefa falha. |
| **Horda atraída** | Enquanto a fumaça existir, a cada ~2 minutos (com jogador por perto) chegam 1 a 3 infectados vindos de fora do campo de visão, até um teto (ex.: 12 vivos), no padrão do spawner do ninho/zona infestada. |
| **Rádio** | Ao criar: *"Mayday, mayday, we're going down... grid near X, Z"* (formato do rádio existente). Sem rádio, a fumaça é o aviso. |

## 3. Regras de aparecimento

- **Tipo:** `heli_crash`. Peso **1** a partir do **dia 15** (configurável, `enableHeliCrashes`, `heliCrashFromDay`).
- **Local:** superfície aberta (céu visível), terreno razoavelmente plano numa área de cerca de 11 × 11, de 60 a 120 blocos do jogador (mais longe que os outros encontros, porque a fumaça avisa), fora do campo de visão. O modo `near` do comando de teste usa a distância curta (14–34) e dispensa a direção do olhar.
- **Duração:** o encontro expira em 3 dias de jogo; a carcaça e os destroços ficam no mundo como cenário.
- Não aparece dentro de cidades densas se não houver espaço plano (o validador recusa).

## 4. Integração técnica (onde mexer)

Projeto `lazarus_events` (Forge 1.20.1, mapeamentos oficiais). Seguir os padrões existentes:

| Peça | Como |
|---|---|
| Classe nova `HeliCrash.java` | `TYPE = "heli_crash"`; `find(...)` (local plano a céu aberto), `place(level, pos, rotation, tier, id)` (carcaça, fogo, destroços, loot, infectados, piloto), `tick(level, data)` chamado de `LazarusEvents.onTick` (horda atraída, apagar fogo/fumaça, fim do encontro). |
| `LazarusEvents` | Incluir `HeliCrash.TYPE` em `TYPES`; config (`enableHeliCrashes`, `heliCrashFromDay`); peso no sorteio; colocação (rota própria, como os eventos de interior em `attemptBuilding`, porque a carcaça não é um modelo do `EncounterScenery`); `label` (*"helicopter crash"*) e `radioLine`; chamar `HeliCrash.tick`. |
| Estado | `EventData.Encounter.data` (`CompoundTag`) guarda o que precisar: fim do fogo/fumaça, posições das fogueiras, contagem da horda, piloto. |
| Atores | `LazarusEvents.spawn(level, pos, id, encounterId, persistent)` marca todos com a tag do encontro. Equipamento militar: `MilitaryCollapse.equip`. |
| Piloto | Reaproveitar o fluxo do `RescueEncounter` (diálogo, tarefa de escolta, recompensa) ou uma variante dele com outro dono/tarefa; usar a API pública do Lazarus NPCs (`NpcApi.assign`, `NpcApi.offerTask`, `NpcApi.adjustReputation`). |
| Loot | Tabelas `dyairdrop:chests/<nome><tier>`; tier = `min(5, 1 + dia/10)`. |
| Reaproveitar | `HideoutKits` para pequenos conjuntos (caixas militares, sucata) se ajudar; `LazarusEvents.radio`; `InfestedZone.streetSpot` como referência para spawn fora da visão. |

## 5. Testes

GameTests ficam no projeto `lazarus_npcs` (`EventsScenarioGameTests`), chamando as classes do Events por reflexão (helper `call(...)`). A arena de teste **não tem céu** (fica enterrada), então o teste chama `HeliCrash.place` direto num chão plano, sem passar pelo `find`.

- monta a carcaça (blocos de ferro/concreto em quantidade mínima), fogo e pelo menos uma fogueira de sinal;
- cria as duas caixas de loot com tabela e os infectados com equipamento militar;
- com o piloto forçado, ele recebe a ordem de esperar e oferece a tarefa;
- `tick` com o tempo avançado apaga o fogo e depois a fumaça; a horda respeita o teto;
- recusa em lugar sem espaço plano (teste do validador com um obstáculo).

No jogo: `/lazarusevents test heli_crash near` num campo aberto; ver a fumaça de longe; esperar a horda chegar; escoltar o piloto.

## 6. Critérios de aceite

1. A coluna de fumaça é visível de longe e leva o jogador ao local.
2. A carcaça parece um helicóptero caído (não um monte de blocos), em qualquer das 4 rotações.
3. O fogo não se espalha nem destrói o terreno em volta.
4. Loot militar alto e coerente com o tier; infectados de farda.
5. A horda chega com o tempo e para no teto.
6. Piloto (quando há) pode ser escoltado com recompensa, como no Rescue.
7. Todos os GameTests passam (os instáveis conhecidos estão em [PENDENCIAS_E_TASKS.md](PENDENCIAS_E_TASKS.md)).

## 7. Decisões (com padrão sugerido)

1. **Carcaça por código ou modelo `.nbt`?** Padrão: por código (como a quarentena), com uma lista de blocos relativa e rotação; um `.nbt` capturado no jogo pode substituir depois.
2. **Dia de início.** Padrão: dia 15.
3. **Fogo real?** Padrão: fogo sobre bloco que não queima (netherrack escondido sob a carcaça) e `doFireTick` não importa; nunca fogo encostado em madeira/folhas.
4. **Recompensa do piloto.** Padrão: +3 Military e um item de valor (ex.: munição), como o Rescue.
5. **Teto da horda.** Padrão: 12 vivos, 1–3 a cada ~2 min com jogador a até 64 blocos.
