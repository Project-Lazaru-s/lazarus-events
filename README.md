<p align="center">
  <img src="docs/banner.png" alt="Lazarus Events: acampamento de suprimentos, barricada, sinalizador e posto militar em uma cidade abandonada" width="100%">
</p>

<p align="center">
  <img alt="Minecraft 1.20.1" src="https://img.shields.io/badge/Minecraft-1.20.1-3c8527">
  <img alt="Forge 47.x" src="https://img.shields.io/badge/Forge-47.x-dd7d2b">
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-b07219">
  <img alt="Versão 0.6.0" src="https://img.shields.io/badge/vers%C3%A3o-0.6.0-6b5b3e">
</p>

# Lazarus Events

**Lazarus Events** é um mod para Minecraft Forge 1.20.1 criado para o modpack **Project Lazarus**. Ele espalha pequenos encontros pelo mundo enquanto o jogador explora: acampamentos abandonados, barricadas após disparos, pedidos de socorro (verdadeiros ou não), defesas contra hordas e bases militares. Cada cenário tem uma construção própria, personagens ou infectados e recursos que fazem sentido para o local.

Os encontros são colocados em áreas já carregadas do *Overworld*. O mod não gera chunks novos, não depende dos antigos scripts de eventos do KubeJS e guarda o progresso separadamente em cada mundo. Os textos dentro do jogo estão em inglês, seguindo o padrão do modpack.

---

## Sumário

