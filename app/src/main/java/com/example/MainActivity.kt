package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import com.example.location.LearningLocationBasis
import com.example.location.LocationPermissionCheckDialog
import com.example.location.LocationPermissionManager
import com.example.location.LocationPermissionStatus
import com.example.location.LocationUiState
import com.example.location.SocioLocationManager
import com.example.location.rememberLocationPermissionController
import com.example.report.SocioReportExporter
import com.example.ui.theme.BlueDeep
import com.example.ui.theme.SocioDetectiveTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0F2454")),
      navigationBarStyle = SystemBarStyle.light(
        android.graphics.Color.parseColor("#FFFFFF"),
        android.graphics.Color.parseColor("#0F2454"),
      ),
    )
    setContent {
      SocioDetectiveTheme {
        SocioDetectiveWebHost(onExitApp = { finish() })
      }
    }
  }
}

private class AndroidLocationBridge(
  private val locationManager: SocioLocationManager,
  private val onRequestGpsFromJs: (basisKey: String) -> Unit,
) {
  @JavascriptInterface
  fun requestGpsLocation(basisKey: String) {
    onRequestGpsFromJs(basisKey)
  }

  @JavascriptInterface
  fun saveManualRegion(
    basisKey: String,
    province: String,
    regencyOrCity: String,
    district: String,
    village: String,
  ) {
    locationManager.saveManualRegion(
      province = province,
      regencyOrCity = regencyOrCity,
      district = district,
      village = village,
      basis = LearningLocationBasis.fromKey(basisKey),
    )
  }

  @JavascriptInterface
  fun setLearningBasis(basisKey: String) {
    locationManager.setLearningBasis(LearningLocationBasis.fromKey(basisKey))
  }

  @JavascriptInterface
  fun hasLocationPermission(): Boolean = locationManager.hasLocationPermission()
}

private class AndroidReportBridge(
  private val onExportCsv: (suggestedFilename: String, csvContent: String) -> Unit,
  private val onExportPdf: (suggestedFilename: String, reportJson: String) -> Unit,
) {
  @JavascriptInterface
  fun exportExcelCsvReport(suggestedFilename: String, csvContent: String) {
    onExportCsv(suggestedFilename, csvContent)
  }

  @JavascriptInterface
  fun exportPdfReport(suggestedFilename: String, reportJson: String) {
    onExportPdf(suggestedFilename, reportJson)
  }
}

private class AndroidAppBridge(private val context: Context) {
  @JavascriptInterface
  fun getAppInfoJson(): String {
    return JSONObject().apply {
      put("isAndroidApp", true)
      put("appName", "SocioDetective")
      put("packageId", BuildConfig.APPLICATION_ID)
      put("versionName", BuildConfig.VERSION_NAME)
      put("versionCode", BuildConfig.VERSION_CODE)
    }.toString()
  }

