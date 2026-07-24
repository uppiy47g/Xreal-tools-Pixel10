package com.raphael.handmouse

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.raphael.handmouse.capture.GlassesConnection
import com.raphael.handmouse.overlay.DexDisplayMonitor
import com.raphael.handmouse.service.EyeCaptureService
import com.raphael.handmouse.service.HandMouseAccessibilityService
import com.raphael.handmouse.tracking.HandPoint
import com.raphael.handmouse.tracking.HandTracker
import com.raphael.handmouse.tracking.PinchDetector
import com.raphael.handmouse.tracking.PinchEvent
import com.raphael.handmouse.ui.HandOverlayView
import com.raphael.handmouse.util.Prefs

/**
 * Wizard de setup + preview + debug de tracking. Passos: permissão CAMERA (pré-condição do
 * grant USB para dispositivo de classe vídeo), POST_NOTIFICATIONS, isenção de otimização de
 * bateria — e só então inicia o [EyeCaptureService], que conduz os 2 grants USB sequenciais
 * (HID + câmera pós re-enumeração). O pipeline (captura + conversão + tracking) vive no
 * serviço; esta Activity só se conecta a ele como listener e hospeda o preview (Tarefa 3:
 * trocado de `SurfaceView` pra `ImageView` + [HandOverlayView] — ver Javadoc de
 * `EyeCaptureService` sobre a integração do `ImageReader`) e o [PinchDetector] local usado
 * só pra colorir o HUD de debug (a lógica de cursor/clique real é Tarefa 4/5).
 */
class MainActivity : AppCompatActivity(), EyeCaptureService.StateListener, EyeCaptureService.TrackingListener {

    companion object {
        private const val HANDSHAKE_WARNING_SECONDS = 10

        /** Grant USB pedido PELA ACTIVITY antes de iniciar o FGS (fix 2026-07-23) — ver
         * [ensureUsbPermissionThenStart]. */
        private const val ACTION_MAIN_USB_PERMISSION = "com.raphael.handmouse.MAIN_USB_PERMISSION"
    }

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var accessibilityDot: View
    private lateinit var countdownText: TextView
    private lateinit var logText: TextView
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnStartCapture: Button
    private lateinit var setupCard: View
    private lateinit var setupOnetimeNote: View
    private lateinit var setupCompleteLabel: View
    private lateinit var btnToggleSetup: Button
    private lateinit var previewImage: ImageView
    private lateinit var handOverlay: HandOverlayView
    // Preview recolhível (2026-07-23, pedido do usuário): recolhido, a Activity se DESREGISTRA
    // do fluxo de frames (removeTrackingListener) — zero trabalho de UI por frame. Persistido em
    // Prefs.previewVisible (default recolhido). O tracking/cursor do SERVIÇO não é afetado.
    private lateinit var btnTogglePreview: Button
    private lateinit var previewCard: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var btnOpenAccessibilitySettings: Button
    private lateinit var dexDisplayStatusText: TextView
    private lateinit var voiceLangGroup: RadioGroup

    // Tarefa 5: checklist anti-kill Samsung (best-effort, ver runBestEffortIntent).
    private lateinit var btnBatteryAppSettings: Button
    private lateinit var btnBatteryBackgroundUsage: Button
    private lateinit var btnBatteryDeviceSettings: Button

    // Só para o HUD de debug (cor do esqueleto) — a lógica de cursor/clique real (Tarefa 4/5)
    // tem sua própria instância no pipeline de produção (CursorPipeline, dono:
    // HandMouseAccessibilityService).
    private val debugPinchDetector = PinchDetector()

    private lateinit var prefs: Prefs

    private var countdownTimer: CountDownTimer? = null

    // Instância PRÓPRIA (não a do HandMouseAccessibilityService) — só para status somente-
    // leitura (nome/bounds físicos do display), reaproveitando o padrão do dex-spike; não cria
    // nenhuma janela (ver Javadoc de DexDisplayMonitor.windowBounds sobre por que MainActivity
    // não chama esse método).
    private lateinit var dexDisplayMonitor: DexDisplayMonitor

