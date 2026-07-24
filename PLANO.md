# PLANO — Mouse Virtual por Hand Tracking (XREAL Eye + Galaxy S25 + DeX)

> **Objetivo**: assistir séries deitado com o DeX renderizado nos óculos XREAL One Pro, controlando TUDO com a mão: um cursor overlay system-wide segue a mão (capturada pela câmera XREAL Eye) e o gesto de pinch (polegar+indicador) injeta um clique naquela posição — sobre qualquer app (Netflix, navegador, launcher do DeX).
>
> **Data do plano**: julho/2026. Baseado em 6 pesquisas técnicas profundas + 3 verificações adversariais (incluindo a pesquisa completa de injeção de clique/overlay no display DeX). Este documento é autossuficiente: um agente de código deve conseguir executá-lo sem re-pesquisar.

---

## 1. Visão geral

App Android nativo (Kotlin, sem Unity, sem SDK oficial XREAL) rodando no Galaxy S25 como foreground service + AccessibilityService. O feed da câmera Eye é capturado via USB Host API (não Camera2/CameraX — a Eye NÃO é uma webcam UVC comum), decodificado por hardware, alimentado no MediaPipe HandLandmarker, e o resultado vira um cursor desenhado no display do DeX + cliques injetados por acessibilidade.

```
┌─────────────────────────── XREAL One Pro + Eye ────────────────────────────┐
│  Câmera Eye ──► SoC X1 ──► USB composto (VID 0x3318 / PID 0x0436)          │
│  Display 1920x1080 ◄── DP Alt Mode (mesmo cabo USB-C; sobra USB 2.0)       │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │ cabo USB-C único
┌──────────────────────────────────▼──────────────── Galaxy S25 ─────────────┐
│                                                                            │
│  [1] Ativação HID (libnr_glasses_api.so via JNI — NRBSPSetUsbConfigAll)    │
│        └─► re-enumeração USB (13 → 17 interfaces, UVC0 aparece)            │
│                                                                            │
│  [2] EyeCaptureService (FGS type=connectedDevice, wakelock parcial)        │
│        UsbDeviceConnection.bulkTransfer(64KB) ──► bitstream H.265          │
│        2048x1512 @~30-60fps, UVC bulk, montagem de frame por FID/EOF       │
│                                   │                                        │
│  [3] MediaCodec (decoder HEVC HW) │                                        │
│        └─► SurfaceTexture/ImageReader ─► downscale ─► RGBA ByteBuffer      │
│                                   │                                        │
│  [4] MediaPipe HandLandmarker (tasks-vision 0.10.29+, GPU, LIVE_STREAM)    │
│        └─► 21 landmarks @ ~30fps (inferência ~5-10ms no 8 Elite)           │
│                                   │                                        │
│  [5] GestureEngine                │                                        │
│        ├─ One Euro Filter (x,y) ──► posição do cursor                      │
│        └─ Pinch detector (dist 4↔8 normalizada, histerese + debounce)      │
│                                   │                                        │
│  [6] CursorOverlay ───────────────┤  TYPE_ACCESSIBILITY_OVERLAY no         │
│        (a) janela fullscreen      │  Display do DeX via createDisplay-     │
│            transparente + view    │  Context/createWindowContext; ou       │
│        (b) attachAccessibility-   │  SurfaceControlViewHost + Trans-       │
│            OverlayToDisplay(34+)  │  action.setPosition (60fps no SF)      │
│                                   │                                        │
│  [7] HandMouseAccessibilityService▼                                        │
│        dispatchGesture(GestureDescription com setDisplayId(dexDisplayId))  │
│        └─► clique/drag/scroll injetado sobre QUALQUER app no DeX           │
│                                                                            │
│  DeX (Desktop Mode do Android 16 / One UI 8) renderizado nos óculos        │
│  com Netflix/navegador em foreground — nosso app fica em background        │
└────────────────────────────────────────────────────────────────────────────┘
```

Pontos-chave da arquitetura:
- **Captura ≠ câmera Android**: a Eye só aparece no bus após um comando HID proprietário (libs nativas do app oficial Glasses Control, carregadas em runtime). O stream é H.265 sobre UVC bulk — nenhuma lib UVC pronta funciona; replicamos o pipeline do repo Aloim.
- **Sem restrição camera-while-in-use**: o caminho USB host não passa pelo CameraService — a checagem de `CAMERA` é estática e única, no grant da permissão USB. O stream continua com o app em background e tela apagada; sem indicador verde, sem corte por perda de foco.
- **Overlay + clique no MESMO AccessibilityService**: `TYPE_ACCESSIBILITY_OVERLAY` dispensa SYSTEM_ALERT_WINDOW, é imune a `setHideOverlayWindows()` (Android 12+) e aparece sobre janelas FLAG_SECURE (Netflix); `dispatchGesture` com `setDisplayId()` (API 30+) direciona o toque ao display do DeX.
- **Latência estimada fim-a-fim**: ~35–60 ms (bulk read + decode HEVC ~10-20ms + conversão ~2-5ms + inferência ~5-10ms + filtro/overlay ~1ms). Aceitável para cursor.

---

## 2. Veredicto de viabilidade

**Veredicto geral: PROVÁVEL (go condicional).** Nenhuma das verificações adversariais refutou as premissas físicas/de protocolo, mas há lacunas empíricas que NINGUÉM testou publicamente. Elas são baratas de validar (Fase 0, ~1-2h com hardware em mãos) e devem ser validadas ANTES de escrever qualquer código de produção.

### Confirmado (alta confiança)
- **Acesso ao feed da Eye sem SDK Unity**: repo Aloim funcional, confirmado em Galaxy S26 Ultra. Protocolo documentado (HID activation → UVC probe/commit manual → bulk 64KB → MediaCodec HEVC). Ressalva: exige o APK oficial Glasses Control instalado (fonte das .so em runtime) — "sem SDK" é verdadeiro, "sem nenhum software XREAL" é falso.
- **Banda USB**: max packet size de 512 bytes no endpoint bulk = assinatura de USB 2.0 High Speed; os pares D+/D− são fisicamente separados das lanes DP e nunca são removidos pelo Alt Mode. O stream H.265 (dezenas de Mbps) cabe com folga nos ~300 Mbps efetivos. O produto foi PROJETADO para display + câmera simultâneos (gravação POV enquanto exibe imagem é feature de fábrica).
- **Restrições de câmera do Android 14/15/16 não se aplicam ao caminho USB host**: enforcement de while-in-use/indicador verde/toggle vive no CameraService (AppOps OP_CAMERA); bulk transfers num fd já aberto são invisíveis a ele. FGS `connectedDevice` não tem timeout (limites de 6h são só dataSync/mediaProcessing) e seu pré-requisito é exatamente `UsbManager.requestPermission()`.
- **Overlay em display secundário**: `createDisplayContext(dexDisplay).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY)` é o caminho clássico documentado; desde API 34 existe `attachAccessibilityOverlayToDisplay(displayId, SurfaceControl)` dedicado a overlay em display específico. Categoria Samsung oficial para achar o display: `"com.samsung.android.hardware.display.category.DESKTOP"` (fallback `DISPLAY_CATEGORY_PRESENTATION`).
- **FLAG_SECURE do Netflix NÃO esconde o overlay**: a flag impede captura/exibição da janela DO NETFLIX em displays não seguros — não impede outra janela de ser composta por cima pelo SurfaceFlinger (mesmo mecanismo dos controles do player). E `setHideOverlayWindows()` só afeta TYPE_APPLICATION_OVERLAY — TYPE_ACCESSIBILITY_OVERLAY é imune.
- **`GestureDescription.Builder.setDisplayId(int)`**: confirmado no api_diff oficial da API 30. Coordenadas em pixels do display alvo.
- **MediaPipe HandLandmarker** é o estado da arte em 2026, mantido (google-ai-edge), ~5-10ms de inferência GPU no 8 Elite (extrapolação do benchmark oficial Pixel 6: 12,27ms GPU). GestureRecognizer NÃO tem pinch pronto — cálculo geométrico manual é o caminho certo.

