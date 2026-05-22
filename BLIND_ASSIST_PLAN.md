# Blind Assist — Implementation Plan
> Dành cho coding agent. Đọc toàn bộ trước khi bắt đầu code.

---

## 1. Tổng quan dự án

App Android (Kotlin) hỗ trợ người khiếm thị phát hiện vật cản và ước lượng khoảng cách trong thời gian thực.
Camera di động theo người dùng đang đi bộ.

**Output cuối:**
- Cảnh báo TTS tiếng Việt
- Nút DevMode: bật/tắt overlay bbox + hiển thị tham số realtime (FPS, số object, Z, tiltRad)

**Stack:**
- Kotlin `1.9.0` + CameraX `1.5.0-alpha06`
- OpenCV `4.10.0` (`org.opencv:opencv`)
- TFLite `2.16.1` — model `MiDaS_small` (`intel-isl/MiDaS`)
- `TYPE_ROTATION_VECTOR` sensor
- TTS (`TextToSpeech` Android API)

---

## 2. Cấu trúc project

```
app/
├── src/main/
│   ├── assets/
│   │   └── MiDaS_small.tflite
│   ├── java/.../
│   │   ├── sensor/
│   │   │   └── TiltEstimator.kt
│   │   ├── depth/
│   │   │   └── MiDaSInference.kt
│   │   ├── detection/
│   │   │   ├── DepthMaskProcessor.kt
│   │   │   └── ContourDetector.kt
│   │   ├── tracking/
│   │   │   ├── UnionFind.kt
│   │   │   ├── BBoxMerger.kt
│   │   │   └── MultiObjectTracker.kt
│   │   ├── distance/
│   │   │   └── DistanceEstimator.kt
│   │   ├── alert/
│   │   │   └── AlertManager.kt
│   │   ├── ui/
│   │   │   ├── CameraOverlayView.kt
│   │   │   ├── DevModePanel.kt
│   │   │   └── MainActivity.kt
│   │   └── pipeline/
│   │       └── PipelineManager.kt
│   └── res/
│       └── layout/
│           └── activity_main.xml
└── build.gradle
```

---

## 3. Dependencies

> **BẮT BUỘC:** Dùng Version Catalog (`libs.versions.toml`), KHÔNG hardcode version string trong `build.gradle`.
> Các version dưới đây đã được test ổn định — KHÔNG tự ý nâng version.
> **YÊU CẦU:** JDK 17+ (AGP 9.x bắt buộc). Verify bằng `java -version` trước khi build.

### `gradle/libs.versions.toml`

```toml
[versions]
agp = "9.2.1"
coreKtx = "1.18.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
appcompat = "1.7.1"
material = "1.14.0"
activityKtx = "1.13.0"
constraintlayout = "2.2.1"
camerax = "1.5.0-alpha06"
tflite = "2.16.1"
opencv = "4.10.0"
kotlin = "2.0.21"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activityKtx" }
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
tensorflow-lite = { group = "org.tensorflow", name = "tensorflow-lite", version.ref = "tflite" }
tensorflow-lite-gpu = { group = "org.tensorflow", name = "tensorflow-lite-gpu", version.ref = "tflite" }
tensorflow-lite-gpu-api = { group = "org.tensorflow", name = "tensorflow-lite-gpu-api", version.ref = "tflite" }
opencv-android = { group = "org.opencv", name = "opencv", version.ref = "opencv" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### `app/build.gradle.kts` — phần plugins + dependencies

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ... trong block android { }:
kotlinOptions {
    jvmTarget = "11"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TFLite
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)

    // OpenCV
    implementation(libs.opencv.android)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

> **Lưu ý `tensorflow-lite-gpu-api`:** Cần khai báo riêng để GpuDelegate resolve đúng symbols ở runtime với TFLite `2.16.x`.
> **Lưu ý `camerax = "1.5.0-alpha06"`:** Alpha — nhưng đã test ổn định cho ImageAnalysis use case. Không cần thêm `mavenCentral()` bổ sung, snapshot repo không cần thiết.

---

## 4. Các module — spec chi tiết

---

### 4.1 `TiltEstimator.kt`

**Mục đích:** Cung cấp góc nghiêng camera (radian) theo thời gian thực dùng `TYPE_ROTATION_VECTOR`.

**Quy tắc convention (BẮT BUỘC giữ nhất quán xuyên suốt toàn bộ project):**
```
tiltRad = 0      → camera nhìn ngang (phone đứng thẳng)
tiltRad dương    → camera nghiêng XUỐNG phía đất
tiltRad âm       → camera ngửa lên trời (không xảy ra trong use case bình thường)
```

**Conversion từ pitch của `SensorManager.getOrientation()`:**
```kotlin
val pitch = angles[1].toDouble()       // angles[1] từ getOrientation()
val tiltRad = -(pitch + Math.PI / 2)   // convert sang convention trên
// Giải thích:
//   phone đứng thẳng → pitch = -π/2 → tiltRad = 0      ✅
//   phone nghiêng 15° xuống → pitch ≈ -1.31 → tiltRad ≈ +0.26 rad ✅
```

**Spec:**
```kotlin
class TiltEstimator(context: Context) : SensorEventListener {
    // Sensor: TYPE_ROTATION_VECTOR
    //   → OS-level fusion của gyro + accel + mag
    //   → tốt nhất cho đi bộ, đã lọc rung bước chân
    //   → KHÔNG dùng TYPE_ACCELEROMETER hay Accel+Mag thủ công

