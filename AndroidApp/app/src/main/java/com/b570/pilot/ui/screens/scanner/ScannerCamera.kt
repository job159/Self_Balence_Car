package com.b570.pilot.ui.screens.scanner

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.b570.pilot.link.QrResult
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.ui.components.rememberDisplayRotation
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "B570Scanner"

/** 起始線性變焦：OLED 上的 QR 只有約 10 mm，先放大一點。 */
internal const val INITIAL_ZOOM = 0.25f

/** ML Kit 自動變焦上限（倍）。 */
private const val MAX_AUTO_ZOOM = 6f

/** 曝光補償（EV）：亮的 OLED 在暗背景上容易過曝糊掉，稍微壓暗。 */
private const val EXPOSURE_BIAS_EV = -0.7f

internal enum class CamStatus { STARTING, STREAMING, NO_CAMERA, ERROR }

/** 掃描成功：車子的連線資訊 + QR 在預覽上的位置（px；null = 無法換算）。 */
internal class ScanSuccess(val credentials: WifiCredentials, val rect: Rect?)

/**
 * 掃描頁狀態與相機控制。所有 Compose state 只在主執行緒寫入；
 * [onAutoZoom] 由 ML Kit 執行緒呼叫，只碰 @Volatile 欄位。
 */
@Stable
internal class ScannerController {
    var status by mutableStateOf(CamStatus.STARTING)
        private set
    var hasTorch by mutableStateOf(false)
        private set
    var torchOn by mutableStateOf(false)
        private set
    var zoomLinear by mutableFloatStateOf(INITIAL_ZOOM)
        private set
    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var zoomAvailable by mutableStateOf(false)
        private set
    var success by mutableStateOf<ScanSuccess?>(null)
        private set
    /** 每次拒絕（非 B570 的 QR）+1，覆蓋層用來閃紅光。 */
    var rejectCount by mutableIntStateOf(0)
        private set
    /** 變更即重新綁定相機（「重試」）。 */
    var bindAttempt by mutableIntStateOf(0)
        private set

    /** 預覽在畫面上的尺寸（px），把 QR 位置換算到畫面用。 */
    var viewWidth = 0
    var viewHeight = 0

    @Volatile private var camera: Camera? = null
    @Volatile private var userZoomAtMs = 0L
    private var minRatio = 1f
    private var maxRatio = 1f
    private var pinchRatio = 1f
    private var lastPinchAtMs = 0L
    private var lastRejectRaw: String? = null
    private var lastRejectAtMs = 0L
    private var lastAnyRejectAtMs = 0L

    // ------------------------------------------------------------------ 相機生命週期

    fun attach(cam: Camera) {
        camera = cam
        hasTorch = cam.cameraInfo.hasFlashUnit()
        cam.cameraInfo.zoomState.value?.let { onZoomState(it) }
        cam.cameraControl.setLinearZoom(zoomLinear)
        applyExposureBias(cam)
    }

    fun detach() {
        camera = null
        torchOn = false
    }

    fun onStreaming() {
        if (status == CamStatus.STARTING) status = CamStatus.STREAMING
    }

    fun onCameraError(noCamera: Boolean) {
        status = if (noCamera) CamStatus.NO_CAMERA else CamStatus.ERROR
    }

    fun retryBind() {
        status = CamStatus.STARTING
        bindAttempt++
    }

    fun onTorchState(state: Int) {
        torchOn = state == TorchState.ON
    }

    fun onZoomState(z: ZoomState) {
        zoomRatio = z.zoomRatio
        minRatio = z.minZoomRatio
        maxRatio = z.maxZoomRatio
        zoomAvailable = maxRatio > minRatio + 0.01f
        // 使用者剛拖過滑桿：以手上的值為準，避免滑塊被舊值拉回
        if (SystemClock.uptimeMillis() - userZoomAtMs > 600) zoomLinear = z.linearZoom
    }

    // ------------------------------------------------------------------ 使用者操作

    /** 狀態以 TorchState 回報為準（開啟失敗時圖示不會騙人）。 */
    fun toggleTorch() {
        camera?.cameraControl?.enableTorch(!torchOn)
    }

