# Próximos passos — o que VOCÊ precisa fazer manualmente

---

## SESSÃO DE ESTABILIDADE — 2026-07-23 (tarde) — recuperação autônoma + 60fps + 1080p

Commits `6ba115b`..`7fa9874`, tudo validado em hardware. Resumo: crash nativo (SIGSEGV) e
ANRs corrigidos; conexão USB confiável (grant antes do FGS, reconexão automática, poll de
presença 10s — **desplugar nunca mais é necessário**); recuperação de stall em camadas
(~0,5–3s auto-recuperados); cursor a 60fps + interpolação por vsync ("mouse de verdade");
gestos anti-falso-positivo (palma exige mão parada, punho por razão ponta/PIP 250ms);
câmera negociada em **1920x1080 HEVC** (~33% menos carga no X1 dos óculos).

**Descoberta-chave**: blinks/resets de link acontecem SÓ no modo **Anchored** (sobrecarga
do chip X1). Recomendação de uso: **Follow** pra sessões longas; anchor de câmera SEMPRE off.

**PRÓXIMO GRANDE PASSO — pipeline MJPEG** (ataca a CAUSA dos stalls, não só a recuperação):
ver `docs/superpowers/specs/2026-07-23-mjpeg-pipeline-design.md` — fmt2 da câmera é MJPEG
e rodou 4000 frames sem stall no teste; plano em 3 fases com spike barato de GO/NO-GO.

---

## SESSÃO DE TUNING — 2026-07-23 (cursor relativo + drag sem backlog)

Mudanças aplicadas nesta sessão (compiladas, instaladas, aguardando veredito de feel do usuário):

1. **Drag reescrito (dispatch guiado por conclusão, `GestureInjector`)**: root cause do lag/"continua mexendo após soltar" era o system_server tocar strokes continuadas EM SÉRIE (cada uma consome a duração inteira) enquanto alimentávamos a cada 50ms → fila crescia sem limite (latência 97→1468ms medida; endDrag valia 1,5s atrasado). Agora: máx 1 stroke em voo, posições coalescem em `pendingTarget`, `onCompleted` despacha o próximo segmento (60ms) pra posição mais recente. **Validado pelo usuário: "dragndrop tá bem bom".**
2. **Cursor RELATIVO (modo trackpad, `RelativeCursorMapper`)**: substituiu o mapeamento absoluto + calibração de 2 pontos (wizard APOSENTADO — botões/strings/Prefs/CalibrationBox/CursorMapper removidos). Deltas × ganho fixo (`spanX=0.40` = fração do FOV que cruza a tela; ajustar ali a sensibilidade), correção de aspecto 4:3 (0.75), clamp sem acúmulo nas bordas, re-ancoragem em `onHandLost` (tirar a mão e reposicionar = levantar o dedo do trackpad). Zero configuração na primeira abertura.
3. **`HOLD_THRESHOLD_MS` 700→500ms** (drag arma mais rápido) e **dead-zone 2.5→1.5px**.
4. **`OneEuroFilter` RETUNING DE RAIZ: beta 0.1→0.007, minCutoff→1.0** (2ª rodada do dia; a 1ª tinha só descido minCutoff 0.8→0.4, sem efeito). Diagnóstico: beta=0.1 era ~25x alto pra sinal em PIXELS — o termo de velocidade dominava o cutoff até em repouso e o filtro ficava efetivamente DESLIGADO (por isso 3 iterações de minCutoff nunca mudaram o tremor; o "treme no movimento" era ruído cru do MediaPipe × ganho). 0.007 é o clássico do paper do Casiez pra pixels. Lição: beta escala com a UNIDADE do sinal — sintoma diagnóstico: se mexer no minCutoff não muda nada, o beta está dominando.
5. **Stall do stream: detecção 8s→1,5s** (`UvcCameraHelper`: limiar dividido — 6 timeouts/3s antes do 1º frame, 3 timeouts/1,5s com stream rodando).
6. **Pacote de PRECISÃO pra alvos pequenos** (3 frentes): (a) **ganho adaptativo por velocidade** no `RelativeCursorMapper` (estilo "Enhance pointer precision": <~3px/frame usa 0.35 do ganho, rampa linear até ganho cheio em ~18px/frame, EMA 0.5 na velocidade); (b) **âncora do clique 50→180ms** atrás (o pinch confirma ~100ms depois de o gesto começar a perturbar a mão; 180ms cai ANTES da perturbação — filosofia HoloLens/Vision Pro); (c) **freeze visual do cursor durante PRESSED reativado** (`CursorRenderDecision`) — o que você vê durante o pinch É a âncora onde o clique cai.
7. **Punho fechado 2s = recentralizar cursor** (`FistDetector` + `RelativeCursorMapper.recenter`, pedido do usuário — o modo relativo perde a correspondência mão↔centro com clamps/re-ancoragens). Métrica: média dist(ponta→punho)/handScale dos 4 dedos (sem polegar), EMA+histerese (entra <1.25, sai >1.50, **thresholds de bancada — afinar em hardware**), debounce 3 frames. Punho confirmado SUPRIME o pinch (no punho o polegar encosta no indicador = clique fantasma); em PRESSED aborta o pinch pendente; em DRAGGING não interfere. Dispara 1x por hold.
8. **Guard do FGS `connectedDevice` (CRÍTICO, era "baixa prioridade")**: reinstalar o APK revoga o grant USB; o watchdog religava a captura sem óculos → `SecurityException` no `startForeground` → crash → **a11y service ia pro backoff de 2h do Android**. Guard em 3 camadas: `EyeCaptureService.start()` e o watchdog checam `isGlassesAttached()` (deviceList, VID 13080) antes de iniciar; `onStartCommand` faz try/catch → `stopSelf()`.

