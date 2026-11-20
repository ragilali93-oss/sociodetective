package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

enum class LearningLocationBasis(val key: String) {
  RESIDENCE("rumah"),
  SCHOOL("sekolah");

  companion object {
    fun fromKey(key: String?): LearningLocationBasis =
      entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: RESIDENCE
  }
}

enum class LocationSource {
  GPS,
  MANUAL,
}

data class RegionAddress(
  val province: String,
  val regencyOrCity: String,
  val district: String = "",
  val village: String = "",
  val latitude: Double? = null,
  val longitude: Double? = null,
  val source: LocationSource = LocationSource.MANUAL,
)

sealed interface LocationUiState {
  data object Idle : LocationUiState

  data object RequestingPermission : LocationUiState

  data object FetchingGps : LocationUiState

  data class ManualEntryRequired(val reason: String) : LocationUiState

  data class Resolved(
    val region: RegionAddress,
    val basis: LearningLocationBasis,
  ) : LocationUiState

  data class Error(val message: String) : LocationUiState
}

/**
 * Manages location acquisition via Android Location Services (FusedLocationProviderClient + Geocoder)
 * as well as transparent manual region entry and persistence for residence/school learning modes.
 */
class SocioLocationManager(
  private val context: Context,
  private val fusedLocationClient: FusedLocationProviderClient =
    LocationServices.getFusedLocationProviderClient(context),
) {
  private val prefs =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _activeBasis = MutableStateFlow(
    LearningLocationBasis.fromKey(prefs.getString(KEY_ACTIVE_BASIS, null)),
  )
  val activeBasis: StateFlow<LearningLocationBasis> = _activeBasis.asStateFlow()

  private val _uiState = MutableStateFlow<LocationUiState>(
    getSavedRegion(_activeBasis.value)?.let {
      LocationUiState.Resolved(it, _activeBasis.value)
    } ?: LocationUiState.Idle,
  )
  val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

  fun requiredPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
  )

  fun hasLocationPermission(): Boolean =
    hasFineLocationPermission() || hasCoarseLocationPermission()

  fun hasFineLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

  fun hasCoarseLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

  fun isLocationServicesEnabled(): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching {
      lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)
  }

  fun markRequestingPermission() {
    _uiState.value = LocationUiState.RequestingPermission
  }

  fun onPermissionDenied() {
    _uiState.value = LocationUiState.ManualEntryRequired(
      "Izin lokasi tidak diberikan. Tidak masalah—silakan masukkan wilayah tempat tinggal atau sekolah secara manual.",
    )
  }

  fun setLearningBasis(basis: LearningLocationBasis) {
    _activeBasis.value = basis
    prefs.edit().putString(KEY_ACTIVE_BASIS, basis.key).apply()
    val saved = getSavedRegion(basis)
    _uiState.value = if (saved != null) {
      LocationUiState.Resolved(saved, basis)
    } else {
      LocationUiState.ManualEntryRequired(
        "Wilayah untuk mode ${if (basis == LearningLocationBasis.SCHOOL) "Sekolah" else "Tempat Tinggal"} belum diatur.",
      )
    }
  }

  fun getSavedRegion(basis: LearningLocationBasis = _activeBasis.value): RegionAddress? {
    val raw = prefs.getString(regionPrefKey(basis), null) ?: return null
    return runCatching {
      val json = JSONObject(raw)
      RegionAddress(
        province = json.optString("province", ""),
        regencyOrCity = json.optString("regencyOrCity", ""),
        district = json.optString("district", ""),
        village = json.optString("village", ""),
        latitude = if (json.has("latitude") && !json.isNull("latitude")) json.getDouble("latitude") else null,
        longitude = if (json.has("longitude") && !json.isNull("longitude")) json.getDouble("longitude") else null,
        source = runCatching {
          LocationSource.valueOf(json.optString("source", LocationSource.MANUAL.name))
        }.getOrDefault(LocationSource.MANUAL),
      )
    }.getOrNull()?.takeIf { it.province.isNotBlank() && it.regencyOrCity.isNotBlank() }
  }

  /**
   * Saves a user-entered (or GPS-confirmed) region locally for the chosen learning basis.
   * Requires at least [province] and [regencyOrCity] to be non-blank.
   */
  fun saveManualRegion(
    province: String,
    regencyOrCity: String,
    district: String = "",
    village: String = "",
    basis: LearningLocationBasis = _activeBasis.value,
    latitude: Double? = null,
    longitude: Double? = null,
    source: LocationSource = if (latitude != null && longitude != null) LocationSource.GPS else LocationSource.MANUAL,
  ): Result<RegionAddress> {
    val cleanProvince = province.trim()
    val cleanRegency = regencyOrCity.trim()
    val cleanDistrict = district.trim()
    val cleanVillage = village.trim()

    if (cleanProvince.isBlank() || cleanRegency.isBlank()) {
      val errorMsg = "Provinsi dan Kabupaten/Kota wajib diisi untuk menyimpan wilayah."
      _uiState.value = LocationUiState.Error(errorMsg)
      return Result.failure(IllegalArgumentException(errorMsg))
    }

    val region = RegionAddress(
      province = cleanProvince,
      regencyOrCity = cleanRegency,
      district = cleanDistrict,
      village = cleanVillage,
      latitude = latitude,
      longitude = longitude,
      source = source,
    )

    val json = JSONObject().apply {
      put("province", region.province)
      put("regencyOrCity", region.regencyOrCity)
      put("district", region.district)
      put("village", region.village)
      if (region.latitude != null) put("latitude", region.latitude)
      if (region.longitude != null) put("longitude", region.longitude)
      put("source", region.source.name)
    }

    _activeBasis.value = basis
    prefs.edit()
      .putString(KEY_ACTIVE_BASIS, basis.key)
      .putString(regionPrefKey(basis), json.toString())
      .apply()

    _uiState.value = LocationUiState.Resolved(region, basis)
    return Result.success(region)
  }

  /**
   * Requests a one-shot device location via FusedLocationProviderClient and attempts
   * reverse-geocoding down to village/district/regency/province levels.
   * Gracefully emits ManualEntryRequired if permissions are absent or GPS is unavailable.
   */
  @SuppressLint("MissingPermission")
  suspend fun fetchCurrentGpsLocation(
    basis: LearningLocationBasis = _activeBasis.value,
  ): Result<RegionAddress> {
    if (!hasLocationPermission()) {
      onPermissionDenied()
      return Result.failure(SecurityException("Location permission has not been granted."))
    }

    if (!isLocationServicesEnabled()) {
      val msg = "GPS/Layanan lokasi perangkat nonaktif. Kamu tetap bisa memasukkan wilayah secara manual."
      _uiState.value = LocationUiState.ManualEntryRequired(msg)
      return Result.failure(IllegalStateException(msg))
    }

    _uiState.value = LocationUiState.FetchingGps

    val priority = if (hasFineLocationPermission()) {
      Priority.PRIORITY_HIGH_ACCURACY
    } else {
      Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }

    val cts = CancellationTokenSource()
    val location: Location? = runCatching {
      fusedLocationClient.getCurrentLocation(priority, cts.token).awaitNullable()
        ?: fusedLocationClient.lastLocation.awaitNullable()
    }.getOrNull()

    if (location == null) {
      val msg = "Koordinat GPS belum dapat dideteksi saat ini. Silakan isi wilayah secara manual."
      _uiState.value = LocationUiState.ManualEntryRequired(msg)
      return Result.failure(IllegalStateException(msg))
    }

    val resolved = reverseGeocode(location.latitude, location.longitude)
    if (resolved.province.isNotBlank() && resolved.regencyOrCity.isNotBlank()) {
      saveManualRegion(
        province = resolved.province,
        regencyOrCity = resolved.regencyOrCity,
        district = resolved.district,
        village = resolved.village,
        basis = basis,
        latitude = resolved.latitude,
        longitude = resolved.longitude,
        source = LocationSource.GPS,
      )
    } else {
      _uiState.value = LocationUiState.ManualEntryRequired(
        "Koordinat GPS terdeteksi (${resolved.latitude}, ${resolved.longitude}), namun detail wilayah administratif perlu dilengkapi manual.",
      )
    }

    return Result.success(resolved)
  }

  private suspend fun reverseGeocode(latitude: Double, longitude: Double): RegionAddress {
    val lat = (Math.round(latitude * 100000.0) / 100000.0)
    val lon = (Math.round(longitude * 100000.0) / 100000.0)

    if (!Geocoder.isPresent()) {
      return RegionAddress(
        province = "",
        regencyOrCity = "",
        latitude = lat,
        longitude = lon,
        source = LocationSource.GPS,
      )
    }

    val geocoder = Geocoder(context, Locale.forLanguageTag("id-ID"))
    val address: Address? = withContext(Dispatchers.IO) {
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
              if (cont.isActive) cont.resume(addresses.firstOrNull())
            }
          }
        } else {
          @Suppress("DEPRECATION")
          geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }
      }.getOrNull()
    }

    return RegionAddress(
      province = address?.adminArea.orEmpty().trim(),
      regencyOrCity = (address?.subAdminArea ?: address?.locality).orEmpty().trim(),
      district = (address?.locality ?: address?.subLocality).orEmpty().trim(),
      village = (address?.subLocality ?: address?.thoroughfare).orEmpty().trim(),
      latitude = lat,
      longitude = lon,
      source = LocationSource.GPS,
    )
  }

  private suspend fun <T> Task<T>.awaitNullable(): T? =
    suspendCancellableCoroutine { cont ->
      addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
      }
      addOnFailureListener {
        if (cont.isActive) cont.resume(null)
      }
      addOnCanceledListener {
        if (cont.isActive) cont.resume(null)
      }
    }

  companion object {
    private const val PREFS_NAME = "sociodetective_location_prefs"
    private const val KEY_ACTIVE_BASIS = "active_basis"

    private fun regionPrefKey(basis: LearningLocationBasis): String = "region_${basis.key}"
  }
}
