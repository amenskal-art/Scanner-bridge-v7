package com.ameen.uvccam

import com.jiangdg.uvc.UVCCamera
import kotlin.math.abs

/**
 * AUSBC 3.3.3 ships native exposure functions inside UVCCamera but never exposes
 * a public Java wrapper for them. This object reaches the private native methods
 * via reflection and maps exposure to a 0..100 percent scale, matching how the
 * library handles brightness, contrast, etc.
 *
 * UVC CT_AE_MODE values: 1 = manual, 2 = full auto, 4 = shutter priority,
 * 8 = aperture priority. Most webcams implement 1 and 8.
 */
object UvcExposure {

    private const val MODE_MANUAL = 1
    private const val MODE_FULL_AUTO = 2
    private const val MODE_APERTURE_PRIORITY = 8

    private val cls = UVCCamera::class.java

    private val ptrField by lazy {
        cls.getDeclaredField("mNativePtr").apply { isAccessible = true }
    }
    private val setModeMethod by lazy {
        cls.getDeclaredMethod("nativeSetExposureMode", java.lang.Long.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
    }
    private val getModeMethod by lazy {
        cls.getDeclaredMethod("nativeGetExposureMode", java.lang.Long.TYPE)
            .apply { isAccessible = true }
    }
    private val updateLimitMethod by lazy {
        cls.getDeclaredMethod("nativeUpdateExposureLimit", java.lang.Long.TYPE)
            .apply { isAccessible = true }
    }
    private val setExposureMethod by lazy {
        cls.getDeclaredMethod("nativeSetExposure", java.lang.Long.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
    }
    private val getExposureMethod by lazy {
        cls.getDeclaredMethod("nativeGetExposure", java.lang.Long.TYPE)
            .apply { isAccessible = true }
    }

    private fun nativePtr(camera: UVCCamera): Long = ptrField.getLong(camera)

    private fun intField(camera: UVCCamera, name: String): Int =
        cls.getDeclaredField(name).apply { isAccessible = true }.getInt(camera)

    /** Switch between auto and manual exposure. */
    fun setAutoExposure(camera: UVCCamera, auto: Boolean) {
        synchronized(camera) {
            val ptr = nativePtr(camera)
            if (ptr == 0L) return
            if (!auto) {
                setModeMethod.invoke(null, ptr, MODE_MANUAL)
                return
            }
            // Aperture priority is the standard "auto" mode on webcams;
            // fall back to full auto if the camera rejected it.
            setModeMethod.invoke(null, ptr, MODE_APERTURE_PRIORITY)
            val current = getModeMethod.invoke(null, ptr) as? Int ?: return
            if (current == MODE_MANUAL) {
                setModeMethod.invoke(null, ptr, MODE_FULL_AUTO)
            }
        }
    }

    /** True when the camera is in any auto exposure mode. */
    fun isAutoExposure(camera: UVCCamera): Boolean {
        synchronized(camera) {
            val ptr = nativePtr(camera)
            if (ptr == 0L) return true
            val mode = getModeMethod.invoke(null, ptr) as? Int ?: return true
            return mode != MODE_MANUAL
        }
    }

    /** Set exposure time as 0..100 percent of the camera's supported range. */
    fun setExposurePercent(camera: UVCCamera, percent: Int) {
        synchronized(camera) {
            val ptr = nativePtr(camera)
            if (ptr == 0L) return
            updateLimitMethod.invoke(camera, ptr)
            val min = intField(camera, "mExposureMin")
            val max = intField(camera, "mExposureMax")
            val range = abs(max - min)
            if (range > 0) {
                val absValue = (percent.coerceIn(0, 100) / 100f * range).toInt() + min
                setExposureMethod.invoke(null, ptr, absValue)
            }
        }
    }

    /** Read exposure time as 0..100 percent of the camera's supported range. */
    fun getExposurePercent(camera: UVCCamera): Int {
        synchronized(camera) {
            val ptr = nativePtr(camera)
            if (ptr == 0L) return 50
            updateLimitMethod.invoke(camera, ptr)
            val min = intField(camera, "mExposureMin")
            val max = intField(camera, "mExposureMax")
            val range = abs(max - min)
            if (range <= 0) return 50
            val absValue = getExposureMethod.invoke(null, ptr) as? Int ?: return 50
            return (((absValue - min) * 100f) / range).toInt().coerceIn(0, 100)
        }
    }
}