    private val requestCameraPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Permissão CAMERA concedida" else "Permissão CAMERA negada")
            updatePermissionsButtonState()
        }

    private val requestNotificationsPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Permissão de notificações concedida" else "Permissão de notificações negada")
            updatePermissionsButtonState()
        }

    private val requestRecordAudioPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Permissão de microfone concedida" else "Permissão de microfone negada — controle por voz indisponível")
            // fix de revisão final 2026-07-23: se a captura já está rodando, o FGS foi iniciado
            // (talvez) SEM o type de microfone — atualiza os types agora em vez de esperar um
            // reinício do serviço (ver EyeCaptureService.refreshForegroundServiceTypes).
            if (granted) EyeCaptureService.getInstance()?.refreshForegroundServiceTypes()
            updatePermissionsButtonState()
        }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                appendLog("USB attached — escaneando óculos...")
                onGlassesAttached()
            }
        }
    }

    // Resultado do grant USB pedido pela Activity (ver ensureUsbPermissionThenStart): concedido →
    // inicia o FGS de captura (agora a isenção "USB Device" do tipo connectedDevice existe).
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_MAIN_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                appendLog("Permissão USB dos óculos concedida — iniciando captura")
                startCaptureService()
            } else {
                appendLog("Permissão USB dos óculos negada — toque em iniciar pra tentar de novo")
                btnStartCapture.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        accessibilityDot = findViewById(R.id.accessibilityDot)
        countdownText = findViewById(R.id.countdownText)
        logText = findViewById(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        setupCard = findViewById(R.id.setupCard)
        setupOnetimeNote = findViewById(R.id.setupOnetimeNote)
        setupCompleteLabel = findViewById(R.id.setupCompleteLabel)
        btnToggleSetup = findViewById(R.id.btnToggleSetup)
        previewImage = findViewById(R.id.previewImage)
        handOverlay = findViewById(R.id.handOverlay)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        dexDisplayStatusText = findViewById(R.id.dexDisplayStatusText)
        voiceLangGroup = findViewById(R.id.voiceLangGroup)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        previewCard = findViewById(R.id.previewCard)
        btnBatteryAppSettings = findViewById(R.id.btnBatteryAppSettings)
        btnBatteryBackgroundUsage = findViewById(R.id.btnBatteryBackgroundUsage)
        btnBatteryDeviceSettings = findViewById(R.id.btnBatteryDeviceSettings)

        prefs = Prefs(this)

        appendLog(getString(R.string.usb_prompt_warning))

        btnGrantPermissions.setOnClickListener { runPermissionWizard() }
        btnStartCapture.setOnClickListener { startCapture() }
        btnToggleSetup.setOnClickListener {
            prefs.setupSectionExpanded = !prefs.setupSectionExpanded
            applySetupSectionState()
        }
        btnTogglePreview.setOnClickListener { setPreviewVisible(!prefs.previewVisible) }
        btnOpenAccessibilitySettings.setOnClickListener {
            appendLog("Abrindo configurações de acessibilidade")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Seletor de idioma do reconhecimento de voz (2026-07-23, spec voice-control):
        // default "system"; a escolha vale a partir da PRÓXIMA sessão de escuta (o
        // VoiceCommandController lê o pref a cada startListening — sem restart).
        val checkedId = when (prefs.voiceLanguage) {
            "pt-BR" -> R.id.voiceLangPt
            "en-US" -> R.id.voiceLangEn
            else -> R.id.voiceLangSystem
        }
        voiceLangGroup.check(checkedId)
        voiceLangGroup.setOnCheckedChangeListener { _, id ->
            prefs.voiceLanguage = when (id) {
                R.id.voiceLangPt -> "pt-BR"
                R.id.voiceLangEn -> "en-US"
                else -> com.raphael.handmouse.util.Prefs.VOICE_LANGUAGE_SYSTEM
            }
            appendLog("Idioma do reconhecimento de voz: ${prefs.voiceLanguage}")
        }

        btnBatteryAppSettings.setOnClickListener {
            runBestEffortIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        btnBatteryBackgroundUsage.setOnClickListener {
            runBestEffortIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        btnBatteryDeviceSettings.setOnClickListener {
            runBestEffortIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        }

        dexDisplayMonitor = DexDisplayMonitor(this).apply {
            listener = DexDisplayMonitor.Listener { display -> updateDexDisplayStatus(display) }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val permFilter = IntentFilter(ACTION_MAIN_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            // RECEIVER_EXPORTED: o resultado do requestPermission chega do system_server (mesmo
            // padrão do GlassesConnection/EyeCaptureService).
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbAttachReceiver, filter)
            registerReceiver(usbPermissionReceiver, permFilter)
        }

        updatePermissionsButtonState()
        applySetupSectionState()
    }

    override fun onResume() {
        super.onResume()
        // Registro SEMPRE no companion (fix 2026-07-24, caso 2 do "status preso" — ver
        // EyeCaptureService.stateListener): não depende de a instância do serviço já existir;
        // se ele nascer depois desta Activity, a primeira transição já chega aqui.
        EyeCaptureService.stateListener = this
        EyeCaptureService.getInstance()?.let { service ->
            // Sincroniza o estado ATUAL na hora do registro (fix 2026-07-23, "status preso em
            // 'Permissões OK'"): o listener só recebe TRANSIÇÕES — uma Activity (re)criada com
            // o serviço já em STREAMING (ex.: processo reiniciado por re-enumeração USB, que
            // mata e recria Activity e serviço em ordens diferentes) nunca via estado nenhum e
            // exibia o texto inicial do wizard pra sempre, parecendo "tracking morto" com o
            // pipeline saudável.
            onStateChanged(service.state, "Estado atual (sincronizado ao abrir)")
        }
        setPreviewVisible(prefs.previewVisible) // aplica o estado salvo + (des)registra o listener
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appendLog("Iniciado por USB_DEVICE_ATTACHED")
            onGlassesAttached()
        } else {
            maybeResumeCapture()
        }
        dexDisplayMonitor.start()
        updateAccessibilityStatus()
    }

    /** Recuperação ao abrir o app (6ª rodada 2026-07-23): se uma captura desejada
     * ([Prefs.captureActive]) está PARADA (serviço morto, ou vivo em IDLE/ERROR depois de um
     * reset de link em que os óculos sumiram do barramento), religa a cadeia — com a Activity
     * em foreground o diálogo de permissão USB pode aparecer, o que o serviço em background não
     * consegue garantir. Estados de handshake em andamento/STREAMING não são tocados (retryScan
     * num pipeline saudável re-dispararia a ativação da câmera por cima do stream vivo). */
    private fun maybeResumeCapture() {
        if (!prefs.captureActive || !allWizardPermissionsGranted()) return
        val service = EyeCaptureService.getInstance()
        if (service == null) {
            appendLog("Captura desejada mas serviço parado — retomando")
            ensureUsbPermissionThenStart()
            return
        }
        when (service.state) {
            EyeCaptureService.PipelineState.IDLE, EyeCaptureService.PipelineState.ERROR -> {
                appendLog("Captura parada (${service.state}) — retomando")
                ensureUsbPermissionThenStart()
            }
            else -> {} // handshake em andamento ou streaming — não interfere
        }
    }

    override fun onPause() {
        super.onPause()
        EyeCaptureService.stateListener = null
        EyeCaptureService.getInstance()?.removeTrackingListener(this)
        // Em background ninguém desenha o preview — desliga a alocação por frame no serviço
        // (onResume religa via setPreviewVisible conforme a preferência salva).
        EyeCaptureService.getInstance()?.previewFramesEnabled = false
        dexDisplayMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        countdownTimer?.cancel()
    }

    // --- Status do HandMouseAccessibilityService + display do DeX (Tarefa 4) ---

    private fun updateAccessibilityStatus() {
        val active = HandMouseAccessibilityService.instance != null
        accessibilityStatusText.text = getString(
            if (active) R.string.accessibility_status_active else R.string.accessibility_status_inactive
        )
        tintDot(accessibilityDot, if (active) R.color.hm_accent else R.color.hm_error)
    }

    /** Colore o dot oval de status (@drawable/status_dot) via backgroundTintList. */
    private fun tintDot(dot: View, colorRes: Int) {
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun updateDexDisplayStatus(display: android.view.Display?) {
        dexDisplayStatusText.text = if (display == null) {
            getString(R.string.dex_display_status_none)
        } else {
            val mode = display.mode
            "Display do DeX: id=${display.displayId} \"${display.name}\" ${mode.physicalWidth}x${mode.physicalHeight}"
        }
    }

    // --- Wizard de permissões ---

    private fun runPermissionWizard() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestBatteryExemption()
        // Voz (fix de revisão final 2026-07-23): o pedido de mic vem DEPOIS da isenção de
        // bateria e SEM return — mic é opcional ("negar não bloqueia nada"), e o return antigo
        // fazia uma negação permanente (auto-deny do Android) travar o wizard antes do passo
        // de bateria, que é o load-bearing anti-kill da Samsung.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        updatePermissionsButtonState()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            appendLog("Solicitando isenção de otimização de bateria...")
            runBestEffortIntent(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    /** Dispara [intent] sem checar de antemão se existe uma Activity que a resolva — algumas
     * telas de configuração (principalmente as específicas de fabricante, Samsung neste caso)
     * variam por versão do One UI e não têm uma forma confiável de checar disponibilidade antes
     * de tentar. Falha vira só um log, nunca crash (brief Tarefa 5: "deep-link best-effort com
     * try/catch" — card de checklist anti-kill Samsung, PLANO.md §7). */
    private fun runBestEffortIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("Não foi possível abrir a tela de configurações: ${e.message}")
        }
    }

    private fun allWizardPermissionsGranted(): Boolean {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return cameraOk && notifOk
    }

    private fun updatePermissionsButtonState() {
        val ready = allWizardPermissionsGranted()
        btnStartCapture.isEnabled = ready
        // Só escreve o texto do wizard quando o pipeline NÃO tem estado próprio a exibir (fix
        // 2026-07-24, "status volta pra 'Permissões OK' com o stream vivo"): este método roda
        // no onCreate e em todo callback de permissão, e sobrescrevia incondicionalmente o
        // texto real ([STREAMING]/[ERROR]...) que onStateChanged mantém.
        val pipelineState = EyeCaptureService.getInstance()?.state
        if (ready && (pipelineState == null || pipelineState == EyeCaptureService.PipelineState.IDLE)) {
            statusText.text = "Permissões OK — pronto para conectar os óculos"
        }
    }

    /** Aplica o estado colapsado/expandido do card de Setup (2026-07-24, onboarding UX):
     * botão Show/Hide, checkmark "✓ Complete" (só quando [Prefs.setupCompleted]) e a nota de
     * "one-time setup" (some assim que o setup completou, mesmo se o usuário reabrir o card
     * manualmente depois). Chamado no onCreate, no toggle manual e ao completar o setup pela
     * primeira vez (ver [onStateChanged]). */
    private fun applySetupSectionState() {
        val expanded = prefs.setupSectionExpanded
        setupCard.visibility = if (expanded) View.VISIBLE else View.GONE
        btnToggleSetup.text = getString(if (expanded) R.string.btn_preview_hide else R.string.btn_preview_show)
        setupCompleteLabel.visibility = if (prefs.setupCompleted) View.VISIBLE else View.GONE
        setupOnetimeNote.visibility = if (prefs.setupCompleted) View.GONE else View.VISIBLE
    }

    // --- Captura ---

    /** Ver [ensureUsbPermissionThenStart] — o botão agora passa pelo grant USB primeiro. */
    private fun startCapture() {
        ensureUsbPermissionThenStart()
    }

    /**
     * Fix do "tenho que plugar/desplugar várias vezes" (2026-07-23): o FGS `connectedDevice` SÓ
     * pode dar `startForeground` com a permissão USB dos óculos JÁ concedida (isenção "USB
     * Device" do tipo de FGS) — mas quem pedia essa permissão era o próprio serviço, DEPOIS de
     * iniciar: ovo-e-galinha que só se resolvia quando o Android auto-concedia via intent-filter
     * de USB_DEVICE_ATTACHED (sorte de timing). A ACTIVITY agora conduz o grant ANTES de iniciar
     * o serviço: acha os óculos no barramento → pede a permissão se faltar
     * ([usbPermissionReceiver] inicia o serviço no callback) → senão inicia direto.
     */
    private fun ensureUsbPermissionThenStart() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val glasses: UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == GlassesConnection.XREAL_VID }
        if (glasses == null) {
            appendLog("Nenhum óculos XREAL no USB — conecte o cabo e tente de novo")
            return
        }
        if (!usb.hasPermission(glasses)) {
            appendLog("Solicitando permissão USB dos óculos (pré-requisito do serviço)...")
            btnStartCapture.isEnabled = false
            val intent = Intent(ACTION_MAIN_USB_PERMISSION).apply { setPackage(packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(glasses, PendingIntent.getBroadcast(this, 3, intent, flags))
            return
        }
        startCaptureService()
    }

    private fun startCaptureService() {
        appendLog("Iniciando EyeCaptureService...")
        EyeCaptureService.start(this)
        EyeCaptureService.stateListener = this
        if (prefs.previewVisible) {
            EyeCaptureService.getInstance()?.previewFramesEnabled = true
            EyeCaptureService.getInstance()?.addTrackingListener(this)
        }
        btnStartCapture.isEnabled = false
    }

    /** ATTACH dos óculos (receiver dinâmico ou relaunch via intent-filter do manifest): se o
     * serviço já vive, um retryScan basta (ele mesmo religa o pipeline se preciso); se o
     * serviço morreu mas o usuário já tinha uma captura ativa ([Prefs.captureActive]), religa
     * a cadeia inteira passando pelo grant USB — sem depender de mais um plug/desplug. */
    private fun onGlassesAttached() {
        val service = EyeCaptureService.getInstance()
        if (service != null) {
            service.retryScan()
        } else if (prefs.captureActive && allWizardPermissionsGranted()) {
            ensureUsbPermissionThenStart()
        }
    }

    /** Aplica visibilidade do preview + REGISTRO no fluxo de frames de uma vez (ver o comentário
     * do campo [previewCard]): recolhido = sem listener = zero custo de UI por frame. Idempotente
     * (addTrackingListener é um Set; remove sem estar registrado é no-op) — chamado do botão, do
     * onResume (estado salvo) e do startCapture. */
    private fun setPreviewVisible(visible: Boolean) {
        prefs.previewVisible = visible
        previewCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnTogglePreview.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
        val service = EyeCaptureService.getInstance() ?: return
        // previewFramesEnabled: gate de alocação por frame no serviço (ver Javadoc lá) — segue
        // a visibilidade do preview 1:1.
        service.previewFramesEnabled = visible
        if (visible) service.addTrackingListener(this) else service.removeTrackingListener(this)
    }

    // --- EyeCaptureService.StateListener ---

    override fun onStateChanged(state: EyeCaptureService.PipelineState, message: String) {
        statusText.text = "[$state] $message"
        appendLog(message)

        // Dot da status pill: verde = streaming OK, vermelho = erro, âmbar = qualquer estado
        // transitório do handshake (mesma paleta do esqueleto/HUD).
        tintDot(statusDot, when (state) {
            EyeCaptureService.PipelineState.STREAMING -> R.color.hm_accent
            EyeCaptureService.PipelineState.ERROR -> R.color.hm_error
            else -> R.color.hm_warning
        })

        // Onboarding UX (2026-07-24): 1ª vez que o pipeline chega a STREAMING com as permissões
        // do wizard OK, marca o setup como completo e colapsa o card automaticamente — dali em
        // diante o usuário controla show/hide manualmente (ver applySetupSectionState).
        if (state == EyeCaptureService.PipelineState.STREAMING && !prefs.setupCompleted && allWizardPermissionsGranted()) {
            prefs.setupCompleted = true
            prefs.setupSectionExpanded = false
            applySetupSectionState()
        }

        when (state) {
            EyeCaptureService.PipelineState.CAMERA_ENABLING,
            EyeCaptureService.PipelineState.REQUESTING_CAMERA_PERMISSION -> startHandshakeCountdown()
            EyeCaptureService.PipelineState.STREAMING,
            EyeCaptureService.PipelineState.ERROR -> stopHandshakeCountdown()
            else -> {}
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    private fun startHandshakeCountdown() {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(HANDSHAKE_WARNING_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdownText.text = "Aceite o prompt USB agora — ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                countdownText.text = ""
            }
        }.start()
    }

    private fun stopHandshakeCountdown() {
        countdownTimer?.cancel()
        countdownText.text = ""
    }

    // --- EyeCaptureService.TrackingListener (debug: preview + esqueleto + HUD) ---

    override fun onFrameConverted(bitmap: Bitmap, conversionMs: Double) {
        previewImage.setImageBitmap(bitmap)
        lastConversionMs = conversionMs
    }

    override fun onHandResult(result: HandTracker.Result) {
        val points = result.landmarks.map { HandPoint(it.x(), it.y(), it.z()) }
        val pinchEvent = debugPinchDetector.update(points)
        if (pinchEvent != null) {
            appendLog("Pinch: $pinchEvent")
        }

        val metrics = HandOverlayView.Metrics(
            inferenceFps = result.inferenceFps,
            endToEndLatencyMs = result.latencyMs,
            conversionMs = lastConversionMs,
        )
        handOverlay.update(result.landmarks, debugPinchDetector.isPinched, metrics)
    }

    override fun onHandLost() {
        debugPinchDetector.reset() // evita EMA/debounce "velhos" contaminarem quando a mão voltar
        handOverlay.clearHand()
    }

    override fun onTrackingError(message: String) {
        appendLog("Erro de tracking: $message")
    }

    // último tempo de conversão conhecido (atualizado a cada frame convertido); usado como
    // aproximação no HUD quando um resultado de mão chega (ver HandOverlayView.Metrics) —
    // resultados chegam de forma assíncrona, não há correlação exata frame<->resultado sem
    // um identificador de frame dedicado (fora de escopo pra uma métrica de debug).
    private var lastConversionMs: Double = 0.0

    private fun appendLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logText.append("[$time] $message\n")
    }
}