    fun setLinearZoom(value: Float) {
        val v = value.coerceIn(0f, 1f)
        userZoomAtMs = SystemClock.uptimeMillis()
        zoomLinear = v
        camera?.cameraControl?.setLinearZoom(v)
    }

    fun pinch(factor: Float) {
        val cam = camera ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastPinchAtMs > 300) pinchRatio = zoomRatio
        lastPinchAtMs = now
        pinchRatio = (pinchRatio * factor).coerceIn(minRatio, maxRatio)
        cam.cameraControl.setZoomRatio(pinchRatio)
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /** ML Kit 建議放大（QR 太小）。使用者 2 秒內動過變焦就不搶。 */
    fun onAutoZoom(ratio: Float): Boolean {
        val cam = camera ?: return false
        if (SystemClock.uptimeMillis() - userZoomAtMs < 2000) return false
        cam.cameraControl.setZoomRatio(ratio)
        return true
    }

    // ------------------------------------------------------------------ 辨識結果（主執行緒）

    /** @return true = 找到 B570 配對碼（之後不再分析） */
    fun onBarcodes(codes: List<Barcode>, imageWidth: Int, imageHeight: Int, onReject: (String) -> Unit): Boolean {
        if (success != null) return true
        for (b in codes) {
            val raw = b.rawValue ?: continue
            when (val r = WifiCredentials.parseCarQr(raw)) {
                is QrResult.Ok -> {
                    success = ScanSuccess(r.credentials, mapToView(b.boundingBox, imageWidth, imageHeight))
                    return true
                }
                QrResult.NotWifi -> reject(raw, NOT_B570, onReject)
                is QrResult.Invalid -> {
                    // 看起來是 B570 但格式不對才顯示細節，其他 Wi-Fi QR 一律說「不是 B570」
                    val looksLikeCar = WifiCredentials.parseQr(raw)?.ssid?.startsWith("B570", ignoreCase = true) == true
                    reject(raw, if (looksLikeCar) r.reasonZh else NOT_B570, onReject)
                }
            }
        }
        return false
    }

    private fun reject(raw: String, message: String, onReject: (String) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (raw == lastRejectRaw && now - lastRejectAtMs < 6000) return
        if (now - lastAnyRejectAtMs < 2500) return
        lastRejectRaw = raw
        lastRejectAtMs = now
        lastAnyRejectAtMs = now
        rejectCount++
        onReject(message)
    }