  @JavascriptInterface
  fun shareSocioDetective(title: String, message: String) {
    runCatching {
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, message)
      }
      val chooser = Intent.createChooser(sendIntent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
    }.onFailure {
      Toast.makeText(context, "Tidak dapat membuka menu bagikan.", Toast.LENGTH_SHORT).show()
    }
  }

  @JavascriptInterface
  fun openExternalUrl(url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }.onFailure {
      Toast.makeText(context, "Gagal membuka tautan di browser.", Toast.LENGTH_SHORT).show()
    }
  }

  @JavascriptInterface
  fun copyToClipboard(label: String, text: String) {
    runCatching {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
      Toast.makeText(context, "Tautan disalin ke clipboard.", Toast.LENGTH_SHORT).show()
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SocioDetectiveWebHost(onExitApp: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val locationManager = remember(context) { SocioLocationManager(context.applicationContext) }
  val htmlContent = remember {
    context.assets.open("sociodetective.html").bufferedReader().use { it.readText() }
  }

  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var pendingGeoOrigin by remember { mutableStateOf<String?>(null) }
  var pendingGeoCallback by remember { mutableStateOf<GeolocationPermissions.Callback?>(null) }
  var pendingNativeGpsBasis by remember { mutableStateOf<LearningLocationBasis?>(null) }
  var pendingCsvContent by remember { mutableStateOf<String?>(null) }
  var pendingPdfJson by remember { mutableStateOf<String?>(null) }
  var pendingFilePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

  val pickVisualMediaLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    val callback = pendingFilePathCallback
    pendingFilePathCallback = null
    if (callback != null) {
      callback.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }
  }

  val createCsvLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv"),
  ) { uri ->
    val content = pendingCsvContent
    pendingCsvContent = null
    if (uri != null && content != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.let { out ->
          SocioReportExporter.writeCsvWithBom(out, content)
        }
      }.onSuccess {
        Toast.makeText(context, "Laporan Excel (CSV) berhasil disimpan.", Toast.LENGTH_SHORT).show()
      }.onFailure {
        Toast.makeText(context, "Gagal menyimpan laporan CSV.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val createPdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf"),
  ) { uri ->
    val reportJson = pendingPdfJson
    pendingPdfJson = null
    if (uri != null && reportJson != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.let { out ->
          SocioReportExporter.writePdfFromJson(out, reportJson)
        }
      }.onSuccess {
        Toast.makeText(context, "Laporan PDF berhasil disimpan.", Toast.LENGTH_SHORT).show()
      }.onFailure {
        Toast.makeText(context, "Gagal menyimpan laporan PDF.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val locationPermissionManager = remember(context) { LocationPermissionManager(context) }
  var showLocationPermissionCheck by remember { mutableStateOf(false) }

  fun dispatchLocationResultToWeb(payload: JSONObject) {
    val wv = webViewRef ?: return
    val escaped = JSONObject.quote(payload.toString())
    wv.post {
      wv.evaluateJavascript(
        "window.onAndroidLocationResult && window.onAndroidLocationResult($escaped);",
        null,
      )
    }
  }

  fun notifyLocationManualFallback(message: String) {
    locationManager.onPermissionDenied()
    val payload = JSONObject().apply {
      put("status", "denied")
      put("message", message)
    }
    dispatchLocationResultToWeb(payload)
  }

  fun runNativeGpsFetch(basis: LearningLocationBasis) {
    if (!locationPermissionManager.hasAnyLocationPermission()) {
      pendingNativeGpsBasis = basis
      showLocationPermissionCheck = true
      return
    }
    scope.launch {
      val result = locationManager.fetchCurrentGpsLocation(basis)
      val payload = JSONObject()
      result.onSuccess { region ->
        payload.put("status", "ok")
        payload.put("basis", basis.key)
        payload.put("provinsi", region.province)
        payload.put("kabupaten", region.regencyOrCity)
        payload.put("kecamatan", region.district)
        payload.put("desa", region.village)
        if (region.latitude != null) payload.put("lat", region.latitude)
        if (region.longitude != null) payload.put("lon", region.longitude)
      }.onFailure { err ->
        val state = locationManager.uiState.value
        val reason = (state as? LocationUiState.ManualEntryRequired)?.reason
          ?: err.message
          ?: "Lokasi GPS tidak tersedia. Silakan isi daerah secara manual."
        payload.put("status", "manual_required")
        payload.put("message", reason)
      }
      dispatchLocationResultToWeb(payload)
    }
  }

  val permissionController = rememberLocationPermissionController(
    permissionManager = locationPermissionManager,
  ) { status ->
    val isAnyLocationGranted = status is LocationPermissionStatus.Granted

    val origin = pendingGeoOrigin
    val callback = pendingGeoCallback
    pendingGeoOrigin = null
    pendingGeoCallback = null
    if (origin != null && callback != null) {
      callback.invoke(origin, isAnyLocationGranted, false)
    }

    val nativeBasis = pendingNativeGpsBasis
    if (nativeBasis != null) {
      if (isAnyLocationGranted) {
        pendingNativeGpsBasis = null
        showLocationPermissionCheck = false
        runNativeGpsFetch(nativeBasis)
      } else {
        showLocationPermissionCheck = true
      }
    }
  }

  fun requestCoordinatesWithPermissionCheck(basis: LearningLocationBasis) {
    pendingNativeGpsBasis = basis
    permissionController.checkBeforeFetchingCoordinates(
      onGranted = {
        pendingNativeGpsBasis = null
        showLocationPermissionCheck = false
        runNativeGpsFetch(basis)
      },
      onPermissionRequired = {
        locationManager.markRequestingPermission()
        showLocationPermissionCheck = true
      },
    )
  }

  BackHandler {
    val wv = webViewRef
    if (wv != null) {
      wv.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { result ->
        if (result != "true") {
          if (wv.canGoBack()) {
            wv.goBack()
          } else {
            onExitApp()
          }
        }
      }
    } else {
      onExitApp()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(BlueDeep)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("sociodetective_webview"),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
          }
          addJavascriptInterface(
            AndroidLocationBridge(locationManager) { basisKey ->
              val basis = LearningLocationBasis.fromKey(basisKey)
              post {
                requestCoordinatesWithPermissionCheck(basis)
              }
            },
            "AndroidLocationBridge",
          )
          addJavascriptInterface(
            AndroidReportBridge(
              onExportCsv = { suggestedName, csvContent ->
                post {
                  pendingCsvContent = csvContent
                  createCsvLauncher.launch(suggestedName)
                }
              },
              onExportPdf = { suggestedName, reportJson ->
                post {
                  pendingPdfJson = reportJson
                  createPdfLauncher.launch(suggestedName)
                }
              },
            ),
            "AndroidReportBridge",
          )
          addJavascriptInterface(AndroidAppBridge(ctx), "AndroidAppBridge")
          webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
              view: WebView?,
              request: WebResourceRequest?,
            ): WebResourceResponse? {
              val url = request?.url?.toString() ?: return null
              if (url.startsWith("https://sociodetective.local/vendor/")) {
                val assetRelPath = url.removePrefix("https://sociodetective.local/").substringBefore("?")
                return runCatching {
                  val mime = if (assetRelPath.endsWith(".js")) "application/javascript" else "application/octet-stream"
                  WebResourceResponse(mime, "UTF-8", ctx.assets.open(assetRelPath))
                }.getOrNull()
              }
              return null
            }

            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?,
            ): Boolean {
              val url = request?.url?.toString() ?: return false
              if (url.startsWith("https://sociodetective.local/")) {
                return false
              }
              if (url.startsWith("http://") || url.startsWith("https://")) {
                return try {
                  ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                  true
                } catch (_: Exception) {
                  false
                }
              }
              return false
            }
          }
          webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
              origin: String?,
              callback: GeolocationPermissions.Callback?,
            ) {
              if (origin == null || callback == null) return
              permissionController.checkBeforeFetchingCoordinates(
                onGranted = {
                  callback.invoke(origin, true, false)
                },
                onPermissionRequired = {
                  pendingGeoOrigin = origin
                  pendingGeoCallback = callback
                  permissionController.requestPermissions()
                },
              )
            }

            override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?,
            ): Boolean {
              if (filePathCallback == null) return false
              pendingFilePathCallback?.onReceiveValue(null)
              pendingFilePathCallback = filePathCallback
              return try {
                pickVisualMediaLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
                true
              } catch (_: Exception) {
                pendingFilePathCallback = null
                filePathCallback.onReceiveValue(null)
                false
              }
            }
          }
          loadDataWithBaseURL(
            "https://sociodetective.local/",
            htmlContent,
            "text/html",
            "UTF-8",
            null,
          )
          webViewRef = this
        }
      },
      update = { wv ->
        webViewRef = wv
      },
    )

    if (showLocationPermissionCheck) {
      LocationPermissionCheckDialog(
        permissionController = permissionController,
        onPermissionsConfirmed = {
          val basis = pendingNativeGpsBasis ?: locationManager.activeBasis.value
          pendingNativeGpsBasis = null
          showLocationPermissionCheck = false
          runNativeGpsFetch(basis)
        },
        onDismissToManual = {
          pendingNativeGpsBasis = null
          showLocationPermissionCheck = false
          notifyLocationManualFallback(
            "Izin lokasi tidak diberikan. Tidak masalah—aplikasi tetap berjalan normal. Silakan isi daerahmu secara manual di bawah ini.",
          )
        },
      )
    }
  }
}
