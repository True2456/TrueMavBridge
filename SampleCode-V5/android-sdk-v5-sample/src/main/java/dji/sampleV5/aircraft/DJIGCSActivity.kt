package dji.sampleV5.aircraft

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMeteringMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.core.widget.compass.CompassWidget
import dji.v5.ux.core.widget.setting.SettingPanelWidget
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget
import dji.v5.ux.core.util.ViewUtil
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import dji.v5.ux.core.util.StreamingManager
import dji.v5.ux.core.util.MavlinkMissionHandler
import dji.v5.ux.core.util.VirtualStickMissionManager
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget
import dji.v5.et.create
import dji.v5.et.set

/**
 * Custom GCS Activity with TrueGCS aesthetic and functional camera controls.
 */
class DJIGCSActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var primaryFpvWidget: FPVWidget
    private lateinit var topBarPanel: TopBarPanelWidget
    private lateinit var compassWidget: CompassWidget
    private lateinit var cameraQuickConfig: CameraVisiblePanelWidget
    private lateinit var cameraSettingsDrawer: SettingPanelWidget
    private lateinit var touchOverlay: View
    private lateinit var mapWebView: WebView
    private lateinit var pipContainer: View
    private lateinit var mainContainer: FrameLayout
    private var isMapMaximized = false
    
    private var aircraftLat = 0.0
    private var aircraftLng = 0.0
    private var aircraftHeading = 0.0
    
    private lateinit var btnToggleMenu: Button
    
    private var focusIcon: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var mavlinkHandler: MavlinkMissionHandler
    private lateinit var btnMissionMenu: ImageButton

    private val availableCameraUpdatedListener: ICameraStreamManager.AvailableCameraUpdatedListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(availableCameraList: List<ComponentIndexType>) {
            if (availableCameraList.isNotEmpty()) {
                val cameraIndex = availableCameraList[0]
                runOnUiThread {
                    primaryFpvWidget.updateVideoSource(cameraIndex)
                    // Bind functional widgets to the active camera source
                    bindCameraToWidgets(cameraIndex)
                }
            }
        }

        override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: Map<ComponentIndexType, Boolean>) {
            // No-op - Required by DJI SDK to prevent AbstractMethodError crash
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gcs)

        drawerLayout = findViewById(R.id.root_view)
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        topBarPanel = findViewById(R.id.panel_top_bar)
        compassWidget = findViewById(R.id.widget_compass)

        // 🎨 SLEEK UI: Hide bulky background layers to leave only the Heading Arrow and North Indicator
        compassWidget.findViewById<View>(dji.v5.ux.R.id.imageview_compass_background)?.visibility = View.GONE
        compassWidget.findViewById<View>(dji.v5.ux.R.id.imageview_inner_circles)?.visibility = View.GONE
        compassWidget.findViewById<View>(dji.v5.ux.R.id.progressbar_compass_attitude)?.visibility = View.GONE
        
        // 🗺️ TRUEGCS MAP SETUP: Reliable Leaflet-based map with ArcGIS tiles
        mapWebView = findViewById(R.id.map_webview)
        mainContainer = findViewById(R.id.main_view_container)
        pipContainer = findViewById(R.id.pip_view_container)
        
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.setSupportZoom(true)
        mapWebView.settings.builtInZoomControls = true
        mapWebView.settings.displayZoomControls = false
        mapWebView.webViewClient = WebViewClient()
        mapWebView.loadUrl("file:///android_asset/map.html")

        // Subscribe to Aircraft Location
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation), this) { _, value: LocationCoordinate2D? ->
            value?.let {
                aircraftLat = it.latitude
                aircraftLng = it.longitude
                updateMapPosition()
            }
        }

        // Subscribe to Aircraft Heading (for the icon rotation)
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyCompassHeading), this) { _, value: Double? ->
            value?.let {
                aircraftHeading = it
                updateMapPosition()
            }
        }

        // 🔄 VIEW SWAP: Toggle between Video and Map
        findViewById<View>(R.id.pip_click_overlay).setOnClickListener {
            toggleMapSwap()
        }
        touchOverlay = findViewById(R.id.touch_overlay)
        cameraQuickConfig = findViewById(R.id.widget_camera_config)
        cameraSettingsDrawer = findViewById(R.id.camera_settings_drawer)
        btnToggleMenu = findViewById(R.id.btn_toggle_camera)

        // Toggle Full Settings Drawer
        btnToggleMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        // --- NEW: MISSION LOGIC ---
        btnMissionMenu = findViewById(R.id.btn_mission_menu)

        StreamingManager.startTelemetryStreaming(14550, "255.255.255.255")
        mavlinkHandler = MavlinkMissionHandler { msgId, payload ->
            StreamingManager.sendMavlinkPacket(msgId, payload)
        }

        btnMissionMenu.setOnClickListener {
            showMissionMenu()
        }
        // ---------------------------

        // Hide the persistent metering circle by default
        val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
        CameraKey.KeyCameraMeteringMode.create(cameraIndex).set(CameraMeteringMode.AVERAGE)

        // Local focus square feedback
        focusIcon = ImageView(this).apply {
            setImageResource(dji.v5.ux.R.drawable.uxsdk_ic_focus_target_auto)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(100, 100)
        }
        (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.addView(focusIcon)

        touchOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val xNorm = event.x / touchOverlay.width.toDouble()
                val yNorm = event.y / touchOverlay.height.toDouble()
                
                CameraKey.KeyCameraFocusTarget.create(cameraIndex).set(DoublePoint2D(xNorm, yNorm))

                focusIcon?.apply {
                    translationX = event.rawX - 50f
                    translationY = event.rawY - 50f
                    visibility = View.VISIBLE
                }

                handler.removeCallbacksAndMessages("HIDE_ICON")
                handler.postAtTime({
                    focusIcon?.visibility = View.GONE
                }, "HIDE_ICON", SystemClock.uptimeMillis() + 2000)
            }
            true
        }

        // Immersive/Full Screen
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        // Monitor camera connection
        MediaDataCenter.getInstance().cameraStreamManager.addAvailableCameraUpdatedListener(availableCameraUpdatedListener)
    }

    private fun bindCameraToWidgets(cameraIndex: ComponentIndexType) {
        val lens = CameraLensType.CAMERA_LENS_WIDE
        
        // Ensure UI components know which camera to control
        primaryFpvWidget.updateVideoSource(cameraIndex)
        cameraQuickConfig.updateCameraSource(cameraIndex, lens)
        compassWidget.setGimbalIndex(cameraIndex)
        
        // Direct binding to the controls widget fixes the static sliders and buttons
        findViewById<CameraControlsWidget>(R.id.widget_camera_controls)?.updateCameraSource(cameraIndex, lens)
    }

    private fun updateMapPosition() {
        runOnUiThread {
            mapWebView.evaluateJavascript("updateLocation($aircraftLat, $aircraftLng, $aircraftHeading)", null)
        }
    }

    private fun toggleMapSwap() {
        isMapMaximized = !isMapMaximized
        
        val fpvView = primaryFpvWidget
        val mapView = mapWebView
        
        // Remove from parents
        (fpvView.parent as? android.view.ViewGroup)?.removeView(fpvView)
        (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
        
        if (isMapMaximized) {
            // Map to Fullscreen, FPV to PIP
            mainContainer.addView(mapView, 0)
            (pipContainer as? android.view.ViewGroup)?.addView(fpvView, 0)
            
            // Hide camera touch overlay so we can interact with the map
            touchOverlay.visibility = View.GONE
        } else {
            // FPV to Fullscreen, Map to PIP
            mainContainer.addView(fpvView, 0)
            (pipContainer as? android.view.ViewGroup)?.addView(mapView, 0)
            
            // Restore camera touch overlay
            touchOverlay.visibility = View.VISIBLE
        }
    }

    private fun showMissionMenu() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mission_menu, null)
        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_execute_mission).setOnClickListener {
            VirtualStickMissionManager.startMission(mavlinkHandler.getMissionItems())
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_abort_mission).setOnClickListener {
            VirtualStickMissionManager.stopMission()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        KeyManager.getInstance().cancelListen(this)
        MediaDataCenter.getInstance().cameraStreamManager.removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        ViewUtil.setKeepScreen(this, true)
    }

    override fun onPause() {
        super.onPause()
        ViewUtil.setKeepScreen(this, false)
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }
    
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }
}
