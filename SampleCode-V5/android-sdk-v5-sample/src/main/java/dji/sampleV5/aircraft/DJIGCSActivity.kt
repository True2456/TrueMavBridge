package dji.sampleV5.aircraft

import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMeteringMode
import dji.sdk.keyvalue.value.camera.CameraISO
import dji.sdk.keyvalue.value.camera.CameraShutterSpeed
import dji.sdk.keyvalue.value.camera.CameraExposureCompensation
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.core.widget.setting.SettingPanelWidget
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget
import dji.v5.ux.core.util.ViewUtil
import dji.v5.ux.core.util.MobileGPSLocationUtil
import android.location.Location
import android.location.LocationListener
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import dji.v5.ux.core.util.StreamingManager
import dji.v5.ux.core.util.MavlinkMissionHandler
import dji.v5.ux.core.util.VirtualStickMissionManager
import dji.v5.ux.core.util.WaypointItem
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
    
    
    private var focusIcon: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mapWaypoints = mutableListOf<WaypointItem>()

    private lateinit var mavlinkHandler: MavlinkMissionHandler
    private lateinit var btnMissionMenu: ImageButton
    private lateinit var btnCameraMenu: ImageButton

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
        
        // 🗺️ TRUEGCS MAP SETUP: Reliable Leaflet-based map with ArcGIS tiles
        mapWebView = findViewById(R.id.map_webview)
        mainContainer = findViewById(R.id.main_view_container)
        pipContainer = findViewById(R.id.pip_view_container)
        
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.setSupportZoom(true)
        mapWebView.settings.builtInZoomControls = true
        mapWebView.settings.displayZoomControls = false
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.domStorageEnabled = true
        mapWebView.addJavascriptInterface(MapInterface(), "Android")
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

        // --- NEW: MISSION LOGIC ---
        btnMissionMenu = findViewById(R.id.btn_mission_menu)

        StreamingManager.startTelemetryStreaming(14550, "255.255.255.255")
        mavlinkHandler = MavlinkMissionHandler { msgId, payload ->
            StreamingManager.sendMavlinkPacket(msgId, payload)
        }

        btnMissionMenu.setOnClickListener {
            showMissionMenu()
        }

        findViewById<View>(R.id.btn_takeoff_land).setOnClickListener {
            KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartTakeoff), object: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { Log.i("TrueGCS", "Takeoff Success") }
                override fun onFailure(error: IDJIError) { Log.e("TrueGCS", "Takeoff Failed: ${error.description()}") }
            })
        }

        findViewById<View>(R.id.btn_rth).setOnClickListener {
            KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartGoHome), object: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { Log.i("TrueGCS", "RTH Success") }
                override fun onFailure(error: IDJIError) { Log.e("TrueGCS", "RTH Failed: ${error.description()}") }
            })
        }

        findViewById<View>(R.id.btn_main_menu).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                drawerLayout.openDrawer(GravityCompat.END)
            }
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

        btnCameraMenu = findViewById(R.id.btn_camera_menu)
        btnCameraMenu.setOnClickListener {
            showCameraMenu()
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

        setupMobileGPS()
    }

    private fun showCameraMenu() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_camera_menu, null)
        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        dialog.show()

        val window = dialog.window
        window?.setGravity(Gravity.BOTTOM)
        val params = window?.attributes
        params?.width = WindowManager.LayoutParams.WRAP_CONTENT
        params?.height = WindowManager.LayoutParams.WRAP_CONTENT
        params?.y = (100 * resources.displayMetrics.density).toInt() // Increased offset
        window?.attributes = params

        val cameraIndex = ComponentIndexType.LEFT_OR_MAIN

        // TRIGGER AUTO FOCUS (Center)
        dialogView.findViewById<View>(R.id.btn_trigger_af).setOnClickListener {
            CameraKey.KeyCameraFocusTarget.create(cameraIndex).set(DoublePoint2D(0.5, 0.5))
            
            focusIcon?.apply {
                translationX = (touchOverlay.width / 2 - 50).toFloat()
                translationY = (touchOverlay.height / 2 - 50).toFloat()
                visibility = View.VISIBLE
            }
            handler.removeCallbacksAndMessages("HIDE_ICON")
            handler.postDelayed({ focusIcon?.visibility = View.GONE }, 2000)
        }

        val lens = CameraLensType.CAMERA_LENS_WIDE

        // TOGGLE AF/MF
        dialogView.findViewById<View>(R.id.btn_toggle_af_mf).setOnClickListener {
            val key = KeyTools.createCameraKey(CameraKey.KeyCameraFocusMode, cameraIndex, lens)
            KeyManager.getInstance().getValue(key, object: CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.camera.CameraFocusMode> {
                override fun onSuccess(mode: dji.sdk.keyvalue.value.camera.CameraFocusMode?) {
                    val nextMode = if (mode == dji.sdk.keyvalue.value.camera.CameraFocusMode.MANUAL) 
                        dji.sdk.keyvalue.value.camera.CameraFocusMode.AF 
                    else 
                        dji.sdk.keyvalue.value.camera.CameraFocusMode.MANUAL
                    
                    KeyManager.getInstance().setValue(key, nextMode, null)
                }
                override fun onFailure(error: IDJIError) {}
            })
        }

        // TOGGLE AE LOCK
        dialogView.findViewById<View>(R.id.btn_ae_lock).setOnClickListener {
            val key = KeyTools.createCameraKey(CameraKey.KeyAELockEnabled, cameraIndex, lens)
            KeyManager.getInstance().getValue(key, object: CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                override fun onSuccess(locked: Boolean?) {
                    KeyManager.getInstance().setValue(key, !(locked ?: false), null)
                }
                override fun onFailure(error: IDJIError) {}
            })
        }

        setupSliders(dialogView, cameraIndex, lens)
    }

    private fun setupSliders(view: View, cameraIndex: ComponentIndexType, lens: CameraLensType) {
        val seekIso: SeekBar = view.findViewById(R.id.seek_iso)
        val txtIso: TextView = view.findViewById(R.id.txt_iso_val)
        val seekShutter: SeekBar = view.findViewById(R.id.seek_shutter)
        val txtShutter: TextView = view.findViewById(R.id.txt_shutter_val)
        val seekEv: SeekBar = view.findViewById(R.id.seek_ev)
        val txtEv: TextView = view.findViewById(R.id.txt_ev_val)
        val seekFocus: SeekBar = view.findViewById(R.id.seek_focus)
        val txtFocus: TextView = view.findViewById(R.id.txt_focus_val)

        // ISO
        val isoKey = KeyTools.createCameraKey(CameraKey.KeyISO, cameraIndex, lens)
        val isoRangeKey = KeyTools.createCameraKey(CameraKey.KeyISORange, cameraIndex, lens)
        var isoList = listOf<CameraISO>()

        KeyManager.getInstance().listen(isoRangeKey, this) { _, range ->
            isoList = range ?: listOf()
            seekIso.max = (isoList.size - 1).coerceAtLeast(0)
        }
        KeyManager.getInstance().listen(isoKey, this) { _, value ->
            val idx = isoList.indexOf(value)
            if (idx >= 0) {
                seekIso.progress = idx
                txtIso.text = value?.name ?: "AUTO"
            }
        }
        seekIso.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (f && p < isoList.size) {
                    KeyManager.getInstance().setValue(isoKey, isoList[p], null)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // SHUTTER
        val shutterKey = KeyTools.createCameraKey(CameraKey.KeyShutterSpeed, cameraIndex, lens)
        val shutterRangeKey = KeyTools.createCameraKey(CameraKey.KeyShutterSpeedRange, cameraIndex, lens)
        var shutterList = listOf<CameraShutterSpeed>()

        KeyManager.getInstance().listen(shutterRangeKey, this) { _, range ->
            shutterList = range ?: listOf()
            seekShutter.max = (shutterList.size - 1).coerceAtLeast(0)
        }
        KeyManager.getInstance().listen(shutterKey, this) { _, value ->
            val idx = shutterList.indexOf(value)
            if (idx >= 0) {
                seekShutter.progress = idx
                txtShutter.text = value?.name ?: "1/50"
            }
        }
        seekShutter.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (f && p < shutterList.size) {
                    KeyManager.getInstance().setValue(shutterKey, shutterList[p], null)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // EV
        val evKey = KeyTools.createCameraKey(CameraKey.KeyExposureCompensation, cameraIndex, lens)
        val evRangeKey = KeyTools.createCameraKey(CameraKey.KeyExposureCompensationRange, cameraIndex, lens)
        var evList = listOf<CameraExposureCompensation>()

        KeyManager.getInstance().listen(evRangeKey, this) { _, range: List<CameraExposureCompensation>? ->
            evList = range ?: listOf()
            seekEv.max = (evList.size - 1).coerceAtLeast(0)
        }
        KeyManager.getInstance().listen(evKey, this) { _, value: CameraExposureCompensation? ->
            val idx = evList.indexOf(value)
            if (idx >= 0) {
                seekEv.progress = idx
                (txtEv as? TextView)?.setText(value?.name?.replace("_", ".")?.replace("P", "+")?.replace("N", "-") ?: "0.0")
            }
        }
        seekEv.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (f && p < evList.size) {
                    KeyManager.getInstance().setValue(evKey, evList[p], null)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // FOCUS
        val focusKey = KeyTools.createCameraKey(CameraKey.KeyCameraFocusRingValue, cameraIndex, lens)
        val focusMinKey = KeyTools.createCameraKey(CameraKey.KeyCameraFocusRingMinValue, cameraIndex, lens)
        val focusMaxKey = KeyTools.createCameraKey(CameraKey.KeyCameraFocusRingMaxValue, cameraIndex, lens)

        KeyManager.getInstance().listen(focusMinKey, this) { _, value: Int? ->
            if (value != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                seekFocus.min = value
            }
        }
        KeyManager.getInstance().listen(focusMaxKey, this) { _, value: Int? ->
            if (value != null) {
                seekFocus.max = value
            }
        }
        val finalTxtFocus = txtFocus
        KeyManager.getInstance().listen(focusKey, this) { _, value: Int? ->
            if (value != null) {
                seekFocus.progress = value
                (finalTxtFocus as? TextView)?.setText(value.toString())
            }
        }
        seekFocus.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (f) {
                    KeyManager.getInstance().setValue(focusKey, p, null)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun bindCameraToWidgets(cameraIndex: ComponentIndexType) {
        val lens = CameraLensType.CAMERA_LENS_WIDE
        
        // Ensure UI components know which camera to control
        primaryFpvWidget.updateVideoSource(cameraIndex)
        cameraQuickConfig.updateCameraSource(cameraIndex, lens)
        
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

        dialog.show() // Show first so we can modify window

        // SET CENTER-BOTTOM GRAVITY AND SIZE
        val window = dialog.window
        window?.setGravity(Gravity.BOTTOM)
        val params = window?.attributes
        params?.width = WindowManager.LayoutParams.WRAP_CONTENT
        params?.height = WindowManager.LayoutParams.WRAP_CONTENT
        params?.y = (100 * resources.displayMetrics.density).toInt() // Distance from bottom
        window?.attributes = params

        val editAlt = dialogView.findViewById<EditText>(R.id.edit_mission_alt)

        dialogView.findViewById<View>(R.id.btn_execute_mission).setOnClickListener {
            val altOverride = editAlt.text.toString().toDoubleOrNull() ?: 30.0
            
            // Combine map waypoints and mavlink waypoints (or just use map if present)
            val finalWps = if (mapWaypoints.isNotEmpty()) mapWaypoints else mavlinkHandler.getMissionItems()
            
            VirtualStickMissionManager.startMission(finalWps, altOverride)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_abort_mission).setOnClickListener {
            VirtualStickMissionManager.stopMission()
            dialog.dismiss()
        }

        dialog.show()
    }

    inner class MapInterface {
        @android.webkit.JavascriptInterface
        fun onWaypointsChanged(json: String) {
            try {
                val array = org.json.JSONArray(json)
                val newList = mutableListOf<WaypointItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lng = obj.getDouble("lng")
                    newList.add(WaypointItem(lat, lng, 30.0))
                }
                runOnUiThread {
                    mapWaypoints = newList
                    Log.i("TrueGCS", "Map waypoints updated: ${mapWaypoints.size}")
                }
            } catch (e: Exception) {
                Log.e("TrueGCS", "Error parsing map waypoints", e)
            }
        }
    }

    private fun setupMobileGPS() {
        MobileGPSLocationUtil.getInstance().addLocationListener(object : LocationListener {
            override fun onLocationChanged(location: Location) {
                mapWebView.post {
                    mapWebView.evaluateJavascript("updateControllerLocation(${location.latitude}, ${location.longitude})", null)
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        })
        MobileGPSLocationUtil.getInstance().startUpdateLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        MobileGPSLocationUtil.getInstance().clearAllLocationListener()
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