### Incerto (ninguém testou — validar na Fase 0)
1. **S25 especificamente**: repo Aloim validado só no S26 Ultra; S24/S25 explicitamente "não testados". Mesma família One UI/Snapdragon → risco baixo, mas real.
2. **Coexistência DeX ativo + ativação da câmera**: zero relatos públicos. O risco NÃO é banda — é a **re-enumeração USB** (bus reset, 13→17 interfaces) disparada pela ativação HID, que pode piscar/derrubar a sessão DeX. O vídeo trafega na camada Alt Mode (mux do Type-C), não na pilha USB — em teoria o display não cai, mas é teoria.
3. **dispatchGesture em display secundário na One UI**: histórico de inconsistências entre OEMs (issue aberta 350653948 no Google IssueTracker pedindo mais flexibilidade); poucos relatos de uso em DeX especificamente. Maior risco técnico depois da coexistência.
4. **Overlay chegando no display certo**: relatos em fóruns Samsung de apps "appear on top" desenhando na tela do celular em vez do monitor DeX (causa provável: adicionaram a view no display default). A solução via `createDisplayContext` é hipótese forte baseada em docs oficiais, não fato verificado por app público conhecido.
5. **One UI 8 / DeX novo (Desktop Mode AOSP)**: comportamento de tela do celular mudou (relatos de tela forçada ligada estendendo o desktop); comportamento de FGS/tela-apagada validado no One UI 6/7 pode não valer. Verificar a versão exata instalada no S25.
6. **Dependência frágil**: as .so proprietárias (`libnr_glasses_api.so`/`libota-lib.so`) vêm do APK oficial Glasses Control; update de firmware/app da XREAL pode quebrar a API NRBSP*.
7. **Tracking com mão em ângulo "deitado"**: MediaPipe é treinado majoritariamente em vistas frontais; mão vista de cima/lateral (apoiada no peito) pode degradar.

### Spikes de validação — Dia 1 (rodar NESTA ordem, antes de codar)

| # | Spike | Como rodar | Resultado esperado (GO) | Se falhar (NO-GO parcial) |
|---|-------|-----------|------------------------|---------------------------|
| S0 | Feed da Eye no S25 | Instalar app oficial XREAL Glasses Control + APK pronto do repo (`apk/XRCam-debug.apk`). Conectar óculos SEM DeX. Aceitar os 2 prompts USB rapidamente (handshake dá timeout se demorar). | Preview do feed 2048x1512 aparece e mantém fps por 10 min. | Ver Plano B da seção 3.1 (fixar versões, capturar HID raw). Se irrecuperável: pivotar para câmera frontal do S25. |
| S1 | DeX + câmera, ordem A | Com DeX já ativo nos óculos, abrir o XRCam e ativar a câmera. Observar a sessão DeX durante a re-enumeração. | Feed funciona; DeX no máximo pisca e se recupera sozinho. | Testar ordem B. |
| S2 | DeX + câmera, ordem B | Ativar a câmera PRIMEIRO (XRCam), depois iniciar o DeX (a config uvc0=1 persiste até desconectar). | Ambos coexistem. Se só a ordem B funciona, o app deve sempre ativar câmera antes do DeX (viável — documentar no onboarding). | Se nenhuma ordem funciona: Plano B seção 3.1 (Unity FPV) ou câmera frontal. |
| S3 | Clique injetado no display DeX | Mini-app descartável: AccessibilityService com `canPerformGestures="true"`, botão/timer que dispara `dispatchGesture` com `setDisplayId(dexDisplayId)` num ponto fixo (ex.: ícone do launcher do DeX). Testar também sobre Netflix aberto. | O tap acontece no DeX, não na tela do celular, inclusive sobre o player. | Fallback 1: Shizuku + `input tap -d <displayId>`. Fallback 2: HID Bluetooth virtual (mouse BT emulado). Se nada injeta no DeX: projeto inviável como concebido — reavaliar. |
| S4 | Overlay no display DeX | No mesmo mini-app: `createDisplayContext(dexDisplay).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY)` + `addView` de um quadrado vermelho. Repetir com Netflix fullscreen tocando (FLAG_SECURE + hardware overlay plane). | Quadrado aparece nos óculos (não no celular) e permanece visível sobre o vídeo. | Fallback 1: `attachAccessibilityOverlayToDisplay` + SurfaceControlViewHost (API 34+). Fallback 2: TYPE_APPLICATION_OVERLAY (SAW). |
| S5 | Toggle de privacidade | Repetir S0 com o toggle de câmera das Quick Settings LIGADO (bloqueado). | Stream continua funcionando; sem indicador verde. | Documentar: usuário desliga o toggle antes da sessão; abrir stream no setup e nunca fechar. |
| S6 | Tela do celular apagada | Com feed + DeX rodando, deixar a tela do celular apagar por timeout e depois bloquear (verificar comportamento do One UI 8: a tela pode ficar forçada ligada estendendo o desktop). | Stream USB continua (logcat com timestamps de frames) e DeX continua tocando. | Manter tela ligada em modo touchpad/brilho mínimo (mitigação aceitável). |
| S7 | Soak de estabilidade | Netflix tocando no DeX + XRCam capturando por 30+ min, app minimizado. Monitorar logcat (ActivityManager kills, usb disconnect) e `getThermalHeadroom()` a cada 5 min. | Sem kill, sem throttling severo, sem gap de frames. | Aplicar checklist Samsung completo (seção 7) e re-testar; considerar janela mínima visível no DeX. |
| S8 | Reconexão no meio da sessão | Desconectar e reconectar o cabo com tudo rodando. | Recuperação em <30s com no máximo 1 toque (diálogo USB). | Projetar UX de recuperação assistida (notificação full-screen-intent). |

**Critério de GO para construir**: S0, (S1 ou S2), S3 e S4 passam. S5–S8 informam mitigações, não bloqueiam. **Registrar as versões exatas que funcionaram** (APK Glasses Control, firmware dos óculos, build One UI) e congelá-las.

---

## 3. Decisões de arquitetura

