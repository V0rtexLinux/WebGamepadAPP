# 🎮 GameMapper

Aplicativo Android nativo (Kotlin + Gradle) que mapeia automaticamente os controles de qualquer jogo de navegador web.

## Como Funciona

O app usa um **WebView oculto** como motor de análise — carrega o jogo, injeta JavaScript antes e depois do carregamento, e detecta:

- **Eventos de teclado** (`keydown`, `keypress`, `keyup`) registrados pelo jogo  
- **Zonas de canvas** interativas (toque, clique, movimento de mouse)  
- **Elementos clicáveis** (`<button>`, `[role="button"]`, `.btn`, `[onclick]`, etc.)  
- **Zonas de toque** (`touchstart`, `touchend`, `pointerdown`)  
- **Padrões de classe/id** específicos de jogos (`dpad`, `joystick`, `arrow`, `move`, etc.)

Funciona com **qualquer** jogo web: CPJourney, Club Penguin Rewritten, servidores privados de fans, e qualquer outro jogo HTML5/Flash-era.

## Funcionalidades

| Feature | Descrição |
|---------|-----------|
| 🔍 Análise automática | Detecta controles de qualquer jogo web |
| 🎨 Interface bonita | Dark theme estilo gaming (tema `#1A1A2E`) |
| 🔄 Remapear | Botão para gerar mapeamento alternativo com algoritmo diferente |
| ⊞ Layouts | 3 estilos: Lista Agrupada, Grade, Gamepad |
| 💾 Perfis | Salva e carrega mapeamentos por jogo |
| ⬆ Compartilhar | Exporta o mapeamento como texto |
| 🛡 Fallback | Se JS falhar, usa análise de domínio/URL |

## Como Construir

### Pré-requisitos
- **Android Studio** Hedgehog (2023.1.1) ou mais recente
- **JDK 17** ou mais recente
- Android SDK 34

### Passo a Passo

```bash
# 1. Clonar / baixar o projeto
cd GameMapper

# 2. Abrir no Android Studio
# File > Open > selecionar a pasta GameMapper

# 3. Aguardar Gradle sync automático

# 4. Executar no dispositivo ou emulador
# Run > Run 'app'
```

### Linha de Comando

```bash
# Gerar APK de debug
./gradlew assembleDebug

# APK gerado em:
# app/build/outputs/apk/debug/app-debug.apk

# Instalar direto no dispositivo conectado
./gradlew installDebug
```

## Estrutura do Projeto

```
app/src/main/
├── java/com/gamemapper/
│   ├── activities/
│   │   ├── SplashActivity.kt       # Tela de abertura animada
│   │   ├── MainActivity.kt         # Tela principal (URL input)
│   │   ├── AnalyzerActivity.kt     # Motor de análise (WebView oculto + JS)
│   │   ├── ControlMapActivity.kt   # Exibe o mapeamento de controles
│   │   └── ProfilesActivity.kt     # Lista de perfis salvos
│   ├── adapters/
│   │   ├── ControlGroupAdapter.kt  # Adapter para lista/grade de controles
│   │   └── ProfileAdapter.kt       # Adapter para lista de perfis
│   ├── models/
│   │   └── ControlModel.kt         # Data classes (ControlModel, ControlProfile)
│   ├── services/
│   │   ├── GameAnalyzerJS.kt       # Scripts JavaScript de análise
│   │   └── ControlParser.kt        # Parseia JSON → ControlModel
│   └── utils/
│       ├── Constants.kt            # Constantes globais
│       ├── ColorUtils.kt           # Cores por categoria/tipo
│       └── ProfileStorage.kt       # SharedPreferences storage
└── res/
    ├── layout/                     # XML de layouts
    ├── drawable/                   # Ícones vetoriais + drawables
    ├── anim/                       # Animações (fade, slide)
    └── values/                     # Cores, strings, temas
```

## Arquitetura de Análise (3 camadas)

```
PHASE 1 - Early Hook (onPageStarted)
  → Injeta GameAnalyzerJS.EARLY_HOOK_SCRIPT
  → Monkey-patches EventTarget.addEventListener
  → Captura TODOS os event listeners antes do jogo carregar

PHASE 2 - Deep Analysis (onPageFinished + 2.5s delay)
  → Injeta GameAnalyzerJS.DEEP_ANALYSIS_SCRIPT
  → Analisa: keyboard, canvas, elementos, touch zones
  → Retorna JSON estruturado

PHASE 3 - ControlParser
  → Parseia JSON → List<ControlModel>
  → Categoriza: MOVEMENT / ACTION / UI / INTERACTION
  → Deduplica e ordena
```

## Botão Remapear

O botão **🔄 Remapear** usa um algoritmo completamente diferente:
- `DEEP_ANALYSIS_SCRIPT`: Analisa via event hooks (phase 1+2)
- `REMAP_ANALYSIS_SCRIPT`: Analisa via seletores CSS e padrões de classe/id

Isso garante que sempre existe uma alternativa mesmo se o primeiro mapeamento não agradou.

## Permissões Usadas

- `INTERNET` — carregar o jogo web
- `ACCESS_NETWORK_STATE` — verificar conectividade

Sem câmera, localização, armazenamento externo ou qualquer permissão sensível.
