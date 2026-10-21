# Validação local — 20/10/2026

## Aceite manual atualizado — 21/10/2026

- [x] Dano da explosão de gás natural: usuário confirmou que está bom na local.4.
- [x] Destruição de blocos pela explosão: usuário confirmou que está boa na local.4.
- [x] Máscaras no gás natural: usuário confirmou que estão funcionando muito bem na local.5.
- [ ] Loot ampliado do túnel: próximo reteste solicitado pelo usuário.
- [ ] Alcance da explosão: sem confirmação isolada nesta rodada.
- [ ] Ignição por tochas e checagem complementar de máscara/conjunto completo no gás infeccioso: concluir reteste no pack.

Entrega autorizada pelo usuário em branch T1/T2 e pull request para revisão do Kaique. Os itens sem confirmação explícita acima continuam registrados como pendentes de aceite manual, embora a suíte automatizada tenha passado.

Para voltar ao túnel do mesmo `New World`, foi conferido no save piso de cascalho em Y 40 e espaço livre em X 160 / Y 41 / Z 424. Teleporte de teste: `/tp @s 160.5 41 424.5`; confirmar `METRO_TUNNEL` com `where` e criar um novo `metro_gas near` para conferir o loot revisado. Barris já gerados não recebem reposição retroativa.

Base: `dfdcd727a4abf752c8a7ac5dfd801f2b1c110989`. Build de teste: `0.23.0-local.1`, Java 17.0.20.1, Forge 47.4.10 / Minecraft 1.20.1.

Comando executado:

```text
gradlew.bat build runGameTestServer -x downloadAssets --console=plain
```

Resultado final: **BUILD SUCCESSFUL; All 13 required tests passed**. Log integral local: `validation-final.log` (ignorado pelo Git).

Cobertura executada:

1. Volume de gás em corredor longo, paredes e saídas nas duas pontas, com corte na fronteira da estação.
2. Abrigo com quatro barracas, quatro civis infectados, diário e dois baús; outra plataforma preservada.
3. Rejeição integral de cena que atingiria um contêiner existente.
4. Mineiros equipados, diário, minério e trilho/carrinho.
5. Volume natural somente em ar, efeitos distintos e proteção/desgaste da máscara.
6. Aviso de três segundos, ignição sem reiniciar timer, dano da explosão e preservação do terreno.
7. Quebra concluída com chance forçada e cooldown por jogador.
8. Fallback seguro sem CityWorld e bandas de altura das duas linhas.
9. Ignição automática por fogueira e preservação do bloco na explosão.
10. Quebra cancelada ou não concluída sem liberação de gás.
11. Ignição pelo evento real do TaCZ no barramento Forge.
12. Regressão do veneno do gás infeccioso e proteção do hazmat nos dois gases.
13. Serialização/releitura de volume, ignição pendente e reserva de local já usado.

`git diff --check` passou. A sintaxe do script PowerShell foi conferida. O JAR não contém classes/arena de teste nem JARs de terceiros.

Artefato: `build/libs/lazarusevents-0.23.0-local.1.jar`.

SHA-256: `A4317C4324D35A692F3342A9B011F1ACA751D7CD6631377F0F651E93A8C9B744`.

## Limites do resultado

O servidor de arena não carrega CityWorld. A geometria foi conferida no código decompilado do JAR 6.3.0 instalado, mas busca de lotes, escadarias, alas disponíveis e visual precisam da validação manual no modpack. A suíte não substitui esse aceite. Não foi instalado o novo JAR na instância principal, nem executado cliente gráfico.

O primeiro download de assets do cliente falhou por timeout numa música; os testes foram executados com `-x downloadAssets`, pois o servidor não precisa desses recursos audiovisuais. O runtime reduzido também registra avisos de integrações/receitas opcionais ausentes; os 13 testes terminaram aprovados e o processo Gradle saiu com código 0. A integração visual com todos os mods da Alpha 1.4.5 permanece no roteiro manual.

Não houve commit, push ou publicação. As alterações estão locais no clone.

## Correção de plataforma — 21/10/2026, local.2

Após recusa no teste manual, o save foi lido com o Minecraft fechado, sem alterar o mundo. A plataforma oeste da estação próxima de X 104 / Z 424 tinha musgo em uma posição fixa de infectado; o centro também possuía escadas e grades. A busca anterior exigia que o jogador estivesse no chunk central, embora alas da plataforma possam ser classificadas como túnel.

A build `0.23.0-local.2` resolve a estação próxima pelo CityWorld e tenta posições livres na mesma passagem para os quatro infectados, preservando o musgo e a proteção de contêineres/escadarias. A mensagem de recusa agora informa a estação próxima. O teste de regressão reproduz musgo no terceiro spawn e bancos de laje da plataforma salva.

Resultado: **14 GameTests aprovados**, log `validation-platform-fix.log`; build final em `build-platform-fix.log`. Aceite visual no mundo do usuário continua pendente.

## Correção da busca de mineiros — 21/10/2026, local.3

O comando foi recusado apesar de `where` confirmar `MINE`. A posição salva era uma escadaria de lajes em Y 32,5, e a busca anterior só examinava três níveis próximos e exigia um retângulo de cinco por três blocos.

A busca agora examina piso coberto carregado até 24 blocos ao redor, 48 abaixo e oito acima da origem. Preserva o layout anterior onde ele cabe e usa uma montagem adaptativa nas galerias estreitas: trilho/carrinho, baú, diário, lâmpada apagada e três infectados. Os móveis sólidos não podem interromper a conectividade da passagem; pilares e contêineres existentes continuam protegidos.

