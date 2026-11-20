package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.location.LearningLocationBasis
import com.example.location.LocationSource
import com.example.location.LocationUiState
import com.example.location.SocioLocationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SocioLocationManagerTest {

  private lateinit var context: Context
  private lateinit var locationManager: SocioLocationManager

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences("sociodetective_location_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    locationManager = SocioLocationManager(context)
  }

  @Test
  fun `requiredPermissions includes fine and coarse location`() {
    val permissions = locationManager.requiredPermissions().toSet()
    assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    assertTrue(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
  }

  @Test
  fun `saveManualRegion validates required fields and persists per learning basis`() {
    val invalidResult = locationManager.saveManualRegion(province = "", regencyOrCity = "Kota Bandung")
    assertTrue(invalidResult.isFailure)
    assertTrue(locationManager.uiState.value is LocationUiState.Error)

    val residenceResult = locationManager.saveManualRegion(
      province = "Jawa Barat",
      regencyOrCity = "Kota Bandung",
      district = "Coblong",
      village = "Dago",
      basis = LearningLocationBasis.RESIDENCE,
    )
    assertTrue(residenceResult.isSuccess)
    val savedResidence = locationManager.getSavedRegion(LearningLocationBasis.RESIDENCE)
    assertNotNull(savedResidence)
    assertEquals("Jawa Barat", savedResidence?.province)
    assertEquals("Kota Bandung", savedResidence?.regencyOrCity)
    assertEquals("Coblong", savedResidence?.district)
    assertEquals("Dago", savedResidence?.village)
    assertEquals(LocationSource.MANUAL, savedResidence?.source)

    val schoolResult = locationManager.saveManualRegion(
      province = "DKI Jakarta",
      regencyOrCity = "Jakarta Selatan",
      basis = LearningLocationBasis.SCHOOL,
    )
    assertTrue(schoolResult.isSuccess)
    assertEquals(LearningLocationBasis.SCHOOL, locationManager.activeBasis.value)
    assertEquals("Jakarta Selatan", locationManager.getSavedRegion(LearningLocationBasis.SCHOOL)?.regencyOrCity)
    assertEquals("Kota Bandung", locationManager.getSavedRegion(LearningLocationBasis.RESIDENCE)?.regencyOrCity)
  }

  @Test
  fun `fetchCurrentGpsLocation without permission transitions gracefully to ManualEntryRequired`() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    Shadows.shadowOf(app).denyPermissions(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    assertFalse(locationManager.hasLocationPermission())

    val result = locationManager.fetchCurrentGpsLocation(LearningLocationBasis.RESIDENCE)
    assertTrue(result.isFailure)
    assertTrue(locationManager.uiState.value is LocationUiState.ManualEntryRequired)
  }
}
