package dji.v5.ux.core.util

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.LogUtils
import dji.sdk.keyvalue.value.common.ComponentIndexType
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
    private const val TAG = "StreamingManager"
    private var videoSocket: DatagramSocket? = null
    private var telemetrySocket: DatagramSocket? = null
    
    private val isVideoStreaming = AtomicBoolean(false)
    private val isTelemetryStreaming = AtomicBoolean(false)
    
    private var videoPort = 5008
    private var telemetryPort = 14550
    private var targetAddress = InetAddress.getByName("255.255.255.255")
    
    private const val MAX_UDP_PAYLOAD = 65507
    private var seq: Byte = 0

    private val videoReceiveStreamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, _ ->
        if (isVideoStreaming.get()) {
            sendVideoData(data, offset, length)
        }
    }

    fun startVideoStreaming(port: Int, address: String) {
        videoPort = port
        targetAddress = InetAddress.getByName(address)
        if (videoSocket == null) {
            videoSocket = DatagramSocket().apply { broadcast = true }
        }
        isVideoStreaming.set(true)
        MediaDataCenter.getInstance().cameraStreamManager.addReceiveStreamListener(ComponentIndexType.LEFT_OR_MAIN, videoReceiveStreamListener)
        LogUtils.i(TAG, "Started video streaming on $address:$port")
    }

    fun stopVideoStreaming() {
        isVideoStreaming.set(false)
        MediaDataCenter.getInstance().cameraStreamManager.removeReceiveStreamListener(videoReceiveStreamListener)
        videoSocket?.close()
        videoSocket = null
    }

    private fun sendVideoData(data: ByteArray, offset: Int, length: Int) {
        DJIExecutor.getExecutor().execute {
            try {
                if (length > MAX_UDP_PAYLOAD) {
                    var remaining = length
                    var currentOffset = offset
                    while (remaining > 0) {
                        val chunkSize = if (remaining > MAX_UDP_PAYLOAD) MAX_UDP_PAYLOAD else remaining
                        videoSocket?.send(DatagramPacket(data, currentOffset, chunkSize, targetAddress, videoPort))
                        currentOffset += chunkSize
                        remaining -= chunkSize
                    }
                } else {
                    videoSocket?.send(DatagramPacket(data, offset, length, targetAddress, videoPort))
                }
            } catch (e: Exception) {}
        }
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

    private fun sendMavlinkPacket(msgId: Int, payload: ByteArray) {
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
            tmp = tmp xor (tmp shl 4)
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        }
        packet.putShort(crc.toShort())
        
        val data = packet.array()
        telemetrySocket?.send(DatagramPacket(data, data.size, targetAddress, telemetryPort))
    }
}