    // Rate: SENSOR_DELAY_UI (~60ms) — đủ cho tilt, không cần GAME

    // Deadband: chỉ update _tiltRad khi thay đổi > 1° = toRadians(1.0)
    //           tránh Z giật liên tục do nhiễu nhỏ

    // Thread-safety: @Volatile var _tiltRad: Double

    // getTiltRad() trả Double?
    //   → null nếu onSensorChanged chưa được gọi lần nào (sensor chưa ready)
    //   → caller PHẢI xử lý null: không tính Z khi null

    fun start()            // registerListener — gọi ở onResume()
    fun stop()             // unregisterListener — gọi ở onPause(), tránh drain battery
    fun getTiltRad(): Double?
}
```

---

### 4.2 `MiDaSInference.kt`

**Mục đích:** Chạy TFLite inference với model `MiDaS_small`, trả về depth map dạng `Mat`.

**Model spec (`intel-isl/MiDaS` — `MiDaS_small`):**
```
File:    MiDaS_small.tflite  (đặt trong assets/)
Input:   [1, 3, 256, 256]  — CHW format, RGB float32, normalize về [0.0, 1.0]
                             (KHÔNG phải NHWC [1,256,256,3] — đây là điểm hay nhầm)
Output:  [1, 256, 256]     — float32, inverse depth (giá trị CAO = object GẦN)
```

**Spec:**
```kotlin
class MiDaSInference(context: Context) {
    // Khởi tạo:
    //   1. Load model từ assets bằng FileUtil.loadMappedFile()
    //   2. Thử GpuDelegate trước — tăng tốc 3-5x trên device có GPU
    //   3. Nếu GpuDelegate throw exception → fallback CPU, numThreads=4
    //   4. Log delegate đang dùng để debug

    // infer(frameBitmap):
    //   1. Resize Bitmap về 256×256
    //   2. Convert sang float32 array theo CHW (channel-first):
    //      Loop: channel R trước (256×256 values), rồi G, rồi B
    //      normalize: pixel_float = pixel_int / 255.0f
    //      inputArray size = 1 × 3 × 256 × 256 = 196608 floats
    //      KHÔNG dùng HWC (channel-last) — model sẽ cho kết quả sai hoàn toàn
    //   3. Chạy interpreter.run(inputArray, outputArray)
    //      outputArray: FloatArray(256 × 256)
    //   4. Wrap output thành Mat 256×256 CV_32F
    //   5. Resize Mat về frameWidth × frameHeight (kích thước gốc)
    //   6. Trả về Mat CV_32F — KHÔNG normalize ở đây, để DepthMaskProcessor làm

    // QUAN TRỌNG: MiDaS trả RELATIVE depth
    //   → giá trị chỉ có nghĩa trong cùng 1 frame
    //   → KHÔNG so sánh giá trị tuyệt đối giữa 2 frame khác nhau

    fun infer(frameBitmap: Bitmap): Mat   // gọi ở background thread (Dispatchers.Default)
    fun close()                            // interpreter.close() — gọi ở onDestroy()
}
```

---

### 4.3 `DepthMaskProcessor.kt`

**Mục đích:** Chuyển depth map (float32) → binary foreground mask (CV_8U).

**Spec:**
```kotlin
object DepthMaskProcessor {

    // Bước 1 — Normalize về [0, 255] CV_8U
    //   Core.normalize(depthMat, normMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

    // Bước 2 — Crop ROI: chỉ phân tích phần dưới 2/3 frame
    //   roiY = frameHeight / 3
    //   roiH = frameHeight - roiY           (= 2/3 frame)
    //   roi = Rect(0, roiY, frameWidth, roiH)
    //   ⚠️ OpenCV Rect(x, y, width, height) — height là KÍCH THƯỚC, không phải tọa độ cuối
    //   Lý do: phần trên frame thường là trời, tường xa → không phải vật cản

