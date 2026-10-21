# Revisão técnica de T1/T2 — 0.23.0-local.5

Revisão do clone local sobre a base `dfdcd727a4abf752c8a7ac5dfd801f2b1c110989` e das alterações locais de T1/T2. Referências: README, plano de territórios, pendências, roteiro de teste, código de produção, testes e recursos. As APIs externas foram conferidas nos JARs usados pela instância Alpha 1.4.5. Não se trata de uma auditoria integral dos outros repositórios Lazarus nem de uma comparação com mudanças remotas posteriores à base citada.

## Complemento vigente: local.5

A pedido do usuário, o loot da local.4 foi substituído por reservas médicas premium tanto em `infection_zone` quanto em `metro_gas`, e o baú dos mineiros ganhou tabelas próprias com recursos e picareta de diamante encantada. A tag respiratória passou de 16 para 24 IDs; conjuntos Contamination/Biohazard completos foram incluídos em `hazmat`. Quantidades, IDs e decisões atuais: [RECOMPENSAS_T1_T2.md](RECOMPENSAS_T1_T2.md). Geometria, persistência, efeitos e explosão permanecem como revisados. O índice final foi atualizado para os fontes locais atuais.

## Objetivo e critérios

O metrô oferece atalhos perigosos, suprimentos e narrativa ambiental. As minas oferecem recursos e encontros em estruturas existentes. A mineração profunda exige planejamento de iluminação e proteção respiratória. Esses sistemas devem aproveitar o gás Z3, as tabelas de loot do pack, os infectados existentes e o SavedData atual, sem gerar uma segunda infraestrutura de eventos.

Foram examinados os trechos de seleção, geometria, montagem, rollback, persistência, exposição, ignição e integração. O índice de linhas no fim deste documento permite localizar cada método na revisão local. Intervalos incluem o corpo inteiro do método; imports e declarações foram conferidos quanto a dependências e visibilidade. Linhas triviais de sintaxe não são apresentadas como achados individuais.

## Requisitos aplicados

| Pedido | Implementação e consequência |
|---|---|
| Mais loot no gás do túnel | Reservas próprias premium `infection_medical1..5`, reutilizadas por `metro_medical1..5`: kits, medicamentos, filtros, máscara e peitoral garantidos. Tabelas sobrescrevíveis por datapack. |
| Tocha acende gás natural | Tochas normais/de almas nas duas mãos; blocos de tocha e suas variantes de parede; fogo, lava, velas e fogueiras acesas. Criativo pode iniciar ignição; espectador não. |
| Explosão destrói blocos | `ExplosionInteraction.BLOCK`; resistência, proteção e hooks de explosão do Forge continuam aplicáveis. Não significa destruir qualquer bloco. |
| Dano pelo menos quatro vezes maior | Multiplicador padrão 4, mínimo configurável 4, exclusivo da fonte de explosão de gás, antes da armadura. |
| Área maior | Potência padrão 5, antes 2,5. O alcance vanilla cresce com a potência; paredes, distância e exposição determinam o resultado real. |
| Mais proteção Apocalypse Now | Tag com 24 IDs: 21 máscaras/capacetes e três adaptações MO4. Slots funcionais do Curios passam a ser consultados. |

O pedido posterior de destruição substitui explicitamente o plano inicial de explosão sem dano ao terreno. Plano, pendências e roteiro foram atualizados para não perpetuar regras contraditórias.

## Leitura do fluxo de execução

### `LazarusEvents`

