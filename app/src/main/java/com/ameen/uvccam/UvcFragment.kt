package com.ameen.uvccam

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.uvc.UVCCamera
import java.util.concurrent.Executors

class UvcFragment : CameraFragment() {

    private lateinit var content: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlExecutor = Executors.newSingleThreadExecutor()
    private val pendingWrites = HashMap<String, Runnable>()

    @Volatile
    private var uvcCamera: UVCCamera? = null

    @Volatile
    private var safeMode = false

    private var suppressUi = false
    private var connected = false

    // Views
    private lateinit var statusChip: TextView
    private lateinit var switchAutoExposure: MaterialSwitch
    private lateinit var sliderExposure: Slider
    private lateinit var valueExposure: TextView
    private lateinit var switchAutoWb: MaterialSwitch
    private lateinit var sliderWb: Slider
    private lateinit var valueWb: TextView
    private lateinit var sliderBrightness: Slider
    private lateinit var valueBrightness: TextView
    private lateinit var sliderContrast: Slider
    private lateinit var valueContrast: TextView
    private lateinit var sliderSaturation: Slider
    private lateinit var valueSaturation: TextView
    private lateinit var sliderSharpness: Slider
    private lateinit var valueSharpness: TextView
    private lateinit var sliderZoom: Slider
    private lateinit var valueZoom: TextView
    private lateinit var switchSafeMode: MaterialSwitch
    private lateinit var btnReset: MaterialButton