    // Bước 3 — Dynamic threshold theo percentile (KHÔNG dùng ngưỡng cứng)
    //   Lý do: MiDaS relative depth thay đổi scale theo từng frame/scene
    //   foregroundRatio = 0.35f  (35% pixel có depth cao nhất = foreground)
    //   Cách tính:
    //     flat = normMat_roi.reshape(1,1)  → 1D array
    //     sort ascending
    //     cutoffIdx = (flat.cols() * (1f - foregroundRatio)).toInt()
    //     threshold = flat.get(0, cutoffIdx)[0]
    //   Apply: Imgproc.threshold(normMat_roi, mask, threshold, 255.0, THRESH_BINARY)

    // Bước 4 — Morphology để clean mask
    //   kernel = getStructuringElement(MORPH_ELLIPSE, Size(5.0, 5.0))
    //   MORPH_OPEN  → xóa noise pixel nhỏ
    //   MORPH_CLOSE → lấp lỗ hổng bên trong object

    // Bước 5 — Trả về mask full frame size (không chỉ ROI)
    //   Tạo Mat zeros cùng size frame, copy mask vào vùng ROI

    fun process(depthMat: Mat, frameWidth: Int, frameHeight: Int): Mat
}
```

---

### 4.4 `ContourDetector.kt`

**Mục đích:** Tìm contour từ mask → raw bbox list.

**Spec:**
```kotlin
object ContourDetector {
    // findContours: RETR_EXTERNAL, CHAIN_APPROX_SIMPLE
    //   RETR_EXTERNAL: chỉ lấy contour ngoài cùng, bỏ nested contour
    //   CHAIN_APPROX_SIMPLE: nén điểm thẳng, giảm memory

    // Filter contour:
    //   minArea = 800 px²   → bỏ noise nhỏ
    //   maxArea = frameArea * 0.7  → bỏ contour chiếm gần hết frame (toàn bộ background bị detect)

    // Trả về List<Rect>: boundingRect() của mỗi contour hợp lệ

    fun detect(mask: Mat, frameWidth: Int, frameHeight: Int): List<Rect>
}
```

---

### 4.5 `UnionFind.kt`

**Mục đích:** Data structure cho bước merge bbox trong `BBoxMerger`.

```kotlin
class UnionFind(size: Int) {
    // find(x): path compression — O(α(n)) amortized
    fun find(x: Int): Int

    // union(x, y): merge 2 group
    fun union(x: Int, y: Int)

    // groups(): trả Map<root, List<memberIndices>>
    //   dùng để group các bbox cần merge với nhau
    fun groups(): Map<Int, List<Int>>
}
```

---

### 4.6 `BBoxMerger.kt`

**Mục đích:** Merge các bbox overlap hoặc gần nhau → 1 bbox đại diện per group.

**Spec:**
```kotlin
object BBoxMerger {

    // shouldMerge(a, b): true nếu HOẶC
    //   Điều kiện 1: IoU(a, b) >= 0.1f
    //   Điều kiện 2: gap pixel giữa 2 cạnh gần nhất <= 25px (cả X lẫn Y)
    //     gapX = max(0, max(a.x, b.x) - min(a.x+a.width,  b.x+b.width))
    //     gapY = max(0, max(a.y, b.y) - min(a.y+a.height, b.y+b.height))
    //     → shouldMerge nếu gapX <= 25 && gapY <= 25
    //   LÝ DO cần điều kiện 2: contour từ depth map cùng object hay tách nhau
    //   mà IoU = 0 (không overlap) nhưng thực ra là cùng 1 vật cản

    // mergeGroup(boxes): weighted average theo area
    //   cx = Σ(area_i × centerX_i) / Σ(area_i)
    //   cy = Σ(area_i × centerY_i) / Σ(area_i)
    //   w  = Σ(area_i × w_i)       / Σ(area_i)
    //   h  = Σ(area_i × h_i)       / Σ(area_i)
    //   result = Rect(cx - w/2, cy - h/2, w, h)
    //   LÝ DO dùng weighted avg thay vì bounding union:
    //   bounding union làm bbox phình to, nuốt background

    // merge(boxes):
    //   1. Tạo UnionFind(boxes.size)
    //   2. Với mỗi cặp (i, j): nếu shouldMerge → union(i, j)
    //   3. Với mỗi group: mergeGroup(boxes trong group) → 1 Rect
    //   4. Trả về List<Rect> đã merge

    fun merge(boxes: List<Rect>): List<Rect>
}
```

---

### 4.7 `MultiObjectTracker.kt`

**Mục đích:** SORT-lite tracker — liên kết bbox giữa các frame, smooth bằng Kalman Filter.

**Kalman Filter:**
```
State vector:       [x, y, w, h, vx, vy]  — 6 states
Measurement vector: [x, y, w, h]           — 4 measurements

