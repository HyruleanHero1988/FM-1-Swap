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
        assertEquals(AppIcon.GREEN, AppIcon.fromId("green"))
        assertEquals(AppIcon.NAVY, AppIcon.fromId("Navy"))
    }

    @Test
    fun oldSettingsIdsMoveOntoTheSevenHardwareColours() {
        assertEquals(AppIcon.GRAY, AppIcon.fromId("white"))
        assertEquals(AppIcon.BROWN, AppIcon.fromId("cream"))
        assertEquals(AppIcon.BROWN, AppIcon.fromId("rust"))
        assertEquals(AppIcon.GREEN, AppIcon.fromId("mint"))
        assertEquals(AppIcon.NAVY, AppIcon.fromId("Slate"))
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
        assertEquals(
            listOf(
                "com.fmswitch.icon.Black",
                "com.fmswitch.icon.Purple",
                "com.fmswitch.icon.Green",
                "com.fmswitch.icon.Orange",
                "com.fmswitch.icon.Navy",
                "com.fmswitch.icon.Brown",
                "com.fmswitch.icon.Gray",
            ),
            names,
        )
    }

    @Test
    fun manifestDefaultCountsAsTheBlackAliasUntilChanged() {
        val fresh = AppIcon.entries.associateWith { IconComponentState.DEFAULT }
        assertEquals(setOf(AppIcon.BLACK.component), AppIconSwitch.enabledComponents(fresh))
    }

    @Test
    fun switchingDoesNothingWhenThatAliasIsAlreadyTheLauncher() {
        assertNull(AppIconSwitch.plan(AppIcon.BLACK, setOf(AppIcon.BLACK.component)))
        assertNull(AppIconSwitch.plan(AppIcon.GRAY, setOf(AppIcon.GRAY.component)))
    }

    @Test
    fun switchingEnablesOneAliasAndDisablesTheRest() {
        val plan = AppIconSwitch.plan(AppIcon.GREEN, setOf(AppIcon.BLACK.component))
        checkNotNull(plan)
        assertEquals(AppIcon.GREEN.component, plan.first)
        assertEquals(AppIcon.entries.filter { it != AppIcon.GREEN }.map { it.component }, plan.second)
        assertTrue(AppIcon.BLACK.component in plan.second)
    }

    @Test
    fun hardwareColoursStayAShortList() {
        assertEquals(
            listOf("black", "purple", "green", "orange", "navy", "brown", "gray"),
            AppIcon.entries.map { it.id },
        )
    }
}
