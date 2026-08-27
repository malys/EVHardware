package com.evsuite.hardware

import com.evsuite.hardware.saic.SaicVehicleControl.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que la sonde doit établir sans voiture : l'enchaînement des deux passes et les refus.
 *
 * La vitre simulée reproduit le comportement qui a rendu la sonde nécessaire : le service
 * accepte n'importe quelle commande et n'en applique que deux. Une passe unique ne peut donc
 * pas voir les deux sens — c'est exactement ce que ces tests vérifient.
 */
class GlassProbeTest {

    /** Vitre où [openCommand] ouvre en grand et [closeCommand] referme ; le reste est inerte. */
    private class FakeGlass(
        private val openCommand: Int,
        private val closeCommand: Int,
        var percent: Int = 0,
    ) : GlassProbe.Glass {
        val sent = mutableListOf<Int>()
        var readable = true
        override fun position(window: Window): Int? = percent.takeIf { readable }
        override fun send(window: Window, command: Int): Boolean {
            sent += command
            when (command) {
                openCommand -> percent = 100
                closeCommand -> percent = 0
            }
            return true
        }
    }

    private fun probe(
        glass: GlassProbe.Glass,
        stopped: () -> Boolean = { true },
        ignitionRun: () -> Boolean = { true },
    ) = GlassProbe.run(
        window = Window.DRIVER,
        glass = glass,
        stopped = stopped,
        ignitionRun = ignitionRun,
        sleep = {},
    )

    @Test
    fun `les deux sens sont trouves depuis une vitre fermee`() {
        // Depuis 0 %, la commande qui ferme ne bouge rien : sans seconde passe elle passerait
        // pour inerte, et l'action « fermer » resterait à jamais non prouvée.
        val glass = FakeGlass(openCommand = 5, closeCommand = 2)
        val result = probe(glass)

        assertEquals(5, result.openCommand)
        assertEquals(2, result.closeCommand)
        assertTrue(result.proven)
        assertNull(result.refusal)
        // Repartie de 0 %, la vitre doit y revenir : une sonde qui laisse la vitre ouverte
        // est un défaut, pas un résultat.
        assertTrue(result.restored)
        assertEquals(0, glass.percent)
    }

    @Test
    fun `une commande deja classee n'est pas renvoyee`() {
        val glass = FakeGlass(openCommand = 0, closeCommand = 1)
        val result = probe(glass)

        assertEquals(0, result.openCommand)
        assertEquals(1, result.closeCommand)
        // 0 ouvre, 1 referme, les deux sens sont connus : la sonde s'arrête là, plus une
        // remise en place. Rien au-delà de la commande 1 ne doit avoir été envoyé.
        assertEquals(listOf(0, 1), glass.sent.take(2))
        assertTrue(glass.sent.none { it > 1 })
    }

    @Test
    fun `une vitre inerte ne prouve rien`() {
        val glass = FakeGlass(openCommand = 99, closeCommand = 98)
        val result = probe(glass)

        assertNull(result.openCommand)
        assertNull(result.closeCommand)
        assertEquals(GlassProbe.Refusal.NOTHING_MOVED, result.refusal)
        assertFalse(result.proven)
        // Les huit commandes, deux fois : la seconde passe ne peut être sautée sur la foi de
        // la première, qui ne voit pas les fermetures.
        assertEquals(16, glass.sent.size)
    }

    @Test
    fun `un seul sens trouve n'est pas une preuve`() {
        val glass = FakeGlass(openCommand = 3, closeCommand = 99)
        val result = probe(glass)

        assertEquals(3, result.openCommand)
        assertNull(result.closeCommand)
        assertEquals(GlassProbe.Refusal.ONE_DIRECTION_ONLY, result.refusal)
        // Rien ne peut refermer la vitre : il faut le dire, pas laisser croire au contraire.
        assertFalse(result.restored)
    }

    @Test
    fun `la voiture qui repart interrompt la sonde`() {
        val glass = FakeGlass(openCommand = 5, closeCommand = 2)
        var checks = 0
        // Arrêtée pour la lecture initiale et la première commande, puis en mouvement.
        val result = probe(glass, stopped = { checks++ < 2 })

        assertEquals(GlassProbe.Refusal.NOT_STOPPED, result.refusal)
        assertEquals(1, glass.sent.size)
    }

    @Test
    fun `contact coupe, aucune commande n'est envoyee`() {
        val glass = FakeGlass(openCommand = 5, closeCommand = 2)
        val result = probe(glass, ignitionRun = { false })

        assertEquals(GlassProbe.Refusal.IGNITION_NOT_RUN, result.refusal)
        assertTrue(glass.sent.isEmpty())
    }

    @Test
    fun `une position illisible arrete tout`() {
        val glass = FakeGlass(openCommand = 5, closeCommand = 2).apply { readable = false }
        val result = probe(glass)

        assertEquals(GlassProbe.Refusal.UNREADABLE, result.refusal)
        assertTrue(glass.sent.isEmpty())
    }
}
