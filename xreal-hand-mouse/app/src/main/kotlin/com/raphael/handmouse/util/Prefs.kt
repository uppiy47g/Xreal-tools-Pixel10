package com.raphael.handmouse.util

import android.content.Context
import android.content.SharedPreferences

/**
 * `SharedPreferences` tipadas — flag "captura ativa" (usada pelo watchdog do
 * [com.raphael.handmouse.service.HandMouseAccessibilityService] — ver Javadoc dele). Os
 * thresholds do pinch ([com.raphael.handmouse.tracking.PinchDetector]) NÃO são configuráveis por
 * decisão explícita do brief — permanecem constantes de compilação, nada aqui os expõe.
 *
 * A calibração de 2 pontos que vivia aqui foi APOSENTADA em 2026-07-23 junto com o mapeamento
 * absoluto — o cursor agora é relativo ([com.raphael.handmouse.tracking.RelativeCursorMapper],
 * modo trackpad, zero configuração). As chaves `calibration_box_*` de instalações antigas ficam
 * órfãs no XML — inofensivas, ignoradas.
 *
 * Não testável em JVM puro sem Robolectric (depende de `Context`/`SharedPreferences` reais) —
 * mesma limitação já aceita nas Tarefas 2-4 pra classes equivalentes.
 */
class Prefs(context: Context) {

    companion object {
        private const val PREFS_NAME = "hand_mouse_prefs"
        private const val KEY_CAPTURE_ACTIVE = "capture_active"
        private const val KEY_PREVIEW_VISIBLE = "preview_visible"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SETUP_SECTION_EXPANDED = "setup_section_expanded"
        private const val KEY_GESTURES_VISIBLE = "gestures_visible"
        private const val KEY_VOICE_COMMANDS_VISIBLE = "voice_commands_visible"

        /** Valor especial de [voiceLanguage]: usa o idioma do sistema (default). */
        const val VOICE_LANGUAGE_SYSTEM = "system"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Preview da câmera + esqueleto na MainActivity visível? Default FALSE (recolhido) de
     * propósito (2026-07-23, pedido do usuário): com o preview recolhido a Activity se
     * DESREGISTRA do fluxo de frames (`removeTrackingListener`) — zero trabalho de UI por frame
     * (setImageBitmap + redraw do esqueleto a ~30fps). A conversão RGBA em si continua no
     * serviço (o MediaPipe precisa dela) — a economia é só o lado da UI, mas é real. */
    var previewVisible: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_VISIBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW_VISIBLE, value).apply()

    /** "O usuário/sistema pretende que a captura esteja rodando" — setada em
     * `EyeCaptureService.start()`, consultada pelo watchdog do a11y service. NUNCA limpa em
     * `EyeCaptureService.onDestroy()` de propósito: um `onDestroy()` inesperado (kill do sistema)
     * é exatamente o cenário que o watchdog existe pra detectar e recuperar — limpar a flag ali
     * quebraria essa detecção. Este app não tem hoje um botão "parar captura" explícito na UI
     * (ver MainActivity) — uma vez iniciada, a intenção normal é permanecer ativa.
     *
     * **Fix de revisão (achado Important I6)**: só é limpa por `EyeCaptureService.stop()` (a
     * PARADA INTENCIONAL — espelha o `= true` de `start()`), que hoje não tem chamador em
     * produção mas precisa estar correto pra qualquer uso futuro; se ficasse quebrado (setando
     * `true` sem nunca setar `false` em NENHUM caminho), o companion `stop()` seria uma
     * "armadilha de ressurreição": chamar `stop()` pararia o serviço, mas o watchdog do a11y
     * service continuaria vendo `captureActive=true` e reiniciaria a captura sozinho segundos
     * depois — o "parar" nunca pegaria de verdade. */
    var captureActive: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ACTIVE, value).apply()

    /** Idioma do reconhecimento de voz (2026-07-23, spec voice-control): "system" (default —
     * SpeechRecognizer usa o locale do aparelho), "pt-BR" ou "en-US" (forçados via
     * RecognizerIntent.EXTRA_LANGUAGE). A tabela de comandos é bilíngue independente disto —
     * a escolha afeta só a qualidade do reconhecimento de fala livre (ditado, nomes de app). */
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, VOICE_LANGUAGE_SYSTEM) ?: VOICE_LANGUAGE_SYSTEM
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()

    /** true assim que o pipeline chegou a STREAMING com todas as permissões do wizard concedidas
     * pelo menos uma vez (2026-07-24, onboarding UX — ver MainActivity.onStateChanged). Nunca é
     * limpa por este design (não há "refazer setup do zero" além de reinstalar o app). */
    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    /** Visibilidade do CONTEÚDO do card de Setup (nota + os 2 botões) — não confundir com
     * [setupCompleted]. Default true (mostrado); é forçada a false UMA VEZ, no instante em que
     * [setupCompleted] vira true (auto-colapsa a primeira vez) — depois disso segue só o toggle
     * manual do usuário (MainActivity.applySetupSectionState). */
    var setupSectionExpanded: Boolean
        get() = prefs.getBoolean(KEY_SETUP_SECTION_EXPANDED, true)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_SECTION_EXPANDED, value).apply()

    /** Visibilidade do card "Gestures" (2026-07-24, onboarding UX) — conteúdo estático, sem
     * efeito colateral no serviço; default true (mostrado). */
    var gesturesVisible: Boolean
        get() = prefs.getBoolean(KEY_GESTURES_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURES_VISIBLE, value).apply()

    /** Visibilidade do card "Voice commands" (2026-07-24, onboarding UX) — default true
     * (mostrado). */
    var voiceCommandsVisible: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_COMMANDS_VISIBLE, value).apply()
}