- [Encontros](#encontros)
- [Como aparecem](#como-aparecem)
- [Comandos para testar](#comandos-para-testar)
- [Configuração](#configuração)
- [Instalação](#instalação)
- [Créditos e licença](#créditos-e-licença)

---

## Encontros

| Evento | Desde o dia | O que o jogador encontra |
|---|:---:|---|
| **Cache** | 1 | Pequeno acampamento com tenda branca, cama, fogueira, lanternas, defesas improvisadas, infectados e suprimentos em um barril. |
| **Gunfire** | 1 | Barricada de estrada com sacos de areia e cobertura de madeira, onde um soldado enfrenta infectados. |
| **Flare** | 1 | Marca de resgate no chão, sinalizador vermelho, infectados e uma **Medical Drop Box** do Apocalypse Now. |
| **Rescue** | 1 | Sobrevivente ferido junto a um sinalizador, pedindo bandagens e escolta até um lugar seguro. |
| **Last Defense** | 3 | Três sobreviventes defendem uma barricada e pedem reforço contra ondas de infectados. |
| **False Radio** | 6 | Um pedido de socorro pelo rádio que é uma emboscada de saqueadores em volta de um acampamento com carga. |
| **Military** | 10 | Base militar guardada por quatro militares, com barris de suprimentos, geladeira e gerador ligado. |

Rescue, Last Defense, False Radio e Military usam a API do **Lazarus NPCs**: ordens aos personagens (esperar, seguir, guardar, recuar), conversa com escolhas, tarefa no diário (aba **Encounters**) e recompensa. Só um jogador por vez pode aceitar a tarefa de um mesmo personagem.

### Rescue

O sobrevivente pede **duas bandagens**. Depois de tratado, segue o jogador até um **ponto seguro** a cerca de 40 blocos, na direção de onde o jogador veio. Ao chegar, o jogador recebe **2 comidas enlatadas, 1 água enlatada e 1 antibiótico** e **+5 de reputação** com a facção do sobrevivente. Se ele morrer, a tarefa falha. Se a tarefa for abandonada ou vencer, ele volta a esperar e outro jogador pode ajudar.

### Last Defense

Os três defensores são da mesma facção. Quem aceita precisa **ficar a até 24 blocos da barricada por dois minutos**; o tempo só conta com o jogador por perto. Os infectados chegam em **três ondas de 6, 9 e 12** (mais um a cada dez dias de mundo, até +4). Cada defensor que cai é anunciado; se todos caírem, a tarefa falha. Recompensa: **3 comidas enlatadas, 2 bandagens** e **+2 de reputação por defensor vivo** (até +6) com a facção deles.

### False Radio

Três saqueadores guardam a carga. Quando o jogador chega a 20 blocos, **um deles vai até ele** e faz a exigência pessoalmente; o grupo segura o fogo por 30 segundos de conversa, e atacar encerra a trégua na hora. A conversa tem quatro saídas:

- **Pagar 3 comidas enlatadas:** o grupo deixa o jogador passar, mas a carga fica com eles.
- **Blefar** que há uma patrulha chegando: uma tentativa; metade das vezes eles acreditam e se afastam, deixando a carga.
- **Brigar pela carga:** tarefa opcional, concluída quando não resta saqueador perto da carga (mortos, em fuga ou rendidos), com **2 comidas enlatadas e +2 Wardens**.
- **Ir embora.**

Não há troca amigável. A mensagem de descoberta e o rádio não revelam que é uma armadilha. Se o jogador simplesmente se aproximar, valem o aviso e o pedágio normais dos saqueadores.

### Military

Os soldados **guardam a base** e o médico fica no posto. A recepção depende da reputação com os **Military**: abaixo de 10, área restrita, mas é possível oferecer ajuda; a partir de 10, boas-vindas; com -20 ou menos, o jogador é mandado embora. A tarefa **Secure the perimeter** traz três ondas de infectados contra a cerca: mate **12 atacantes** e volte ao soldado que deu a ordem para receber **30 munições 5.56, 2 bandagens e +3 Military**.

### Construções e loot

As construções são templates próprios capturados de cenários feitos no jogo. Seus blocos são adaptados à orientação do local. Tapetes de musgo e lajes do terreno podem ser substituídos pela construção quando há apoio sólido; água e terreno inseguro continuam impedindo a colocação. Encontros gerados antes de uma atualização não são reconstruídos retroativamente.

O cache usa uma tabela de suprimentos; os barris militares usam tabelas de armas e suprimentos do Dyairdrop. A Medical Drop Box usa o comportamento e o loot do próprio bloco do Apocalypse Now. Rescue reaproveita a marca do Flare, Last Defense a barricada do Gunfire e False Radio o acampamento do Cache, com a carga em um barril de suprimentos do Dyairdrop.

---

## Como aparecem

- Após a primeira entrada no mundo, a primeira tentativa natural é programada para cerca de **um dia de jogo** depois. Após um encontro bem-sucedido, a próxima tentativa ocorre em **1 a 2 dias**, por padrão.
- Cache, Gunfire, Flare e Rescue podem aparecer desde o começo; Last Defense a partir do **dia 3**; False Radio a partir do **dia 6**, junto com os saqueadores; e Military a partir do **dia 10**, por padrão. Pesos de sorteio entre os tipos liberados: Cache 9, Gunfire 6, Flare 4, Rescue 4, Last Defense 3, False Radio 3 e Military 1.
- O mod procura um local adequado em chunks carregados, de 40 a 78 blocos do jogador, fora da direção para a qual ele está olhando. O terreno precisa ser seguro para a construção.
- Um local inválido adia a tentativa em vez de consumir o intervalo inteiro. O limite padrão é de **quatro encontros acompanhados** ao mesmo tempo; registros antigos expiram depois de sete dias de jogo.
- Ao chegar a 24 blocos de um encontro, o jogador recebe uma mensagem de descoberta. Quem carrega o *walkie-talkie* do Immersive Frequencies no inventário pode receber pelo rádio coordenadas aproximadas de um encontro recém-criado, se estiver a até 192 blocos.

Essas distâncias e condições explicam por que um comando de teste pode responder **No valid location**. O modo `near` procura entre 14 e 34 blocos e dispensa a regra da direção da câmera; ele continua exigindo terreno e chunks válidos.

---

## Comandos para testar

Os comandos exigem permissão de operador, e os testes de colocação devem ser feitos no *Overworld*.

| Comando | Função |
|---|---|
| `/lazarusevents status` | Mostra o tempo até a próxima tentativa, o número de encontros acompanhados e o último resultado. |
| `/lazarusevents test cache near` | Procura um local próximo para o acampamento. |
| `/lazarusevents test gunfire near` | Procura um local próximo para a barricada. |
| `/lazarusevents test flare near` | Procura um local próximo para o sinalizador. |
| `/lazarusevents test military near` | Procura um local próximo para o posto militar, mesmo antes do dia 10. |
| `/lazarusevents test rescue near` | Procura um local próximo para o sobrevivente ferido. |
| `/lazarusevents test lastdefense near` | Procura um local próximo para a barricada defendida. |
| `/lazarusevents test falseradio near` | Procura um local próximo para a emboscada do falso sinal. |
| `/lazarusevents test <tipo> natural` | Usa a distância e a regra de direção dos encontros naturais; relata os motivos de rejeição. |
| `/lazarusevents locate <id>` | Mostra as coordenadas de um encontro registrado. O começo do ID é suficiente. |
| `/lazarusevents cancel <id>` | Para de acompanhar o encontro e remove seus personagens identificados; blocos e loot permanecem. Tarefas abertas desse encontro falham sem recompensa para os jogadores online. |

`<tipo>` pode ser `cache`, `gunfire`, `flare`, `military`, `rescue`, `lastdefense` ou `falseradio`. Os comandos de teste criam encontros reais no mundo, mas **não alteram o horário** do próximo evento natural.

---

## Configuração

O arquivo `config/lazarusevents-common.toml` é criado pelo Forge. Nele é possível ativar ou desativar o sistema e cada tipo de encontro, alterar o intervalo entre encontros, definir o primeiro dia de Military e ajustar o limite de encontros acompanhados. O estado dos encontros e o resultado da última tentativa são salvos por mundo.

---

## Instalação

### Requisitos

- **Minecraft 1.20.1**
- **Minecraft Forge 47.x** (desenvolvido com 47.4.10)
- **Java 17**
- **Apocalypse Now 3.0.5 ou superior**
- **Dyairdrop 1.0.0 ou superior**
- **Lazarus NPCs 1.6.0 ou superior**

O Immersive Frequencies é usado apenas para as mensagens opcionais de rádio.

### Jogadores e servidores

1. Baixe [`lazarusevents-0.6.0.jar`](dist/lazarusevents-0.6.0.jar) ou [compile o projeto](#compilar-a-partir-do-código).
2. Coloque o JAR na pasta `mods` do perfil. No CurseForge, clique com o botão direito no perfil e escolha **Open Folder**.
3. Em multiplayer, instale o **mesmo JAR** no servidor e em todos os clientes.
4. Remova versões antigas de `lazarusevents` e reinicie o jogo ou servidor após a troca.

### Compilar a partir do código

Com JDK 17 instalado, execute na raiz do projeto:

```bash
./gradlew build
```

No Windows, use `gradlew.bat build`. O JAR gerado fica em `build/libs/`.

A compilação usa a API do Lazarus NPCs sem embuti-la: copie `lazarusnpcs-1.6.0.jar` para `libs/` antes de compilar. Os testes de ponta a ponta dos encontros ficam no projeto Lazarus NPCs.

---

## Créditos e licença

- Desenvolvido para o **Project Lazarus** por **Kaique Melo**.
- Templates dos encontros: construções originais montadas em jogo para o projeto.
- Banner: arte gerada com IA para o Lazarus Events.
- Não é afiliado à Mojang ou à Microsoft.

**Licença:** All Rights Reserved.
