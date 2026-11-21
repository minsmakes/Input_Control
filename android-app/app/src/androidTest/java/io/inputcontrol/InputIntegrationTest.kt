package io.inputcontrol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.inputcontrol.nativebridge.Native
import io.inputcontrol.preferences.Option
import io.inputcontrol.preferences.Preferences
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class InputIntegrationTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()

    private fun lastEvent(expected: String) {
        activity.waitUntil(5000) {
            Native.snapshot().lineSequence().elementAt(1).contains(expected)
        }
    }

    @Test
    fun keyFeedbackKeepsBoundsAndReleasesOnCancel() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        val esc = activity.onNodeWithText("Esc")
        fun count() = Native.snapshot().lineSequence().first().toLong()
        val initial = count()
        val bounds = esc.fetchSemanticsNode().boundsInRoot
        esc.performTouchInput { down(center) }
        activity.waitUntil(3000) { count() == initial + 1 }
        org.junit.Assert.assertEquals(bounds, esc.fetchSemanticsNode().boundsInRoot)
        esc.performTouchInput { cancel() }
        activity.waitUntil(3000) { count() == initial + 2 }
        esc.performTouchInput {
            repeat(12) {
                click(center)
                advanceEventTime(12)
            }
        }
        activity.waitUntil(3000) { count() == initial + 26 }
        val tab = activity.onNodeWithText("Tab").fetchSemanticsNode().boundsInRoot.center
        activity.onRoot().performTouchInput {
            down(0, bounds.center)
            down(1, tab)
        }
        activity.waitUntil(3000) { count() == initial + 28 }
        activity.onRoot().performTouchInput {
            up(1)
            up(0)
        }
        activity.waitUntil(3000) { count() == initial + 30 }
    }

    @Test
    fun appearanceRetainsLayoutAndKeyboardMode() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        activity.onNodeWithText("?123").performClick()
        val bounds = activity.onNodeWithText("Space").fetchSemanticsNode().boundsInRoot
        val vm = androidx.lifecycle.ViewModelProvider(activity.activity)[RemoteModel::class.java]
        val prior = vm.config.value
        val theme = io.inputcontrol.ui.components.finishes.first { it.id != prior[Option.Theme] }
        try {
            activity.onNodeWithContentDescription("Navigation menu").performClick()
            activity.onNodeWithText("Themes & Appearance").performClick()
            activity.onNodeWithText(theme.title).performScrollTo().performClick()
            activity.onNodeWithContentDescription("Show Decorations").performClick()
            activity.onNodeWithText("Apply").performClick()
            activity.waitUntil(3000) {
                vm.config.value[Option.Theme] == theme.id &&
                    vm.config.value.on(Option.Decorations) != prior.on(Option.Decorations)
            }
            activity.onNodeWithText("ABC").assertExists()
            org.junit.Assert.assertEquals(
                bounds,
                activity.onNodeWithText("Space").fetchSemanticsNode().boundsInRoot,
            )
        } finally {
            runBlocking {
                val store = Preferences(activity.activity.applicationContext)
                store.put(Option.Theme, prior[Option.Theme])
                store.put(Option.Decorations, prior[Option.Decorations])
            }
        }
    }

    @Test
    fun koreanKeyboardRoundTrip() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        activity.onNodeWithText("한/영").performClick()
        listOf("ㅎ", "ㅏ", "ㄴ").forEach { activity.onNodeWithText(it).performClick() }
        activity.onNodeWithText("한").assertExists()
        activity.activityRule.scenario.recreate()
        activity.onNodeWithText("한").assertExists()
        activity.onNodeWithText("한/영").performClick()
        lastEvent("TextCommit")
        activity.onNodeWithText("g").assertExists()
    }

    @Test
    fun dashboardSurvivesModeChanges() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        activity.onNodeWithText("?123").performClick()
        activity.onNodeWithText("₩").assertExists()
        activity.onNodeWithContentDescription("Switch to touchpad").performClick()
        activity.onNodeWithText("Touchpad").assertExists()
        activity.onNodeWithContentDescription("Previous track").assertExists()
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        activity.onNodeWithText("ABC").performClick()
        activity.onNodeWithText("?123").assertExists()
    }

    @Test
    fun launcherAndProfilesSavePerPeer() {
        val vm = androidx.lifecycle.ViewModelProvider(activity.activity)[RemoteModel::class.java]
        val pc = io.inputcontrol.connection.Device("Test PC", "localhost", pin = "test-catalog")
        val pins = io.inputcontrol.preferences.Option.DashboardPins
        val profiles = io.inputcontrol.preferences.Option.ShortcutProfiles
        activity.runOnIdle {
            vm.save(pins, "{}")
            vm.save(profiles, "{}")
            vm.devices.active.value = pc
            vm.devices.status.value = io.inputcontrol.connection.Status.Connected
            vm.devices.apps.value =
                listOf(io.inputcontrol.connection.PcApp(21, "Test Editor", "editor.exe", ""))
            vm.devices.media.value = io.inputcontrol.connection.PcMedia(foreground = "editor.exe")
        }
        activity.onNodeWithText("Edit…").performClick()
        activity.onNodeWithText("Test Editor").performScrollTo().performClick()
        activity.onNodeWithText("Save").performClick()
        activity.waitUntil(5000) { vm.config.value[pins].contains("[21]") }
        activity.onNodeWithText("Test Editor").assertExists()
        activity.onNodeWithContentDescription("Edit shortcut profiles").performClick()
        activity.onNode(hasSetTextAction()).performTextReplacement("Select all = Ctrl+A")
        activity.onNodeWithText("Save").performClick()
        activity.waitUntil(5000) { vm.config.value[profiles].contains("Select all") }
        activity.onNodeWithText("Select all").assertExists()
        activity.runOnIdle { vm.devices.active.value = pc.copy(pin = "other") }
        activity.onNodeWithText("Select all").assertDoesNotExist()
        activity.onNodeWithText("Test Editor").assertDoesNotExist()
    }

    @Test
    fun shiftAndSymbols() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        fun tap(s: String) = activity.onNodeWithText(s).performClick()
        tap("Shift")
        tap("A")
        activity.onNodeWithText("b").assertExists()
        activity.onNodeWithText("Shift").performTouchInput { doubleClick() }
        tap("A")
        tap("B")
        activity.onNodeWithText("C").assertExists()
        tap("⇪ Caps")
        activity.onNodeWithText("a").assertExists()
        tap("Shift")
        tap("!")
        activity.onNodeWithText("1").assertExists()
        tap("?123")
        tap("~")
        tap("ABC")
        activity.waitForIdle()
        activity.onNodeWithText("1").performTouchInput { longClick() }
        val vm = androidx.lifecycle.ViewModelProvider(activity.activity)[RemoteModel::class.java]
        tap("Ctrl")
        tap("Shift")
        tap("A")
        org.junit.Assert.assertEquals(ShiftMode.OFF, vm.shift.value)
        activity.runOnIdle { vm.devices.disconnect() }
        org.junit.Assert.assertTrue(vm.modifiers.value.isEmpty())
        tap("한/영")
        tap("Shift")
        tap("ㄲ")
        activity.onNodeWithText("ㄱ").assertExists()
    }

    @Test
    fun focusRespectsManualOverride() {
        val vm = androidx.lifecycle.ViewModelProvider(activity.activity)[RemoteModel::class.java]
        fun focus(value: Long) {
            activity.runOnIdle { vm.devices.focus.value = value }
        }
        fun mode(label: String) {
            activity.waitUntil(4000) {
                activity.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
        }
        focus(258)
        mode("Switch to keyboard")
        focus(513)
        mode("Switch to touchpad")
        activity.onNodeWithContentDescription("Switch to touchpad").performClick()
        focus(513)
        mode("Switch to keyboard")
        focus(770)
        focus(1025)
        mode("Switch to touchpad")
    }

    @Test
    fun fingerDriftDoesNotScrollKeyboard() {
        activity.onNodeWithContentDescription("Switch to keyboard").performClick()
        val key = activity.onNodeWithText("g")
        val before = key.onParent().fetchSemanticsNode().boundsInRoot
        key.performTouchInput { swipe(center, center.copy(y = center.y - 65f), 200) }
        org.junit.Assert.assertEquals(before, key.onParent().fetchSemanticsNode().boundsInRoot)
        activity.onNodeWithText("Shift").performClick()
        org.junit.Assert.assertEquals(
            before,
            activity.onNodeWithText("G").onParent().fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun disconnectCancelsAnActiveBackspaceRepeat() {
        val vm = androidx.lifecycle.ViewModelProvider(activity.activity)[RemoteModel::class.java]
        fun count() = Native.snapshot().substringBefore('\n').toLong()
        val initial = count()
        activity.runOnIdle { vm.pressKey(42, "") }
        activity.waitUntil(3000) { count() >= initial + 4 }
        activity.runOnIdle { vm.devices.disconnect() }
        Thread.sleep(150)
        val stopped = count()
        Thread.sleep(550)
        org.junit.Assert.assertEquals(stopped, count())
        activity.runOnIdle { vm.endKey(42) }
    }

    @Test
    fun nicknamePersistsByIdentity() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val id = io.inputcontrol.connection.Identity(context)
        val d =
            io.inputcontrol.connection.Device(
                "Original",
                "192.168.1.1",
                pin = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2),
            )
        try {
            id.remember(d)
            id.rename(d.pin, "내 Galaxy Book")
            id.remember(d.copy(host = "192.168.1.2"))
            val saved =
                io.inputcontrol.connection.Identity(context).peers().single { it.pin == d.pin }
            org.junit.Assert.assertEquals("내 Galaxy Book", saved.displayName)
            org.junit.Assert.assertEquals(d.name, saved.name)
            id.rename(d.pin, "")
            org.junit.Assert.assertEquals(d.name, id.peers().single { it.pin == d.pin }.displayName)
        } finally {
            id.forget(d.pin)
        }
    }

    @Test
    fun gesturesReachNativeEngine() {
        val pad = activity.onNodeWithTag("touchpad")
        pad.performTouchInput { click(center) }
        lastEvent("LeftUp")
        pad.performTouchInput { swipe(center, center + Offset(140f, 0f), 120) }
        lastEvent("PointerMove")
        pad.performTouchInput {
            down(0, center)
            down(1, center + Offset(80f, 0f))
            up(1)
            up(0)
        }
        lastEvent("RightUp")
        pad.performTouchInput {
            down(0, center)
            down(1, center + Offset(80f, 0f))
            updatePointerTo(0, center + Offset(0f, 80f))
            updatePointerTo(1, center + Offset(80f, 80f))
            move()
            up(1)
            up(0)
        }
        lastEvent("Scroll")
        for (outward in listOf(true, false)) {
            pad.performTouchInput {
                val from = if (outward) 30f else 130f
                val to = 160f - from
                down(0, center - Offset(from, 0f))
                down(1, center + Offset(from, 0f))
                updatePointerTo(0, center - Offset(to, 0f))
                updatePointerTo(1, center + Offset(to, 0f))
                move()
                up(1)
                up(0)
            }
            lastEvent("Zoom")
        }
        pad.performTouchInput {
            click(center)
            advanceEventTime(60)
            down(center)
            moveTo(center + Offset(100f, 0f))
        }
        lastEvent("PointerMove")
        pad.performTouchInput { up() }
        lastEvent("LeftUp")
    }
}
