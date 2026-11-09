package com.raphael.handmouse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Display priority: Samsung DESKTOP > DISPLAY_CATEGORY_PRESENTATION.
 * The selector is pure JVM code (no android.view.Display or Robolectric needed).
 */
class DexDisplaySelectorTest {

    @Test
    fun `manual selection supports uncategorized Pixel desktop`() {
        val desktop = CategorizedDisplay(3, false, false, name = "XREAL")
        val displays = listOf(CategorizedDisplay(0, false, false, name = "Phone"), desktop)

        assertNull(DexDisplaySelector.select(displays))
        assertEquals(desktop, DexDisplaySelector.select(displays, "XREAL"))
    }

    @Test
    fun `manual selection takes precedence over automatic categories`() {
        val chosen = CategorizedDisplay(4, false, false, name = "XREAL")
        val displays = listOf(CategorizedDisplay(2, true, true, name = "Other"), chosen)

        assertEquals(chosen, DexDisplaySelector.select(displays, "XREAL"))
    }

    @Test
    fun `missing manual target never falls back to another display`() {
        assertNull(DexDisplaySelector.select(
            listOf(CategorizedDisplay(2, true, true, name = "Other")), "XREAL"
        ))
    }

    @Test
    fun `manual selection survives display ID changes`() {
        val chosen = CategorizedDisplay(8, false, false, name = "XREAL")
        assertEquals(chosen, DexDisplaySelector.select(listOf(chosen), "XREAL"))
    }

    @Test
    fun `ambiguous manual display names do not select a target`() {
        assertNull(DexDisplaySelector.select(listOf(
            CategorizedDisplay(2, false, false, name = "External"),
            CategorizedDisplay(3, false, false, name = "External"),
        ), "External"))
    }

    @Test
    fun `phone private and invalid displays are excluded even if categorized`() {
        val displays = listOf(
            CategorizedDisplay(0, true, true, name = "Phone"),
            CategorizedDisplay(2, true, true, name = "Private", isPrivate = true),
            CategorizedDisplay(3, true, true, name = "Removed", isValid = false),
        )
        assertEquals(emptyList<CategorizedDisplay>(), DexDisplaySelector.candidates(displays))
        assertNull(DexDisplaySelector.select(displays))
        for (name in listOf("Phone", "Private", "Removed")) {
            assertNull(DexDisplaySelector.select(displays, name))
        }
    }

    @Test
    fun `desktop tem prioridade sobre presentation`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = true),
            CategorizedDisplay(displayId = 2, isDesktop = true, isPresentation = false),
        )

        val selected = DexDisplaySelector.select(displays)

        assertEquals(2, selected?.displayId)
    }

    @Test
    fun `sem desktop cai para presentation`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false),
            CategorizedDisplay(displayId = 3, isDesktop = false, isPresentation = true),
        )

        val selected = DexDisplaySelector.select(displays)

        assertEquals(3, selected?.displayId)
    }

    @Test
    fun `lista vazia retorna null`() {
        assertNull(DexDisplaySelector.select(emptyList()))
    }

    @Test
    fun `nenhum display categorizado como desktop ou presentation retorna null`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false),
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = false),
        )

        assertNull(DexDisplaySelector.select(displays))
    }

    @Test
    fun `remocao do display desktop selecionado cai pro proximo candidato presentation`() {
        val comDesktop = listOf(
            CategorizedDisplay(displayId = 1, isDesktop = true, isPresentation = false),
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true),
        )
        val primeiraSelecao = DexDisplaySelector.select(comDesktop)
        assertEquals(1, primeiraSelecao?.displayId)

        // simula DisplayManager.DisplayListener.onDisplayRemoved: o display 1 some da lista
        val depoisDaRemocao = comDesktop.filterNot { it.displayId == primeiraSelecao?.displayId }
        val segundaSelecao = DexDisplaySelector.select(depoisDaRemocao)

        assertEquals(2, segundaSelecao?.displayId)
    }

    @Test
    fun `remocao do unico display selecionado retorna null`() {
        val apenasPresentation = listOf(
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true),
        )
        val selecionado = DexDisplaySelector.select(apenasPresentation)
        assertEquals(2, selecionado?.displayId)

        val vazioAposRemocao = apenasPresentation.filterNot { it.displayId == selecionado?.displayId }

        assertNull(DexDisplaySelector.select(vazioAposRemocao))
    }
}