Transition matrix (dt = 1 frame):
  x' = x + vx
  y' = y + vy
  w' = w         (giả sử kích thước không đổi)
  h' = h
  vx' = vx
  vy' = vy

Measurement matrix: observe [x, y, w, h] từ state 6D

Noise params (khởi tạo, tune lại sau khi test thực tế):
  processNoiseCov    = 1e-2  → tin model nhiều
  measurementNoiseCov = 1e-1 → detector từ contour khá noisy
  errorCovPost       = 1.0
```

**Track lifecycle:**
```kotlin
data class Track(
    val id: Int,
    val kf: KalmanFilter,
    var missedFrames: Int = 0,
    var smoothedBox: Rect = Rect()
) {
    // predict(): kf.predict() → cập nhật smoothedBox từ state vector
    //   x = state[0], y = state[1], w = state[2], h = state[3]
    fun predict(): Rect

    // update(det): kf.correct(measurement) → reset missedFrames = 0
    //   measurement = Mat(4,1,CV_32F) chứa [det.x, det.y, det.w, det.h]
    fun update(det: Rect)
}
```

**MultiObjectTracker:**
```kotlin
class MultiObjectTracker {
    // Hằng số:
    //   MAX_MISSED = 8 frame
    //     → cao hơn static camera vì camera di động hay mất track tạm thời
    //   IOU_THRESHOLD = 0.25f
    //     → thấp hơn static camera vì bbox dịch chuyển do camera move

    // update(detections: List<Rect>): List<Track>
    //   Bước 1: predict() tất cả track hiện có
    //   Bước 2: Greedy IoU matching
    //     → đủ cho 2-5 object, không cần Hungarian algorithm
    //     → với mỗi track: tìm detection có IoU cao nhất >= threshold
    //     → mỗi detection chỉ được match 1 track
    //   Bước 3: track matched → update(det), missedFrames = 0
    //   Bước 4: track unmatched → missedFrames++
    //   Bước 5: detection chưa matched → tạo Track mới, init Kalman
    //   Bước 6: xóa track có missedFrames > MAX_MISSED
    //   Bước 7: trả về list track còn sống

    // clearAll(): xóa toàn bộ track
    //   → gọi khi scene change đột ngột (xem PipelineManager)

