package com.raphael.handmouse.input

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.raphael.handmouse.tracking.DisplayBounds

/**
 * Controlador de gestos de MÍDIA — traduz "quero avançar/voltar o vídeo" e "quero mudar o volume"
 * em injeções concretas (via [GestureInjector] pro seek, via [AudioManager] pro volume). Camada
 * fina em cima do [GestureInjector]: mantém a política de mídia (posições do seek, stream de
 * áudio) separada do mecanismo de injeção de gesto. A integração no pipeline/serviço será feita
 * depois por outra pessoa — esta classe só expõe os quatro verbos.
 *
 * ## Seek por duplo-toque — convenção dos players de vídeo
 * [seekForward]/[seekBackward] fazem um duplo-tap ([GestureInjector.doubleTap]) no terço direito /
 * terço esquerdo da tela, que é a convenção do YouTube/Netflix e afins: duplo-toque à DIREITA
 * avança ~10s, à ESQUERDA retrocede ~10s. Usamos 75% / 25% da largura (bem dentro de cada terço,
 * longe da zona central de play/pause) e 50% da altura (meio vertical, evitando as barras de
 * controle de cima/baixo).
 *
 * LIMITAÇÃO ACEITA DE DESIGN (decidida com o usuário): em apps que NÃO são players de vídeo, um
 * duplo-tap nessas coordenadas tem o efeito que aquele app der a um duplo-toque (zoom num mapa,
 * seleção de texto, nada, etc.). Não há como saber genericamente se o app em foco é um player;
 * o gesto de mídia é intencional do usuário, que só o dispara quando está assistindo algo.
 *
 * ## Volume — [AudioManager], sem permissão especial
 * [volumeUp]/[volumeDown] ajustam o [AudioManager.STREAM_MUSIC] (o stream de mídia — vídeo/música)
 * com `ADJUST_RAISE`/`ADJUST_LOWER` e a flag [AudioManager.FLAG_SHOW_UI], que faz o sistema
 * mostrar o slider de volume nativo — o usuário vê o feedback visual do próprio Android. Ajustar
 * o volume por `adjustStreamVolume` NÃO exige permissão especial no manifesto (é uma ação de UI
 * comum de app); só operações como MUTE via `DevicePolicy` ou volume de outros apps exigiriam
 * mais — nada disso é o caso aqui.
 *
 * Não testável em JVM puro (depende de [AudioManager]/[Context] reais do Android e do
 * [GestureInjector], que depende de `AccessibilityService`); toda ação fica logada (brief) pra
 * depuração em hardware, como as demais classes com dependência Android.
 */
class MediaGestureController(
    context: Context,
    private val gestureInjector: GestureInjector,
) {

    companion object {
        private const val TAG = "MediaGestureController"

        /** Fração da largura pro duplo-tap de avançar — terço direito (convenção de player). */
        private const val SEEK_FORWARD_X_FRACTION = 0.75f

        /** Fração da largura pro duplo-tap de retroceder — terço esquerdo (convenção de player). */
        private const val SEEK_BACKWARD_X_FRACTION = 0.25f

        /** Fração da altura pro duplo-tap de seek — meio vertical, longe das barras de controle. */
        private const val SEEK_Y_FRACTION = 0.5f
    }

    // AudioManager resolvido do context no construtor (não guardamos o Context — só o serviço).
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Avança o vídeo: duplo-tap a 75% da largura, 50% da altura, no display de [bounds]. */
    fun seekForward(bounds: DisplayBounds) {
        val x = bounds.width * SEEK_FORWARD_X_FRACTION
        val y = bounds.height * SEEK_Y_FRACTION
        Log.d(TAG, "seekForward: duplo-tap em ($x, $y) display=${bounds.displayId}")
        gestureInjector.doubleTap(x, y, bounds.displayId)
    }

    /** Retrocede o vídeo: duplo-tap a 25% da largura, 50% da altura, no display de [bounds]. */
    fun seekBackward(bounds: DisplayBounds) {
        val x = bounds.width * SEEK_BACKWARD_X_FRACTION
        val y = bounds.height * SEEK_Y_FRACTION
        Log.d(TAG, "seekBackward: duplo-tap em ($x, $y) display=${bounds.displayId}")
        gestureInjector.doubleTap(x, y, bounds.displayId)
    }

    /** Sobe o volume de mídia um passo, com o slider nativo visível (FLAG_SHOW_UI). */
    fun volumeUp() {
        Log.d(TAG, "volumeUp: ADJUST_RAISE em STREAM_MUSIC (FLAG_SHOW_UI)")
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    /** Desce o volume de mídia um passo, com o slider nativo visível (FLAG_SHOW_UI). */
    fun volumeDown() {
        Log.d(TAG, "volumeDown: ADJUST_LOWER em STREAM_MUSIC (FLAG_SHOW_UI)")
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }
}
