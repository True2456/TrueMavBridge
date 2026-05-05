package dji.v5.ux.core.util

import dji.v5.utils.common.LogUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles the MAVLink Mission Microservice protocol.
 * Stores incoming waypoints and triggers the VirtualStickMissionManager.
 */
class MavlinkMissionHandler(private val sendPacketCallback: (msgId: Int, payload: ByteArray) -> Unit) {
    private val TAG = "MavlinkMissionHandler"
    
    private var expectedMissionCount = 0
    private var missionItems = mutableListOf<WaypointItem>()

    fun getMissionItems(): List<WaypointItem> {
        return missionItems
    }

    fun handleMissionMessage(msgId: Int, payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        when (msgId) {
            44 -> { // MISSION_COUNT
                expectedMissionCount = buffer.short.toInt() and 0xFFFF
                val targetSystem = buffer.get().toInt() and 0xFF
                val targetComp = buffer.get().toInt() and 0xFF
                LogUtils.i(TAG, "Received MISSION_COUNT: $expectedMissionCount")
                
                missionItems.clear()
                if (expectedMissionCount > 0) {
                    sendMissionRequest(0)
                } else {
                    sendMissionAck()
                }
            }
            39, 73 -> { // MISSION_ITEM (39) or MISSION_ITEM_INT (73)
                try {
                    val p1 = buffer.float // hold time
                    val p2 = buffer.float // hit radius
                    val p3 = buffer.float // pass radius
                    val p4 = buffer.float // yaw
                    
                    val lat: Double
                    val lng: Double
                    if (msgId == 73) {
                        lat = buffer.int / 1e7
                        lng = buffer.int / 1e7
                    } else {
                        lat = buffer.float.toDouble()
                        lng = buffer.float.toDouble()
                    }
                    val alt = buffer.float.toDouble()
                    
                    val seq = buffer.short.toInt() and 0xFFFF
                    val command = buffer.short.toInt() and 0xFFFF // e.g. 16 = MAV_CMD_NAV_WAYPOINT

                    LogUtils.i(TAG, "Received Waypoint $seq: Lat=$lat, Lng=$lng, Alt=$alt, Hold=$p1")

                    // Ignore home location (seq 0 is sometimes home in some GCS)
                    // Actually, let's just add everything and let the mission manager handle it.
                    val holdTimeMs = (p1 * 1000).toLong()
                    missionItems.add(WaypointItem(lat, lng, alt, holdTimeMs))

                    if (missionItems.size < expectedMissionCount) {
                        sendMissionRequest(missionItems.size)
                    } else {
                        LogUtils.i(TAG, "All $expectedMissionCount waypoints received. Sending ACK.")
                        sendMissionAck()
                        // Automatically start mission when uploaded (or wait for MAV_CMD_MISSION_START)
                        // VirtualStickMissionManager.startMission(missionItems)
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Failed to parse MISSION_ITEM: ${e.message}")
                }
            }
        }
    }

    fun handleCommandLong(command: Int) {
        if (command == 300) { // MAV_CMD_MISSION_START
            LogUtils.i(TAG, "Received MAV_CMD_MISSION_START. Executing ${missionItems.size} waypoints.")
            VirtualStickMissionManager.startMission(missionItems)
        }
        if (command == 193) { // MAV_CMD_DO_PAUSE_CONTINUE (Abort)
             LogUtils.i(TAG, "Received MAV_CMD_DO_PAUSE_CONTINUE. Aborting mission.")
             VirtualStickMissionManager.stopMission()
        }
    }

    private fun sendMissionRequest(seq: Int) {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(seq.toShort()) // seq
        payload.put(255.toByte())       // target_system (GCS)
        payload.put(0.toByte())         // target_component
        sendPacketCallback(40, payload.array())
    }

    private fun sendMissionAck() {
        val payload = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(255.toByte()) // target_system
        payload.put(0.toByte())   // target_component
        payload.put(0.toByte())   // type = MAV_MISSION_ACCEPTED (0)
        sendPacketCallback(47, payload.array())
    }
}