### 3.1 Captura da câmera Eye
- **Escolhido**: replicar o pipeline do repo [Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android](https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android) — USB Host API pura:
  1. **Ativação HID** via JNI para as libs nativas do APK oficial Glasses Control (`libnr_glasses_api.so` 2.1MB, `libota-lib.so` 4.0MB). **Correção (fix de revisão pós-implementação — esta seção assumia "carregadas em runtime do APK instalado", o que é FALSO)**: o repo Aloim, apesar do próprio README dele descrever `System.load` num caminho extraído do APK Glasses Control em runtime, na prática **empacota as .so como `jniLibs` do próprio app** (`XrealGlasses.java`: `System.loadLibrary("ota-lib")`, carregamento estático padrão do Android a partir de `app/src/main/jniLibs/arm64-v8a/`, com os arquivos `.so` *commitados* nesse diretório do repo deles). Nosso app replica esse carregamento estático (ver Javadoc de `GlassesConnection.kt`): as duas .so precisam ser extraídas manualmente do APK Glasses Control instalado e colocadas em `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/` **antes de compilar** (setup §7, item 1) — sem elas o app não builda/roda a ativação HID. `assets/nr_ota_default/` fica vazio (só um `.gitkeep`) — o repo Aloim também não usa esse diretório, mesmo comportamento replicado aqui. Fluxo: `NRBSPWaitPilotReady(timeout)` → `NRBSPGetUsbConfigAll` → `NRBSPSetUsbConfigAll(uvc0=1, ncm=1, ecm=1, hid_ctrl=1, enable=1)` → sleep(3000ms) para re-enumeração (Android trata como dispositivo NOVO → 2ª permissão USB) → `NRBSPGetCameraStatus`.
  2. **UVC manual** via `controlTransfer`: claim das interfaces classe 0x0E subclasses 0x01 (VideoControl) e 0x02 (VideoStreaming); Probe/Commit de 26 bytes (SET_CUR 0x21/0x01/wValue 0x0100 → GET_CUR 0xA1/0x81/0x0100 → COMMIT 0x21/0x01/0x0200), `bFormatIndex=1, bFrameIndex=1, dwFrameInterval=333333` (30fps), `dwMaxVideoFrameSize=5242880`, `dwMaxPayloadTransferSize=262144`; `setInterface()` no alternate com endpoint bulk IN.
  3. **Loop `bulkTransfer()`** com buffer de **exatamente 65536 bytes** (menor trunca IDR frames de ~24KB; maior não retorna dados), timeout 500ms; montagem de frame por toggle de FID (byte1 bit0) + EOF (bit1); header UVC de 2–12 bytes por payload (byte0=len).
  4. **Decodificação**: `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_HEVC)`, csd-0 = VPS+SPS+PPS concatenados com start codes (NAL types HEVC: 32/33/34; IDR 19-20; tipo = `(byte shr 1) and 0x3F`). Threads separadas para leitura USB e decoder (obrigatório — bloquear a thread de leitura perde frames). Não chamar `setDefaultBufferSize()` durante streaming. Copiar a camada `ai/nreal/glasses/control/` (XrealGlasses.java, UsbPrivateFunction.java, UsbConfigList.java) + `GlassesConnection.kt` + `UvcCameraHelper.kt` do repo.
- **Por quê**: única rota conhecida para app Kotlin nativo. Camera2/CameraX não enxergam a Eye (a câmera nem existe no bus antes do comando HID; não passa pelo HAL); libs UVC prontas (saki4510t/UVCCamera, AUSBC, Peter-St) não suportam HEVC+bulk+ativação HID; o SDK oficial (3.0.0+) é Unity-only.
- **Plano B em camadas**:
  1. Libs NRBSP* falham no S25 → fixar a versão exata do APK Glasses Control + firmware dos óculos que o repo usou (não atualizar nada); guardar cópia do APK.
  2. Ainda falha → capturar o comando HID de ativação (usbmon num aparelho funcional) e reproduzir com `hidRawWrite` direto, eliminando as .so.
  3. Coexistência DeX+câmera impossível → SDK oficial Unity (Eye como YUV_420_888 via `GetYUVFormatTextures()`) como app Unity headless exportando frames via IPC para o app Kotlin.
  4. Último recurso → câmera frontal do S25 (MediaPipe aceita qualquer câmera; telefone apoiado apontando para o usuário). Degrada a UX mas destrava o produto.

### 3.2 Hand tracking
- **Escolhido**: **MediaPipe HandLandmarker** (`com.google.mediapipe:tasks-vision:0.10.29` — verificar se há release mais novo no Maven Central antes de fixar), modelo `hand_landmarker.task` (bundle oficial full: palm detection 192x192 + landmark 224x224, float16), `RunningMode.LIVE_STREAM`, `Delegate.GPU`, `setNumHands(1)`, confidences 0.5 (`minHandDetectionConfidence`/`minHandPresenceConfidence`/`minTrackingConfidence`).
- **Por quê**: GestureRecognizer NÃO tem gesto pinch pronto (só None, Closed_Fist, Open_Palm, Pointing_Up, Thumb_Down, Thumb_Up, Victory, ILoveYou) e adiciona 2 classificadores de latência à toa; para o cursor precisamos dos landmarks crus de qualquer forma. ML Kit não tem hand tracking. Modelos acadêmicos (WiLoR/HaMeR) não têm pipeline Android.
- **Detalhes obrigatórios**: `detectAsync(mpImage, timestampMs)` com timestamps monotônicos crescentes; criar o HandLandmarker numa HandlerThread dedicada (delegate GPU preso à thread de criação); frames enviados mais rápido que a inferência são descartados (comportamento desejado — sempre o mais recente). Downscale para ~640-960px de largura antes da inferência. Limitar a inferência a **30 FPS** (térmica). Input: **apenas RGB/RGBA** (`ByteBufferImageBuilder` com `IMAGE_FORMAT_RGB/RGBA` ou `BitmapImageBuilder`) — YUV direto NÃO é suportado; conversão é nossa (libyuv ou caminho GL). Em LIVE_STREAM o tracking reutiliza o bbox do frame anterior e só re-roda palm detection ao perder a mão.
- **Plano B**: nenhum necessário — é o padrão da indústria. Se a inferência GPU der problema no 8 Elite, cair para CPU (17ms no Pixel 6, ainda ok a 30fps).

### 3.3 Injeção de clique (pinch → tap no DeX)
- **Escolhido**: `AccessibilityService` (XML do serviço com `android:canPerformGestures="true"` e `android:isAccessibilityTool="true"`) + `dispatchGesture`:
  ```kotlin
  val path = Path().apply { moveTo(x, y) }
  val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
      .setDisplayId(dexDisplay.displayId)   // API 30+ — direciona o toque ao display DeX
      .build()
  dispatchGesture(gesture, callback, handler)
  ```
  Coordenadas em pixels do display alvo — obter bounds reais via `createDisplayContext(dexDisplay)` → `windowManager.currentWindowMetrics.bounds` (nunca hardcodar 1920x1080: trocar o modo dos óculos para UltraWide re-enumera o display como 3840x1080).
- **Por quê**: única API pública que injeta input sobre qualquer app, em qualquer display, sem root. O AccessibilityService também dá TYPE_ACCESSIBILITY_OVERLAY de graça (mesma habilitação em Configurações > Acessibilidade) e proteção contra kill (processo bound pelo system_server).
- **Plano B em camadas** (testar no spike S3 antes de precisar):
  1. **Shizuku** (Wireless ADB) + `input tap -d <displayId> x y` ou `InputManager#injectInputEvent` via API oculta — mais setup (parear a cada boot), mas contorna qualquer peculiaridade Samsung do dispatchGesture em display secundário.
  2. **HID Bluetooth virtual**: o app registra um `BluetoothHidDevice` (mouse BT emulado) pareado com o próprio S25 — o DeX já trata mouse nativamente (cursor do sistema, clique, scroll). Elimina overlay E injeção de uma vez (o cursor vira o do próprio DeX), ao custo de latência BT e de o app virar "driver de mouse". É o fallback mais robusto se acessibilidade multi-display falhar.
  3. Modo trackpad do DeX (tela do celular) como fallback manual de emergência — não resolve o produto, mas destrava a sessão.

