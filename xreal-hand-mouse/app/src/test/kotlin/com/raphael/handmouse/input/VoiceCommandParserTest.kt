package com.raphael.handmouse.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD do parser de comandos de voz (2026-07-23, spec `2026-07-23-voice-control-design.md`):
 * frase exata (tabela bilíngue pt/en, normalizada: minúsculas + sem acento) → prefixo
 * "abrir/open" → prefixo "escrever/write" → NoMatch. O texto do ditado e o nome do app
 * preservam a forma ORIGINAL (caixa/acentos) — só o roteamento é normalizado.
 */
class VoiceCommandParserTest {

    @Test
    fun `comandos exatos em portugues com acentos e caixa`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("Voltar"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("início"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("INICIO"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("recentes"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("apps recentes"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("enviar"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("apagar tudo"))
        // "fechar" = HOME (sem API pública pra matar app; decisão 2026-07-23).
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("fechar"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("Fechar."))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("close"))
    }

    @Test
    fun `comandos exatos em ingles`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("back"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("home"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("recents"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("send"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("clear"))
    }

    @Test
    fun `abrir app extrai o nome original`() {
        assertEquals(VoiceCommand.OpenApp("YouTube"), VoiceCommandParser.parse("abrir YouTube"))
        assertEquals(VoiceCommand.OpenApp("o YouTube"), VoiceCommandParser.parse("abre o YouTube"))
        assertEquals(VoiceCommand.OpenApp("chrome"), VoiceCommandParser.parse("open chrome"))
    }

    @Test
    fun `ditado preserva o texto original apos o prefixo`() {
        assertEquals(
            VoiceCommand.Dictate("Bom dia, chego em 10 minutos"),
            VoiceCommandParser.parse("escrever Bom dia, chego em 10 minutos"),
        )
        assertEquals(VoiceCommand.Dictate("olá"), VoiceCommandParser.parse("escreve olá"))
        assertEquals(VoiceCommand.Dictate("hello there"), VoiceCommandParser.parse("write hello there"))
    }

    @Test
    fun `prefixo sem resto nao vira comando`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("abrir"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("escrever "))
    }

    @Test
    fun `frases desconhecidas viram NoMatch`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("fazer um cafe"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse(""))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("   "))
    }

    @Test
    fun `pontuacao do formatter on-device nao quebra comandos`() {
        // 3ª rodada de tuning (2026-07-23): o recognizer on-device com EXTRA_ENABLE_FORMATTING
        // pontua ATÉ comandos ("abrir WhatsApp." em vez de "abrir WhatsApp") — a normalização
        // descarta pontuação no roteamento; o payload segue cru (o matcher re-normaliza).
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("Voltar."))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("Apagar tudo."))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("Enviar!"))
        assertEquals(VoiceCommand.OpenApp("WhatsApp."), VoiceCommandParser.parse("abrir WhatsApp."))
    }

    @Test
    fun `espacos extras sao tolerados`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("  voltar  "))
        assertEquals(VoiceCommand.OpenApp("maps"), VoiceCommandParser.parse("  abrir   maps"))
    }
}