- O construtor registra a configuração territorial e um único listener `NaturalGas`. O hook de TaCZ só é instanciado se o mod estiver carregado. Curios tem uma ponte separada, com consulta condicionada à presença do mod.
- `onTick` mantém a agenda narrativa existente, acrescenta a resolução de cenas territoriais e exclui bolsões naturais da contagem das vagas narrativas. O perigo de mineração não deve impedir acampamentos e encontros de facção.
- `randomType` conserva os pesos existentes e acrescenta os territoriais. Entrar no metrô aumenta a possibilidade dos eventos relevantes; não garante uma geração imediata. Frequência natural continua sujeita à agenda global.
- `attempt` encaminha primeiro os novos tipos aos sistemas próprios. Assim `metro_gas` recebe geometria de túnel e recompensa específica, em vez de cair no localizador genérico de cômodos.
- Comandos administrativos continuam exigindo permissão. O comando forçado de gás ignora sorteio/cooldown para diagnóstico; isso não representa a frequência real em sobrevivência.
- Cancelamento remove registro/atores e preserva loot/blocos, como no comportamento já existente. Não há desfazer automático da explosão.

### `CityWorldSites`

- A dependência permanece opcional por reflexão; ausência da classe responde vazio e não derruba o servidor.
- `subway` converte os campos da API para um record interno, evitando espalhar reflexão pelos geradores.
- `contains` compara Y com o piso de cada linha. O teste de chunk do CityWorld sozinho marcaria jogadores acima do metrô como estando dentro dele.
- `findLots` converte o raio expresso em chunks pelo Events para blocos na API; aplica o limite de oito chunks.
- `territory` prioriza superfície, depois metrô e lotes especiais. Esgoto continua sendo uma aproximação por profundidade, limitação da API já documentada na T0.
- A busca de montagem verifica carregamento antes de ler a geometria. Encontrar metadados de um lote não autoriza carregar sua estrutura.

### `GasVolumes`

- `air`: exige chunk carregado, colisão vazia e ausência de fluido; ar aqui significa espaço transitável pelo gás, não apenas `Blocks.AIR`.
- `floor`: exige dois blocos livres e suporte firme abaixo. Impede ator em teto, água ou vazio.
- `roof`: rejeita céu aberto e procura cobertura até seis blocos acima; mantém a identidade de galeria fechada.
- `tunnel`: BFS horizontal com limite de 800 células e distância Manhattan 29. Rejeita salão excessivo em vez de truncá-lo e fingir enclausuramento. As faces de corte podem funcionar como saídas; exige duas e no mínimo 12 células.
- O predicado territorial interrompe o túnel na estação; as consultas de reflexão são armazenadas por chunk durante a busca.
- `pocket`: BFS tridimensional, até 512 células, alcance de oito blocos em X/Z e três em Y. Respeita paredes e fluidos; não usa uma esfera que atravesse terreno.
- `bounds`: AABB serve à consulta de entidades; exposição ainda precisa confirmar pertencimento às células. Estar dentro da caixa não basta para respirar gás.

### `TerritoryEvents`

- `weights`: switches independentes e seleção coerente com o território; sem CityWorld, novos encontros de cenário ficam fora da seleção.
- `attempt`: cria registro somente depois da montagem; salva células/expiração do gás e reserva de cenas persistentes. O barril médico do metrô agora passa por `placeMetro`.
- `station`: resolve a estação real mesmo quando o jogador está na extensão da plataforma, classificada como túnel. Tenta alas a ±16 blocos antes do centro, ocupado por escadas.
- A reserva do abrigo usa o chunk central da estação, sem distinguir os pisos, evitando reposição na outra plataforma da mesma estação.
- `shelterOnPlatform`: procura posições alternativas para os infectados no mesmo corredor; musgo não deve invalidar a plataforma inteira.
- `mine`: começa em território de mina e procura galerias até 48 blocos abaixo, onde a entrada em escadaria pode finalmente desembocar em espaço utilizável.
- `unseen`: o modo natural evita surgimento perto e na linha de visão de jogadores; testes `near` dispensam essa restrição para diagnóstico.
- `shelter` e `miners`: mapas ordenados e record `Scene` separam descrição de cena e mutação do mundo. A lâmpada dos mineiros permanece apagada.
- `fits`: impede substituição de block entities, fluidos e estruturas rígidas; verifica suporte e posições de atores. As exceções para musgo, teias, trilhos e lajes seguem os cenários observados no mapa.
- `build`: valida antes de escrever, guarda snapshot dos estados substituídos e descarta os atores já criados em caso de falha. Isso preserva a estratégia local de montagem sem um framework novo.
- `tick`: loot não materializado ainda conta como pendente. Chunk descarregado não significa contêiner vazio. A reserva do local sobrevive ao encerramento do encontro.