### 3.4 Overlay do cursor
- **Escolhido (caminho A.1, conservador)**: `TYPE_ACCESSIBILITY_OVERLAY` numa janela **fullscreen transparente estática** no display do DeX:
  ```kotlin
  val displayContext = createDisplayContext(dexDisplay)
      .createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)   // API 30+; type IGUAL ao do LayoutParams
  val wm = displayContext.getSystemService(WindowManager::class.java)
  wm.addView(cursorRootView, params)  // MATCH_PARENT, FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE
                                      // | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS, TRANSLUCENT
  ```
  Regras: o `type` do createWindowContext e do LayoutParams DEVEM ser iguais; a view deve ser inflada com o windowContext (não com o Context do serviço) para pegar as métricas do display DeX. Cursor = ImageView filha movida com `setTranslationX/Y` (hardware-accelerated: só atualização de display list, sem relayout/IPC), sincronizada com `Choreographer.postFrameCallback` no vsync. **NUNCA** `wm.updateViewLayout()` por frame (IPC ao system_server a cada frame = jank garantido).
- **Caminho A.2 (upgrade de performance, API 34+)**: `attachAccessibilityOverlayToDisplay(displayId, surfaceControl)` com `SurfaceControlViewHost`:
  ```kotlin
  val viewHost = SurfaceControlViewHost(displayContext, dexDisplay, /*hostToken=*/ null)
  viewHost.setView(cursorView, cursorSizePx, cursorSizePx)
  attachAccessibilityOverlayToDisplay(dexDisplay.displayId, viewHost.surfacePackage!!.surfaceControl)
  // mover: SurfaceControl.Transaction().setPosition(sc, x, y).apply()  → roda no SurfaceFlinger,
  // fora do measure/layout/draw — o jeito mais barato possível de mover algo a 60fps.
  // Chamar de novo com outro displayId TRANSFERE o overlay (útil no hot-plug dos óculos).
  ```
  API recente com poucos exemplos públicos (gotchas no ciclo de vida do SurfacePackage) — implementar A.1 primeiro; migrar para A.2 só se houver jank medido.
- **Por quê TYPE_ACCESSIBILITY_OVERLAY e não TYPE_APPLICATION_OVERLAY**: (1) dispensa SYSTEM_ALERT_WINDOW e o fluxo de Settings; (2) é **imune a `Window.setHideOverlayWindows(true)`** (Android 12+), que apps podem usar para esconder overlays de terceiros; (3) janela "trusted" (isenta de untrusted-touch). `FLAG_NOT_TOUCHABLE` é essencial — senão a janela MATCH_PARENT engoliria os próprios cliques que o dispatchGesture injeta. `FLAG_NOT_FOCUSABLE` obrigatório — senão o DeX perde input de teclado.
- **Netflix/FLAG_SECURE**: FLAG_SECURE impede screenshot/cast da janela do Netflix — NÃO impede overlay por cima na composição local (SurfaceFlinger compõe janelas acima do vídeo, mesmo com hardware overlay plane — é o mecanismo dos controles do player e legendas). Validar visualmente no S4 (comportamento pode variar por firmware).
- **Encontrar o display do DeX**:
  ```kotlin
  val dex = displayManager.getDisplays("com.samsung.android.hardware.display.category.DESKTOP").firstOrNull()
      ?: displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
  ```
  NÃO hardcodar displayId (típico 2 em Samsung, não garantido). Registrar `DisplayManager.DisplayListener` (onDisplayAdded/Removed/Changed) para: hot-plug dos óculos, troca de modo normal↔UltraWide (re-enumera o display e reinicia o DeX — displayId/resolução mudam em runtime). Remover a view em `onDisplayRemoved` (senão leak/crash).
- **Plano B**: TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW (mesmo código, type diferente) — só como fallback/debug, pelas desvantagens acima.

### 3.5 Serviço em background
- **Escolhido**: FGS com `foregroundServiceType="connectedDevice"` (NÃO "camera"), iniciado sempre a partir da Activity visível (setup), com `PARTIAL_WAKE_LOCK` durante a captura. Tudo (captura + overlay + a11y) no **mesmo processo** — comunicação por singleton/objeto compartilhado, não IPC; o bind do system_server ao AccessibilityService praticamente imuniza contra o kill da Samsung.
- **Por quê**: tipo `camera` sujeitaria o app a regras while-in-use sem ganho (não usamos CameraService); `connectedDevice` é o encaixe oficial para USB, não tem timeout de execução (Android 14/15 limitam só dataSync/mediaProcessing a ~6h) e o pré-requisito (requestPermission USB) já é atendido. Com targetSdk 34+ passar o tipo também na chamada: `startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`.
- **Manifest defensivo**: declarar `CAMERA` e pedir em runtime — desde Android 9/10 o `UsbUserPermissionManager` exige CAMERA concedida para dar permissão USB a dispositivo com interface de classe vídeo (targetSdk>=28). Se quiser cinto e suspensório: declarar `connectedDevice|camera` + `FOREGROUND_SERVICE_CAMERA` e iniciar sempre com Activity visível (o caso oficial "continue to access the camera from background" cobre isso).
- **Plano B contra kill Samsung**: (i) checklist One UI obrigatório no onboarding (seção 7 — é 100% configuração manual, sem solução via código, per dontkillmyapp); (ii) watchdog: o a11y service (que sobrevive) detecta morte do FGS e o reinicia — se background-start negado, usa o próprio a11y/notificação para trazer a Activity 1s à frente; START_STICKY; (iii) fallback de escopo: janela minúscula do app sempre visível num canto do DeX (janela freeform conta como TOP → processo nunca cached).

---

## 4. Estrutura do projeto

```
xreal-hand-mouse/
├── settings.gradle.kts
├── build.gradle.kts
├── app/
│   ├── build.gradle.kts            # minSdk 34, targetSdk 35, tasks-vision 0.10.29+
│   └── src/main/
│       ├── AndroidManifest.xml     # ver seção 6.5
│       ├── assets/
│       │   └── hand_landmarker.task
│       ├── res/xml/
│       │   ├── usb_device_filter.xml      # vendor-id 13080 (0x3318), product-id 1078 (0x0436)
│       │   └── accessibility_config.xml   # canPerformGestures=true, isAccessibilityTool=true
│       ├── java/ai/nreal/glasses/control/ # COPIADO do repo Aloim (wrappers JNI)
│       │   ├── XrealGlasses.java
│       │   ├── UsbPrivateFunction.java
│       │   └── UsbConfigList.java
│       └── kotlin/com/raphael/handmouse/
│           ├── MainActivity.kt            # setup wizard: grants USB (2x), CAMERA,
│           │                              # a11y, battery exemption; inicia o FGS;
│           │                              # recebe USB_DEVICE_ATTACHED
│           ├── capture/
│           │   ├── GlassesConnection.kt   # ativação HID (adaptado do Aloim)
│           │   ├── UvcCameraHelper.kt     # probe/commit UVC + bulk reader (Aloim)
│           │   ├── HevcDecoder.kt         # MediaCodec HEVC → ImageReader/Surface
│           │   └── FrameConverter.kt      # YUV→RGBA downscale (libyuv ou GL)
│           ├── tracking/
│           │   ├── HandTracker.kt         # HandLandmarker LIVE_STREAM wrapper
│           │   ├── OneEuroFilter.kt       # ~40 linhas, 1 instância por eixo
│           │   ├── PinchDetector.kt       # histerese + debounce + EMA
│           │   └── CursorMapper.kt        # coords câmera → coords display DeX
│           │                              # (calibração: activeBox/ganho/deadzone)
│           ├── overlay/
│           │   ├── DexDisplayMonitor.kt   # DisplayManager + categoria DESKTOP +
│           │   │                          # DisplayListener (hot-plug, troca de modo)
│           │   └── CursorOverlay.kt       # A.1: janela fullscreen + ImageView;
│           │                              # A.2 (opcional): SurfaceControlViewHost
│           ├── input/
│           │   └── GestureInjector.kt     # dispatchGesture(displayId): tap, drag
│           │                              # (continueStroke), scroll, long-press
│           ├── service/
│           │   ├── EyeCaptureService.kt   # FGS connectedDevice; orquestra o pipeline
│           │   └── HandMouseAccessibilityService.kt  # a11y: overlay + injeção +
│           │                                          # watchdog do FGS
│           └── util/
│               ├── Prefs.kt               # calibração persistida, thresholds
│               └── ThermalMonitor.kt      # getThermalHeadroom → reduzir FPS
```