9. **Gestos de mídia — VALIDADOS pelo usuário** (implementados via subagentes): palma aberta (4 dedos individualmente estendidos, detecção ESTRITA pra mão relaxada não contar) = modo mídia com cursor congelado (re-âncora na saída, sem salto). Swipe horizontal = seek ±10s (duplo-tap injetado a 75%/25% da largura — convenção YouTube/Netflix; 2º tap só dispara após o 1º completar, falha-segura); swipe vertical = volume ± (AudioManager, FLAG_SHOW_UI). `PalmSwipeDetector` puro (12 testes), cooldown 600ms, thresholds de bancada (1.55/1.35 palma; travel 0.12, janela 400ms).
10. **Preview recolhível na MainActivity — VALIDADO**: botão Mostrar/Ocultar, default recolhido, persistido em Prefs; recolhido a Activity se desregistra do fluxo de frames (zero redraw de UI por frame).

**Estado**: TUDO validado pelo usuário em hardware e commitado no repo privado github.com/nudou350/Xreal-tools. Feel do cursor aprovado ("mil vezes melhor"). Knobs de tuning documentados nos próprios arquivos.

---

## ✅✅ SESSÃO DE VALIDAÇÃO EM HARDWARE — 2026-07-22 (CONCLUÍDA COM SUCESSO)

**A PROVA DE CONCEITO ESTÁ COMPLETA E FUNCIONANDO NO HARDWARE.** Fluxo ponta-a-ponta validado no Galaxy S25 + XREAL One Pro: câmera Eye ativada por USB puro (sem SDK Unity) → decodificação HEVC → MediaPipe hand tracking (~27fps, 12ms fim-a-fim) → cursor visível nos óculos → **pinch = clique** e **segurar pinch = drag-and-drop**, ambos injetados no DeX. Todos os spikes GO/NO-GO passaram e a coexistência câmera+DeX foi confirmada nas duas ordens.

