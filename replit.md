# GameMapper — CPPS Gamepad Mapper

Android app that maps gamepad controls to web-based Club Penguin Private Servers (CPPSs) and lets you play with a physical controller.

## What it does

1. **Analisa** — abre o jogo em WebView invisível, injeta JS para detectar todos os eventos de teclado/mouse/canvas e gera um perfil de mapeamento automático.
2. **Mapeia** — exibe o mapeamento gerado em 3 layouts (lista agrupada, grade, estilo gamepad).
3. **Loga** — detecta páginas de login de CPPSs. Para servidores com formulário HTML (CPPS.app etc.) oferece preenchimento automático. Para jogos com login no canvas (CPJourney, Yukon) o usuário digita normalmente.
4. **Joga** — abre o jogo em WebView full-screen, injeta cursor virtual, traduz botões do gamepad em eventos JS na página.

## Gamepad mapping (em jogo)

| Botão | Ação |
|-------|------|
| D-pad / Analógico esquerdo | Move cursor virtual |
| A | Clique no cursor (mover pinguim) |
| B | 2ª ação mapeada (padrão: Esc) |
| X | 1ª ação / interação mapeada |
| Y | Toggle overlay de mapeamento |
| L1 | 1ª tecla UI mapeada (padrão: M - mapa) |
| R1 | 2ª tecla UI mapeada (padrão: I - inventário) |
| Start | Enter |
| Select | T (abrir chat no CP) |

## Tipos de login dos CPPSs suportados

| Servidor | Tipo | Como funciona |
|----------|------|---------------|
| play.cpjourney.net | Canvas (Phaser/HTML5) | Login desenhado no canvas — usuário digita direto no jogo |
| cpps.app | HTML Form | Campos `<input>` reais — app oferece preenchimento automático |
| icer.ink | Canvas | Login no canvas |
| cplegacy.com | Canvas | Login no canvas |

## Estrutura do projeto

```
app/src/main/java/com/gamemapper/
├── activities/
│   ├── SplashActivity.kt       — tela de splash
│   ├── MainActivity.kt         — tela principal com chips de CPPS
│   ├── AnalyzerActivity.kt     — WebView de análise (invisível)
│   ├── ControlMapActivity.kt   — exibe mapeamento gerado + botão Jogar
│   ├── GameplayActivity.kt     — WebView full-screen + gamepad input
│   └── ProfilesActivity.kt     — lista de perfis salvos
├── services/
│   ├── GameAnalyzerJS.kt       — scripts JS de análise (3 fases)
│   ├── CppsLoginHandler.kt     — detecção e injeção de login CPPS
│   └── ControlParser.kt        — converte JSON de análise em ControlModel
├── models/
│   └── ControlModel.kt         — ControlModel, ControlProfile, AnalysisResult
├── adapters/                   — RecyclerView adapters
└── utils/
    ├── Constants.kt            — constantes + lista de CPPSs
    ├── ColorUtils.kt           — cores por categoria
    └── ProfileStorage.kt       — persistência de perfis (SharedPreferences/Gson)
```

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## User preferences

- Idioma padrão: Português (BR)
- Tema: dark (azul-escuro + vermelho accent)
