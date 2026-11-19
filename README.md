# NDS para APK

App Android que transforma uma ROM `.nds` **sua** em um APK próprio (ícone, nome e pacote
do jogo), que abre direto no jogo. Funciona com qualquer ROM que o melonDS rode.

```
┌───────────────────────┐   escolhe a ROM   ┌──────────────────────────────┐
│ generator (app)       │ ────────────────► │ APK do jogo                  │
│  • lê título/ícone    │                   │  • player (frontend libretro)│
│  • troca nome/pacote  │                   │  • núcleo melonDS            │
│  • embute a ROM       │                   │  • assets/game.nds           │
│  • assina e instala   │                   └──────────────────────────────┘
└───────────────────────┘
```

* `player/` – APK-modelo. Frontend libretro em C++ (vídeo por `ANativeWindow`, áudio AAudio,
  entrada) + `MainActivity` com controle virtual, toque na tela inferior, controle físico,
  estados salvos (3 slots), avanço rápido e save da cartucha.
* `generator/` – o app que você instala. Contém o `player` compilado como `template.apk`.
  Ao gerar um jogo ele copia o modelo, reescreve o manifesto binário (pacote e nome), troca os
  ícones pelo do banner da ROM, remove a assinatura antiga, embute a ROM sem compressão
  (alinhada) e assina (v1+v2+v3, biblioteca `apksig`).
* `tests/run.sh` – testes sem Android do que é puro Java: AXML (UTF-8 e UTF-16), leitura de
  ROM/ícone do banner e montagem/alinhamento do APK.

## Como compilar (GitHub Actions)

1. Crie um repositório (pode ser privado) e envie todo o conteúdo desta pasta.
2. O workflow `.github/workflows/build.yml` roda sozinho no push: executa os testes, baixa o
   núcleo melonDS (`tools/fetch_core.sh`), compila o `player`, embute no `generator` e publica
   o artefato **nds-para-apk** (`generator-release.apk`).
3. Baixe o artefato, instale o APK e abra **NDS para APK**.

Se o download do núcleo falhar, coloque um `libretro_core.so` (arm64) em `cores/` e rode de
novo. Pelo botão *Run workflow* dá para escolher a ordem de tentativa (`melonds` ou `melondsds`).

## Usando

1. **Escolher ROM** → o app mostra o ícone/título lidos da ROM; edite o nome se quiser.
2. **Gerar APK** (alguns segundos; a ROM é copiada para dentro do APK).
3. **Instalar agora** (na primeira vez o Android pede para permitir instalar de esta fonte)
   ou **Salvar o APK em um arquivo**.

Cada jogo vira um app independente (`com.ndspack.game.g<código>`), com saves próprios.
Gerar o mesmo jogo de novo atualiza o app existente (a assinatura é a mesma).

No jogo: toque na tela inferior = toque do DS; botão ☰ ou o botão Voltar abre o menu
(estados, reiniciar, avanço rápido, sair). Controle físico: A/B/X/Y por posição, D-pad/analógico,
L1/R1, Start/Select, botão Home do controle abre o menu.

## Detalhes e limites

* **BIOS/firmware:** opcionais. Sem eles o melonDS usa a BIOS livre embutida. Para usar os
  reais, copie `bios7.bin`, `bios9.bin` e `firmware.bin` para
  `Android/data/<pacote do jogo>/files/system/`.
* **Opções do núcleo:** crie `core_options.txt` na pasta de dados internos do app com linhas
  `chave = valor` (as chaves são as do núcleo, ex. `melonds_screen_layout = ...`). Sem o
  arquivo valem os padrões do núcleo.
* **Tamanho:** o APK tem o tamanho da ROM + ~20 MB. Na primeira abertura a ROM é extraída
  para o armazenamento interno (some da pasta ao desinstalar).
* **Chave de assinatura:** `generator/src/main/assets/signing.p12` é uma chave de teste
  pública (senha `ndspack`), usada pelo gerador e pelos jogos. Troque por uma sua se for
  distribuir o gerador (é preciso refazer o build).
* **Licenças:** o melonDS é GPLv3; o APK gerado o contém e, portanto, este projeto também
  deve seguir a GPL. Gere APKs apenas de jogos que você possui e não os compartilhe.
* **Compatibilidade:** é emulação, não recompilação: o desempenho e a compatibilidade são
  os do núcleo escolhido. Só arm64-v8a.

## Se algo falhar

* `adb logcat -s NdsPlayer NdsCore` mostra erros do frontend e do núcleo.
* "Núcleo do emulador ausente": o passo *Baixar o núcleo* não rodou ou não achou o arquivo.
* "O núcleo não conseguiu abrir a ROM": ROM inválida/criptografada ou núcleo incompatível;
  tente o outro núcleo (`melondsds`).