Fluxo de dados entre classes: `EyeCaptureService` → `GlassesConnection`(ativa) → `UvcCameraHelper`(bulk, thread própria) → `HevcDecoder`(thread própria) → `FrameConverter` → `HandTracker.detectAsync` → resultListener (thread do MediaPipe) → `PinchDetector` + `OneEuroFilter` + `CursorMapper` → `CursorOverlay.move(x,y)` (postado no Choreographer) e `GestureInjector.tap/drag(x,y)` no `HandMouseAccessibilityService` (mesmo processo).

---

## 5. Fases de implementação

### Fase 0 — Spikes de validação (Dia 1, sem código de produção)
Classes: nenhuma (APKs prontos + mini-app descartável para S3/S4).
- [ ] S0: XRCam-debug.apk mostra o feed da Eye no S25 (10 min estável)
- [ ] S1/S2: coexistência DeX + câmera (testar as duas ordens; anotar qual funciona)
- [ ] S3: dispatchGesture com setDisplayId clica no DeX (mini-app descartável), inclusive sobre Netflix
- [ ] S4: overlay TYPE_ACCESSIBILITY_OVERLAY aparece no display do DeX e sobre Netflix fullscreen
- [ ] S5: stream com toggle de câmera das Quick Settings ligado
- [ ] S6: stream + DeX sobrevivem à tela do celular apagada/bloqueada (documentar comportamento One UI 8)
- [ ] S7: soak 30 min Netflix + captura (kills? térmica? gaps de frame?)
- [ ] S8: desconectar/reconectar cabo no meio — custo de recuperação
- [ ] Registrar versões exatas que funcionaram: APK Glasses Control, firmware dos óculos, build One UI — e NÃO atualizar durante o projeto
**Pronto quando**: S0 + (S1|S2) + S3 + S4 = GO. Documentar resultados neste arquivo.

### Fase 1 — Feed da câmera Eye na tela do app
Classes: `MainActivity`, `GlassesConnection`, `UvcCameraHelper`, `HevcDecoder`.
- [ ] Projeto Gradle criado (minSdk 34, targetSdk 35); camada `ai/nreal/glasses/control/` copiada do repo Aloim
- [ ] Ativação HID funcionando (logs de NRBSPGetCameraStatus OK)
- [ ] Fluxo dos 2 prompts USB com retry amigável (handshake dá timeout se o usuário demorar — mostrar countdown/instrução)
- [ ] Bulk reader em thread dedicada montando frames H.265 (log de NAL types 32/33/34/19-20)
- [ ] MediaCodec decodificando para SurfaceView no app; preview visível
- [ ] Intent-filter USB_DEVICE_ATTACHED + device_filter (grant implícito no attach)
**Teste manual**: com os óculos conectados (sem DeX ainda), abrir o app e ver o feed. Depois repetir com DeX ativo na ordem que passou na Fase 0.
**Pronto quando**: preview estável por 15 min, com DeX ativo, fps ~30, sem crash em reconexão de cabo.

### Fase 2 — MediaPipe + landmarks renderizados
Classes: `FrameConverter`, `HandTracker` + view de debug no app.
- [ ] `hand_landmarker.task` no assets; dependência tasks-vision fixada (verificar última versão no Maven Central)
- [ ] HevcDecoder redirecionado para ImageReader (YUV_420_888) OU SurfaceTexture+FBO GL
- [ ] FrameConverter: downscale + YUV→RGBA (libyuv `I420Scale`+`I420ToABGR`, ~1-3ms) → `ByteBufferImageBuilder`; pool de buffers reutilizados (MPImage.close() após consumo)
- [ ] HandLandmarker em LIVE_STREAM/GPU numa HandlerThread; timestamps monotônicos
- [ ] Throttle de inferência a 30 FPS (descartar frames)
- [ ] Overlay de debug NO APP desenhando os 21 landmarks sobre o preview
- [ ] Medir e logar: latência de inferência, latência fim-a-fim (timestamp do frame → resultado), fps efetivo
**Teste manual**: usuário de óculos, mão levantada no FOV da Eye — esqueleto da mão desenhado sobre o preview segue a mão. Testar DEITADO (mão vista de ângulos não-frontais, apoiada no peito — anotar degradação; é o risco 6).
**Pronto quando**: landmarks estáveis a ~30fps, inferência <15ms, tracking funciona com a mão apoiada no peito/colchão (postura deitada).

### Fase 3 — Cursor overlay no DeX seguindo a mão
Classes: `DexDisplayMonitor`, `CursorOverlay`, `CursorMapper`, `OneEuroFilter`, `HandMouseAccessibilityService` (só a parte de overlay), `EyeCaptureService` (FGS mínimo).
- [ ] AccessibilityService declarado e habilitável (accessibility_config.xml com canPerformGestures + isAccessibilityTool)
- [ ] DexDisplayMonitor: categoria DESKTOP + fallback PRESENTATION + DisplayListener (add/remove/changed — troca de modo dos óculos)
- [ ] CursorOverlay A.1: janela fullscreen transparente no display DeX (createDisplayContext + createWindowContext, view inflada com o windowContext), cursor ImageView, move via setTranslationX/Y no Choreographer
- [ ] CursorMapper: mapeamento normalizado (0-1 da câmera) → pixels do DeX via `currentWindowMetrics.bounds`, com espelhamento se necessário, activeBox (ganho ajustável — mão na borda do FOV → tela inteira), clamp; calibração de 2 pontos persistida em Prefs
- [ ] OneEuroFilter em x/y (minCutoff=1.0, beta=0.05, dCutoff=1.0, freq=fps medido); reset ao reaparecer a mão; dead-zone radial de 2-3px
- [ ] Pipeline movido para o FGS connectedDevice (notificação persistente); app pode ir para background
- [ ] Cursor some (fade) após ~2s sem mão detectada
- [ ] (Opcional, se jank medido) migrar para A.2: attachAccessibilityOverlayToDisplay + SurfaceControlViewHost + Transaction.setPosition
**Teste manual**: DeX + Netflix aberto, app em background — cursor visível nos óculos seguindo a mão, parado sem jitter, sem lag perceptível em movimento rápido. Tuning do One Euro conforme procedimento oficial (beta=0 → reduzir minCutoff com a mão parada até sumir o jitter → subir beta em movimento até o lag ficar ok).
**Pronto quando**: cursor 30fps+ suave sobre Netflix fullscreen, com o app em background, por 15 min.