### O que foi corrigido/ajustado nesta sessão (tudo já commitável, app instalado e rodando)
1. **Setup (Passo 0)**: `.so` corretas vêm do **XRCam do repo Aloim**, NÃO do ControlGlasses (ver aviso no Passo 0). Firmware pelo OTA web, não pelo ControlGlasses (que faz downgrade). `assets/nr_ota_default/` extraído e integrado.
2. **Bugs de app corrigidos**: tema (`Theme.AppCompat`), `.so` incompatíveis (`UnsatisfiedLinkError`), `ImageReader`→**`getOutputImage`** (crash CheckJNI no decoder de hardware Samsung), overlay do cursor com **retry** (BadToken no display recém-criado do DeX), permissão CAMERA runtime antes da ativação.
3. **Auto-recuperação do stream**: quando a câmera choca (soluço ao reposicionar a tela do DeX), o app reinicia sozinho em ~8s até 4x, em vez de congelar 60s. **Validado — o usuário aprovou.**
4. **Tuning do cursor (aprovado pelo usuário)**: referência trocada da ponta do indicador (landmark 8, ruidosa) para o **nó (landmark 5)** — matou tremor e drift no pinch; `OneEuroFilter` minCutoff 0.8/beta 0.1; congelamento visual removido; lookback de clique 50ms.
5. **Pinch natural + clique**: `EXIT_THRESHOLD` 0.50→0.38 e debounce assimétrico (UP=2) — solta o pinch com pouca separação. Limiar clique-vs-drag em 700ms.
6. **Drag-and-drop (via time de agentes + revisão adversarial)**: causa do cancelamento era **path degenerado** (só `moveTo` = comprimento 0, completava em 7ms e quebrava a cadeia `continueStroke`). Fix: segmentos reais de 2px começando onde o anterior terminou. **Validado em hardware** — `beginDrag COMPLETED após 289ms` (não mais 7ms), cadeia de continuação viva, objeto arrasta de verdade. Plano B (stroke única bufferizada) NÃO foi necessário.
7. **Drag mais fluido (aplicado 2026-07-22, pós-validação)**: `DRAG_SEGMENT_DURATION_MS` baixado de 320ms → **130ms** em `GestureInjector.kt`. O log tinha mostrado os `onCompleted` empilhando (289 → 549 → 951 → 1425ms) + segmentos CANCELLED no fim = backlog (cada segmento de 320ms durava ~6x o throttle de 50ms, alimentado mais rápido do que "tocava"). 130ms (~2,6x o throttle) reduz o backlog e o cursor acompanha a mão de perto. **Compilado e instalado — falta o usuário testar o feel** (se sob throttling térmico a cadeia quebrar, subir pra ~180ms).

### O que falta polir (para "continuar outra hora") — nada bloqueia, é refinamento
- **Drag: testar o feel com os 130ms** (item 7 acima). Se ainda houver lag, o backlog persiste e vale reduzir mais / repensar; se a cadeia quebrar sob calor, subir pra ~180ms.
- **Clique "não 100%"**: melhorou muito com o pinch natural; se ainda escapar, mexer no `HOLD_THRESHOLD_MS` (talvez 700→550ms agora que a liberação é rápida) ou afinar o `PinchDetector`.
- **Feel geral do cursor**: se travado/tremendo, ajustar `OneEuroFilter` (minCutoff/beta) e/ou a `RadialDeadZone`; recalibrar movendo a mão bem amplo entre os cantos (ganho menor).
- **Bug conhecido (baixa prioridade)**: `EyeCaptureService` crasha com `SecurityException` (FGS `connectedDevice`) se a captura iniciar SEM os óculos conectados. O fluxo normal (auto-start via `USB_DEVICE_ATTACHED`) evita, mas vale um guard.
- **`DRAG_MIN_SEGMENT_PX` / instrumentação**: os logs verbosos de diagnóstico do `GestureInjector` (accepted/COMPLETED/CANCELLED por stroke) podem ser reduzidos quando o drag estiver afinado.
- **Dívidas técnicas do ledger** (`.superpowers/sdd/progress.md`) continuam válidas.