    fun update(detections: List<Rect>): List<Track>
    fun clearAll()
    fun activeTracks(): List<Track>  // trả về snapshot hiện tại
}
```

---

### 4.8 `DistanceEstimator.kt`

**Mục đích:** Tính khoảng cách Z (mét) từ bbox và tilt sensor.

**Công thức:**
```
v_bottom   = bbox.y + bbox.height          (pixel tọa độ chân vật cản)
alpha      = atan((v_bottom - cy) / fy)    (góc lệch tia nhìn so với optical axis)
totalAngle = tiltRad + alpha               (tổng góc so với mặt đất)
Z          = H / tan(totalAngle)           (khoảng cách mét)
```

**Guards BẮT BUỘC — trả null nếu vi phạm bất kỳ điều kiện nào:**
```
1. tiltRad == null               → sensor chưa ready
2. totalAngle <= toRadians(3.0)  → tia nhìn gần ngang/lên trời, tan → 0 → Z → ∞
3. bbox.y + bbox.height >= frameHeight - 5  → chân bbox bị crop bởi cạnh frame
4. Z < 0.2 hoặc Z > 4.0 mét     → ngoài tầm tin cậy thực tế
```

**EMA smooth Z per track:**
```
beta = 0.35f
smoothZ[trackId] = beta * rawZ + (1 - beta) * smoothZ[trackId]
LÝ DO per-track: mỗi object có trajectory Z riêng
```

**Spec:**
```kotlin
class DistanceEstimator(
    private val fy: Double,   // focal length Y pixel — lấy từ camera intrinsics
    private val cy: Double,   // optical center Y — thường = frameHeight / 2.0
    private val H: Double     // chiều cao camera mét = userHeightM * 0.6
) {
    // estimate(): trả Double? — null nếu vi phạm bất kỳ guard nào ở trên
    fun estimate(trackId: Int, bbox: Rect, frameHeight: Int, tiltRad: Double): Double?

    // removeTrack(): dọn dẹp smoothZ map khi track bị xóa, tránh memory leak
    fun removeTrack(trackId: Int)
    fun clearAll()  // gọi khi tracker.clearAll()
}
```

**Camera intrinsics — lấy 1 lần khi camera khởi động:**
```kotlin
// Dùng CameraManager (android.hardware.camera2), KHÔNG hardcode
val characteristics = cameraManager.getCameraCharacteristics(cameraId)
val focalLengths = characteristics.get(LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
val sensorSize   = characteristics.get(SENSOR_INFO_PHYSICAL_SIZE)

val fy: Double
val cy: Double = frameHeight / 2.0

if (focalLengths != null && sensorSize != null) {
    val focalLengthMm  = focalLengths[0]
    val sensorHeightMm = sensorSize.height
    fy = (focalLengthMm / sensorHeightMm) * frameHeight
} else {
    // Fallback heuristic khi device không cung cấp intrinsics
    // Giả sử FoV dọc ~ 50° → fy ≈ frameHeight / (2 * tan(25°))
    fy = frameHeight * 1.07
    Log.w(TAG, "Camera intrinsics unavailable, using heuristic fy=$fy")
}
```

---

### 4.9 `AlertManager.kt`

**Mục đích:** Phát cảnh báo TTS tiếng Việt, tránh spam.

**Zone mapping (theo centerX của bbox):**
```
centerX < frameWidth * 0.35  → zone = "bên trái"
centerX > frameWidth * 0.65  → zone = "bên phải"
else                          → zone = "phía trước"
```

**Proximity → TTS message:**
```
Z < 0.8m  → CRITICAL → "Cảnh báo! Vật cản [zone]"
Z < 1.5m  → NEAR     → "Vật cản [zone], [Z] mét"   (Z làm tròn 1 chữ số)
Z < 3.0m  → MID      → "Chú ý [zone]"
Z >= 3.0m → FAR      → im lặng, không phát TTS
```

**Cooldown per zone — BẮT BUỘC tránh TTS spam:**
```
CRITICAL : 1500ms
NEAR     : 2500ms
MID      : 4000ms

Logic:
  - Mỗi zone (trái/trước/phải) có timestamp phát TTS lần cuối riêng
  - Chỉ phát nếu (currentTime - lastSpokenTime[zone]) > cooldown[proximity]
  - Nếu nhiều zone cùng cảnh báo 1 lúc: ưu tiên zone có Z nhỏ nhất
  - TTS queue: QUEUE_FLUSH (ngắt câu đang nói) cho CRITICAL
               QUEUE_ADD  (nối hàng đợi)      cho NEAR, MID
```

**Spec:**
```kotlin
class AlertManager(context: Context) {
    // TTS init:
    //   TextToSpeech(context, onInitListener)
    //   onInit: set locale = Locale("vi", "VN")
    //   Nếu locale không support: fallback Locale.getDefault(), log warning
    //   isReady = true chỉ sau khi onInit SUCCESS

    // update() chỉ gọi TTS khi isReady == true

    fun update(tracks: List<Track>, distances: Map<Int, Double>, frameWidth: Int)
    fun shutdown()  // tts.stop(); tts.shutdown()
}
```

---

### 4.10 `CameraOverlayView.kt`

**Mục đích:** Custom View vẽ bbox + label lên camera preview — chỉ hiển thị khi DevMode bật.

**Spec:**
```kotlin
class CameraOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // visibility: VISIBLE khi devMode=true, GONE khi devMode=false
    // Thread-safe update qua setData() từ main thread → invalidate()

    // Vẽ mỗi track có Z hợp lệ:
    //   Rect stroke theo proximity:
    //     CRITICAL → Color.RED,    strokeWidth = 4px
    //     NEAR     → Color(255,165,0) (cam), strokeWidth = 3px
    //     MID      → Color.YELLOW, strokeWidth = 2px
    //   Label text phía trên rect: "ID:[id]  [Z]m  [zone]"
    //     font 13sp, background rect đen 50% alpha để readable
    //   Track không có Z → vẽ rect abu-abu, label "ID:[id]  N/A"

    // Scale bbox từ frame coords → view coords:
    //   scaleX = view.width  / frameWidth
    //   scaleY = view.height / frameHeight

    fun setData(
        tracks: List<Track>,
        distances: Map<Int, Double>,
        frameWidth: Int,
        frameHeight: Int
    )
}
```

---

### 4.11 `DevModePanel.kt`

**Mục đích:** Overlay hiển thị tham số realtime khi DevMode bật — dành cho debug và demo.

**Thông tin hiển thị:**
```
┌─────────────────────────────┐
│ DEV MODE                [X] │
├─────────────────────────────┤
│ FPS:        24              │
│ Objects:    3               │
│ Tilt:       14.2°           │
│ Inference:  87ms            │
├─────────────────────────────┤
│ Track #1   Z=1.2m   NEAR    │
│ Track #2   Z=2.8m   MID     │
│ Track #3   Z=null   N/A     │
└─────────────────────────────┘
```

**Spec:**
```kotlin
data class DevStats(
    val fps: Float,
    val objectCount: Int,
    val tiltDeg: Float?,          // tiltRad * 180/π, null nếu sensor chưa ready
    val inferenceMs: Float,       // thời gian MiDaS inference lần gần nhất
    val trackDistances: Map<Int, Double?>  // trackId → Z (null nếu không tính được)
)

