package com.raphael.handmouse.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.raphael.handmouse.util.Prefs

private const val TAG = "DexDisplayMonitor"
private const val CATEGORY_SAMSUNG_DESKTOP = "com.samsung.android.hardware.display.category.DESKTOP"

/**
 * Descobre e observa o display externo de desktop. As categorias Samsung `DESKTOP` e Android
 * `PRESENTATION` são usadas no modo automático; a seleção manual permite outros displays públicos.
 * Nunca fixa displayId: os IDs podem mudar
 * quando os displays ou modos são reenumerados. A seleção é feita pela função JVM pura
 * [DexDisplaySelector.select]; esta classe a conecta aos displays Android reais.
 *
 * Cada instância registra seu PRÓPRIO `DisplayManager.DisplayListener` — não existe um monitor
 * global único. `MainActivity` (status somente-leitura) e `HandMouseAccessibilityService` (dono
 * da janela do cursor) têm cada uma a sua própria instância, com o [Context] apropriado a cada
 * papel (ver [windowBounds] sobre por que isso importa: só o a11y service tem permissão de
 * criar uma janela `TYPE_ACCESSIBILITY_OVERLAY` de verdade).
 */
class DexDisplayMonitor(private val context: Context) {

    fun interface Listener {
        /** Called when the selected external display appears, disappears, or changes. */
        fun onDexDisplayChanged(display: Display?)
    }

    var listener: Listener? = null

    var currentDexDisplay: Display? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val prefs = Prefs(context)
    private var started = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_SELECTED_DISPLAY) refresh()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        prefs.registerChangeListener(preferenceListener)
        displayManager.registerDisplayListener(displayListener, mainHandler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        prefs.unregisterChangeListener(preferenceListener)
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun selectableDisplays(): List<Display> =
        DexDisplaySelector.candidates(categorize()).mapNotNull { displayManager.getDisplay(it.displayId) }

    fun displayDiagnostics(): String = displayManager.displays.joinToString("\n") { display ->
        "id=${display.displayId} name=${display.name} valid=${display.isValid} flags=${display.flags}"
    }

    /**
     * Bounds reais (pixels) do [display], via `createDisplayContext` + `createWindowContext` +
     * `currentWindowMetrics`. Recalcular após mudanças de display, pois a resolução pode mudar
     * em runtime. [windowType] deve ser o mesmo tipo de janela que será criado nesse display
     * (`TYPE_ACCESSIBILITY_OVERLAY`); chamar a partir de um [context] que não seja um
     * `AccessibilityService` vinculado pode falhar, retornando `null`.
     */
    fun windowBounds(
        display: Display,
        windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    ): Rect? {
        return try {
            val winCtx = context.createDisplayContext(display).createWindowContext(windowType, null)
            winCtx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao obter bounds do display ${display.displayId}", e)
            null
        }
    }

    private fun refresh() {
        val categorized = categorize()
        val selected = DexDisplaySelector.select(categorized, prefs.selectedDisplayName)
        val display = selected?.let { displayManager.getDisplay(it.displayId) }
        currentDexDisplay = display
        listener?.onDexDisplayChanged(display)
    }

    private fun categorize(): List<CategorizedDisplay> {
        val desktopIds = runCatching { displayManager.getDisplays(CATEGORY_SAMSUNG_DESKTOP) }
            .onFailure { Log.e(TAG, "Failed to query Samsung desktop displays", it) }
            .getOrDefault(emptyArray())
            .map { it.displayId }
            .toSet()
        val presentationIds = runCatching { displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) }
            .onFailure { Log.e(TAG, "Failed to query presentation displays", it) }
            .getOrDefault(emptyArray())
            .map { it.displayId }
            .toSet()
        return displayManager.displays.map { d ->
            CategorizedDisplay(
                displayId = d.displayId,
                isDesktop = d.displayId in desktopIds,
                isPresentation = d.displayId in presentationIds,
                name = d.name,
                isPrivate = d.flags and Display.FLAG_PRIVATE != 0,
                isValid = d.isValid,
            )
        }
    }
}