### `MineScenes`

- `find`: enumera candidatos carregados, ordena pela distância e tenta o layout fixo antes do adaptativo; o agrupamento por oito blocos evita repetir BFS em cada célula da mesma galeria.
- `gallery`: limita a busca e tenta até 24 inícios de trilho, nos dois eixos. Exige três trilhos, três posições de móveis e três posições de atores.
- `connected`: verifica se remover uma posição de passagem para inserir um móvel desconecta o piso. Preserva a rota através de galerias estreitas e dos pilares.
- A regressão usa apenas os blocos da mina salva durante o teste local, sem dados pessoais ou entidades do save. A descoberta real do lote continua sendo validação no CityWorld.

### `NaturalGas`

- `GasBlastSource`: conserva o tipo vanilla de dano de explosão e acrescenta identidade Java para aplicar o multiplicador somente a este perigo. TNT, gás infeccioso e dano de inalação não recebem o aumento.
- `blastDamage`: alteração no `LivingHurtEvent`, antes da armadura. Outros listeners ainda podem cancelar/modificar dano; proteção respiratória não concede imunidade ao impacto.
- `explode`: delega dano, empurrão e resistência de blocos ao mecanismo vanilla/Forge. Não há segundo ataque manual nem dupla aplicação do dano.
- `blastLoaded`: verifica todos os chunks do alcance conservador antes da explosão automática. Se faltar algum, a ignição aguarda carregamento normal.
- `mining`: somente jogador em sobrevivência, Overworld, pedra/minério abaixo do Y configurado. A verificação é enfileirada para o tick seguinte; notificação de quebra cancelada/inconclusa não deve criar gás.
- `release`: cooldown no NBT persistente do jogador, limite próprio de 16 bolsões e rejeição de sobreposição de células. Sorteio e comando forçado usam o mesmo construtor de volume.
- `inside`: considera pés e olhos. Não exige que o centro geométrico da entidade coincida com a célula inicial do bolsão.
- `breathe`: usa a regra compartilhada de equipamento, mas aplica apenas tontura, fraqueza e dano leve. Não chama o caminho de infecção da Z3.
- `ignite`: primeira fonte vence; a posição e o prazo de 60 ticks são persistidos. Novos tiros não reiniciam o aviso.
- `spark`: somente bolsões naturais no Overworld; gás de metrô não se torna explosivo por acidente.
- `lighter` e `GasGunHook.fire`: listeners de baixa prioridade, sem receber eventos já cancelados; tiro considera olhos e pés do atirador, inclusive armas com supressor.
- `flame`: distingue fontes realmente acesas das apagadas. Tochas normal/de almas, de chão e parede são explícitas. Lanternas e tochas de redstone não entram como chama.
- `playerIgnition`: verificação de modo fica separada da imunidade de inalação. Criativo com tocha acende; espectador não interfere.
- `tick`: processa quebras concluídas, depois ignição vencida, expiração, partículas e exposição. Remove o registro antes de detonar para impedir nova detonação do mesmo bolsão.
- A busca de fogo considera a própria célula e vizinhos: uma tocha que ocupe uma célula antes vazia não pode escapar da detecção.
- `stopped`: limpa apenas a fila transitória de quebras; bolsões e avisos ficam no SavedData.

### `GasZone`, `GasCurios` e tags

