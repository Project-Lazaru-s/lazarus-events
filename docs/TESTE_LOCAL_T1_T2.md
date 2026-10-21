# Teste local de T1 e T2

Esta entrega usa a versão de desenvolvimento **0.23.0-local.5**. Nenhum push é necessário para testar.

## Build e testes automáticos

Java 17 e os JARs locais são necessários. Execute na raiz:

```powershell
.\tools\test-local.ps1
```

O script prepara `libs/` a partir da Alpha 1.4.5, usa o JDK portátil ao lado do clone quando disponível e executa build + GameTests. Para outra instância/JDK, informe `-Instance` e `-Jdk`. `-BuildOnly` gera só o JAR.

Os testes pertencem ao Events, em `src/test`, e não são incluídos no JAR distribuído. O servidor de testes usa `run/`, separado dos saves do modpack. Não inclui CityWorld: as cenas são testadas com geometria de arena e o fallback sem CityWorld é verificado. A descoberta de lotes e a composição visual exigem a conferência no mapa real abaixo.

Dependências locais ignoradas pelo Git: NPCs 1.16.0, Apocalypse Now 3.0.5, Curios 5.14.1, TaCZ 1.1.8 hotfix2, Dyairdrop 1.0.0 hotfix3.0 e GeckoLib 4.8.4. Não redistribua esses JARs com o código.

## Instalar para conferir no modpack

Com o jogo fechado, use uma cópia de teste da instância e do mundo. Substitua apenas o JAR de Events pelo arquivo em `build/libs/lazarusevents-0.23.0-local.5.jar`, preservando o antigo fora de `mods`. Deve haver apenas um JAR de Events em `mods`.

O patch não precisa de nova versão de NPCs. As versões instaladas de CityWorld 6.3.0 e NPCs 1.16.0 continuam sendo a referência.

## Comandos e roteiro de aceite

Comandos exigem permissão de operador e são executados no Overworld:

```text
/lazarusevents where
/lazarusevents test metro_gas near
/lazarusevents test metro_shelter near
/lazarusevents test lost_miners near
/lazarusevents test natural_gas near
/lazarusevents status
/lazarusevents locate <inicio-do-uuid>
/lazarusevents cancel <inicio-do-uuid>
```

- **Túnel:** fique no piso entre estações. O gás deve acompanhar a galeria por até 59 blocos, parar nas paredes e não invadir a estação. Confira ambas as saídas, barril médico premium com kits, antibióticos, bandagens, filtros, máscara e peitoral garantidos do tier atual, rádio com walkie-talkie, máscara, filtro, hazmat e fuga de humanos. Gás de metrô usa os efeitos infecciosos existentes e dura dois dias de jogo.
- **Estação:** fique em uma plataforma ou no trecho do metrô próximo à estação. O comando procura uma ala livre da estação, com preferência pelas extensões sem escadarias. Confira quatro barracas com camas, quatro infectados civis, dois baús do tier atual, caixas/lixo, aviso e diário no atril. A outra plataforma e os trilhos ficam livres. Ao esvaziar os baús, o encontro termina; o local continua reservado no save, sem reposição de loot. Se não couber sem atingir escadarias ou contêineres, o comando informa falha.
- **Gás natural:** fique numa galeria subterrânea. Teste sem proteção e com máscara; os efeitos são tontura, fraqueza e dano leve, sem infecção. Fogueira acesa, fogo, tocha normal/de almas na mão ou colocada (inclusive na parede), isqueiro ou tiro dentro do bolsão devem iniciar um chiado de três segundos e depois uma explosão que destrói blocos conforme a resistência vanilla. A potência padrão passou de 2,5 para 5,0, e o dano da explosão recebe multiplicador 4 antes da armadura. A explosão afeta uma área maior; paredes e resistência dos blocos ainda influenciam o resultado. Lanternas comuns não acendem o gás. A máscara protege da inalação, não da explosão. Tochas também iniciam ignição no criativo; espectadores não. Confira máscara no capacete (24 IDs, incluindo Contamination, Soldier, GP5, MO4 de camuflagem e Biohazard) e cada variante MO4 no slot funcional do Curios, nunca apenas no inventário ou slot cosmético.
- **Mineração real:** abaixo de Y 0, quebre pedra ou minério. O padrão é uma chance em 400 quebras concluídas e intervalo de dez minutos por jogador. O comando forçado serve para conferir o efeito sem esperar o sorteio. Cancelar a quebra não deve gerar gás.
- **Mina:** use `where` para confirmar `MINE`. A busca inclui galerias carregadas até 24 blocos ao redor e 48 abaixo da entrada, pois a escadaria de lajes não comporta a cena. A mensagem informa as coordenadas exatas da montagem; ela pode ficar num piso inferior. Confira trilhos/carrinho, lâmpada apagada, três mineiros infectados equipados com capacete/picareta, minério em quantidade, diamantes/esmeraldas, picareta de diamante encantada, proteção respiratória, medicamentos e diário. O layout adaptativo preserva pilares e verifica que os móveis não cortam a passagem da galeria.

Para aproximação automática, `test <tipo> natural` usa a busca natural dos encontros narrativos. A seleção automática acrescenta os eventos de metrô quando o jogador está no metrô e os mineiros quando está em mina/caverna. O gás de mineração é independente da agenda narrativa.

## Configuração e persistência

`config/lazarusevents-territories.toml` contém os switches de metrô/mina e, em `naturalGas`, `enabled`, `belowY`, `chanceOneIn`, `cooldownSeconds`, `durationSeconds`, `explosionPower` (5,0; faixa 2,5–12,0) e `explosionDamageMultiplier` (4,0; faixa 4,0–16,0). Os novos valores são adicionados ao arquivo existente pelo Forge. O padrão do bolsão é três minutos. Para testar o sorteio rapidamente, temporariamente use `chanceOneIn = 1`, mantendo o cooldown configurado. Restaure os valores ao concluir.

Volumes, expiração e aviso de ignição ficam no SavedData existente. O cooldown é persistido no personagem. Bolsões não consomem as quatro vagas de encontros narrativos; têm limite próprio de 16. A busca não força chunks a carregar. Cancelar deixa blocos e loot, como nos eventos anteriores.

T3, T4 e lore futura das zonas não entram nesta entrega. Os resultados de execução automática e os pontos de validação manual devem ser registrados antes da publicação.

## Revisão local.5

O loot médico foi substituído por reservas próprias premium nos gases de infecção e do metrô; a mina ganhou tabela por tier. Confira as [garantias e equipamentos](RECOMPENSAS_T1_T2.md). Teste em encontros novos: barris/baús já abertos não são reabastecidos. Efeitos aplicados antes de vestir a máscara podem durar até expirar; proteção impede nova exposição, não funciona como tratamento. Os conjuntos Contamination/Biohazard completos também devem proteger sem desgaste.
