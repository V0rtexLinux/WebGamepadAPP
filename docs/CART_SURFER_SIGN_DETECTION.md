# Cart Surfer — Detecção de Placa por Imagem (Base64 + Template Matching)

Este documento descreve a recriação do auto-farm do **Cart Surfer** no
`WebGamepadAPP`, trocando a detecção de curva baseada em **cor** por uma
detecção baseada em **imagem** (template matching via base64), conforme
solicitado.

## Resumo do que foi feito

1. **Clonagem dos repositórios**
   - `WebGamepadAPP` (app Android/Kotlin que injeta JavaScript no WebView do
     jogo Club Penguin Journey via Ruffle/Flash).
   - `Cart-Bot` (bot Java de referência que usa `java.awt.Robot` para detectar
     cor na tela do desktop). Usado como referência da lógica de virar.

2. **Recorte da placa** a partir do screenshot enviado
   - Localização automática da placa amarela/preta (chevron de "curva à frente")
     via máscara de cor pura (R>=200, G>=155, B<=70) em OpenCV.
   - Recorte do template: **140×505 px** (`sign_right_template.png`), que é o
     chevron da **parede direita** (aponta para a esquerda → curva vai para a
     **esquerda** → tecla **A**).
   - Geração da versão **espelhada na horizontal** (`sign_left_template_flipped.png`)
     que representa o chevron da **parede esquerda** (aponta para a direita →
     curva vai para a **direita** → tecla **D**).
   - Versões "small" (70×252 px, 0.5×) mais próximas do tamanho real do canvas
     in-game, usadas como templates embarcados.

3. **Conversão para base64** — os dois templates small foram codificados em
   base64 PNG e embutidos diretamente na constante `CART_SURFER_FARM_SIGN`
   dentro de `FarmScripts.kt`.

4. **Novo script `CART_SURFER_FARM_SIGN`** — substitui o antigo
   `CART_SURFER_FARM_AUTO` (baseado em cor) como script padrão do Cart Surfer.

## Como a detecção por imagem funciona

O script injetado (JavaScript puro, executado dentro do WebView do app) faz o
seguinte a cada tick do loop (50 ms):

1. **Carrega os templates uma única vez** decodificando os base64 em canvases
   offscreen e extraindo arrays de luminância (tons de cinza) com média e
   desvio pré-computados.

2. **Captura o frame atual do canvas do jogo**: desenha o canvas WebGL do
   Ruffle em um canvas 2D offscreen (o `CART_SURFER_EARLY_INJECT` força
   `preserveDrawingBuffer: true` no WebGL, sem o qual o canvas não pode ser
   lido), reduz por fator 2 (downscale) e converte para tons de cinza.

3. **Template matching por correlação cruzada normalizada (NCC)** — o
   equivalente JS do `cv2.matchTemplate(..., TM_CCOEFF_NORMED)` do OpenCV.
   Para cada parede (direita e esquerda), percorre a zona correspondente do
   canvas calculando o score NCC do template contra cada posição candidata,
   e guarda a melhor correspondência.

4. **Tracking temporal das placas** — as placas se repetem 4, 5 ou 6 vezes ao
   longo do túnel antes de cada curva (elas "deslizam" em direção ao carrinho).
   O script mantém uma fila por direção; quando uma correspondência acima do
   threshold (`MATCH_THRESHOLD = 0.55`) aparece longe de qualquer placa já
   vista (além de `SIGN_MIN_SPACING_PX`), ela é contada como uma **nova placa**.
   A mesma placa, conforme desliza, apenas atualiza sua posição. Placas que
   saem da zona de visualização são removidas da fila.

5. **Gatilho da virada** — quando a fila de uma direção atinge
   `TURN_AT_REMAINING = 2` (ou seja, **sobram 2 placas** na contagem antes da
   curva), o script executa a virada:
   - Fila da **parede direita** com 2 placas → chevron aponta esquerda →
     curva para **esquerda** → pressiona **A**.
   - Fila da **parede esquerda** com 2 placas → chevron aponta direita →
     curva para **direita** → pressiona **D**.
   A virada usa o **Surf Turn** (↑ + direção) para ganhar pontos extras, e a
   fila é limpa após a virada.

6. **Tricks alternados** — entre as curvas, o script continua executando a
   sequência de tricks alternados (Flip→Handstand→SpinR→RunTracks→…), sem
   repetição consecutiva, para evitar a penalidade de 50% — mesma estratégia
   do script original.

7. **Estratégia de vida** — preservada: no `CRASH_TURN` (6ª curva) o script
   intencionalmente não vira, gasta 1 vida e estende o tempo da run.

## Direção: por que a placa espelhada

O chevron do jogo tem faixas diagonais amarelas e pretas. A placa que aparece
na **parede direita** do túnel aponta para a **esquerda** (indicando que a
curva vai para a esquerda). Para detectar a placa da **parede esquerda** sem
precisar de um segundo recorte, basta **espelhar horizontalmente** o template
da parede direita — o chevron espelhado aponta para a direita e casa
perfeitamente com a placa da parede esquerda. Assim, um único recorte gera os
dois templates para as duas direções, exatamente como pedido.

