package dji.sampleV5.aircraft

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraMeteringMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.core.widget.hsi.HorizontalSituationIndicatorWidget
import dji.v5.ux.core.widget.setting.SettingPanelWidget
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget
import dji.v5.ux.core.util.ViewUtil
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
    private lateinit var hsiWidget: HorizontalSituationIndicatorWidget
    private lateinit var cameraQuickConfig: CameraVisiblePanelWidget
    private lateinit var cameraSettingsDrawer: SettingPanelWidget
    private lateinit var touchOverlay: View
    private lateinit var btnToggleMenu: Button
    
    private var focusIcon: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val availableCameraUpdatedListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
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
            // No-op
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gcs)

        drawerLayout = findViewById(R.id.root_view)
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        topBarPanel = findViewById(R.id.panel_top_bar)
        hsiWidget = findViewById(R.id.widget_hsi)
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
        hsiWidget.updateCameraSource(cameraIndex, lens)
        
        // Direct binding to the controls widget fixes the static sliders and buttons
        findViewById<CameraControlsWidget>(R.id.widget_camera_controls)?.updateCameraSource(cameraIndex, lens)
    }

    override fun onDestroy() {
        super.onDestroy()
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
    
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }
}