### Fase 4 — Pinch → clique injetado
Classes: `PinchDetector`, `GestureInjector`.
- [ ] PinchDetector: razão dist3(4,8)/dist3(0,5), EMA alpha 0.5, histerese entra<0.28 / sai>0.50, debounce 3 frames (~100ms)
- [ ] Congelamento do cursor no clique: buffer circular de posições, usar a de ~100ms atrás no pinch-down (o ato de pinçar move o indicador — solução HoloLens/Vision Pro)
- [ ] GestureInjector.tap(x,y): dispatchGesture com setDisplayId do DeX, stroke de 50ms
- [ ] Feedback visual do cursor: cor/escala muda no pinch-down e no clique confirmado
- [ ] Tratar callback onCancelled do dispatchGesture (log + retry 1x)
**Teste manual**: navegar o launcher do DeX inteiro só com a mão: abrir Netflix, escolher episódio, play/pause, voltar. Medir taxa de cliques errados/fantasma em 5 min de uso.
**Pronto quando**: 10 cliques consecutivos em alvos de ~48dp com >90% de acerto, zero cliques fantasma em 5 min de mão visível sem pinçar.

### Fase 5 — Robustez e gestos extras
Classes: todas + `ThermalMonitor`, watchdog no a11y service.
- [ ] Drag: pinch-hold >300ms → StrokeDescription com `willContinue=true` encadeada via `continueStroke()` seguindo a mão; última com willContinue=false no pinch-up
- [ ] Scroll: drag vertical convertido em fling, ou modo scroll dedicado (decidir testando)
- [ ] Reconexão USB: detach → notificação full-screen-intent que traz a Activity com 1 toque para re-grant; estado do pipeline pronto para resumo <5s (permissão USB persiste até detach físico — cachear tudo que puder)
- [ ] Watchdog: a11y service detecta morte do FGS e reinicia (via Activity se background-start negado); START_STICKY
- [ ] ThermalMonitor: getThermalHeadroom/OnThermalStatusChangedListener → reduzir inferência para 20-24 FPS sob throttling (cursor ainda fluido via One Euro + interpolação no Choreographer)
- [ ] Onboarding com checklist Samsung (seção 7) verificável (deep-links para as telas de config)
- [ ] Soak test final: 2h de episódios com uso do cursor a cada ~10 min
**Pronto quando**: sessão de 2h sem intervenção manual (exceto re-grant se o cabo desconectar), bateria e térmica documentadas.

---

## 6. Detalhes técnicos críticos

### 6.1 Frame UVC → MPImage (caminho CPU com libyuv)
```kotlin
// HevcDecoder: MediaCodec → ImageReader (YUV_420_888, 2048x1512)
// FrameConverter (por frame, ~3-8ms total):
fun convert(image: Image): MPImage {
    val (y, u, v) = image.planes
    // 1) downscale p/ 768x576 (libyuv I420Scale) — MediaPipe usa 192/224 interno
    LibYuv.i420Scale(y.buffer, y.rowStride, u.buffer, u.rowStride,
                     v.buffer, v.rowStride, 2048, 1512,
                     dstY, dstU, dstV, 768, 576, FilterMode.BILINEAR)
    // 2) I420 → RGBA (libyuv I420ToABGR ≈ RGBA little-endian)
    LibYuv.i420ToAbgr(dstY, dstU, dstV, rgbaBuffer /* do pool */, 768 * 4, 768, 576)
    return ByteBufferImageBuilder(rgbaBuffer, 768, 576, MPImage.IMAGE_FORMAT_RGBA).build()
}
// Enviar: handLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
// Regras: timestamps MONOTÔNICOS; pool de rgbaBuffer; mpImage.close() no resultListener.
// NÃO usar RenderScript (removido). Alternativa GPU (se CPU pesar): decode → SurfaceTexture
// → shader OES em FBO 768x576 → glReadPixels → ByteBuffer RGBA. Evita tocar o frame
// 2048x1512 na CPU.
```

### 6.2 Mapeamento câmera → display DeX (com calibração)
```kotlin
class CursorMapper(private val prefs: Prefs) {
    // Landmark de referência: 8 (INDEX_TIP) — ou 9 (MIDDLE_MCP) se jitter demais.
    // A Eye aponta para fora: a mão aparece "como vista pelos olhos" → x direto,
    // sem espelhar. (Validar empiricamente na Fase 3; se andar invertido, flip.)
    //
    // Resolução do DeX: NUNCA hardcodar. Obter de
    //   createDisplayContext(dex).createWindowContext(...).getSystemService(WindowManager)
    //     .currentWindowMetrics.bounds
    // e re-obter em onDisplayChanged (troca normal 1920x1080 ↔ UltraWide 3840x1080
    // re-enumera o display e reinicia o DeX).
    //
    // Zona ativa: deitado, a mão fica na BORDA INFERIOR do FOV (apoiada no peito).
    // Mapear uma sub-região da imagem para a tela inteira (ganho > 1):
    //   activeBox default: x ∈ [0.15, 0.85], y ∈ [0.45, 0.95]  (calibrável)
    fun map(nx: Float, ny: Float, bounds: Rect): PointF {
        val bx = ((nx - prefs.boxLeft) / (prefs.boxRight - prefs.boxLeft)).coerceIn(0f, 1f)
        val by = ((ny - prefs.boxTop) / (prefs.boxBottom - prefs.boxTop)).coerceIn(0f, 1f)
        return PointF(bx * bounds.width(), by * bounds.height())
    }
    // Calibração de 2 pontos (wizard): pedir pinch no canto sup-esq e inf-dir
    // da tela; gravar as posições normalizadas da mão como boxLeft/Top/Right/Bottom
    // com margem de 5%. Persistir em Prefs.
}
```

### 6.3 Detector de pinch com histerese
```kotlin
class PinchDetector {
    private var ema = Float.NaN
    private var candidate: Boolean? = null; private var candidateFrames = 0
    var isPinched = false; private set

    /** landmarks: normalizados (x,y,z) do HandLandmarkerResult.landmarks()[0] */
    fun update(lm: List<NormalizedLandmark>): PinchEvent? {
        val d48 = dist3(lm[4], lm[8])            // THUMB_TIP ↔ INDEX_TIP
        val hand = dist3(lm[0], lm[5])           // WRIST ↔ INDEX_MCP (escala da mão)
        val ratio = d48 / hand                   // invariante à distância da câmera
        ema = if (ema.isNaN()) ratio else 0.5f * ratio + 0.5f * ema
        val want = when {
            !isPinched && ema < 0.28f -> true    // entra em pinch
            isPinched && ema > 0.50f -> false    // sai (histerese dupla — NUNCA 1 threshold)
            else -> return null
        }
        if (candidate != want) { candidate = want; candidateFrames = 0 }
        if (++candidateFrames < 3) return null   // debounce ~100ms @30fps
        isPinched = want; candidate = null
        return if (want) PinchEvent.DOWN else PinchEvent.UP
    }
    fun reset() { ema = Float.NaN; candidate = null; isPinched = false }
}
// No PinchEvent.DOWN: usar a posição do cursor de ~100ms ATRÁS (buffer circular
// de ~6 posições) — pinçar desloca a ponta do indicador (solução HoloLens/Vision Pro).
// worldLandmarks() (metros) é alternativa, mas o z deles é menos estável — a razão
// normalizada em coordenadas de imagem é a prática consolidada.
```

