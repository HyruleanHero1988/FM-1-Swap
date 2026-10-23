package com.fmswitch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {
    @Test
    fun unknownIdStaysOnTheShippedBlackIcon() {
        assertEquals(AppIcon.BLACK, AppIcon.fromId(null))
        assertEquals(AppIcon.BLACK, AppIcon.fromId(""))
        assertEquals(AppIcon.BLACK, AppIcon.fromId("phosphor"))
        assertEquals(AppIcon.MINT, AppIcon.fromId("mint"))
        assertEquals(AppIcon.SLATE, AppIcon.fromId("Slate"))
    }

    @Test
    fun aliasesAreUniqueAndSitBesideMainActivity() {
        val names = AppIcon.entries.map { it.component }
        assertEquals(names.size, names.toSet().size)
        AppIcon.entries.forEach { icon ->
            assertEquals("com.fmswitch.icon.${icon.alias}", icon.component)
            assertTrue(icon.alias[0].isUpperCase())
        }
        assertEquals("com.fmswitch.icon.Black", AppIcon.BLACK.component)
        assertTrue(AppIcon.BLACK.isDefault)
        assertEquals(1, AppIcon.entries.count { it.isDefault })
    }

    @Test
    fun manifestDefaultCountsAsTheBlackAliasUntilChanged() {
        val fresh = AppIcon.entries.associateWith { IconComponentState.DEFAULT }
        assertEquals(setOf(AppIcon.BLACK.component), AppIconSwitch.enabledComponents(fresh))
    }

    @Test
    fun switchingDoesNothingWhenThatAliasIsAlreadyTheLauncher() {
        assertNull(AppIconSwitch.plan(AppIcon.BLACK, setOf(AppIcon.BLACK.component)))
        assertNull(AppIconSwitch.plan(AppIcon.WHITE, setOf(AppIcon.WHITE.component)))
    }

    @Test
    fun switchingEnablesOneAliasAndDisablesTheRest() {
        val plan = AppIconSwitch.plan(AppIcon.MINT, setOf(AppIcon.BLACK.component))
        checkNotNull(plan)
        assertEquals(AppIcon.MINT.component, plan.first)
        assertEquals(AppIcon.entries.filter { it != AppIcon.MINT }.map { it.component }, plan.second)
        assertTrue(AppIcon.BLACK.component in plan.second)
    }

    @Test
    fun hardwareColoursStayAShortList() {
        assertEquals(
            listOf("black", "white", "cream", "rust", "mint", "orange", "purple", "slate"),
            AppIcon.entries.map { it.id },
        )
    }
}
