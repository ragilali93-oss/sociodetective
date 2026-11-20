package com.example.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LocationPermissionStatus {
  data object NotRequested : LocationPermissionStatus

  data class Granted(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
  ) : LocationPermissionStatus {
    val isPrecise: Boolean get() = fineGranted
  }

  data class NeedsRationale(
    val fineDenied: Boolean,
    val coarseDenied: Boolean,
  ) : LocationPermissionStatus

  data class Denied(
    val permanentlyDenied: Boolean,
  ) : LocationPermissionStatus
}

/**
 * Manages runtime permission checks and requests for fine and coarse location
 * using the standard [ActivityResultContracts.RequestMultiplePermissions] contract.
 */
class LocationPermissionManager(
  private val context: Context,
) {
  private val appContext: Context = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _status = MutableStateFlow(evaluateStatus(context.findActivity()))
  val status: StateFlow<LocationPermissionStatus> = _status.asStateFlow()

  fun requiredPermissions(): Array<String> = LOCATION_PERMISSIONS.copyOf()

  fun createPermissionsContract(): ActivityResultContracts.RequestMultiplePermissions =
    ActivityResultContracts.RequestMultiplePermissions()

  fun hasFineLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

  fun hasCoarseLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

  fun hasAnyLocationPermission(): Boolean =
    hasFineLocationPermission() || hasCoarseLocationPermission()

  fun hasAllLocationPermissions(): Boolean =
    hasFineLocationPermission() && hasCoarseLocationPermission()

  fun shouldShowRationale(activity: Activity? = context.findActivity()): Boolean {
    if (activity == null) return false
    return LOCATION_PERMISSIONS.any { permission ->
      ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
  }

  fun hasRequestedBefore(): Boolean =
    prefs.getBoolean(KEY_HAS_REQUESTED_ONCE, false)

  fun markRequested() {
    prefs.edit().putBoolean(KEY_HAS_REQUESTED_ONCE, true).apply()
  }

  fun refreshStatus(activity: Activity? = context.findActivity()): LocationPermissionStatus {
    val updated = evaluateStatus(activity)
    _status.value = updated
    return updated
  }

  fun handlePermissionResult(
    grants: Map<String, Boolean>,
    activity: Activity? = context.findActivity(),
  ): LocationPermissionStatus {
    markRequested()
    val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocationPermission()
    val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasCoarseLocationPermission()

    val nextStatus = when {
      fineGranted || coarseGranted -> LocationPermissionStatus.Granted(
        fineGranted = fineGranted,
        coarseGranted = coarseGranted,
      )
      shouldShowRationale(activity) -> LocationPermissionStatus.NeedsRationale(
        fineDenied = true,
        coarseDenied = true,
      )
      else -> LocationPermissionStatus.Denied(permanentlyDenied = true)
    }

    _status.value = nextStatus
    return nextStatus
  }

  /**
   * Verifies fine or coarse location permission before attempting to fetch user coordinates.
   */
  fun checkBeforeFetchingCoordinates(
    activity: Activity? = context.findActivity(),
    onGranted: (LocationPermissionStatus.Granted) -> Unit,
    onPermissionRequired: (LocationPermissionStatus) -> Unit,
  ) {
    when (val current = refreshStatus(activity)) {
      is LocationPermissionStatus.Granted -> onGranted(current)
      else -> onPermissionRequired(current)
    }
  }

  fun createAppSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", appContext.packageName, null)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  private fun evaluateStatus(activity: Activity?): LocationPermissionStatus {
    val fineGranted = hasFineLocationPermission()
    val coarseGranted = hasCoarseLocationPermission()
    return when {
      fineGranted || coarseGranted -> LocationPermissionStatus.Granted(
        fineGranted = fineGranted,
        coarseGranted = coarseGranted,
      )
      shouldShowRationale(activity) -> LocationPermissionStatus.NeedsRationale(
        fineDenied = !fineGranted,
        coarseDenied = !coarseGranted,
      )
      hasRequestedBefore() -> LocationPermissionStatus.Denied(permanentlyDenied = true)
      else -> LocationPermissionStatus.NotRequested
    }
  }

  companion object {
    val LOCATION_PERMISSIONS: Array<String> = arrayOf(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private const val PREFS_NAME = "sociodetective_location_permission_prefs"
    private const val KEY_HAS_REQUESTED_ONCE = "has_requested_location_once"
  }
}

@Stable
class LocationPermissionController(
  val manager: LocationPermissionManager,
  val status: LocationPermissionStatus,
  private val activity: Activity?,
  private val launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
) {
  fun hasAnyLocationPermission(): Boolean = manager.hasAnyLocationPermission()

  fun requestPermissions() {
    manager.markRequested()
    launcher.launch(manager.requiredPermissions())
  }

  fun checkBeforeFetchingCoordinates(
    onGranted: (LocationPermissionStatus.Granted) -> Unit,
    onPermissionRequired: (LocationPermissionStatus) -> Unit,
  ) {
    manager.checkBeforeFetchingCoordinates(
      activity = activity,
      onGranted = onGranted,
      onPermissionRequired = onPermissionRequired,
    )
  }

  fun openAppSettings(context: Context) {
    context.startActivity(manager.createAppSettingsIntent())
  }
}

/**
 * Remembers a [LocationPermissionController] wired to [ActivityResultContracts.RequestMultiplePermissions]
 * and automatically refreshes permission state when the Composable screen resumes.
 */
@Composable
fun rememberLocationPermissionController(
  permissionManager: LocationPermissionManager? = null,
  onPermissionResult: (LocationPermissionStatus) -> Unit = {},
): LocationPermissionController {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val manager = remember(context, permissionManager) {
    permissionManager ?: LocationPermissionManager(context)
  }
  val latestOnResult by rememberUpdatedState(onPermissionResult)

  val launcher = rememberLauncherForActivityResult(
    contract = remember(manager) { manager.createPermissionsContract() },
  ) { grants ->
    val resolvedStatus = manager.handlePermissionResult(grants, activity)
    latestOnResult(resolvedStatus)
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, manager, activity) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        manager.refreshStatus(activity)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val status by manager.status.collectAsStateWithLifecycle()

  return remember(manager, status, activity, launcher) {
    LocationPermissionController(
      manager = manager,
      status = status,
      activity = activity,
      launcher = launcher,
    )
  }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