- `place` e `placeMetro` delegam a `medicalReward`, com prefixos próprios e tier por dia. A Z3 original e o metrô agora usam reservas premium; a quarentena mantém sua própria montagem militar.
- As tabelas de metrô referenciam as reservas médicas próprias do Events. A local.5 substitui as três rolagens de `medicalairdrop` por equipamento e medicina garantidos. O pack pode sobrescrever esses caminhos sem alterar Java.
- `medicalReward` verifica a tabela final antes de modificar o mundo. O soro agora pertence ao loot JSON, com chance de 35–55% por tier; não existe inserção Java que sobrescreva slots ou force materialização antecipada.
- A capacidade continua em 27 slots, com preenchimento vanilla. A suíte amostra os cinco tiers e os três caminhos de recompensa, verificando capacidade e garantias. Armaduras e ferramentas são sorteadas uma vez; as quantidades não dependem de repetir toda a tabela genérica.
- `mask` prioriza proteção na cabeça, depois consulta o Curios se presente. `findFirstCurio` usa equipamento funcional; item guardado no inventário ou slot cosmético não basta.
- `protectedBreath` mantém hazmat completo sem desgaste e máscara com desgaste a cada dois segundos. Itens sem durabilidade, como as adaptações MO4 do Apocalypse Now, não ganham uma durabilidade artificial nesta entrega.
- O filtro repara a mesma máscara selecionada para proteção. Os três itens de adaptação não precisam de reparo porque não têm durabilidade.
- NPCs humanos continuam usando a tag do equipamento de cabeça. Não foi introduzido inventário Curios para NPCs.
- IDs conferidos no JAR incluem os respiradores antigos, três capacetes Contamination, Soldier, duas MO4 de camuflagem, GP5 (`rusty_helmet`) e Biohazard. A tabela completa de adições está em `RECOMPENSAS_T1_T2.md`. Entradas são opcionais (`required: false`) como as existentes.
- A imagem não fornece IDs textuais. A cobertura foi ampliada pelos itens respiratórios reais; capacetes balísticos, máscaras decorativas e panos de rosto não receberam imunidade genérica.

### `TerritoryConfig`, `EventData` e build

- Configuração separada preserva os nomes existentes e adiciona somente `explosionPower` e `explosionDamageMultiplier`. Forge adiciona defaults aos arquivos antigos; não é necessário apagar configurações.
- `EventData.load/save` conserva os campos de encontros antigos e os dados específicos em compound separado. `TerritorySites` ausente em save antigo resulta em conjunto vazio.
- `near` exclui gás natural para não bloquear a agenda narrativa; reservas de locais continuam independentes dos encontros ativos.
- O descarte geral de encontros não persistentes após sete dias continua vigente. Portanto uma ignição em área nunca recarregada também pode ser removida nessa manutenção; esta revisão não altera a política global de retenção.
- Java 17, Forge 47.4.10, package-private nos auxiliares, records e métodos estáticos seguem o padrão atual. Não foi adotado formatador global nem reorganizado código de facções fora do escopo.
- TaCZ e Curios são `compileOnly` para integração; seus JARs não são incorporados ao mod. Dependências de execução do ambiente de testes ficam em `libs/`, ignorada pelo Git.
- Templates e classes GameTest pertencem a `src/test`; não devem aparecer no JAR reobfuscado distribuído.

## Verificação e limites do aceite

A suíte cobre geometria de túnel, abrigo, plataforma arruinada, montagem de mineiros, mina salva, passagem estreita, inalação, máscaras, hazmat, persistência, mineração concluída/cancelada, TaCZ, aviso, detonação e destruição. Foram acrescentados cenários para dano real ×4, 24 IDs registrados, Curios funcional, tocha colocada, tocha na mão no criativo e tabelas médicas nos cinco tiers.

O teste de dano compara explosão vanilla e explosão de gás na mesma potência e posição relativa, com entidades separadas e vida abaixo do limite do atributo. A mudança de potência amplia o perigo adicionalmente; o multiplicador não depende desse aumento para funcionar.

