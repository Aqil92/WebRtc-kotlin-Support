package com.aqil.webrtc.web_rtc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.aqil.webrtc.utils.AppRTCUtils
import org.webrtc.ThreadUtils


/**
 * AppRTCProximitySensor manages functions related to the proximity sensor in
 * the AppRTC demo.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
class AppRTCProximitySensor private constructor(context: Context, private val onSensorStateListener: Runnable?) :
    SensorEventListener {

    // This class should be created, started and stopped on one thread
    // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
    // the case. Only active when |DEBUG| is set to true.
    private val threadChecker = ThreadUtils.ThreadChecker()
    private val sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var lastStateReportIsNear = false

    init {
        Log.d(TAG, "AppRTCProximitySensor" + AppRTCUtils.threadInfo)
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    /**
     * Activate the proximity sensor. Also do initialization if called for the
     * first time.
     */
    fun start(): Boolean {
        threadChecker.checkIsOnValidThread()
        Log.d(TAG, "start" + AppRTCUtils.threadInfo)
        if (!initDefaultSensor()) {
            // Proximity sensor is not supported on this device.
            return false
        }
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        return true
    }

    /**
     * Deactivate the proximity sensor.
     */
    internal fun stop() {
        threadChecker.checkIsOnValidThread()
        Log.d(TAG, "stop" + AppRTCUtils.threadInfo)
        if (proximitySensor == null) {
            return
        }
        sensorManager.unregisterListener(this, proximitySensor)
    }

    /**
     * Getter for last reported state. Set to true if "near" is reported.
     */
    internal fun sensorReportsNearState(): Boolean {
        threadChecker.checkIsOnValidThread()
        return lastStateReportIsNear
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        threadChecker.checkIsOnValidThread()
        AppRTCUtils.assertIsTrue(sensor.type == Sensor.TYPE_PROXIMITY)
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.e(TAG, "The values returned by this sensor cannot be trusted")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        threadChecker.checkIsOnValidThread()
        AppRTCUtils.assertIsTrue(event.sensor.type == Sensor.TYPE_PROXIMITY)
        // As a best practice; do as little as possible within this method and
        // avoid blocking.
        val distanceInCentimeters = event.values[0]
        if (distanceInCentimeters < proximitySensor!!.maximumRange) {
            Log.d(TAG, "Proximity sensor => NEAR state")
            lastStateReportIsNear = true
        } else {
            Log.d(TAG, "Proximity sensor => FAR state")
            lastStateReportIsNear = false
        }

        // Report about new state to listening client. Client can then call
        // sensorReportsNearState() to query the current state (NEAR or FAR).
        onSensorStateListener?.run()

        Log.d(
            TAG, "onSensorChanged" + AppRTCUtils.threadInfo + ": "
                    + "accuracy=" + event.accuracy + ", timestamp=" + event.timestamp + ", distance="
                    + event.values[0]
        )
    }

    /**
     * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
     * does not support this type of sensor and false will be returned in such
     * cases.
     */
    private fun initDefaultSensor(): Boolean {
        if (proximitySensor != null) {
            return true
        }
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            return false
        }
        logProximitySensorInfo()
        return true
    }

    /**
     * Helper method for logging information about the proximity sensor.
     */
    private fun logProximitySensorInfo() {
        if (proximitySensor == null) {
            return
        }
        val info = StringBuilder("Proximity sensor: ")
        info.append("name=").append(proximitySensor!!.name)
        info.append(", vendor: ").append(proximitySensor!!.vendor)
        info.append(", power: ").append(proximitySensor!!.power)
        info.append(", resolution: ").append(proximitySensor!!.resolution)
        info.append(", max range: ").append(proximitySensor!!.maximumRange)
        // Added in API level 9.
        info.append(", min delay: ").append(proximitySensor!!.minDelay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            // Added in API level 20.
            info.append(", type: ").append(proximitySensor!!.stringType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Added in API level 21.
            info.append(", max delay: ").append(proximitySensor!!.maxDelay)
            info.append(", reporting mode: ").append(proximitySensor!!.reportingMode)
            info.append(", isWakeUpSensor: ").append(proximitySensor!!.isWakeUpSensor)
        }
        Log.d(TAG, info.toString())
    }

    companion object {
        private val TAG = "AppRTCProximitySensor"

        /**
         * Construction
         */
        internal fun create(context: Context, sensorStateListener: Runnable): AppRTCProximitySensor {
            return AppRTCProximitySensor(context, sensorStateListener)
        }
    }
}
