package com.example.location

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R

/**
 * Reusable Composable check screen that verifies fine & coarse location permissions via
 * [LocationPermissionController] before allowing coordinate fetching via [onFetchCoordinates].
 */
@Composable
fun LocationCoordinateCheckScreen(
  permissionController: LocationPermissionController,
  onFetchCoordinates: () -> Unit,
  onUseManualLocation: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val fineGranted = permissionController.manager.hasFineLocationPermission()
  val coarseGranted = permissionController.manager.hasCoarseLocationPermission()
  val hasAnyPermission = fineGranted || coarseGranted
  val isPermanentlyDenied =
    permissionController.status is LocationPermissionStatus.Denied &&
      permissionController.status.permanentlyDenied

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.background,
          ),
        ),
      )
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(20.dp),
    contentAlignment = Alignment.Center,
  ) {
    LocationPermissionCheckCard(
      fineGranted = fineGranted,
      coarseGranted = coarseGranted,
      hasAnyPermission = hasAnyPermission,
      isPermanentlyDenied = isPermanentlyDenied,
      onPrimaryAction = {
        permissionController.checkBeforeFetchingCoordinates(
          onGranted = { onFetchCoordinates() },
          onPermissionRequired = { status ->
            if (status is LocationPermissionStatus.Denied && status.permanentlyDenied) {
              permissionController.openAppSettings(context)
            } else {
              permissionController.requestPermissions()
            }
          },
        )
      },
      onRequestAgain = { permissionController.requestPermissions() },
      onUseManualLocation = onUseManualLocation,
      modifier = Modifier.widthIn(max = 520.dp),
    )
  }
}

/**
 * Modal dialog shown inside a Composable screen before attempting to fetch user coordinates
 * when fine/coarse permissions have not yet been granted or need rationale.
 */
@Composable
fun LocationPermissionCheckDialog(
  permissionController: LocationPermissionController,
  onPermissionsConfirmed: () -> Unit,
  onDismissToManual: () -> Unit,
) {
  val context = LocalContext.current
  val fineGranted = permissionController.manager.hasFineLocationPermission()
  val coarseGranted = permissionController.manager.hasCoarseLocationPermission()
  val hasAnyPermission = fineGranted || coarseGranted
  val isPermanentlyDenied =
    permissionController.status is LocationPermissionStatus.Denied &&
      permissionController.status.permanentlyDenied

  Dialog(
    onDismissRequest = onDismissToManual,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 24.dp),
      contentAlignment = Alignment.Center,
    ) {
      LocationPermissionCheckCard(
        fineGranted = fineGranted,
        coarseGranted = coarseGranted,
        hasAnyPermission = hasAnyPermission,
        isPermanentlyDenied = isPermanentlyDenied,
        onPrimaryAction = {
          permissionController.checkBeforeFetchingCoordinates(
            onGranted = { onPermissionsConfirmed() },
            onPermissionRequired = { status ->
              if (status is LocationPermissionStatus.Denied && status.permanentlyDenied) {
                permissionController.openAppSettings(context)
              } else {
                permissionController.requestPermissions()
              }
            },
          )
        },
        onRequestAgain = { permissionController.requestPermissions() },
        onUseManualLocation = onDismissToManual,
        modifier = Modifier.widthIn(max = 480.dp),
      )
    }
  }
}

@Composable
internal fun LocationPermissionCheckCard(
  fineGranted: Boolean,
  coarseGranted: Boolean,
  hasAnyPermission: Boolean,
  isPermanentlyDenied: Boolean,
  onPrimaryAction: () -> Unit,
  onRequestAgain: () -> Unit,
  onUseManualLocation: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("location_permission_check_card"),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    border = BorderStroke(
      width = 1.dp,
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
              Brush.linearGradient(
                colors = listOf(
                  MaterialTheme.colorScheme.primary,
                  MaterialTheme.colorScheme.tertiary,
                ),
              ),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.MyLocation,
            contentDescription = stringResource(R.string.location_permission_badge_desc),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(26.dp),
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.location_permission_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = if (isPermanentlyDenied) {
              stringResource(R.string.location_permission_permanently_denied_desc)
            } else {
              stringResource(R.string.location_permission_rationale_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionStatusRow(
          icon = Icons.Outlined.GpsFixed,
          label = stringResource(R.string.location_permission_fine_label),
          granted = fineGranted,
          tag = "fine_location_status_row",
        )
        PermissionStatusRow(
          icon = Icons.Outlined.LocationOn,
          label = stringResource(R.string.location_permission_coarse_label),
          granted = coarseGranted,
          tag = "coarse_location_status_row",
        )
      }

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val primaryTestTag = when {
          hasAnyPermission -> "fetch_coordinates_button"
          isPermanentlyDenied -> "open_location_settings_button"
          else -> "grant_location_permission_button"
        }

        Button(
          onClick = onPrimaryAction,
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag(primaryTestTag),
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        ) {
          Icon(
            imageVector = when {
              hasAnyPermission -> Icons.Rounded.MyLocation
              isPermanentlyDenied -> Icons.Rounded.Settings
              else -> Icons.Rounded.CheckCircle
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = when {
              hasAnyPermission -> stringResource(R.string.location_permission_fetch_coordinates_button)
              isPermanentlyDenied -> stringResource(R.string.location_permission_open_settings_button)
              else -> stringResource(R.string.location_permission_grant_button)
            },
            fontWeight = FontWeight.SemiBold,
          )
        }

        if (isPermanentlyDenied && !hasAnyPermission) {
          OutlinedButton(
            onClick = onRequestAgain,
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize()
              .testTag("retry_location_permission_button"),
            shape = RoundedCornerShape(14.dp),
          ) {
            Text(text = stringResource(R.string.location_permission_grant_button))
          }
        }

        OutlinedButton(
          onClick = onUseManualLocation,
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("manual_location_fallback_button"),
          shape = RoundedCornerShape(14.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.EditLocationAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(text = stringResource(R.string.location_permission_manual_button))
        }
      }
    }
  }
}

@Composable
private fun PermissionStatusRow(
  icon: ImageVector,
  label: String,
  granted: Boolean,
  tag: String,
) {
  val containerColor = if (granted) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
  }
  val contentTint = if (granted) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(tag),
    shape = RoundedCornerShape(14.dp),
    color = containerColor,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentTint,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
          contentDescription = null,
          tint = contentTint,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = if (granted) {
            stringResource(R.string.location_permission_status_granted)
          } else {
            stringResource(R.string.location_permission_status_denied)
          },
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = contentTint,
        )
      }
    }
  }
}