O ambiente automatizado usa dependências mínimas e não carrega todo o modpack nem CityWorld. O usuário já confirmou funcionamento de encontros em builds anteriores; isso não substitui o reteste visual da revisão atual. Confirmar no pack: loot médico com overrides, cada peça da imagem no slot correto, alcance percebido, dano com as armaduras usadas e posicionamento final dos mineiros na mina real.

Resultado e identidade do artefato final: ver [VALIDACAO_T1_T2.md](VALIDACAO_T1_T2.md). Nenhum commit ou push faz parte desta etapa.

## Índice de linhas da revisão

Intervalos de localização da declaração até o próximo método, incluindo comentários e delimitadores. Índice atualizado para local.5; a classe principal lista somente os métodos ligados a T1/T2.

### `NaturalGas.java` — 213 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–40 | Package, imports, contrato da classe, constantes e estado |
| 41–49 | `Break` |
| 50–54 | `blastDamage` |
| 55–61 | `explode` |
| 62–69 | `blastLoaded` |
| 70–75 | `mineable` |
| 76–85 | `mining` |
| 86–107 | `release` |
| 108–111 | `inside` |
| 112–119 | `breathe` |
| 120–127 | `ignite` |
| 128–134 | `spark` |
| 135–140 | `lighter` |
| 141–148 | `flame` |
| 149–153 | `torch` |
| 154–158 | `playerIgnition` |
| 159–211 | `tick` |
| 212–213 | `stopped` |

### `GasZone.java` — 262 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–59 | Package, imports, contrato da classe, constantes e estado |
| 60–62 | `GasZone` |
| 63–65 | `space` |
| 66–68 | `space` |
| 69–88 | `find` |
| 89–93 | `place` |
| 94–97 | `placeMetro` |
| 98–107 | `medicalReward` |
| 108–111 | `barrel` |
| 112–128 | `barrel` |
| 129–130 | `gassed` |
| 131–137 | `record` |
| 138–139 | `inGas` |
| 140–145 | `hazmat` |
| 146–151 | `mask` |
| 152–154 | `masked` |
| 155–172 | `breathe` |
| 173–183 | `protectedBreath` |
| 184–194 | `choke` |
| 195–202 | `cells` |
| 203–249 | `gasTick` |
| 250–262 | `filter` |

### `GasCurios.java` — 17 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–9 | Package, imports, contrato da classe, constantes e estado |
| 10–11 | `GasCurios` |
| 12–17 | `mask` |

### `GasGunHook.java` — 19 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–12 | Package, imports, contrato da classe, constantes e estado |
| 13–19 | `fire` |

### `GasVolumes.java` — 86 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–15 | Package, imports, contrato da classe, constantes e estado |
| 16–17 | `GasVolumes` |
| 18–22 | `air` |
| 23–27 | `floor` |
| 28–34 | `roof` |
| 35–57 | `tunnel` |
| 58–76 | `pocket` |
| 77–86 | `bounds` |

### `TerritoryConfig.java` — 32 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–30 | Package, imports, contrato da classe, constantes e estado |
| 31–32 | `TerritoryConfig` |

### `TerritoryEvents.java` — 345 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–42 | Package, imports, contrato da classe, constantes e estado |
| 43–44 | `TerritoryEvents` |
| 45–46 | `handles` |
| 47–57 | `weights` |
| 58–87 | `attempt` |
| 88–95 | `announce` |
| 96–123 | `tunnel` |
| 124–157 | `station` |
| 158–176 | `shelterOnPlatform` |
| 177–189 | `mine` |
| 190–195 | `siteKey` |
| 196–206 | `unseen` |
| 207–208 | `Scene` |
| 209–211 | `reserve` |
| 212–215 | `at` |
| 216–241 | `shelter` |
| 242–255 | `miners` |
| 256–268 | `fits` |
| 269–323 | `build` |
| 324–331 | `book` |
| 332–345 | `tick` |