Regressões adicionadas: geometria real da mina salva (somente blocos, sem dados de jogador nem entidades) e galeria estreita com pilar de madeira. A primeira tentativa de ampliar pouco a busca ainda falhou no recorte do save; a versão final alcançou as galerias inferiores.

Resultado final: **BUILD SUCCESSFUL; All 16 required tests passed**, log `validation-mine-fix.log`. O aceite no cliente real continua pendente; a mensagem de colocação fornece a posição, que pode estar abaixo da escadaria de entrada.

## Melhorias de gás e revisão técnica — 21/10/2026, local.4

Foram aplicados os seis ajustes solicitados: três rolagens de loot médico no túnel, ignição por tochas colocadas e empunhadas, destruição de blocos, dano de explosão ×4 antes da armadura, potência padrão 5 em vez de 2,5 e proteção para 16 IDs respiratórios do Apocalypse Now, incluindo três adaptações MO4 no Curios funcional.

Resultado final: **BUILD SUCCESSFUL; All 22 required tests passed**, log local `validation-gas-improvements-final.log`. Mesmo comando de build e servidor descrito no início deste registro. Novos cenários validam dano efetivo ×4 em comparação à explosão vanilla na mesma potência, todos os IDs de proteção, slot Curios sem capacete, tocha colocada, tocha na mão no criativo versus espectador e tabelas de loot dos cinco tiers. Testes antigos de preservação de terreno/fogueira foram substituídos pelo requisito posterior de destruição.

A primeira rodada detectou expectativas incorretas nos testes novos: eram 16 IDs, não 17; somente o tier um da dependência garante uma maçã por rolagem; e vida 2000 ultrapassava o teto do atributo da entidade. As expectativas foram corrigidas após conferir registros, tabelas e vida efetiva; a comparação final usa vida 1000. Não se ocultou a divergência reduzindo o multiplicador de produção.

A revisão também confirmou área de explosão carregada antes da detonação automática e inserção do soro apenas em slot vazio no barril do metrô. Detalhes, rastreamento de métodos e limitações: [REVISAO_T1_T2.md](REVISAO_T1_T2.md).

Artefato: `build/libs/lazarusevents-0.23.0-local.4.jar`, 348158 bytes.

SHA-256: `08DF7C6001145DE97E65EDBBC3B864FBF54A2C714EA3A12E49162982D304FA3F`.

Inspeção do ZIP: metadados local.4 corretos, cinco tabelas novas presentes, JSONs válidos, pontes de produção presentes, nenhuma classe GameTest nem template de arena/regressão incluído. Estruturas de produção preexistentes continuam no JAR.

Instalado na Alpha 1.4.5 com Minecraft fechado e hashes de origem/destino conferidos. Somente `lazarusevents-0.23.0-local.4.jar` permanece em `mods`. Backup da local.3: `backups/events-before-gas-improvements-20261021-021923`, dentro da instância.

O servidor mínimo ainda registra receitas antigas inválidas e avisos de integrações ausentes das dependências. Não foram alteradas receitas nesta revisão. O processo terminou com código 0 e todos os testes passaram. Reteste no cliente completo continua necessário para loot com KubeJS, peças da imagem nos slots corretos, balanceamento de dano/alcance e colocação final dos mineiros. Nenhum commit, push ou publicação foi realizado.

## Proteção complementar e loot premium — 21/10/2026, local.5

Pedidos aplicados: capacetes White/Blue/Green Contamination, Soldier Gas Mask, variantes MO4 Forest/Mud, GP5 e Biohazard adicionados à proteção. MO4 comum e PKM-4 já constavam e foram novamente validados. Total: 24 IDs respiratórios. Conjuntos Contamination e Biohazard completos entram na proteção hazmat sem desgaste; as cores Contamination compartilham as botas registradas como `contamination_suit_white_boots`.

Gases de infecção e metrô recebem tabelas premium próprias com medicina, filtros e duas peças de equipamento garantidas. Mineiros recebem recursos ampliados, diamantes, esmeraldas, picareta de diamante encantada e suprimentos de expedição, em cinco tiers. Não é reposição retroativa de contêineres. Tabelas, quantidades e IDs: [RECOMPENSAS_T1_T2.md](RECOMPENSAS_T1_T2.md).

Resultado: **BUILD SUCCESSFUL; All 23 required tests passed**, log `validation-premium-loot.log`. O teste de recompensa avalia 25 amostras para cada uma das 15 combinações de tabela/tier, totalizando 375 sorteios, verificando garantias e capacidade de 27 slots. Testes de equipamentos verificam todos os 24 registros e quatro conjuntos completos adicionais. A regressão dos mineiros verifica minério em qualquer slot, pois o preenchimento de loot agora é aleatório, sem depender de uma posição fixa.

Dano e destruição do gás natural permanecem sem alterações, já aprovados pelo usuário. A suíte de explosão e ignição passou novamente.

Artefato: `build/libs/lazarusevents-0.23.0-local.5.jar`, 354527 bytes.

SHA-256: `7E0C380C6F42C331968BC29EB8F57815520CBA816852186982E45C942EBFAFC5`.

Inspeção de artefato aprovada: metadados corretos, 15 tabelas de recompensa presentes, JSONs válidos, sem classes e arenas GameTest. `git diff --check` aprovado.

Instalado na Alpha 1.4.5 com Minecraft fechado, apenas um JAR Events em `mods` e hash conferido. Backup da local.4: `backups/events-before-premium-loot-20261021-025342`, dentro da instância. Sem commit ou push. Reteste manual dos novos equipamentos e do valor percebido das recompensas pendente.
