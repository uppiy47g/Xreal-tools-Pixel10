package com.raphael.handmouse.tracking

/**
 * Máquina de estados clique-vs-drag (PLANO.md Fase 4/5, brief Tarefa 5). Classe PURA (sem
 * dependência de Android) — 1 instância no [CursorPipeline].
 *
 * ```
 * IDLE --pinchDown--> PRESSED
 * PRESSED --pinchUp (< 300ms)--> IDLE                (emite Click)
 * PRESSED --300ms ainda pinçado--> DRAGGING          (emite DragStart)
 * DRAGGING --positionUpdate--> DRAGGING              (emite DragMove)
 * DRAGGING --pinchUp--> IDLE                         (emite DragEnd)
 * ```
 *
 * ## Decisão de assinatura (ver Javadoc de `ClickDragStateMachineTest` pro detalhe completo)
 * [pinchDown] recebe `x,y` — é o chamador ([CursorPipeline]) quem resolve a posição CONGELADA
 * (via [PositionHistory.positionAt], ~100ms atrás) antes de invocar. Essa posição fica fixa
 * (`downX`/`downY`) e é reusada tanto em [Action.Click] quanto em [Action.DragStart] — o drag
 * "nasce" no mesmo ponto congelado que um clique nasceria, só diverge dali em diante conforme
 * [positionUpdate] traz a posição AO VIVO da mão. [pinchUp] não recebe posição: [Action.DragEnd]
 * usa a última posição ao vivo conhecida (`lastX`/`lastY`, atualizada por [positionUpdate]).
 *
 * O limiar de 300ms é checado tanto em [positionUpdate] (o "tick" normal, ~30fps) quanto,
 * independentemente, em [pinchUp] — assim um chamador que não tique [positionUpdate] durante
 * todo o hold (ex.: dead-zone suprimindo frames com a mão parada) ainda promove corretamente
 * pra Drag no momento do UP, em vez de emitir um Click indevido depois de >300ms pinçado.
 */
class ClickDragStateMachine {

    companion object {
        /** Hold ainda pinçado além deste limiar vira drag; abaixo dele, o pinch é um clique.
         * Limiar INCLUSIVO — exatamente no valor já conta como drag (`elapsed >= HOLD_THRESHOLD_MS`).
         *
         * **Ajuste de hardware (2026-07-22):** subido de 300ms → 700ms. Em hardware, um pinch de
         * clique deliberado (juntar/separar os dedos, gesto inerentemente mais lento e menos
         * preciso que um clique de mouse) passava fácil dos 300ms e era classificado como drag —
         * e como o drag via `continueStroke` é cancelado pelo sistema no display secundário do DeX
         * (One UI), o resultado era "nenhum clique acontece". 700ms deixa o clique confortável e
         * reserva o drag pra um hold claramente intencional.
         *
         * **Ajuste de hardware (2026-07-23):** descido de 700ms → 500ms ("demora pra reconhecer o
         * drag"). Os 700ms eram defesa de quando a LIBERAÇÃO do pinch era lenta (EXIT 0.50,
         * debounce UP=3); com a liberação rápida atual (EXIT 0.38, UP=2) o pinch de clique termina
         * bem antes de 500ms, então dá pra armar o drag mais cedo sem reclassificar cliques.
         *
         * **Ajuste de hardware (2026-07-23, 3ª rodada, "cursor fica travado demais no pinch"):**
         * 500ms → 400ms. À época, o cursor visível ficava CONGELADO durante toda a fase PRESSED
         * (freeze visual — removido em 2026-07-24, ver Javadoc do [CursorPipeline]) e este limiar
         * era a duração máxima desse freeze. Com a inferência a 60fps o pinch confirma DOWN/UP
         * ~2× mais rápido, então um clique deliberado termina em ~200-300ms — 400ms ainda tem
         * folga pra não reclassificar clique como drag. */
        const val HOLD_THRESHOLD_MS = 400L
    }

    /** Fase pública (fix de revisão da Tarefa 5): originalmente exposta pro [CursorPipeline]
     * congelar o cursor visível durante [Phase.PRESSED] (freeze visual — removido em 2026-07-24,
     * ver Javadoc do [CursorPipeline]); segue exposta pro pipeline ler a fase (ex.: gate do
     * thumbs-up, drag órfão no onHandLost). Antes era um `enum class State` privado. */
    enum class Phase { IDLE, PRESSED, DRAGGING }

    sealed class Action {
        data class Click(val x: Float, val y: Float) : Action()
        data class DragStart(val x: Float, val y: Float) : Action()
        data class DragMove(val x: Float, val y: Float) : Action()
        data class DragEnd(val x: Float, val y: Float) : Action()
    }

    /** Fase atual — `IDLE` fora de qualquer gesto, `PRESSED` entre o pinch-down e a resolução
     * (Click ou promoção a Drag), `DRAGGING` durante o arrasto. */
    var phase: Phase = Phase.IDLE
        private set

    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** Pinch confirmado (DOWN) — [x]/[y] já devem ser a posição CONGELADA resolvida pelo
     * chamador. Nunca emite ação por si só. */
    fun pinchDown(x: Float, y: Float, downAtMs: Long): List<Action> {
        phase = Phase.PRESSED
        downX = x
        downY = y
        this.downAtMs = downAtMs
        lastX = x
        lastY = y
        return emptyList()
    }

    /** Atualização de posição AO VIVO da mão (chamada a cada frame, tipicamente). Em [Phase.PRESSED],
     * promove pra [Phase.DRAGGING] (emite [Action.DragStart]) se já passou do limiar; em
     * [Phase.DRAGGING], emite [Action.DragMove]. Sem efeito em [Phase.IDLE]. */
    fun positionUpdate(x: Float, y: Float, atMs: Long): List<Action> {
        lastX = x
        lastY = y

        return when (phase) {
            Phase.IDLE -> emptyList()
            Phase.PRESSED -> {
                if (atMs - downAtMs >= HOLD_THRESHOLD_MS) {
                    phase = Phase.DRAGGING
                    listOf(Action.DragStart(downX, downY))
                } else {
                    emptyList()
                }
            }
            Phase.DRAGGING -> listOf(Action.DragMove(x, y))
        }
    }

    /** Pinch confirmado (UP). `IDLE` -> ignorado (sem DOWN correspondente). `PRESSED` -> `Click`
     * se ainda dentro do limiar, ou promoção tardia pra Drag (`DragStart`+`DragEnd`) se o
     * limiar já passou sem que [positionUpdate] tivesse tickado a transição. `DRAGGING` ->
     * `DragEnd` na última posição ao vivo conhecida. */
    fun pinchUp(upAtMs: Long): List<Action> {
        val actions = when (phase) {
            Phase.IDLE -> emptyList()
            Phase.PRESSED -> {
                if (upAtMs - downAtMs < HOLD_THRESHOLD_MS) {
                    listOf(Action.Click(downX, downY))
                } else {
                    listOf(Action.DragStart(downX, downY), Action.DragEnd(lastX, lastY))
                }
            }
            Phase.DRAGGING -> listOf(Action.DragEnd(lastX, lastY))
        }
        phase = Phase.IDLE
        return actions
    }

    /** Reseta pro estado inicial (chamar quando a mão some — mesmo espírito de
     * [PositionHistory.reset]/[PinchDetector.reset]: nenhum estado transitório sobrevive à
     * perda de tracking). */
    fun reset() {
        phase = Phase.IDLE
        downX = 0f
        downY = 0f
        downAtMs = 0L
        lastX = 0f
        lastY = 0f
    }
}