### `MineScenes.java` — 103 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–19 | Package, imports, contrato da classe, constantes e estado |
| 20–21 | `MineScenes` |
| 22–43 | `find` |
| 44–88 | `gallery` |
| 89–103 | `connected` |

### `CityWorldSites.java` — 143 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–23 | Package, imports, contrato da classe, constantes e estado |
| 24–31 | `Subway` |
| 32–33 | `CityWorldSites` |
| 34–35 | `available` |
| 36–47 | `type` |
| 48–59 | `call` |
| 60–64 | `field` |
| 65–69 | `streetLevel` |
| 70–80 | `lotClass` |
| 81–94 | `subway` |
| 95–99 | `inSubway` |
| 100–105 | `contains` |
| 106–122 | `findLots` |
| 123–132 | `fullInfo` |
| 133–143 | `territory` |

### `EventData.java` — 121 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–21 | Package, imports, contrato da classe, constantes e estado |
| 22–25 | `get` |
| 26–41 | `load` |
| 42–48 | `add` |
| 49–54 | `remove` |
| 55–68 | `prune` |
| 69–76 | `near` |
| 77–82 | `result` |
| 83–121 | `save` |

### `LazarusEvents.java` — 769 linhas

| Linhas | Trecho conferido |
|---|---|
| 123–160 | `LazarusEvents` |
| 169–219 | `onTick` |
| 227–260 | `randomType` |
| 266–293 | `commands` |
| 294–317 | `where` |
| 318–335 | `test` |
| 336–346 | `locate` |
| 347–363 | `cancel` |
| 364–393 | `attempt` |
| 394–426 | `attemptBuilding` |
| 682–689 | `removeActors` |
| 734–749 | `label` |

### `TerritoryGameTests.java` — 444 linhas

| Linhas | Trecho conferido |
|---|---|
| 1–27 | Package, imports, contrato da classe, constantes e estado |
| 28–44 | `heldTorchIgnitesInCreativeButNotSpectator` |
| 45–66 | `gasBlastMultipliesActualDamage` |
| 67–81 | `everyRegisteredMaskProtectsBothGases` |
| 82–96 | `functionalCuriosMaskProtectsWithoutHelmet` |
| 97–112 | `placedSoulTorchAutomaticallyIgnitesGas` |
| 113–141 | `premiumRewardsGuaranteeEquipmentAndFitContainer` |
| 142–159 | `contaminationAndBiohazardSetsProtectWithoutWear` |
| 160–163 | `room` |
| 164–169 | `player` |
| 170–175 | `actors` |
| 176–189 | `tunnelStaysInsideAndHasTwoCutExits` |
| 190–207 | `shelterFurnishesOnlyOnePlatform` |
| 208–219 | `blockedSceneDoesNotOverwriteExistingLoot` |
| 220–236 | `ruinedPlatformMovesSpawnsAroundMoss` |
| 237–252 | `lostMinersHaveEquipmentDiaryAndOre` |
| 253–268 | `lostMinersFindGalleryFromSavedSlabStaircase` |
| 269–283 | `minersAdaptToNarrowGalleryAndPreserveSupports` |
| 284–303 | `naturalGasProtectsMasksAndNeverInfects` |
| 304–330 | `ignitionWarnsThenDestroysBlocks` |
| 331–351 | `successfulMiningRollAndCooldown` |
| 352–360 | `cityWorldFallbackAndHeightBands` |
| 361–377 | `burningCampfireAutomaticallyIgnitesPocket` |
| 378–397 | `cancelledMiningCannotReleaseGas` |
| 398–410 | `taczShotIgnitesGasThroughEventBus` |
| 411–427 | `originalInfectiousGasKeepsPoisonAndHazmatRules` |
| 428–444 | `saveReloadKeepsGasWarningAndExhaustedSite` |