### 6.4 dispatchGesture com displayId + overlay no display DeX
```kotlin
// --- injeção (HandMouseAccessibilityService / GestureInjector) ---
fun tap(x: Float, y: Float, displayId: Int) {
    val path = Path().apply { moveTo(x, y) }
    val gesture = GestureDescription.Builder()
        .setDisplayId(displayId)                              // API 30+
        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        .build()
    dispatchGesture(gesture, object : GestureResultCallback() {
        override fun onCancelled(g: GestureDescription) { Log.w(TAG, "cancelled") }
    }, null)
}
// Drag: StrokeDescription(path, 0, dur, /*willContinue=*/true) encadeada com
// continueStroke() a cada atualização de posição; última com willContinue=false.

// --- localizar o display do DeX ---
val dex = displayManager
    .getDisplays("com.samsung.android.hardware.display.category.DESKTOP")   // oficial Samsung
    .firstOrNull()
    ?: displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
// + DisplayListener para add/remove/changed (hot-plug e troca de modo dos óculos)

// --- overlay caminho A.1 (clássico, API 30+) ---
val winCtx = createDisplayContext(dex!!)
    .createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)    // MESMO type do LayoutParams!
val wm = winCtx.getSystemService(WindowManager::class.java)
val lp = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT,
    TYPE_ACCESSIBILITY_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
wm.addView(rootView /* inflada com winCtx! */, lp)
// Mover: cursorView.translationX = fx; cursorView.translationY = fy  (via Choreographer)
// NUNCA wm.updateViewLayout() por frame.

// --- overlay caminho A.2 (API 34+, se A.1 tiver jank) ---
val viewHost = SurfaceControlViewHost(winCtx, dex, /*hostToken=*/ null)
viewHost.setView(cursorView, cursorSizePx, cursorSizePx)
attachAccessibilityOverlayToDisplay(dex.displayId, viewHost.surfacePackage!!.surfaceControl)
// Mover: SurfaceControl.Transaction().setPosition(sc, x, y).apply()  (SurfaceFlinger puro)
```

### 6.5 Manifest (essência)
```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.CAMERA"/> <!-- pré-condição do grant USB p/ UVC -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

<service android:name=".service.EyeCaptureService" android:exported="false"
         android:foregroundServiceType="connectedDevice"/>
<service android:name=".service.HandMouseAccessibilityService" android:exported="false"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
  <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
  <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_config"/>
</service>
<activity android:name=".MainActivity">
  <intent-filter><action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"/></intent-filter>
  <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
             android:resource="@xml/usb_device_filter"/>
</activity>
<!-- usb_device_filter.xml: <usb-device vendor-id="13080" product-id="1078"/> -->
<!-- accessibility_config.xml: android:canPerformGestures="true"
     android:isAccessibilityTool="true" -->
<!-- startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) -->
```

---

## 7. Setup do usuário (Raphael)

1. **Instalar o app oficial XREAL Glasses Control** (obrigatório como FONTE das .so nativas — ver correção na seção 3.1: elas são empacotadas como `jniLibs` do nosso próprio app, não carregadas do Glasses Control em runtime). Atualizar firmware dos óculos UMA vez, anotar a versão, e depois **não atualizar mais** (nem o app, nem o firmware) enquanto o projeto viver. Se o app oficial capturar o diálogo de USB attach, negar a associação automática dele (ele pode disputar o claim das interfaces).
   - **Extrair as .so ANTES de compilar nosso app** (passo único, refeito só se as libs precisarem ser atualizadas): localizar o APK instalado do Glasses Control (pacote `ai.nreal.glasses.control`, ex.: `adb shell pm path ai.nreal.glasses.control`), extrair `libnr_glasses_api.so` e `libota-lib.so` (arquitetura `arm64-v8a`) de dentro dele, e copiá-las para `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/` no nosso repo. Sem esse passo, o build/instalação do nosso app falha na ativação HID (`UnsatisfiedLinkError` — ver `GlassesConnection.initNativeLibrary`).
2. **Sideload do nosso app** (ADB ou APK direto) — só depois do passo 1 (as .so precisam já estar em `jniLibs/` no momento do build).
3. **Permissões no primeiro uso** (o wizard do app guia):
   - Permissão CAMERA (pré-condição do grant USB para dispositivo de classe vídeo).
   - 2 prompts USB sequenciais ao conectar os óculos (antes e depois da re-enumeração) — **aceitar rápido** (o handshake dá timeout). Marcar "usar por padrão" quando oferecido.
   - Ativar o serviço de acessibilidade: `Configurações > Acessibilidade > Apps instalados > Hand Mouse > Ativar` (dá overlay + injeção de gestos de uma vez).
   - Notificações (POST_NOTIFICATIONS).
4. **Checklist anti-kill Samsung** (obrigatório; re-verificar após cada OTA — não há solução via código):
   - `Config > Apps > Hand Mouse > Bateria > Irrestrito`
   - `Config > Bateria > Limites de uso em segundo plano > Apps que nunca suspendem` → adicionar o app; **desligar "Suspender apps não usados"** (senão re-adormece após ~3 dias)
   - Recents → ícone do app → **"Manter aberto" (cadeado)**
   - Não usar modos de economia máxima durante a sessão; se houver kills misteriosos, desligar Adaptive Battery
5. **Toggle de privacidade**: se o spike S5 mostrar interferência, desligar o toggle de câmera das Quick Settings antes da sessão.
6. **DeX**: no One UI 8 inicia sozinho ao conectar os óculos. Se cair em espelhamento, tocar no tile "DeX" nas Quick Settings. Usar resolução **1920x1080 16:9** (não UltraWide — desnecessário para vídeo 16:9 e re-enumera o display). Comportamento de tela do celular no One UI 8 mudou (pode ficar ligada estendendo o desktop) — seguir o que o spike S6 documentou; fallback: tela em modo touchpad, brilho mínimo, sem bloquear.
7. **Modos dos óculos**: **Follow mode** para deitar (estável em qualquer orientação de cabeça; Anchor tem drift de IMU com a cabeça inclinada). Configurar tamanho/distância da tela no OSD ANTES de deitar. A âncora 6DoF fica indisponível — nosso app monopoliza a câmera Eye.
8. **Ordem de inicialização da sessão** (a que passou na Fase 0 — provisoriamente): conectar óculos → aceitar/confirmar grants → app ativa a câmera → DeX sobe → deitar → calibrar (2 pinches nos cantos) → abrir Netflix.
9. **Energia**: a porta USB-C única fica ocupada pelos óculos — sem carga simultânea. Para sessões >2h, hub USB-C com DP alt-mode passthrough + PD (validar antes que o hub não degrada o dispositivo composto USB 2.0 — mais um hop no caminho da câmera).
10. **Shizuku**: NÃO é necessário no plano principal. Só entra se o spike S3 mostrar que dispatchGesture não funciona no display do DeX (aí: instalar Shizuku, parear via Wireless ADB a cada boot, injeção via `input tap -d`).

---

## 8. Riscos e mitigações

