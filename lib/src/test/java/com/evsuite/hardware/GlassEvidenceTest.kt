package com.evsuite.hardware

import com.evsuite.hardware.catalog.ActionType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que la preuve relevée sur une voiture change — et ce qu'elle ne change pas.
 *
 * L'enjeu : `writeProven` parle du projet, `effectProven` parle de la voiture. Confondre les
 * deux rendrait soit les actions vitres définitivement inaccessibles, soit accessibles sur des
 * voitures où rien n'a été constaté.
 */
class GlassEvidenceTest {

    private val generation get() = FirmwareInfo.getGeneration().name

    @After
    fun tearDown() = GlassEvidence.setForTest(null)

    @Test
    fun `sans preuve, effectProven suit writeProven`() {
        GlassEvidence.setForTest(null)
        ActionType.entries.forEach {
            assertTrue("${it.name}", it.writeProven == it.effectProven)
        }
    }

    @Test
    fun `la preuve debloque les actions vitres, et elles seules`() {
        GlassEvidence.setForTest(GlassEvidence.Proof(5, 2, generation, 0L))

        GlassEvidence.ACTIONS.forEach {
            assertFalse("${it.name} reste non prouvée dans le catalogue", it.writeProven)
            assertTrue("${it.name} doit être débloquée par la preuve", it.effectProven)
        }
        // Une preuve sur les vitres ne dit rien d'une autre action : le catalogue reste seul
        // juge partout ailleurs.
        ActionType.entries.filterNot { it in GlassEvidence.ACTIONS }.forEach {
            assertTrue("${it.name}", it.writeProven == it.effectProven)
        }
    }

    @Test
    fun `une preuve d'un autre firmware ne vaut rien`() {
        // Les commandes sont propres au SDK de la génération : ce qui ouvre sur l'une n'a
        // aucune raison d'ouvrir sur l'autre, et une mise à jour ne doit pas laisser derrière
        // elle une autorisation qui n'a plus de fondement.
        GlassEvidence.setForTest(GlassEvidence.Proof(5, 2, "SOME_OTHER_GEN", 0L))

        GlassEvidence.ACTIONS.forEach {
            assertFalse("${it.name} ne doit pas hériter d'une preuve étrangère", it.effectProven)
        }
    }
}
