package dji.v5.ux.core.util
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.LogUtils
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton to manage UDP video and MAVLink telemetry streaming.
 */
object StreamingManager {

    fun setLiveStreamQuality(quality: StreamQuality) {
        MediaDataCenter.getInstance().liveStreamManager.liveStreamQuality = quality
    }

    fun setLiveVideoBitrateMode(mode: LiveVideoBitrateMode) {
        MediaDataCenter.getInstance().liveStreamManager.liveVideoBitrateMode = mode
    }

    fun setLiveVideoBitrate(bitrate: Int) {
        MediaDataCenter.getInstance().liveStreamManager.liveVideoBitrate = bitrate
    }
    private const val TAG = "StreamingManager"
    private var telemetrySocket: DatagramSocket? = null
    
    private val isVideoStreaming = AtomicBoolean(false)
    private val isTelemetryStreaming = AtomicBoolean(false)
    
    private var videoPort = 5008
    private var telemetryPort = 14550
    private var targetAddress = InetAddress.getByName("255.255.255.255")
    
    private const val MAX_UDP_PAYLOAD = 65507
    private var seq: Byte = 0

    fun startVideoStreaming(port: Int, address: String) {
        val rtmpUrl = "rtmp://$address:$port/live/drone"
        val liveStreamConfig = LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(
                RtmpSettings.Builder()
                    .setUrl(rtmpUrl)
                    .build()
            )
            .build()
            
        val streamManager = MediaDataCenter.getInstance().liveStreamManager
        streamManager.liveStreamSettings = liveStreamConfig
        
        streamManager.startStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                isVideoStreaming.set(true)
                LogUtils.i(TAG, "Started RTMP streaming to $rtmpUrl")
            }
            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "Failed to start RTMP stream: ${error.description()}")
            }
        })
    }

    fun stopVideoStreaming() {
        val streamManager = MediaDataCenter.getInstance().liveStreamManager
        streamManager.stopStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                isVideoStreaming.set(false)
                LogUtils.i(TAG, "Stopped RTMP streaming")
            }
            override fun onFailure(error: IDJIError) {}
        })
    }

    fun startTelemetryStreaming(port: Int, address: String) {
        telemetryPort = port
        targetAddress = InetAddress.getByName(address)
        if (telemetrySocket == null) {
            telemetrySocket = DatagramSocket().apply { broadcast = true }
        }
        isTelemetryStreaming.set(true)
        startTelemetryLoop()
        LogUtils.i(TAG, "Started MAVLink telemetry on $address:$port")
    }

    fun stopTelemetryStreaming() {
        isTelemetryStreaming.set(false)
        telemetrySocket?.close()
        telemetrySocket = null
    }

    private fun startTelemetryLoop() {
        DJIExecutor.getExecutor().execute {
            while (isTelemetryStreaming.get()) {
                try {
                    sendMavlinkHeartbeat()
                    sendMavlinkAttitude()
                    sendMavlinkPosition()
                    sendMavlinkGpsRawInt()
                } catch (e: Exception) {
                    LogUtils.e(TAG, "MAVLink loop error: ${e.message}")
                }
                Thread.sleep(200) // 5Hz
            }
        }
    }

    private fun sendMavlinkHeartbeat() {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(0) // custom_mode
        payload.put(2)    // type: quadrotor
        payload.put(3)    // autopilot: ardupilot
        payload.put(81.toByte()) // base_mode: armed
        payload.put(4)    // system_status: active
        payload.put(3)    // mavlink_version
        sendMavlinkPacket(0, payload.array())
    }

    private fun sendMavlinkAttitude() {
        val km = KeyManager.getInstance()
        val att = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt((System.currentTimeMillis() % 1000000).toInt())
        payload.putFloat(Math.toRadians(att?.roll ?: 0.0).toFloat())
        payload.putFloat(Math.toRadians(att?.pitch ?: 0.0).toFloat())
        payload.putFloat(Math.toRadians(att?.yaw ?: 0.0).toFloat())
        payload.putFloat(0f) // rollspeed
        payload.putFloat(0f) // pitchspeed
        payload.putFloat(0f) // yawspeed
        sendMavlinkPacket(30, payload.array())
    }

    private fun sendMavlinkPosition() {
        val km = KeyManager.getInstance()
        val loc = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation))
        val alt = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude)) ?: 0.0
        val vel = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
        
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt((System.currentTimeMillis() % 1000000).toInt())
        payload.putInt(((loc?.latitude ?: 0.0) * 1e7).toInt())
        payload.putInt(((loc?.longitude ?: 0.0) * 1e7).toInt())
        payload.putInt((alt * 1000).toInt()) // MSL mm
        payload.putInt((alt * 1000).toInt()) // Relative mm
        payload.putShort(((vel?.x ?: 0.0) * 100).toInt().toShort()) // cm/s
        payload.putShort(((vel?.y ?: 0.0) * 100).toInt().toShort())
        payload.putShort(((vel?.z ?: 0.0) * 100).toInt().toShort())
        payload.putShort(0) // heading
        sendMavlinkPacket(33, payload.array())
    }

    private fun sendMavlinkGpsRawInt() {
        val km = KeyManager.getInstance()
        val loc = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation))
        val alt = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude)) ?: 0.0
        val sats = km.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0
        val vel = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
        
        val speed = if (vel != null) Math.sqrt(vel.x * vel.x + vel.y * vel.y) else 0.0
        
        val fixType = if (sats >= 4 && loc != null && loc.latitude != 0.0) 3 else 0

        val payload = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        payload.putLong(System.currentTimeMillis() * 1000L) // time_usec (uint64)
        payload.put(fixType.toByte()) // fix_type (uint8)
        payload.putInt(((loc?.latitude ?: 0.0) * 1e7).toInt()) // lat (int32)
        payload.putInt(((loc?.longitude ?: 0.0) * 1e7).toInt()) // lon (int32)
        payload.putInt((alt * 1000).toInt()) // alt (int32)
        payload.putShort(0xFFFF.toShort()) // eph (uint16)
        payload.putShort(0xFFFF.toShort()) // epv (uint16)
        payload.putShort((speed * 100).toInt().toShort()) // vel (uint16) cm/s
        payload.putShort(0) // cog (uint16)
        payload.put(sats.toByte()) // satellites_visible (uint8)
        
        sendMavlinkPacket(24, payload.array())
    }

    fun sendMavlinkPacket(msgId: Int, payload: ByteArray) {
        val packet = ByteBuffer.allocate(payload.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(0xFE.toByte())      // STX
        packet.put(payload.size.toByte())
        packet.put(seq++)
        packet.put(1.toByte())         // SYS ID
        packet.put(1.toByte())         // COMP ID
        packet.put(msgId.toByte())
        packet.put(payload)
        
        var crc = 0xFFFF
        for (i in 1 until packet.position()) {
            var tmp = (packet.get(i).toInt() and 0xFF) xor (crc and 0xFF)
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        }
        
        val crcExtra = when(msgId) {
            0 -> 50
            24 -> 24
            30 -> 39
            33 -> 104
            else -> 0
        }
        if (crcExtra > 0) {
            var tmp = (crcExtra and 0xFF) xor (crc and 0xFF)
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        }
        
        packet.putShort(crc.toShort())
        
        val data = packet.array()
        telemetrySocket?.send(DatagramPacket(data, data.size, targetAddress, telemetryPort))
    }
}
