package dji.v5.ux.core.util

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.utils.common.LogUtils
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class WaypointItem(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val holdTimeMs: Long = 0,
    val targetSpeed: Double = 5.0
)

/**
 * Handles autonomous flight using Virtual Sticks since the Mini 3 does not support native WaypointV3.
 */
object VirtualStickMissionManager {
    private const val TAG = "VirtualStickMissionManager"

    private val missionQueue = mutableListOf<WaypointItem>()
    private var currentWaypointIndex = 0
    val isMissionRunning = AtomicBoolean(false)
    
    private var currentLat = 0.0
    private var currentLng = 0.0
    private var currentAlt = 0.0
    private var currentYaw = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private var holdStartTime: Long = 0
    private var isHolding = false
    
    var onWaypointReached: (() -> Unit)? = null
    private var isMappingMission = false

    private val navigationLoop = object : Runnable {
        override fun run() {
            if (!isMissionRunning.get()) return

            if (missionQueue.isEmpty()) {
                finishMission()
                return
            }

            val target = missionQueue[0]
            
            // 1. Calculate Distance and Bearing
            val distance = calculateDistance(currentLat, currentLng, target.latitude, target.longitude)
            val bearing = calculateBearing(currentLat, currentLng, target.latitude, target.longitude)
            val altError = target.altitude - currentAlt

            // 2. Check Arrival
            if (distance < 1.0 && abs(altError) < 0.5) {
                if (!isHolding && target.holdTimeMs > 0) {
                    isHolding = true
                    holdStartTime = System.currentTimeMillis()
                    LogUtils.i(TAG, "Waypoint $currentWaypointIndex reached. Holding for ${target.holdTimeMs}ms.")
                }

                if (!isHolding || (System.currentTimeMillis() - holdStartTime >= target.holdTimeMs)) {
                    LogUtils.i(TAG, "Waypoint reached. Removing from queue.")
                    isHolding = false
                    
                    if (isMappingMission) {
                        triggerPhoto()
                    }
                    
                    missionQueue.removeAt(0)
                    onWaypointReached?.invoke()
                    // Immediately loop to evaluate the next waypoint
                    handler.post(this)
                    return
                }
            }

            // 3. Navigation Math (Proportional Control)
            var vForward = 0.0
            var vRight = 0.0
            var vUp = 0.0
            var yawSpeed = 0.0

            if (!isHolding) {
                // BODY Coordinate System: X is Forward, Y is Right
                val maxSpeed = target.targetSpeed
                
                // P-Controller for speed (slow down as we approach)
                val kP_Horizontal = 1.0
                var desiredSpeed = distance * kP_Horizontal
                desiredSpeed = desiredSpeed.coerceIn(-maxSpeed, maxSpeed)

                // Since we are yawing to face the target, we simply fly "Forward"
                vForward = desiredSpeed
                vRight = 0.0

                // P-Controller for altitude
                val kP_Vertical = 1.5
                vUp = (altError * kP_Vertical).coerceIn(-3.0, 3.0)

                // P-Controller for Yaw (point nose at target)
                var yawError = bearing - currentYaw
                while (yawError > 180) yawError -= 360
                while (yawError < -180) yawError += 360
                
                val kP_Yaw = 2.0
                yawSpeed = (yawError * kP_Yaw).coerceIn(-45.0, 45.0)
            }

            // 4. Send Virtual Stick Commands
            val param = VirtualStickFlightControlParam()
            param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            param.rollPitchControlMode = RollPitchControlMode.VELOCITY
            param.verticalControlMode = VerticalControlMode.VELOCITY
            param.yawControlMode = YawControlMode.ANGULAR_VELOCITY

            // Swapping pitch/roll axes to fix sideways movement on Mini 3
            param.pitch = vRight    // Now lateral
            param.roll = vForward   // Now longitudinal
            param.verticalThrottle = vUp
            param.yaw = yawSpeed

            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)

            // Loop at ~20Hz
            handler.postDelayed(this, 50)
        }
    }

    fun startMission(waypoints: List<WaypointItem>, altOverride: Double? = null, isMapping: Boolean = false) {
        this.isMappingMission = isMapping
        if (waypoints.isEmpty()) {
            LogUtils.e(TAG, "No waypoints to execute.")
            return
        }
        
        // Apply altitude override if provided
        val finalWaypoints = if (altOverride != null) {
            waypoints.map { it.copy(altitude = altOverride) }
        } else {
            waypoints
        }

        missionQueue.clear()
        missionQueue.addAll(finalWaypoints)
        isHolding = false
        
        LogUtils.i(TAG, "Starting Virtual Stick Mission with ${waypoints.size} waypoints.")

        // Ensure advanced mode is enabled
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
        
        // Enable Virtual Sticks
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                isMissionRunning.set(true)
                setupTelemetryListeners()
                setupSafetyOverrideListener()
                handler.post(navigationLoop)
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "Failed to enable Virtual Sticks: ${error.description()}")
            }
        })
    }

    fun stopMission() {
        if (!isMissionRunning.get()) return
        isMissionRunning.set(false)
        handler.removeCallbacks(navigationLoop)
        
        // Disable Virtual Sticks so pilot regains manual control
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.i(TAG, "Mission Aborted. Manual control restored.")
            }
            override fun onFailure(error: IDJIError) {}
        })
        KeyManager.getInstance().cancelListen(this)
    }

    private fun finishMission() {
        LogUtils.i(TAG, "Mission Complete. Triggering RTH.")
        stopMission()
        
        // Trigger RTH as requested by the user
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartGoHome), object: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                LogUtils.i(TAG, "RTH Initiated successfully.")
            }
            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "Failed to initiate RTH: ${error.description()}")
            }
        })
    }

    private fun setupTelemetryListeners() {
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation), this) { _, value ->
            value?.let {
                currentLat = it.latitude
                currentLng = it.longitude
            }
        }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAltitude), this) { _, value ->
            value?.let { currentAlt = it }
        }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyCompassHeading), this) { _, value ->
            value?.let { currentYaw = it }
        }
    }

    private fun setupSafetyOverrideListener() {
        val km = KeyManager.getInstance()
        val abortThreshold = 50 // roughly 5-10% of stick deflection (range usually -660 to 660)

        val stickListener = { _: Int?, value: Int? ->
            if (isMissionRunning.get() && value != null && abs(value) > abortThreshold) {
                LogUtils.i(TAG, "SAFETY OVERRIDE: Pilot nudged stick. Aborting mission.")
                stopMission()
            }
        }

        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftVertical), this, stickListener)
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftHorizontal), this, stickListener)
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightVertical), this, stickListener)
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightHorizontal), this, stickListener)
    }

    // --- Math Utilities ---
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        var theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360) % 360
    }

    private fun triggerPhoto() {
        LogUtils.i(TAG, "Triggering Photo at waypoint...")
        KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN), object: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                LogUtils.i(TAG, "Photo captured successfully.")
            }
            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "Photo capture failed: ${error.description()}")
            }
        })
    }
}