### Como retomar
App já instalado no S25. Para recompilar: `cd xreal-hand-mouse; $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug` + `adb install -r`. adb Wi-Fi usado na sessão: `adb connect 192.168.15.23:5555` (o IP pode mudar). Fonte das lições detalhadas: memórias Vestige desta sessão (tags `xreal-hand-tracking`).

---

> Estado ORIGINAL em 2026-07-22 (pré-hardware): implementação completa e revisada (13 commits, 85/85 testes unitários verdes, revisão final multi-agente aprovou: **pronto para validação em hardware**). O histórico abaixo é o roteiro que foi seguido — os ✅ marcam o que já rodou.
>
> Apps no repo: `dex-spike/` (mini-app de validação, APK já compilado) e `xreal-hand-mouse/` (o app principal).

## Passo 0 — Extrair as bibliotecas proprietárias (OBRIGATÓRIO antes de compilar o app principal)

A ativação da câmera Eye depende de 2 `.so` proprietárias da XREAL que **não estão no repo** (licença). Sem elas o app compila, mas a ativação HID falha.

> ✅ **PASSO 0 CONCLUÍDO em 2026-07-22.** App instalado: **ControlGlasses do site de desenvolvedores** (pacote `com.xreal.glassescontrol`, versionName `3.0.0.20250307172552`, o "1.1.0" de https://developer.xreal.com/download/ — a Play Store BR não disponibiliza o `com.xreal.glassescontrol.store`). Firmware atualizado com sucesso (reiniciou e resetou). As duas `.so` (arm64-v8a) e o `assets/nr_ota_default/` completo foram extraídos do `base.apk` via `adb pull` e colocados em `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/` e `xreal-hand-mouse/app/src/main/assets/nr_ota_default/`.
>
> **Versões congeladas em 2026-07-22**: ControlGlasses `3.0.0.20250307172552` (instalado, PARADO) + **firmware mais recente via OTA web (https://www.xreal.com/ota)** — anotar o número exato.
>
> ⚠️ **LIÇÃO DE HARDWARE (2026-07-22): NUNCA mais abrir o ControlGlasses.** O "atualizar" dele flasheia o firmware embutido no APK (fev/2025), que é um DOWNGRADE — e esse firmware antigo **não suporta os comandos da câmera Eye** (HID responde `ERR_UNSUPPORTED_CMD`, `NRBSPWaitPilotReady` nunca completa, XRCam trava em "Waiting for pilot ready"). Foi exatamente o que aconteceu na primeira tentativa. A correção foi reflashear o firmware mais novo pelo OTA web com os óculos no PC. O ControlGlasses fica instalado apenas como fonte das `.so` para o XRCam (que as carrega em runtime), sempre em "Forçar parada". As `.so` antigas (do app de mar/2025) **funcionam com o firmware novo** — validado: ativação HID completou e a UVC Camera 0 enumerou.
>
> Outras notas de campo: (1) manter o Control Glasses SEM associação USB padrão — se o Android perguntar "Allow Control Glasses to access XREAL One Pro?", negar; (2) o aviso Android "app isn't 16 KB compatible" nas libs XREAL é inofensivo neste aparelho (S25 roda páginas de 4 KB — verificado via `getconf PAGE_SIZE`), mas é dívida para aparelhos 16 KB futuros.
>
> ⚠️ **CORREÇÃO CRÍTICA DAS .so (2026-07-22): as .so extraídas do ControlGlasses developer-site estavam ERRADAS.** A `libnr_glasses_api.so` do ControlGlasses `3.0.0.20250307172552` (2.047.680 bytes, md5 8da7ab…) é uma versão que **não registra o símbolo JNI `NRBSPWaitPilotReady`** que a `XrealGlasses.java` chama → `UnsatisfiedLinkError` e crash ao ativar a câmera no app principal. **As .so corretas são as que o XRCam do repo Aloim bundla** (`libnr_glasses_api.so` = 2.078.432 bytes md5 d38c87…, `libota-lib.so` = 3.971.344 bytes md5 77ff49…) — funcionam com a MESMA `XrealGlasses.java` (idêntica entre os dois projetos). Já substituídas em `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/`. **Fonte definitiva das .so: `apk/XRCam-debug.apk` do repo Aloim (extrair de `lib/arm64-v8a/`), não o ControlGlasses.** (O `assets/nr_ota_default/` do ControlGlasses, esse sim, está correto.)

## Passo 1 — Spikes S3/S4 com o dex-spike (~15 min) ⚠️ decide o GO/NO-GO

> ✅ **PASSO 1 CONCLUÍDO em 2026-07-22 — GO!** S4: overlay (quadrado vermelho) apareceu nos óculos, inclusive em movimento. S3: tap injetado registrado no DeX. Ambos validados com Netflix em foco.

O APK já está compilado em `dex-spike/app/build/outputs/apk/debug/app-debug.apk`.

```powershell
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" install "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\dex-spike\app\build\outputs\apk\debug\app-debug.apk"
```

1. Ative o serviço: Configurações > Acessibilidade > dex-spike > Ativar.
2. Conecte os óculos, suba o DeX, abra o dex-spike na tela do celular.
3. Selecione o display marcado `[DESKTOP]` na lista.
4. **S4**: botão "Overlay 15s" → o quadrado vermelho deve aparecer NOS ÓCULOS (não no celular), inclusive com Netflix fullscreen tocando.
5. **S3**: botão "Tap centro em 5s" (e depois "4 cantos") com a Netflix em foco → o clique deve acontecer no DeX.
6. O log na tela do celular registra tudo (botão "Copiar log" se quiser me mandar).

**Se S3 falhar**: o plano B é Shizuku ou mouse Bluetooth virtual (PLANO.md §3.3) — me chame que eu implemento. **Se S4 falhar**: fallbacks no PLANO.md §3.4.

## Passo 2 — Spikes S0/S1/S2 com o XRCam do repo Aloim (~30 min)

> ✅ **S0 + COEXISTÊNCIA COM DeX VALIDADOS em 2026-07-22 — o maior risco do projeto caiu.** Câmera Eye ativa por USB puro, transmite **H.265 a 60fps** (2048x1512, GREYSCALE, IF#10 do composto), decodifica com cores/nitidez perfeitas. E o crítico: **câmera a 60fps + DeX ativo coexistem sem problema** — a re-enumeração USB (13→17 interfaces) NÃO derruba a sessão DeX. Falta só formalizar o soak de 10 min. (Primeira tentativa de stream travou em 5 frames — tropeço do encoder engatando; reabrir o stream resolveu, fluxo estável depois.)
>
> ⚠️ **Dois bloqueios de permissão resolvidos no XRCam (valem também para o app principal):**
> 1. **Diálogo em rajada**: MainActivity + CameraActivity pediam permissão em laço e um cancelava o diálogo do outro. Corrigido no fork do XRCam (pedido único + espera) — fonte em `scratchpad/xrcam-src`, patch em GlassesConnection.kt/CameraActivity.kt.
> 2. **`Camera permission required for USB video class devices`** (Android 14+): quando o dispositivo USB ganha interface de vídeo (a UVC, pós-ativação), o Android **recusa** a permissão USB se o app não tiver a runtime **CAMERA** concedida. O XRCam declara mas nunca solicita CAMERA. Resolvido com `pm grant com.reveng26.xrcam android.permission.CAMERA`. **O app principal (xreal-hand-mouse) já pede CAMERA no wizard (PLANO §7) — confirmar que o grant acontece ANTES da ativação, senão a mesma trava ocorre.**
>
> ✅ **S1 E S2 AMBOS VALIDADOS em 2026-07-22**: coexistência funciona nas DUAS ordens — (S1) DeX ativo → ativar câmera, e (S2) câmera ativa → subir DeX. Abrir o app por dentro do DeX abre a janela da câmera dentro da janela do DeX. **O app principal pode inicializar em qualquer ordem, sem restrição de sequência.**
>
> Estado do aparelho (2026-07-22, para rodar o app principal): **só o Hand Mouse (`com.raphael.handmouse`) está habilitado** como candidato ao USB — XRCam e ControlGlasses ficaram `pm disable-user` de propósito, para o Hand Mouse ser o único candidato e o Android conceder permissão USB automática via o intent-filter dele (sem corrida de diálogo). Para voltar a usar o XRCam/ControlGlasses: `pm enable com.reveng26.xrcam` / `pm enable com.xreal.glassescontrol`.

1. Baixe o APK pronto do repo: https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android (pasta `apk/`).
2. **S0**: óculos conectados SEM DeX → abrir XRCam → aceitar os 2 prompts USB RÁPIDO (dá timeout) → feed 2048x1512 deve aparecer e aguentar 10 min.
3. **S1**: com DeX já ativo, ativar a câmera → o DeX sobrevive?
4. **S2**: ativar a câmera ANTES, depois subir o DeX → coexistem? (anote QUAL ordem funcionou — o app principal precisa dessa informação).
5. Os demais spikes (S5–S8, toggle de privacidade/tela apagada/soak/reconexão) estão na tabela do **PLANO.md §2** — informam mitigação, não bloqueiam.

## Passo 3 — Compilar e instalar o app principal

Depois do Passo 0:

```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" install app\build\outputs\apk\debug\app-debug.apk
```

Depois, no S25: wizard do app (CAMERA, notificações, 2 grants USB, acessibilidade, bateria) → preview do feed → landmarks sobre a mão → conectar DeX → cursor nos óculos → pinch = clique. Calibração: botão "Calibrar" (pinch no canto superior-esquerdo, depois inferior-direito).

## Passo 4 — Checklist anti-kill Samsung (pra sessão de 2h não morrer)

- Config > Apps > Hand Mouse > Bateria > **Irrestrito**
- Config > Bateria > Limites de uso em segundo plano > **Apps que nunca suspendem** → adicionar; desligar "Suspender apps não usados"
- Recents → ícone do app → **Manter aberto** (cadeado)
- Detalhes completos: PLANO.md §7.

## O que observar na primeira sessão (suposições que só o hardware confirma)

| O que | Sintoma se estiver errado | Correção (me chame) |
|---|---|---|
| Ordem de bytes RGBA (AbgrBuffer) | Cores trocadas no preview | 1 linha no FrameConverter |
| Chroma NV12 vs NV21 | Cores esverdeadas/rosadas | fallback documentado no código |
| ~~ImageReader sem USAGE_CPU_READ_OFTEN~~ | ~~crash "non-zero capacity for nullptr" na 1ª frame~~ | ✅ CORRIGIDO 2026-07-22 (EyeCaptureService, flag USAGE_CPU_READ_OFTEN) |
| Espelhamento do cursor | Cursor anda pro lado errado | flip no CursorMapper |
| Buffer pool + detectAsync | Landmarks "piscando"/corrompidos | trocar pool por cópia |
| Cancelamentos de drag | Arrastar solta sozinho | alargar segmento (já em 320ms) |

## Dívidas técnicas conhecidas (deferidas de propósito, nada bloqueia)

Pequenos itens de polimento catalogados nas revisões (ex.: fence de teardown pode ganhar @Volatile, contador de retry não reseta num caso raro, scroll dedicado adiado por decisão do plano). Estão listados em `.superpowers/sdd/progress.md`. Quando o hardware validar o core, me peça "aplica as dívidas técnicas do ledger".

## Como retomar comigo

Abra o Claude Code em `C:\Users\Raphael\Documents\Projetos\xreal hand tracking` e me diga o resultado dos spikes (cole o log do dex-spike se puder). Se algo falhar, o PLANO.md tem o plano B de cada área — eu implemento na hora.

Boa noite! 🌙