    /** 影像座標（已轉正）→ 預覽座標（PreviewView FILL_CENTER，兩個串流同為 16:9）。 */
    private fun mapToView(box: android.graphics.Rect?, iw: Int, ih: Int): Rect? {
        if (box == null || iw <= 0 || ih <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val s = max(viewWidth / iw.toFloat(), viewHeight / ih.toFloat())
        val dx = (viewWidth - iw * s) / 2f
        val dy = (viewHeight - ih * s) / 2f
        val r = Rect(box.left * s + dx, box.top * s + dy, box.right * s + dx, box.bottom * s + dy)
        val sane = r.width > 12f && r.height > 12f && r.right > 0f && r.bottom > 0f &&
            r.left < viewWidth && r.top < viewHeight && r.width < viewWidth * 1.2f
        return if (sane) r else null
    }

    private fun applyExposureBias(cam: Camera) {
        val es = cam.cameraInfo.exposureState
        if (!es.isExposureCompensationSupported) return
        val step = es.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = es.exposureCompensationRange
        val index = (EXPOSURE_BIAS_EV / step).roundToInt().coerceIn(range.lower, range.upper)
        if (index != 0) cam.cameraControl.setExposureCompensationIndex(index)
    }

    private companion object {
        const val NOT_B570 = "這不是 B570 的配對碼"
    }
}

/** 目前綁定中的分析用例（螢幕左右翻轉時更新 targetRotation）。 */
private class BoundAnalysis {
    var analysis: ImageAnalysis? = null
}

/**
 * 綁定 CameraX（Preview + ImageAnalysis 1280×720 / KEEP_ONLY_LATEST）與 ML Kit QR 掃描。
 * 離開組合時解除綁定、關閉掃描器與執行緒。
 *
 * 橫向左右翻轉（ROTATION_90 ⇄ 270）不會重建 Activity：PreviewView（COMPATIBLE）自己跟著顯示旋轉，
 * 但 ImageAnalysis 的 targetRotation 要自己更新，否則影像的 rotationDegrees 差 180°，
 * QR 框位置（成功動畫）會畫在相反的位置。這裡用 [rememberDisplayRotation] 即時跟上。
 */
@Composable
internal fun CameraBinding(scan: ScannerController, previewView: PreviewView, onReject: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val reject by rememberUpdatedState(onReject)
    val rotation = rememberDisplayRotation()
    val bound = remember { BoundAnalysis() }
    val displayRotation = rotation.value
    LaunchedEffect(displayRotation) {
        bound.analysis?.let { if (it.targetRotation != displayRotation) it.targetRotation = displayRotation }
    }
    DisposableEffect(owner, previewView, scan.bindAttempt) {
        val main = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation.value)
            .build()
        bound.analysis = analysis

        var disposed = false
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        var scanner: BarcodeScanner? = null
        var analyzer: QrAnalyzer? = null
        val zoomObserver = Observer<ZoomState> { scan.onZoomState(it) }
        val torchObserver = Observer<Int> { scan.onTorchState(it) }
        val streamObserver = Observer<PreviewView.StreamState> { if (it == PreviewView.StreamState.STREAMING) scan.onStreaming() }
        previewView.previewStreamState.observe(owner, streamObserver)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    scan.onCameraError(noCamera = true)
                    return@addListener
                }
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val cam = p.bindToLifecycle(owner, selector, preview, analysis)
                camera = cam
                scan.attach(cam)
                cam.cameraInfo.zoomState.observe(owner, zoomObserver)
                cam.cameraInfo.torchState.observe(owner, torchObserver)

                val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .apply {
                        if (maxZoom > 1.2f) {
                            setZoomSuggestionOptions(
                                ZoomSuggestionOptions.Builder { ratio -> scan.onAutoZoom(ratio) }
                                    .setMaxSupportedZoomRatio(min(maxZoom, MAX_AUTO_ZOOM))
                                    .build(),
                            )
                        }
                    }
                    .build()
                val sc = BarcodeScanning.getClient(options)
                scanner = sc
                val a = QrAnalyzer(sc, main) { codes, w, h ->
                    if (!disposed && scan.onBarcodes(codes, w, h) { msg -> reject(msg) }) analyzer?.stopped = true
                }
                analyzer = a
                analysis.setAnalyzer(analysisExecutor, a)
            } catch (e: Exception) {
                Log.w(TAG, "camera bind failed", e)
                scan.onCameraError(noCamera = false)
            }
        }, main)

        onDispose {
            disposed = true
            analyzer?.stopped = true
            analysis.clearAnalyzer()
            previewView.previewStreamState.removeObserver(streamObserver)
            camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            camera?.cameraInfo?.torchState?.removeObserver(torchObserver)
            runCatching { provider?.unbind(preview, analysis) }
            scanner?.close()
            analysisExecutor.shutdown()
            if (bound.analysis === analysis) bound.analysis = null
            scan.detach()
        }
    }
}

/** 建立一次 PreviewView（TextureView 模式：覆蓋層滑入 / 淡出動畫才會正確）。 */
@Composable
internal fun rememberPreviewView(): PreviewView {
    val context = LocalContext.current
    return remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
}

private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean =
    try {
        hasCamera(selector)
    } catch (e: Exception) {
        false
    }

/** ML Kit 分析器：一次只處理一張（處理完才 close → KEEP_ONLY_LATEST 自然節流）。 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val callbackExecutor: Executor,
    private val onResult: (List<Barcode>, Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var stopped = false

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (stopped || media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val upright = rotation % 180 == 0
        val w = if (upright) image.width else image.height
        val h = if (upright) image.height else image.width
        try {
            scanner.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener(callbackExecutor) { codes -> if (!stopped && codes.isNotEmpty()) onResult(codes, w, h) }
                .addOnCompleteListener(callbackExecutor) { image.close() }
        } catch (e: Exception) {
            image.close()
        }
    }
}
