package com.example

import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.visual.NormalizedCoordinates
import com.example.visual.VisualAction
import com.example.visual.VisualActionPlanner
import com.example.visual.VisualActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TapPrecisionTest {

    private lateinit var actionPlanner: VisualActionPlanner

    @Before
    fun setup() {
        actionPlanner = VisualActionPlanner(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun testExtractActionTarget() {
        assertEquals("Install", actionPlanner.extractActionTarget("tap on Install"))
        assertEquals("Search", actionPlanner.extractActionTarget("click on Search"))
        assertEquals("Start", actionPlanner.extractActionTarget("press on Start"))
        assertEquals("BR Mode", actionPlanner.extractActionTarget("BR Mode पर क्लिक करो"))
        assertEquals("Confirm", actionPlanner.extractActionTarget("Confirm दबाओ"))
        assertEquals("Settings", actionPlanner.extractActionTarget("Settings खोलो"))
        assertEquals("Subscribe", actionPlanner.extractActionTarget("Subscribe"))
    }

    @Test
    fun testNormalizedCoordinatesValidation() {
        // Valid coordinates
        val validCoords = NormalizedCoordinates(0.5f, 0.5f)
        assertTrue(validCoords.isValid())

        val edgeCoords = NormalizedCoordinates(0.05f, 0.95f)
        assertTrue(edgeCoords.isValid())

        // Out of bounds / invalid dummy coordinates
        val negativeCoords = NormalizedCoordinates(-1f, -1f)
        assertFalse(negativeCoords.isValid())

        val zeroCoords = NormalizedCoordinates(0.0f, 0.0f)
        assertFalse(zeroCoords.isValid())
    }

    @Test
    fun testVisualActionDefaultCoordinatesDoNotTemptRandomTaps() {
        // Default VisualAction should not have valid coordinates (should be -1f)
        val defaultAction = VisualAction(
            type = VisualActionType.TAP,
            target = "Button",
            confidence = 0.9f
        )
        assertFalse(defaultAction.hasValidCoordinates())
        assertNull(defaultAction.getNormalizedCoordinates())

        // Explicit valid coordinates
        val actionWithCoords = VisualAction(
            type = VisualActionType.TAP,
            target = "Start Button",
            confidence = 0.95f,
            normalizedX = 0.82f,
            normalizedY = 0.91f
        )
        assertTrue(actionWithCoords.hasValidCoordinates())
        val norm = actionWithCoords.getNormalizedCoordinates()
        assertEquals(0.82f, norm?.x ?: 0f, 0.001f)
        assertEquals(0.91f, norm?.y ?: 0f, 0.001f)
    }

    @Test
    fun testDeviceCoordinateConversion() {
        val norm = NormalizedCoordinates(0.5f, 0.25f)
        val screenWidth = 1080
        val screenHeight = 2400
        val device = norm.toDeviceCoordinates(screenWidth, screenHeight)

        assertEquals(540f, device.x, 0.1f)
        assertEquals(600f, device.y, 0.1f)
    }

    @Test
    fun testPrecisionCoordinateMapper() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mapper = com.example.visual.PrecisionCoordinateMapper(context)

        // Test safe center calculation
        val bounds = Rect(100, 200, 300, 400)
        val center = mapper.getSafeCenter(bounds)
        assertEquals(200f, center.x, 0.1f)
        assertEquals(300f, center.y, 0.1f)

        // Test inside bounds check
        assertTrue(mapper.isInsideBounds(center, bounds))
        assertFalse(mapper.isInsideBounds(com.example.visual.DeviceCoordinates(50f, 50f), bounds))

        // Test screenshot mapping
        val mappedFromShot = mapper.fromScreenshotCoordinates(
            screenshotX = 230f,
            screenshotY = 512f,
            screenshotWidth = 460,
            screenshotHeight = 1024
        )
        val dims = mapper.getPhysicalDimensions()
        assertEquals((dims.width * 0.5f), mappedFromShot.x, 1.0f)
        assertEquals((dims.height * 0.5f), mappedFromShot.y, 1.0f)
    }

    @Test
    fun testKeyboardActionValues() {
        val actions = com.example.visual.KeyboardAction.values()
        assertTrue(actions.contains(com.example.visual.KeyboardAction.ENTER))
        assertTrue(actions.contains(com.example.visual.KeyboardAction.SEARCH))
        assertTrue(actions.contains(com.example.visual.KeyboardAction.SEND))
        assertTrue(actions.contains(com.example.visual.KeyboardAction.DONE))
        assertTrue(actions.contains(com.example.visual.KeyboardAction.HIDE))
    }
}