class DevModePanel(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // Vị trí: góc trái trên, padding 16dp
    // Background: đen 70% alpha, bo góc 8dp
    // Font: monospace 12sp, màu trắng
    // visibility: VISIBLE khi devMode=true, GONE khi devMode=false
    // Update: setStats(DevStats) → invalidate()

    fun setStats(stats: DevStats)
}
```

**Nút toggle DevMode trong MainActivity:**
```
- FloatingActionButton góc phải dưới, icon "bug_report"
- Tap → toggle devMode boolean
- devMode=true  → CameraOverlayView.visibility = VISIBLE
                  DevModePanel.visibility = VISIBLE
- devMode=false → CameraOverlayView.visibility = GONE
                  DevModePanel.visibility = GONE
- Default: devMode = false (production mode, màn hình sạch)
```

---

### 4.12 `PipelineManager.kt`

**Mục đích:** Orchestrate toàn bộ pipeline, xử lý async giữa MiDaS inference và render.

**Kiến trúc threading:**
```
CameraX (ImageAnalysis callback — chạy trên executor riêng của CameraX):
  → convert ImageProxy → Bitmap
  → gọi onFrame()

onFrame() — gọi từ camera executor:
  BG thread (Dispatchers.Default):
    MiDaSInference.infer()
    DepthMaskProcessor.process()
    ContourDetector.detect()
    BBoxMerger.merge()
    → update latestDetections (@Volatile)
    → update lastInferenceMs (@Volatile)

  Main thread (Dispatchers.Main):
    MultiObjectTracker.update(latestDetections)
    DistanceEstimator.estimate() per track
    AlertManager.update()
    CameraOverlayView.setData()   [chỉ nếu devMode]
    DevModePanel.setStats()       [chỉ nếu devMode]
    FPS counter update
```

**Adaptive inference skip:**
```kotlin
// Chỉ launch BG inference job khi job trước đã xong (isActive check)
// Giữa 2 lần inference: Kalman predict() giữ bbox mượt
// Đây là giá trị cốt lõi của Kalman trong pipeline này:
//   → bridge gap giữa các lần MiDaS inference, không phải smooth noise
if (inferenceJob?.isActive != true) {
    inferenceJob = scope.launch(Dispatchers.Default) { ... }
}
// Main thread luôn chạy tracker.update() mỗi frame dù có inference mới hay không
```

**Scene change detection:**
```kotlin
// So sánh foreground pixel count giữa 2 mask liên tiếp
// ratio = currFgCount.toDouble() / prevFgCount
// Nếu ratio < 0.35 hoặc ratio > 2.8 → scene change
//   → tracker.clearAll()
//   → distanceEstimator.clearAll()
// Lý do: MiDaS relative depth thay đổi scale khi scene thay đổi đột ngột
//        Kalman state cũ sẽ bị lệch hoàn toàn, phải reset
```

**FPS counter:**
```kotlin
// Đếm số lần onFrame() được gọi trong 1 giây
// Lưu vào @Volatile currentFps: Float
// Dùng cho DevModePanel
```

**Spec:**
```kotlin
class PipelineManager(
    private val tiltEstimator: TiltEstimator,
    private val midasInference: MiDaSInference,
    private val tracker: MultiObjectTracker,
    private val distanceEstimator: DistanceEstimator,
    private val alertManager: AlertManager,
    private val overlayView: CameraOverlayView,
    private val devModePanel: DevModePanel,
    private val devModeProvider: () -> Boolean,    // lambda lấy trạng thái devMode
    private val userHeightM: Double
) {
    @Volatile var currentFps: Float = 0f
        private set

    fun onFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int, scope: CoroutineScope)
}
```

---

### 4.13 `MainActivity.kt`

**Mục đích:** Entry point — setup toàn bộ component, quản lý lifecycle.

**Spec:**
```kotlin
class MainActivity : AppCompatActivity() {

    // ── Setup flow ──
    // 1. Xin permission CAMERA (ActivityResultContracts.RequestPermission)
    //    Nếu denied → hiển thị dialog giải thích, không crash

