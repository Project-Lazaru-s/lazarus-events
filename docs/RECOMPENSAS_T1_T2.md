# Equipamentos e recompensas — local.5

Esta revisão substitui as três rolagens médicas genéricas da local.4 por recompensas próprias e garantidas. Aplica-se aos novos barris de `infection_zone` e `metro_gas` e aos novos baús de `lost_miners`. Contêineres existentes não são repostos nem reabastecidos.

## Proteção respiratória

Agora são 24 IDs na tag `lazarusevents:gas_masks`, incluindo as três adaptações Curios existentes. Adições conferidas no Apocalypse Now 3.0.5:

| Nome no jogo | ID `apocalypsenow:` |
|---|---|
| White Contamination Suit Helmet | `contamination_suit_white_helmet` |
| Blue Contamination Suit Helmet | `contamination_suit_blue_helmet` |
| Green Contamination Suit Helmet | `contamination_suit_green_helmet` |
| Soldier Gas Mask | `soldier_helmet` |
| MO4 Forest Camo Gas Mask | `mo4_forest_camo_helmet` |
| MO4 Mud Camo Gas Mask | `mo4_mud_camo_helmet` |
| GP5-Gas Mask | `rusty_helmet` |
| Biohazard Helmet | `biohazard_helmet` |

A MO4 comum (`mo4_gas_mask_helmet`) e a PKM-4 (`pkm_4gasmask_helmet`) já estavam na tag e continuam cobertas pela regressão de todos os IDs. As variantes de adaptação MO4 continuam funcionando no slot funcional do Curios.

Capacete sozinho protege a respiração com a regra de desgaste da máscara. Os conjuntos Contamination e Biohazard completos também entram na tag `hazmat`, protegendo sem desgaste, como os hazmats anteriores. As três cores Contamination compartilham as botas `contamination_suit_white_boots` no registro do mod. Armadura não protege da explosão por ser uma máscara: proteção respiratória e dano da explosão são sistemas distintos.

## Reservas médicas de gás infeccioso

Tier = `min(5, 1 + dia / 10)`, igual à progressão existente. Não dependem mais da qualidade das tabelas genéricas `medicalairdrop`.

| Garantia por barril | Tier 1 | Tier 2 | Tier 3 | Tier 4 | Tier 5 |
|---|---:|---:|---:|---:|---:|
| Medical Kit | 2 | 3 | 3 | 4 | 4 |
| Antibiotics | 5 | 6 | 7 | 8 | 9 |
| Bandage | 10 | 12 | 14 | 16 | 18 |
| Breathing Filter Module | 3 | 4 | 4 | 5 | 5 |
| Painkillers | 3 | 4 | 4 | 5 | 5 |
| Canned Water | 3 | 3 | 3 | 3 | 3 |
| Canned Food | 4 | 4 | 4 | 4 | 4 |
| Máscara/capacete respiratório novo | 1 | 1 | 1 | 1 | 1 |
| Peitoral de proteção novo | 1 | 1 | 1 | 1 | 1 |
| Maçã dourada | 1 | 2 | 2 | 3 | 3 |
| Chance de soro experimental adicional | 35% | 40% | 45% | 50% | 55% |

Nos tiers 1–2, máscaras GP5/Soldier/MO4/PKM-4 e peitorais Contamination/Biohazard. Nos tiers 3–5, capacetes Biohazard/Advanced Hazmat/Level A/PKM-4 e peitorais Advanced Hazmat/Level A/Biohazard. Uma peça é sorteada de cada grupo, sem dano pré-aplicado. O jogador recebe duas peças de proteção, não um conjunto completo garantido.

`metro_medical1..5` referenciam `infection_medical1..5` uma vez. Isso mantém o prêmio dos dois gases consistente sem triplicar máscaras, medicamentos e armaduras nem duplicar a definição. As tabelas são recursos normais de datapack e podem ser sobrescritas pelo pack nos caminhos `lazarusevents:chests/...`.

## Reservas dos mineiros

Cada baú contém:

- Ferro bruto: `24 + 4 × tier` a `32 + 4 × tier`.
- Carvão: mesma faixa do ferro.
- Ouro bruto: `10 + 2 × tier` a `16 + 2 × tier`.
- Cobre bruto: `16 + 4 × tier` a `24 + 4 × tier`.
- Diamantes e esmeraldas: cada um `2 + tier` a `4 + tier`.
- Uma picareta de diamante encantada: Eficiência III–V, Durabilidade II–III, Fortuna I–III conforme tier.
- Uma máscara nova (GP5, MO4 ou PKM-4), dois a quatro filtros, dois kits médicos, cinco a nove bandagens, três águas e três comidas enlatadas.
- Um a três blocos de ferro; 30–50% de chance de um a três blocos de ouro adicionais.

No tier 1 isso já garante 28–36 ferros brutos, 28–36 carvões, 12–18 ouros brutos, 20–28 cobres brutos, 3–5 diamantes, 3–5 esmeraldas e picareta com Eficiência III / Durabilidade II / Fortuna I.

Não há geração de loot no gás natural de mineração: o baú é prêmio do encontro dos mineiros. A reserva persistida do local continua impedindo repor um encontro já saqueado.

## Verificação

As tabelas são avaliadas nos cinco tiers para os três caminhos de recompensa, com 25 amostras por combinação (375 sorteios). A suíte verifica itens garantidos, proteção, picareta encantada e capacidade de 27 slots. Também verifica todos os 24 IDs de máscara e os quatro conjuntos completos adicionados. O balanceamento percebido e a interação com todas as regras de equipamentos do pack continuam no aceite manual.
