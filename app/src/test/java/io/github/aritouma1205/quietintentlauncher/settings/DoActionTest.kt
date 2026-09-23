package io.github.aritouma1205.quietintentlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model constraints of the DO action list (design 6): name 1–24 non-blank,
 * aliases 1–32 each and at most 8, at most 12 actions, at most 6 derived
 * ops, plus the up/down reorder helpers.
 */
class DoActionTest {

    private fun action(name: String = "撮る") = DoAction(name = name)

    // ---- Name boundaries ---------------------------------------------------

    @Test
    fun `name of 0 chars is invalid`() {
        assertFalse(ActionRules.isValidName(""))
    }

    @Test
    fun `name of 1 char is valid`() {
        assertTrue(ActionRules.isValidName("あ"))
    }

    @Test
    fun `name of 24 chars is valid`() {
        assertTrue(ActionRules.isValidName("あ".repeat(24)))
    }

    @Test
    fun `name of 25 chars is invalid`() {
        assertFalse(ActionRules.isValidName("あ".repeat(25)))
    }

    @Test
    fun `blank-only name is invalid`() {
        assertFalse(ActionRules.isValidName("   "))
        assertFalse(ActionRules.isValidName("\t　"))
    }

    // ---- Alias boundaries ----------------------------------------------------

    @Test
    fun `alias of 0 chars is invalid`() {
        assertFalse(ActionRules.isValidAlias(""))
    }

    @Test
    fun `alias of 1 char is valid`() {
        assertTrue(ActionRules.isValidAlias("a"))
    }

    @Test
    fun `alias of 32 chars is valid`() {
        assertTrue(ActionRules.isValidAlias("a".repeat(32)))
    }

    @Test
    fun `alias of 33 chars is invalid`() {
        assertFalse(ActionRules.isValidAlias("a".repeat(33)))
    }

    @Test
    fun `eight aliases pass and nine fail`() {
        val ok = action().copy(aliases = List(8) { "a$it" })
        assertNull(ActionRules.validateAction(ok))
        val tooMany = ok.copy(aliases = ok.aliases + "extra")
        assertEquals(
            ActionValidationError.InvalidAlias,
            ActionRules.validateAction(tooMany),
        )
    }

    @Test
    fun `one invalid alias fails the whole action`() {
        val a = action().copy(aliases = listOf("ok", ""))
        assertEquals(
            ActionValidationError.InvalidAlias,
            ActionRules.validateAction(a),
        )
    }

    // ---- Counts -----------------------------------------------------------------

    @Test
    fun `twelve actions pass and thirteen fail`() {
        val twelve = List(12) { action("a$it") }
        assertNull(ActionRules.validateActions(twelve))
        assertEquals(
            ActionValidationError.TooManyActions,
            ActionRules.validateActions(twelve + action("extra")),
        )
        assertTrue(ActionRules.canAdd(twelve.dropLast(1)))
        assertFalse(ActionRules.canAdd(twelve))
    }

    @Test
    fun `six derived ops pass and seven fail`() {
        val ok = action().copy(derivedOps = List(6) { DerivedOp(label = "op$it") })
        assertNull(ActionRules.validateAction(ok))
        val tooMany = ok.copy(derivedOps = ok.derivedOps + DerivedOp(label = "x"))
        assertEquals(
            ActionValidationError.TooManyDerivedOps,
            ActionRules.validateAction(tooMany),
        )
    }

    // ---- Ordering ----------------------------------------------------------------

    @Test
    fun `moveUp swaps with the previous entry`() {
        val list = listOf(action("a"), action("b"), action("c"))
        val moved = ActionRules.moveUp(list, 1)
        assertEquals(listOf("b", "a", "c"), moved.map { it.name })
    }

    @Test
    fun `moveDown swaps with the next entry`() {
        val list = listOf(action("a"), action("b"), action("c"))
        val moved = ActionRules.moveDown(list, 1)
        assertEquals(listOf("a", "c", "b"), moved.map { it.name })
    }

    @Test
    fun `moves at the bounds are no-ops`() {
        val list = listOf(action("a"), action("b"))
        assertEquals(list, ActionRules.moveUp(list, 0))
        assertEquals(list, ActionRules.moveDown(list, 1))
        assertEquals(list, ActionRules.moveUp(list, -1))
        assertEquals(list, ActionRules.moveDown(list, 5))
    }

    @Test
    fun `filter by id deletes an action`() {
        val list = listOf(action("a"), action("b"))
        val kept = list.filter { it.id != list[0].id }
        assertEquals(listOf("b"), kept.map { it.name })
    }

    // ---- Seeded defaults -----------------------------------------------------------

    @Test
    fun `defaults are the six actions in the designed order`() {
        val defaults = DoActionDefaults.defaults()
        assertEquals(
            listOf("撮る", "話す", "聴く", "見る", "移動する", "調べる"),
            defaults.map { it.name },
        )
        assertTrue(defaults.all { it.target == null })
        assertTrue(defaults.all { it.visible })
        assertEquals(6, defaults.map { it.id }.distinct().size)
        // Deterministic: two default instances must compare equal.
        assertEquals(DoActionDefaults.defaults(), defaults)
        assertNull(ActionRules.validateActions(defaults))
    }
}