    // 2. Setup UserHeight input:
    //    - EditText nhập cm + Button "Bắt đầu"
    //    - Validate: 100cm <= input <= 220cm
    //    - Tính H = userHeightCm * 0.006  (cm → mét × 0.6)
    //    - Lưu SharedPreferences để không phải nhập lại mỗi lần mở app

    // 3. Khởi tạo components (sau khi có userHeight):
    //    TiltEstimator, MiDaSInference, MultiObjectTracker,
    //    DistanceEstimator(fy, cy, H), AlertManager,
    //    CameraOverlayView, DevModePanel, PipelineManager

    // 4. Setup CameraX:
    //    - ImageAnalysis: Resolution 640×480
    //    - setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
    //      → tự động drop frame nếu pipeline chậm hơn camera
    //    - Executor: Executors.newSingleThreadExecutor()
    //    - Trong analyze(): convert ImageProxy → Bitmap → pipelineManager.onFrame()

    // 5. Camera intrinsics:
    //    - Lấy fy, cy từ Camera2Interop sau khi CameraX bind xong
    //    - Update DistanceEstimator với giá trị thực

    // 6. DevMode FAB:
    //    - FloatingActionButton icon bug_report, góc phải dưới
    //    - Toggle devMode → update visibility của overlay và panel

    // ── Lifecycle ──
    // onResume:  tiltEstimator.start()
    //            (re-init AlertManager nếu cần — TTS chỉ stop ở onPause, không shutdown)
    // onPause:   tiltEstimator.stop()
    //            alertManager.stop()     [tts.stop() — dừng phát, giữ engine sống]
    // onDestroy: midasInference.close()
    //            alertManager.shutdown()  [tts.shutdown() — giải phóng engine]
    //            cameraExecutor.shutdown()
}
```

---

## 5. Luồng dữ liệu end-to-end

```
CameraX frame (ImageProxy → Bitmap, 640×480)
        │
        ├──[BG Thread: Dispatchers.Default]─────────────────────────────┐
        │   MiDaSInference.infer(bitmap)                                │
        │        ↓ depth Mat CV_32F (resized to 640×480)                │
        │   DepthMaskProcessor.process()                                │
        │        ↓ binary mask CV_8U                                    │
        │   ContourDetector.detect()                                    │
        │        ↓ raw List<Rect>                                       │
        │   BBoxMerger.merge()  [UnionFind + weighted avg]              │
        │        ↓ merged List<Rect>                                    │
        │   @Volatile latestDetections, lastInferenceMs                 │
        └────────────────────────────────────────────────────────────────┘
        │
        ├──[Main Thread: Dispatchers.Main — mỗi frame]──────────────────┐
        │   Scene change check → tracker.clearAll() nếu cần            │
        │   MultiObjectTracker.update(latestDetections)                 │
        │        ↓ List<Track> với smoothedBox (Kalman predict/correct) │
        │   TiltEstimator.getTiltRad()  → Double?                      │
        │   DistanceEstimator.estimate() per track                      │
        │        ↓ Map<trackId, Z>                                      │
        │   AlertManager.update()  → TTS                               │
        │   [if devMode]:                                               │
        │     CameraOverlayView.setData()  → render bbox               │
        │     DevModePanel.setStats()  → render debug info             │
        └────────────────────────────────────────────────────────────────┘
```

---

## 6. Config — hằng số tập trung

```kotlin
object Config {

    // ── MiDaS ──
    const val MIDAS_INPUT_SIZE      = 256          // px, model input width & height

    // ── Detection ──
    const val FOREGROUND_RATIO      = 0.35f        // 35% pixel gần nhất = foreground
    const val MIN_CONTOUR_AREA      = 800          // px²
    const val MAX_CONTOUR_RATIO     = 0.7f         // max fraction of frame area
    const val MERGE_IOU_THRESHOLD   = 0.1f         // IoU để merge bbox
    const val MERGE_GAP_PX          = 25           // pixel gap để merge bbox

    // ── Tracking ──
    const val MAX_MISSED_FRAMES     = 8
    const val IOU_MATCH_THRESHOLD   = 0.25f        // thấp vì camera di động

    // ── Kalman noise ──
    const val PROCESS_NOISE         = 1e-2
    const val MEASUREMENT_NOISE     = 1e-1

    // ── Distance ──
    const val MIN_RELIABLE_Z        = 0.2          // mét
    const val MAX_RELIABLE_Z        = 4.0          // mét
    const val MIN_TOTAL_ANGLE_DEG   = 3.0          // ° — guard tia nhìn ngang
    const val Z_EMA_BETA            = 0.35f        // EMA smooth Z per track
    const val CAMERA_HEIGHT_RATIO   = 0.6          // H = userHeight × ratio