## Validação (testes executados)

- **OpenCV (referência)**: `cv2.matchTemplate` com `TM_CCOEFF_NORMED` encontra
  o template da parede direita com **score 1.0000** na posição exata
  (60.3%, 23.0%) da screenshot original. O template espelhado não casa na
  parede direita (score baixo), confirmando que as duas direções são
  distinguíveis.

- **JavaScript puro (o que roda no app)**: reimplementação da NCC testada em
  Node.js com node-canvas. No canvas de tamanho real do jogo (800×360), o
  scan de ambas as paredes leva **~22 ms**, com score **0.66** para a parede
  certa vs **0.40** para a parede errada — diferença clara acima do threshold.
  No screenshot full-res (2400×1080) com pirâmide (x4→x2), o scan leva
  **~42 ms**.

- **Script embarcado completo**: o JavaScript extraído de `CART_SURFER_FARM_SIGN`
  foi executado em um harness com stubs de browser e alimentado com a
  screenshot real como "game canvas". Resultado:
  - Estado passa de `IDLE` → `PLAYING`.
  - `rightRemaining = 1` (a placa da parede direita foi detectada e contada).
  - `leftRemaining = 0` (nenhuma detecção falsa na parede esquerda).
  - Score da parede direita = **0.760** vs parede esquerda = **0.333**.
  - **Todos os checks de validação passaram.**

## Arquivos

### Templates e referências (em `app/src/main/assets/signs/`)
- `sign_right_template.png` — recorte original do chevron da parede direita
  (140×505 px, aponta para a esquerda → tecla A).
- `sign_left_template_flipped.png` — versão espelhada horizontal
  (aponta para a direita → tecla D).
- `sign_right_template_small.png` / `sign_left_template_small_flipped.png` —
  versões 70×252 px (0.5×) usadas como templates embarcados em base64.
- `sign_detection_visual.png` — visualização dos bounding boxes detectados.
- `reference_screenshot.png` — screenshot original de referência.

### Código
- `app/src/main/java/com/gamemapper/services/FarmScripts.kt`
  - `CART_SURFER_FARM_SIGN` — **novo** script de detecção por imagem
    (base64) com contagem de placas. É o script padrão do Cart Surfer agora.
  - `CART_SURFER_FARM_AUTO` — script antigo baseado em cor, mantido como
    fallback acessível via `scriptForMinigameColor()`.
  - `scriptForMinigame()` — agora retorna `CART_SURFER_FARM_SIGN` para
    `MinigameType.CART_SURFER`.
  - `STOP_ALL_FARMS` e `GET_FARM_STATUS` — atualizados para parar/reportar
    o novo sign farm (`__stopCartSurferSignFarm`, `__csSignFarmStats`).

## Parâmetros ajustáveis (dentro de `CART_SURFER_FARM_SIGN`)

| Parâmetro | Padrão | Descrição |
|---|---|---|
| `MATCH_THRESHOLD` | 0.55 | Score NCC acima do qual a placa é considerada presente. |
| `TURN_AT_REMAINING` | 2 | Quantas placas devem sobrar para disparar a virada. |
| `SIGN_MIN_SPACING_PX` | 60 | Distância mínima (px, downscaled) para contar uma nova placa. |
| `SIGN_COOLDOWN_MS` | 250 | Janela temporal para não recontar a mesma placa. |
| `MAX_QUEUE` | 8 | Tamanho máximo da fila de placas por direção. |
| `DOWNSCALE` | 2 | Fator de redução antes do template matching (performance). |
| `SEARCH_STEP` | 2 | Passo do scan de correlação (performance vs precisão). |
| `RIGHT_ZONE` | 52–78% × 18–80% | Zona da parede direita (fração do canvas). |
| `LEFT_ZONE` | 22–48% × 18–80% | Zona da parede esquerda (fração do canvas). |

## Notas

- O `CART_SURFER_EARLY_INJECT` (injetado em `onPageStarted`) **é obrigatório**
  para o script de imagem funcionar: ele força `preserveDrawingBuffer: true`
  no WebGL do Ruffle, permitindo que o canvas seja desenhado/lido via
  `drawImage` + `getImageData`. Sem isso, o `drawImage(gameCanvas)` retorna
  pixels transparentes e a detecção falha.
- O script antigo por cor (`CART_SURFER_FARM_AUTO`) continua disponível como
  fallback via `FarmScripts.scriptForMinigameColor(type)` caso queira comparar
  ou voltar à abordagem por cor.
- O `Cart-Bot` (repo de referência) usa `java.awt.Robot` para ler cores na
  tela do desktop; sua lógica de `turn(leftTotal, rightTotal, ...)` inspirou a
  estrutura de decisão esquerda/direita, mas aqui tudo roda dentro do WebView
  via JavaScript injetado, sem acesso nativo à tela.