    // ---------------------------------------------------------------------
    // CameraFragment plumbing
    // ---------------------------------------------------------------------

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        content = inflater.inflate(R.layout.fragment_uvc, container, false)
        return content
    }

    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(requireContext())

    override fun getCameraViewContainer(): ViewGroup = content.findViewById(R.id.cameraContainer)

    override fun getCameraRequest(): CameraRequest =
        CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAspectRatioShow(true)
            .setRawPreviewData(false)
            .setCaptureRawImage(false)
            .create()

    override fun initView() {
        super.initView()
        bindViews()
        setControlsEnabled(false)
        setStatus(getString(R.string.status_waiting))
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        controlExecutor.shutdownNow()
    }

    // ---------------------------------------------------------------------
    // Camera state
    // ---------------------------------------------------------------------

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        mainHandler.post {
            if (!isAdded) return@post
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    uvcCamera = extractUvcCamera(self)
                    val name = self.device.productName?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.generic_camera)
                    setStatus(getString(R.string.status_connected, name))
                    setControlsEnabled(true)
                    refreshFromCamera(400L)
                }
                ICameraStateCallBack.State.CLOSED -> {
                    uvcCamera = null
                    setControlsEnabled(false)
                    setStatus(getString(R.string.status_waiting))
                }
                ICameraStateCallBack.State.ERROR -> {
                    setStatus(getString(R.string.status_error, msg ?: "unknown"))
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // UI binding
    // ---------------------------------------------------------------------

    private fun bindViews() {
        statusChip = content.findViewById(R.id.statusChip)
        switchAutoExposure = content.findViewById(R.id.switchAutoExposure)
        sliderExposure = content.findViewById(R.id.sliderExposure)
        valueExposure = content.findViewById(R.id.valueExposure)
        switchAutoWb = content.findViewById(R.id.switchAutoWb)
        sliderWb = content.findViewById(R.id.sliderWb)
        valueWb = content.findViewById(R.id.valueWb)
        sliderBrightness = content.findViewById(R.id.sliderBrightness)
        valueBrightness = content.findViewById(R.id.valueBrightness)
        sliderContrast = content.findViewById(R.id.sliderContrast)
        valueContrast = content.findViewById(R.id.valueContrast)
        sliderSaturation = content.findViewById(R.id.sliderSaturation)
        valueSaturation = content.findViewById(R.id.valueSaturation)
        sliderSharpness = content.findViewById(R.id.sliderSharpness)
        valueSharpness = content.findViewById(R.id.valueSharpness)
        sliderZoom = content.findViewById(R.id.sliderZoom)
        valueZoom = content.findViewById(R.id.valueZoom)
        switchSafeMode = content.findViewById(R.id.switchSafeMode)
        btnReset = content.findViewById(R.id.btnReset)

        bindSlider(sliderExposure, valueExposure, "exposure") { cam, v ->
            UvcExposure.setExposurePercent(cam, v)
        }
        bindSlider(sliderWb, valueWb, "wb") { cam, v -> cam.whiteBlance = v }
        bindSlider(sliderBrightness, valueBrightness, "brightness") { cam, v -> cam.brightness = v }
        bindSlider(sliderContrast, valueContrast, "contrast") { cam, v -> cam.contrast = v }
        bindSlider(sliderSaturation, valueSaturation, "saturation") { cam, v -> cam.saturation = v }
        bindSlider(sliderSharpness, valueSharpness, "sharpness") { cam, v -> cam.sharpness = v }
        bindSlider(sliderZoom, valueZoom, "zoom") { cam, v -> cam.zoom = v }

        switchAutoExposure.setOnCheckedChangeListener { _, checked ->
            sliderExposure.isEnabled = connected && !checked
            if (suppressUi) return@setOnCheckedChangeListener
            scheduleWrite("aeMode", 0L) { cam -> UvcExposure.setAutoExposure(cam, checked) }
        }

        switchAutoWb.setOnCheckedChangeListener { _, checked ->
            sliderWb.isEnabled = connected && !checked
            if (suppressUi) return@setOnCheckedChangeListener
            scheduleWrite("awb", 0L) { cam -> cam.autoWhiteBlance = checked }
        }

        switchSafeMode.setOnCheckedChangeListener { _, checked -> safeMode = checked }

        btnReset.setOnClickListener { resetAll() }
    }

    private fun bindSlider(
        slider: Slider,
        valueView: TextView,
        key: String,
        write: (UVCCamera, Int) -> Unit
    ) {
        valueView.text = slider.value.toInt().toString()
        slider.addOnChangeListener { _, value, fromUser ->
            valueView.text = value.toInt().toString()
            if (!fromUser || suppressUi) return@addOnChangeListener
            val delay = if (safeMode) 450L else 160L
            scheduleWrite(key, delay) { cam -> write(cam, value.toInt()) }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        connected = enabled
        listOf<View>(
            switchAutoExposure, switchAutoWb,
            sliderBrightness, sliderContrast, sliderSaturation,
            sliderSharpness, sliderZoom, btnReset
        ).forEach { it.isEnabled = enabled }
        sliderExposure.isEnabled = enabled && !switchAutoExposure.isChecked
        sliderWb.isEnabled = enabled && !switchAutoWb.isChecked
    }

    private fun setStatus(text: String) {
        statusChip.text = text
    }

    // ---------------------------------------------------------------------
    // Control writes (debounced, single background thread, optional safe mode)
    // ---------------------------------------------------------------------

    private fun scheduleWrite(key: String, delayMs: Long, block: (UVCCamera) -> Unit) {
        pendingWrites.remove(key)?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingWrites.remove(key)
            applyControl(block)
        }
        pendingWrites[key] = task
        if (delayMs <= 0L) task.run() else mainHandler.postDelayed(task, delayMs)
    }

    private fun applyControl(block: (UVCCamera) -> Unit) {
        controlExecutor.execute {
            val cam = uvcCamera ?: return@execute
            try {
                if (safeMode) {
                    // Some camera firmwares refuse control transfers while the
                    // stream is active: pause, write, resume.
                    runCatching { cam.stopPreview() }
                    Thread.sleep(180)
                    block(cam)
                    Thread.sleep(120)
                    runCatching { cam.startPreview() }
                } else {
                    block(cam)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Control write failed", t)
            }
        }
    }

    private fun resetAll() {
        controlExecutor.execute {
            val cam = uvcCamera ?: return@execute
            try {
                if (safeMode) {
                    runCatching { cam.stopPreview() }
                    Thread.sleep(180)
                }
                runCatching { cam.resetBrightness() }
                runCatching { cam.resetContrast() }
                runCatching { cam.resetSaturation() }
                runCatching { cam.resetSharpness() }
                runCatching { cam.resetZoom() }
                runCatching { cam.resetWhiteBlance() }
                runCatching { cam.autoWhiteBlance = true }
                runCatching { UvcExposure.setAutoExposure(cam, true) }
                if (safeMode) {
                    Thread.sleep(120)
                    runCatching { cam.startPreview() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Reset failed", t)
            }
        }
        refreshFromCamera(600L)
    }

    // ---------------------------------------------------------------------
    // Reading camera state back into the UI
    // ---------------------------------------------------------------------

    private data class Snapshot(
        val brightness: Int,
        val contrast: Int,
        val saturation: Int,
        val sharpness: Int,
        val zoom: Int,
        val autoWb: Boolean,
        val wbTemp: Int,
        val autoExposure: Boolean,
        val exposure: Int
    )

    private fun refreshFromCamera(delayMs: Long) {
        controlExecutor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@execute
            }
            val cam = uvcCamera ?: return@execute
            val snap = readSnapshot(cam)
            mainHandler.post {
                if (!isAdded) return@post
                pushToUi(snap)
            }
        }
    }

    private fun readSnapshot(cam: UVCCamera): Snapshot = Snapshot(
        brightness = runCatching { cam.brightness }.getOrDefault(50),
        contrast = runCatching { cam.contrast }.getOrDefault(50),
        saturation = runCatching { cam.saturation }.getOrDefault(50),
        sharpness = runCatching { cam.sharpness }.getOrDefault(50),
        zoom = runCatching { cam.zoom }.getOrDefault(0),
        autoWb = runCatching { cam.autoWhiteBlance }.getOrDefault(true),
        wbTemp = runCatching { cam.whiteBlance }.getOrDefault(50),
        autoExposure = runCatching { UvcExposure.isAutoExposure(cam) }.getOrDefault(true),
        exposure = runCatching { UvcExposure.getExposurePercent(cam) }.getOrDefault(50)
    )

    private fun pushToUi(s: Snapshot) {
        suppressUi = true
        sliderBrightness.value = s.brightness.coerceIn(0, 100).toFloat()
        sliderContrast.value = s.contrast.coerceIn(0, 100).toFloat()
        sliderSaturation.value = s.saturation.coerceIn(0, 100).toFloat()
        sliderSharpness.value = s.sharpness.coerceIn(0, 100).toFloat()
        sliderZoom.value = s.zoom.coerceIn(0, 100).toFloat()
        sliderWb.value = s.wbTemp.coerceIn(0, 100).toFloat()
        sliderExposure.value = s.exposure.coerceIn(0, 100).toFloat()
        switchAutoWb.isChecked = s.autoWb
        switchAutoExposure.isChecked = s.autoExposure
        sliderWb.isEnabled = connected && !s.autoWb
        sliderExposure.isEnabled = connected && !s.autoExposure
        suppressUi = false
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * AUSBC keeps the raw UVCCamera private inside CameraUVC. Locate it by
     * field type so this survives field renames across library versions.
     */
    private fun extractUvcCamera(camera: MultiCameraClient.ICamera): UVCCamera? {
        var cls: Class<*>? = camera.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (UVCCamera::class.java.isAssignableFrom(field.type)) {
                    return try {
                        field.isAccessible = true
                        field.get(camera) as? UVCCamera
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to extract UVCCamera", t)
                        null
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    companion object {
        private const val TAG = "UvcFragment"
    }
}
