package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.location.LocationCoordinateCheckScreen
import com.example.location.LocationPermissionManager
import com.example.location.LocationPermissionStatus
import com.example.location.rememberLocationPermissionController
import com.example.ui.theme.SocioDetectiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationPermissionManagerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var app: Application
  private lateinit var manager: LocationPermissionManager

  @Before
  fun setup() {
    app = ApplicationProvider.getApplicationContext()
    app.getSharedPreferences("sociodetective_location_permission_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    Shadows.shadowOf(app).denyPermissions(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    manager = LocationPermissionManager(app)
  }

  @Test
  fun `requiredPermissions and contract target fine and coarse location`() {
    val permissions = manager.requiredPermissions().toSet()
    assertEquals(
      setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
      ),
      permissions,
    )
    assertTrue(
      manager.createPermissionsContract() is ActivityResultContracts.RequestMultiplePermissions,
    )
  }

  @Test
  fun `checkBeforeFetchingCoordinates blocks coordinate fetch when denied and allows when coarse or fine granted`() {
    var fetchAttempted = false
    var requiredStatus: LocationPermissionStatus? = null

    manager.checkBeforeFetchingCoordinates(
      onGranted = { fetchAttempted = true },
      onPermissionRequired = { requiredStatus = it },
    )

    assertFalse(fetchAttempted)
    assertEquals(LocationPermissionStatus.NotRequested, requiredStatus)

    Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    manager.checkBeforeFetchingCoordinates(
      onGranted = { fetchAttempted = true },
      onPermissionRequired = { requiredStatus = it },
    )

    assertTrue(fetchAttempted)
    val current = manager.status.value as LocationPermissionStatus.Granted
    assertFalse(current.fineGranted)
    assertTrue(current.coarseGranted)
  }

  @Test
  fun `handlePermissionResult records permanent denial and grants accurately`() {
    val deniedStatus = manager.handlePermissionResult(
      grants = mapOf(
        Manifest.permission.ACCESS_FINE_LOCATION to false,
        Manifest.permission.ACCESS_COARSE_LOCATION to false,
      ),
    )
    assertTrue(deniedStatus is LocationPermissionStatus.Denied)
    assertTrue(manager.hasRequestedBefore())

    val grantedStatus = manager.handlePermissionResult(
      grants = mapOf(
        Manifest.permission.ACCESS_FINE_LOCATION to true,
        Manifest.permission.ACCESS_COARSE_LOCATION to true,
      ),
    )
    assertEquals(
      LocationPermissionStatus.Granted(fineGranted = true, coarseGranted = true),
      grantedStatus,
    )
  }

  @Test
  fun `LocationCoordinateCheckScreen verifies permissions inside Composable before fetching coordinates`() {
    var fetchCount = 0
    var manualCount = 0

    composeTestRule.setContent {
      SocioDetectiveTheme(dynamicColor = false) {
        val controller = rememberLocationPermissionController(permissionManager = manager)
        LocationCoordinateCheckScreen(
          permissionController = controller,
          onFetchCoordinates = { fetchCount += 1 },
          onUseManualLocation = { manualCount += 1 },
        )
      }
    }
    composeTestRule.waitForIdle()

    // Permissions denied initially: prompt button shown, fetch button not shown, fetchCount stays 0
    composeTestRule.onNodeWithTag("grant_location_permission_button").assertIsDisplayed()
    assertEquals(0, fetchCount)

    // Clicking manual fallback works without forcing permission
    composeTestRule.onNodeWithTag("manual_location_fallback_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, manualCount)
    assertEquals(0, fetchCount)

    // Grant permissions and verify Composable screen now allows coordinate fetching
    Shadows.shadowOf(app).grantPermissions(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    composeTestRule.runOnUiThread {
      manager.refreshStatus()
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("fetch_coordinates_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fetch_coordinates_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, fetchCount)
  }
}