    // ── Tilt ──
    const val TILT_DEADBAND_DEG     = 1.0          // ° — deadband filter

    // ── Alert ──
    const val THRESHOLD_CRITICAL_M  = 0.8          // mét
    const val THRESHOLD_NEAR_M      = 1.5          // mét
    const val THRESHOLD_MID_M       = 3.0          // mét
    const val COOLDOWN_CRITICAL_MS  = 1500L
    const val COOLDOWN_NEAR_MS      = 2500L
    const val COOLDOWN_MID_MS       = 4000L

    // ── Scene change ──
    const val SCENE_CHANGE_MIN_RATIO = 0.35        // fg giảm > 65% → reset
    const val SCENE_CHANGE_MAX_RATIO = 2.8         // fg tăng > 180% → reset

    // ── User input ──
    const val MIN_USER_HEIGHT_CM    = 100
    const val MAX_USER_HEIGHT_CM    = 220
    const val PREF_KEY_HEIGHT       = "user_height_cm"
}
```

---

## 7. Thứ tự implement

Implement theo thứ tự để test từng layer độc lập:

1. **`TiltEstimator`** — Log tiltRad liên tục. Verify:
   - Đứng thẳng, phone đứng thẳng → tiltRad ≈ 0
   - Nghiêng phone 15° xuống → tiltRad ≈ +0.26 rad
   - Đi bộ → dao động < ±0.035 rad (2°)

2. **`MiDaSInference`** — Log inference time. Verify:
   - GpuDelegate load thành công
   - Output Mat không null, size = frameWidth×frameHeight
   - Inference < 150ms

3. **`DepthMaskProcessor` + `ContourDetector`** — Hiển thị mask lên màn hình (dùng DevMode overlay tạm). Verify mask che đúng vùng object gần.

4. **`UnionFind` + `BBoxMerger`** — Unit test riêng với mock bbox list. Verify merge đúng theo IoU và gap threshold.

5. **`MultiObjectTracker`** — Hiển thị bbox lên màn hình. Verify:
   - Track ID ổn định khi đứng yên > 10 giây
   - Tạo track mới khi object vào frame
   - Xóa track sau MAX_MISSED frame

6. **`DistanceEstimator`** — Đứng cách tường/vật cản đúng 1m, 2m. Verify Z sai số < ±20cm.

7. **`AlertManager`** — Test TTS độc lập với mock data. Verify cooldown hoạt động đúng, không spam.

8. **`DevModePanel` + `CameraOverlayView`** — Render overlay, toggle FAB.

9. **`PipelineManager`** — Kết nối tất cả. Đo FPS thực tế. Verify Kalman bridge frame khi MiDaS chậm.

10. **`MainActivity`** — User height input, SharedPreferences, full lifecycle.

---

## 8. Checklist verify trước khi release

```
SENSOR
[ ] tiltRad đứng yên: dao động < ±1°
[ ] tiltRad đi bộ: dao động < ±2°
[ ] getTiltRad() trả null trước khi sensor ready, không crash

MIDAS
[ ] Inference time < 150ms trên device target
[ ] GpuDelegate load thành công, log xác nhận
[ ] Fallback CPU khi GpuDelegate fail, không crash

DETECTION
[ ] Mask che đúng vùng object gần
[ ] Không có bbox khi không có vật cản
[ ] Bbox không phình to nuốt background

TRACKING
[ ] Track ID ổn định khi đứng yên > 10 giây
[ ] Track ID không bị swap giữa 2 object song song
[ ] Track bị xóa sau đúng MAX_MISSED frame

DISTANCE
[ ] Z ≈ 1.0m khi cách vật 1m (sai số < ±20cm)
[ ] Z ≈ 2.0m khi cách vật 2m (sai số < ±40cm)
[ ] Z null khi bbox chạm cạnh dưới frame
[ ] Z null khi tiltRad null
[ ] Không crash khi totalAngle <= 3°

ALERT
[ ] TTS phát tiếng Việt đúng
[ ] Cooldown hoạt động: không spam khi đứng yên trước vật cản
[ ] CRITICAL ngắt câu đang nói (QUEUE_FLUSH)
[ ] Không phát TTS khi Z >= 3.0m

DEVMODE
[ ] FAB toggle đúng visibility
[ ] FPS hiển thị realtime
[ ] Tất cả track và Z hiển thị đúng trong panel
[ ] Màu bbox đúng theo proximity

PERFORMANCE
[ ] FPS >= 15 khi có 3 object, devMode=false
[ ] FPS >= 12 khi có 3 object, devMode=true
[ ] Không OOM sau 5 phút chạy liên tục
[ ] Battery drain hợp lý (sensor stop khi onPause)
```