| # | Risco | Prob. | Impacto | Mitigação / Plano B |
|---|-------|-------|---------|---------------------|
| 1 | Re-enumeração USB da ativação da câmera derruba a sessão DeX | Média | Alto | Spike S1/S2 dia 1; se só uma ordem funciona, fixar a ordem no onboarding (câmera ANTES do DeX — uvc0=1 persiste até desconectar). Plano B: Unity FPV via SDK oficial. |
| 2 | dispatchGesture não injeta no display do DeX (peculiaridade One UI; issue 350653948) | Média | Crítico | Spike S3 dia 1. Plano B1: Shizuku + `input tap -d`. Plano B2: BluetoothHidDevice (mouse BT virtual — o DeX trata nativamente, elimina overlay e injeção de uma vez). Se nada injeta: projeto inviável como concebido. |
| 3 | Overlay aparece na tela do celular em vez do DeX (relatos em fóruns Samsung) | Baixa-média | Alto | createDisplayContext + createWindowContext com o display correto (causa provável dos relatos: display default). Spike S4. Plano B: attachAccessibilityOverlayToDisplay (API 34) e, por último, TYPE_APPLICATION_OVERLAY. |
| 4 | Repo Aloim não funciona no S25 (só testado em S26 Ultra) | Baixa | Crítico | Spike S0 com APK pronto (30 min). Plano B: fixar versões; capturar HID raw e eliminar as .so; último recurso: câmera frontal do S25. |
| 5 | Update do app/firmware XREAL quebra a API NRBSP* | Média (longo prazo) | Alto | Congelar versões (APK + firmware); guardar cópia do APK funcional. Migração futura: reproduzir o comando HID sem as .so. |
| 6 | Samsung mata o FGS durante o episódio | Baixa-média | Médio | Checklist One UI + a11y no mesmo processo + overlay sempre visível + watchdog START_STICKY. Fallback: janela mínima visível no DeX (processo TOP). |
| 7 | Tracking degrada com a mão em ângulo "deitado" (MediaPipe treinado em vistas frontais) | Média | Médio | Testar cedo (Fase 2); ajustar activeBox/ganho para mão apoiada; se preciso, landmark 9 (mais estável) + confidences menores. |
| 8 | Térmica: DeX + decode série + decode HEVC + GPU inference por 2h | Média | Médio | Inferência a 30fps (não 60), downscale antes do MediaPipe, ThermalMonitor reduz para 20-24fps sob throttling. Hub PD para carga. |
| 9 | Detach/re-enumeração no meio da sessão exige re-grant com Activity em foreground | Média | Médio | Intent-filter USB_DEVICE_ATTACHED + "usar por padrão"; notificação full-screen-intent para re-grant em 1 toque; resumo do pipeline <5s. |
| 10 | Tela do celular bloqueada quebra DeX ou injeção (precedente: bug gamepad One UI 8.0, corrigido só no 8.5) | Média | Médio | Spike S6; mitigação: tela ligada em modo touchpad, brilho mínimo, sem keyguard durante a sessão. |
| 11 | Netflix/DRM: vídeo preto no display secundário (HDCP) ou overlay invisível sobre hardware overlay plane | Baixa | Alto (p/ o caso de uso) | DeX cabeado DP costuma negociar HDCP ok; FLAG_SECURE não afeta overlay local (só captura). Testar visualmente no S4. Independe do nosso app. |
| 12 | Toggle de privacidade de câmera interfere no caminho UVC (kernel upstream já discute controles p/ uvcvideo) | Baixa | Baixo | CAMERA declarada/pedida desde o dia 1; spike S5; abrir o stream no setup e nunca fechar; avisar o usuário. |
| 13 | Latência total >100ms deixa o cursor "borrachudo" | Baixa-média | Médio | Medir na Fase 2; caminho GL para conversão; One Euro tunado; interpolação a 60fps no Choreographer; se preciso, predição linear de 1-2 frames. |
| 14 | Troca de modo dos óculos (normal↔UltraWide) re-enumera o display no meio da sessão | Baixa | Baixo | DisplayListener trata added/removed/changed; re-obter bounds; recomendar ficar em 1080p. |
| 15 | Legal/ToS: engenharia reversa + libs proprietárias | Baixa | Baixo (uso pessoal) | Uso estritamente pessoal; não redistribuir as .so; acompanhar se a XREAL publica API nativa oficial. |

---

## 9. Fontes

### Câmera Eye / protocolo USB
- https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android (repo de referência)
- https://raw.githubusercontent.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android/main/docs/camera-access-guide.md (protocolo completo)
- https://raw.githubusercontent.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android/main/app/src/main/java/com/reveng26/xrcam/UvcCameraHelper.kt
- https://raw.githubusercontent.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android/main/app/src/main/java/com/reveng26/xrcam/GlassesConnection.kt
- https://docs.xreal.com/Camera/Access%20RGB%20Camera (SDK oficial, Unity-only)
- https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.0.0
- https://github.com/saki4510t/UVCCamera · https://github.com/Peter-St/Android-UVC-Camera (por que libs UVC prontas não servem)

### MediaPipe / hand tracking
- https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android
- https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker
- https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer (por que NÃO usar)
- https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision/0.10.29
- https://ai.google.dev/edge/api/mediapipe/java/com/google/mediapipe/framework/image/MPImage
- https://developers.google.com/edge/api/mediapipe/java/com/google/mediapipe/framework/image/ByteBufferImageBuilder
- https://github.com/jaantollander/OneEuroFilter · https://arxiv.org/pdf/2202.10964 (One Euro)
- https://www.mdpi.com/1424-8220/25/17/5411 (pinch/interação) · https://arxiv.org/pdf/2006.10214 (MediaPipe Hands paper)
- https://github.com/google-ai-edge/mediapipe

### Overlay / injeção de clique no DeX
- https://developer.samsung.com/samsung-dex/modify-optimizing.html (categoria DESKTOP, display context — doc oficial)
- https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder
- https://developer.android.com/sdk/api_diff/30/changes/android.accessibilityservice.GestureDescription.Builder (setDisplayId confirmado na API 30)
- https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.attachaccessibilityoverlaytodisplay?view=net-android-35.0 (API 34+)
- https://learn.microsoft.com/dotnet/api/android.content.context.createwindowcontext
- https://issuetracker.google.com/issues/350653948 (inconsistência dispatchGesture multi-display)
- https://r1.community.samsung.com/t5/samsung-dex/appear-on-top-or-app-overlay-issue/m-p/26309884/highlight/true (gotcha: overlay no display errado)
- https://github.com/thbecker/android-accessibility-overlay
- https://developer.android.com/security/fraud-prevention/activities (setHideOverlayWindows vs TYPE_ACCESSIBILITY_OVERLAY)
- https://www.guardsquare.com/blog/protecting-against-android-overlay-attacks-guardsquare
- https://developer.android.com/topic/performance/vitals/render (por que não updateViewLayout por frame)

### Background / FGS / Samsung
- https://developer.android.com/develop/background-work/services/fgs/service-types (connectedDevice = USB)
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://source.android.com/docs/core/camera/external-usb-cameras (CAMERA como pré-condição p/ webcam USB)
- https://github.com/saki4510t/UVCPermissionTest (CAMERA estática p/ targetSdk>=28)
- https://voidcomputing.hu/blog/android-uvc/ (caminho libuvc fora do CameraService)
- https://dontkillmyapp.com/samsung
- https://www.sammobile.com/news/this-trick-might-keep-your-apps-from-force-closing-in-samsung-dex/
- https://community.xreal.com/t/turn-off-phone-display-when-nreal-is-connected-dex/1242

### DeX + XREAL / ambiente
- https://tutorials.xreal.com/docs/glasses/one-series/osd/ultrawide-mode/ (UltraWide re-enumera como 3840x1080)
- https://us.shop.xreal.com/blogs/buying-guide/user-guide_xreal-one-series
- https://developer.samsung.com/sdp/blog/en/2017/07/27/samsung-dex-how-to-detect-the-samsung-dex-mode (UI_MODE_TYPE_DESK)
- https://us.community.samsung.com/t5/Questions/Is-it-possible-to-disable-DEX-auto-start-on-One-UI8/td-p/3406976
- https://r2.community.samsung.com/t5/Galaxy-S/Issues-in-Samsung-Dex-on-One-UI-8/td-p/20814498
- https://r1.community.samsung.com/t5/samsung-dex/samsung-dex-external-display-resolution/td-p/20875408
- https://www.sammobile.com/news/samsung-dex-enable-4k-resolution-guide/ (Good Lock MultiStar p/ resoluções extras)
- https://www.samsung.com/us/support/answer/ANS10001955/ (setup DeX)
- https://www.notebookcheck.net/Xreal-One-Pro-AR-glasses-review-A-giant-external-display-wherever-you-go.1194048.0.html
