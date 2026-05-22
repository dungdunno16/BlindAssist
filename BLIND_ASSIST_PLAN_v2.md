# Blind Assist — Implementation Plan v2
> Dành cho coding agent. Đọc toàn bộ trước khi bắt đầu code.
> Mỗi bước có **checkpoint test** — KHÔNG chuyển sang bước tiếp theo khi chưa pass hết checkpoint.

---

## 1. Tổng quan dự án

App Android (Kotlin) hỗ trợ người khiếm thị phát hiện vật cản và ước lượng khoảng cách trong thời gian thực. Camera di động theo người dùng đang đi bộ.

**Output cuối:**
- Cảnh báo TTS tiếng Việt
- Nút DevMode: bật/tắt overlay bbox + hiển thị tham số realtime (FPS, số object, Z, tiltRad)

**Stack:**
- Kotlin (AGP 9.0 built-in) + CameraX `1.6.1`
- OpenCV `4.10.0` (`org.opencv:opencv`)
- TFLite `2.16.0` — model `MiDaS_small` (`intel-isl/MiDaS`)
- `TYPE_ROTATION_VECTOR` sensor
- TTS (`TextToSpeech` Android API)
- Coroutines `1.9.0`

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
> Các version dưới đây đã được kiểm tra tương thích — KHÔNG tự ý nâng version.
> **YÊU CẦU:** JDK 17+ (AGP 9.x minimum). JDK 21 được khuyến nghị để build nhanh hơn nhưng không bắt buộc.

### `gradle/libs.versions.toml`

```toml
[versions]
agp                  = "9.2.1"
# kotlin version KHÔNG cần — AGP 9.0+ tích hợp sẵn Kotlin compiler
coreKtx              = "1.18.0"
junit                = "4.13.2"
junitVersion         = "1.3.0"
espressoCore         = "3.7.0"
appcompat            = "1.7.1"
material             = "1.14.0"
activityKtx          = "1.13.0"
constraintlayout     = "2.2.1"
camerax              = "1.6.1"
tflite               = "2.16.0"
opencv               = "4.10.0"
coroutines           = "1.9.0"

[libraries]
androidx-core-ktx              = { group = "androidx.core",              name = "core-ktx",              version.ref = "coreKtx"          }
junit                          = { group = "junit",                      name = "junit",                  version.ref = "junit"            }
androidx-junit                 = { group = "androidx.test.ext",          name = "junit",                  version.ref = "junitVersion"     }
androidx-espresso-core         = { group = "androidx.test.espresso",     name = "espresso-core",          version.ref = "espressoCore"     }
androidx-appcompat             = { group = "androidx.appcompat",         name = "appcompat",              version.ref = "appcompat"        }
material                       = { group = "com.google.android.material", name = "material",              version.ref = "material"         }
androidx-activity-ktx          = { group = "androidx.activity",          name = "activity-ktx",           version.ref = "activityKtx"     }
androidx-constraintlayout      = { group = "androidx.constraintlayout",  name = "constraintlayout",       version.ref = "constraintlayout" }
androidx-camera-core           = { group = "androidx.camera",            name = "camera-core",            version.ref = "camerax"          }
androidx-camera-camera2        = { group = "androidx.camera",            name = "camera-camera2",         version.ref = "camerax"          }
androidx-camera-lifecycle      = { group = "androidx.camera",            name = "camera-lifecycle",       version.ref = "camerax"          }
androidx-camera-view           = { group = "androidx.camera",            name = "camera-view",            version.ref = "camerax"          }
tensorflow-lite                = { group = "org.tensorflow",             name = "tensorflow-lite",        version.ref = "tflite"           }
tensorflow-lite-gpu            = { group = "org.tensorflow",             name = "tensorflow-lite-gpu",    version.ref = "tflite"           }
tensorflow-lite-gpu-api        = { group = "org.tensorflow",             name = "tensorflow-lite-gpu-api", version.ref = "tflite"          }
opencv-android                 = { group = "org.opencv",                 name = "opencv",                 version.ref = "opencv"           }
kotlinx-coroutines-android     = { group = "org.jetbrains.kotlinx",      name = "kotlinx-coroutines-android", version.ref = "coroutines"  }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp"    }
# KHÔNG thêm kotlin-android — AGP 9.0+ built-in Kotlin
# Nếu thêm sẽ lỗi: "The 'org.jetbrains.kotlin.android' plugin is no longer required"
```

### `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    // KHÔNG thêm kotlin-android — AGP 9.0+ built-in Kotlin compiler
}

android {
    namespace = "com.example.blindassist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.blindassist"
        minSdk = 26        // ← BẮT BUỘC 26+: CameraX stable + Sensor fusion ổn định
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // kotlinOptions { jvmTarget } KHÔNG cần — tự follow compileOptions.targetCompatibility
    // Ngăn Gradle compress file tflite → load bằng memory-map nhanh hơn
    androidResources {
        noCompress += "tflite"
    }
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

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

> **`tensorflow-lite-gpu-api`:** Khai báo riêng để GpuDelegate resolve đúng symbols ở runtime với TFLite `2.16.x`.
> **`compileSdk` syntax:** Dùng `compileSdk = 36` (integer). KHÔNG dùng block syntax `compileSdk { version = release(36) { ... } }`.
> **AGP 9.0 built-in Kotlin:** KHÔNG cần `org.jetbrains.kotlin.android` plugin. Nếu thêm sẽ build fail.
> **`kotlinOptions { jvmTarget }`:** KHÔNG cần set — tự động follow `compileOptions.targetCompatibility`.
> **`noCompress += "tflite"`:** Bắt buộc để `FileUtil.loadMappedFile()` hoạt động đúng — nếu không có, model bị Gradle compress và load fail.

---

## 4. Config — hằng số tập trung

> Tạo file này trước tất cả các module khác. Mọi magic number đều phải đến từ đây.

```kotlin
object Config {

    // ── MiDaS ──
    const val MIDAS_INPUT_SIZE        = 256

    // ── Detection ──
    const val FOREGROUND_RATIO        = 0.35f
    const val MIN_CONTOUR_AREA        = 800
    const val MAX_CONTOUR_RATIO       = 0.7f
    const val MERGE_IOU_THRESHOLD     = 0.1f
    const val MERGE_GAP_PX            = 25

    // ── Tracking ──
    const val MAX_MISSED_FRAMES       = 8
    const val IOU_MATCH_THRESHOLD     = 0.25f

    // ── Kalman noise ──
    const val PROCESS_NOISE           = 1e-2
    const val MEASUREMENT_NOISE       = 1e-1

    // ── Distance ──
    const val MIN_RELIABLE_Z          = 0.2
    const val MAX_RELIABLE_Z          = 4.0
    const val MIN_TOTAL_ANGLE_DEG     = 3.0
    const val Z_EMA_BETA              = 0.35f
    const val CAMERA_HEIGHT_RATIO     = 0.6

    // ── Tilt ──
    const val TILT_DEADBAND_DEG       = 1.0

    // ── Alert ──
    const val THRESHOLD_CRITICAL_M    = 0.8
    const val THRESHOLD_NEAR_M        = 1.5
    const val THRESHOLD_MID_M         = 3.0
    const val COOLDOWN_CRITICAL_MS    = 1500L
    const val COOLDOWN_NEAR_MS        = 2500L
    const val COOLDOWN_MID_MS         = 4000L

    // ── Scene change ──
    const val SCENE_CHANGE_MIN_RATIO  = 0.35
    const val SCENE_CHANGE_MAX_RATIO  = 2.8

    // ── User input ──
    const val MIN_USER_HEIGHT_CM      = 100
    const val MAX_USER_HEIGHT_CM      = 220
    const val PREF_KEY_HEIGHT         = "user_height_cm"
}
```

---

## 5. Các module — spec chi tiết

---

### 5.1 `TiltEstimator.kt`

**Mục đích:** Cung cấp góc nghiêng camera (radian) theo thời gian thực dùng `TYPE_ROTATION_VECTOR`.

**Convention tiltRad (BẮT BUỘC giữ nhất quán xuyên suốt toàn bộ project):**
```
tiltRad = 0      → camera nhìn ngang (phone đứng thẳng)
tiltRad dương    → camera nghiêng XUỐNG phía đất
tiltRad âm       → camera ngửa lên trời (không xảy ra trong use case bình thường)
```

**Conversion từ pitch:**
```kotlin
val pitch  = angles[1].toDouble()     // angles[1] từ SensorManager.getOrientation()
val tiltRad = -(pitch + Math.PI / 2)
// phone đứng thẳng → pitch = -π/2 → tiltRad = 0      ✅
// phone nghiêng 15° xuống → pitch ≈ -1.31 → tiltRad ≈ +0.26 rad ✅
```

**Spec:**
```kotlin
class TiltEstimator(context: Context) : SensorEventListener {
    // Sensor: TYPE_ROTATION_VECTOR (OS-level fusion gyro + accel + mag)
    //   KHÔNG dùng TYPE_ACCELEROMETER hay Accel+Mag thủ công
    // Rate: SENSOR_DELAY_UI (~60ms) — đủ cho tilt, không cần GAME
    // Deadband: chỉ update _tiltRad khi thay đổi > toRadians(1.0)
    // Thread-safety: @Volatile var _tiltRad: Double

    // getTiltRad() trả Double?
    //   → null nếu onSensorChanged chưa được gọi lần nào
    //   → caller PHẢI xử lý null: không tính Z khi null

    fun start()          // registerListener — gọi ở onResume()
    fun stop()           // unregisterListener — gọi ở onPause()
    fun getTiltRad(): Double?
}
```

---

### 5.2 `MiDaSInference.kt`

**Mục đích:** Chạy TFLite inference với model `MiDaS_small`, trả về depth map dạng `Mat`.

**Model spec:**
```
File:    MiDaS_small.tflite  (đặt trong assets/)
Input:   [1, 3, 256, 256]  — CHW format, RGB float32, normalize [0.0, 1.0]
                             KHÔNG phải NHWC [1,256,256,3] — hay nhầm
Output:  [1, 256, 256]     — float32, inverse depth (CAO = object GẦN)
```

**Spec:**
```kotlin
class MiDaSInference(context: Context) {
    // Khởi tạo:
    //   1. Load model từ assets bằng FileUtil.loadMappedFile()
    //   2. Thử GpuDelegate trước → fallback CPU (numThreads=4) nếu throw
    //   3. Log delegate đang dùng

    // infer(frameBitmap):
    //   1. Resize Bitmap về 256×256
    //   2. Convert sang float32 CHW:
    //      channel R trước (256×256 values), rồi G, rồi B
    //      normalize: pixel_float = pixel_int / 255.0f
    //      inputArray size = 1×3×256×256 = 196608 floats
    //      KHÔNG dùng HWC — model cho kết quả sai hoàn toàn nếu nhầm
    //   3. interpreter.run(inputArray, outputArray)
    //      outputArray: FloatArray(256 × 256)
    //   4. Wrap output thành Mat 256×256 CV_32F
    //   5. Resize Mat về frameWidth × frameHeight
    //   6. Trả về Mat CV_32F — KHÔNG normalize ở đây
    //   7. Release tất cả Mat tạm (resizedMat, wrapMat trước resize)
    //      ⚠️ CHỈ trả về Mat cuối cùng — caller chịu trách nhiệm release nó

    // QUAN TRỌNG: MiDaS trả RELATIVE depth
    //   → giá trị chỉ có nghĩa trong cùng 1 frame
    //   → KHÔNG so sánh giá trị tuyệt đối giữa 2 frame khác nhau

    fun infer(frameBitmap: Bitmap): Mat
    fun close()
}
```

> **⚠️ Mat Memory Policy (áp dụng cho toàn bộ project):**
> Mọi `Mat` tạo tạm bên trong function **PHẢI gọi `.release()`** trước khi return.
> Caller nhận `Mat` từ function khác **PHẢI release sau khi dùng xong**.
> OpenCV `Mat` dùng native memory (JNI) — GC **KHÔNG** tự thu hồi.
> Vi phạm rule này → OOM sau 2-3 phút chạy liên tục.
> Pattern:
> ```kotlin
> val tempMat = Mat()
> try {
>     // ... xử lý với tempMat
> } finally {
>     tempMat.release()
> }
> ```

---

### 5.3 `DepthMaskProcessor.kt`

**Mục đích:** Chuyển depth map (float32) → binary foreground mask (CV_8U).

**Spec:**
```kotlin
object DepthMaskProcessor {

    // Bước 1 — Normalize về [0, 255] CV_8U
    //   Core.normalize(depthMat, normMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

    // Bước 2 — Crop ROI: phần dưới 2/3 frame
    //   roiY = frameHeight / 3
    //   roiH = frameHeight - roiY
    //   roi  = Rect(0, roiY, frameWidth, roiH)
    //   ⚠️ OpenCV Rect(x, y, width, height) — height là KÍCH THƯỚC, không phải tọa độ cuối
    //   Lý do: phần trên frame thường là trời/tường xa, không phải vật cản

    // Bước 3 — Dynamic threshold theo percentile (KHÔNG dùng ngưỡng cứng)
    //   Lý do: MiDaS relative depth thay đổi scale theo từng frame/scene
    //   foregroundRatio = Config.FOREGROUND_RATIO  (35%)
    //   flat = normMat_roi.reshape(1,1)
    //   sort ascending
    //   cutoffIdx = (flat.cols() * (1f - foregroundRatio)).toInt()
    //   threshold  = flat.get(0, cutoffIdx)[0]
    //   Imgproc.threshold(normMat_roi, mask, threshold, 255.0, THRESH_BINARY)

    // Bước 4 — Morphology
    //   kernel = getStructuringElement(MORPH_ELLIPSE, Size(5.0, 5.0))
    //   MORPH_OPEN  → xóa noise pixel nhỏ
    //   MORPH_CLOSE → lấp lỗ hổng bên trong object

    // Bước 5 — Trả về mask full frame size
    //   Tạo Mat zeros cùng size frame, copy mask vào vùng ROI

    // Bước 6 — Release Mat tạm
    //   normMat, flat (sorted copy), kernel, intermediate morph Mats → .release()
    //   CHỈ trả về fullMask — caller chịu trách nhiệm release nó

    fun process(depthMat: Mat, frameWidth: Int, frameHeight: Int): Mat
}
```

---

### 5.4 `ContourDetector.kt`

**Mục đích:** Tìm contour từ mask → raw bbox list.

**Spec:**
```kotlin
object ContourDetector {
    // findContours: RETR_EXTERNAL, CHAIN_APPROX_SIMPLE
    // Filter:
    //   minArea = Config.MIN_CONTOUR_AREA (800 px²)
    //   maxArea = frameArea * Config.MAX_CONTOUR_RATIO (0.7)
    // Trả về List<Rect>: boundingRect() của mỗi contour hợp lệ

    fun detect(mask: Mat, frameWidth: Int, frameHeight: Int): List<Rect>
}
```

---

### 5.5 `UnionFind.kt`

**Mục đích:** Data structure cho bước merge bbox trong `BBoxMerger`.

```kotlin
class UnionFind(size: Int) {
    fun find(x: Int): Int              // path compression
    fun union(x: Int, y: Int)
    fun groups(): Map<Int, List<Int>>  // Map<root, List<memberIndices>>
}
```

---

### 5.6 `BBoxMerger.kt`

**Mục đích:** Merge các bbox overlap hoặc gần nhau → 1 bbox đại diện per group.

**Spec:**
```kotlin
object BBoxMerger {

    // shouldMerge(a, b): true nếu HOẶC:
    //   IoU(a, b) >= Config.MERGE_IOU_THRESHOLD (0.1f)
    //   gapX <= Config.MERGE_GAP_PX && gapY <= Config.MERGE_GAP_PX (25px)
    //     gapX = max(0, max(a.x, b.x) - min(a.x+a.width,  b.x+b.width))
    //     gapY = max(0, max(a.y, b.y) - min(a.y+a.height, b.y+b.height))
    //   Lý do gap: contour cùng object hay tách nhau dù IoU = 0

    // mergeGroup(boxes): weighted average theo area
    //   cx = Σ(area_i × centerX_i) / Σ(area_i)
    //   cy = Σ(area_i × centerY_i) / Σ(area_i)
    //   w  = Σ(area_i × w_i)       / Σ(area_i)
    //   h  = Σ(area_i × h_i)       / Σ(area_i)
    //   Lý do weighted avg: bounding union làm bbox phình to, nuốt background

    // merge(boxes):
    //   1. UnionFind(boxes.size)
    //   2. Với mỗi cặp (i,j): shouldMerge → union(i,j)
    //   3. Với mỗi group: mergeGroup → 1 Rect
    //   4. Trả về List<Rect>

    fun merge(boxes: List<Rect>): List<Rect>
}
```

---

### 5.7 `MultiObjectTracker.kt`

**Mục đích:** SORT-lite tracker — liên kết bbox giữa các frame, smooth bằng Kalman Filter.

**Kalman Filter:**
```
State:       [x, y, w, h, vx, vy]  — 6 states
Measurement: [x, y, w, h]           — 4 measurements

Transition (dt = 1 frame):
  x' = x + vx,  y' = y + vy,  w' = w,  h' = h,  vx' = vx,  vy' = vy

Noise:
  processNoiseCov     = Config.PROCESS_NOISE    (1e-2)
  measurementNoiseCov = Config.MEASUREMENT_NOISE (1e-1)
  errorCovPost        = 1.0
```

**Track:**
```kotlin
data class Track(
    val id: Int,
    val kf: KalmanFilter,
    var missedFrames: Int = 0,
    var smoothedBox: Rect = Rect()
) {
    fun predict(): Rect   // kf.predict() → update smoothedBox từ state[0..3]
    fun update(det: Rect) // kf.correct(measurement) → missedFrames = 0
}
```

**MultiObjectTracker:**
```kotlin
class MultiObjectTracker {
    // MAX_MISSED  = Config.MAX_MISSED_FRAMES  (8)
    // IOU_THRESH  = Config.IOU_MATCH_THRESHOLD (0.25f)

    // update(detections): List<Track>
    //   1. predict() tất cả track
    //   2. Greedy IoU matching (đủ cho 2–5 object)
    //   3. Matched track → update(det), missedFrames = 0
    //   4. Unmatched track → missedFrames++
    //   5. Unmatched detection → tạo Track mới
    //   6. Xóa track có missedFrames > MAX_MISSED
    //   7. Trả về list track còn sống

    // predictOnly(): List<Track>
    //   Chỉ gọi predict() tất cả track, KHÔNG correct bằng detection cũ
    //   Dùng cho các frame không có inference mới
    //   LÝ DO: nếu correct lặp lại bằng detection cũ, Kalman bị "đứng yên" giả tạo

    // clearAll(): xóa toàn bộ track — gọi khi scene change

    fun update(detections: List<Rect>): List<Track>
    fun predictOnly(): List<Track>
    fun clearAll()
    fun activeTracks(): List<Track>
}
```

---

### 5.8 `DistanceEstimator.kt`

**Mục đích:** Tính khoảng cách Z (mét) từ bbox và tilt sensor.

**Công thức:**
```
v_bottom   = bbox.y + bbox.height
alpha      = atan((v_bottom - cy) / fy)
totalAngle = tiltRad + alpha
Z          = H / tan(totalAngle)
```

**Guards — trả null nếu vi phạm bất kỳ điều kiện nào:**
```
1. tiltRad == null
2. totalAngle <= toRadians(Config.MIN_TOTAL_ANGLE_DEG)  (3.0°)
3. bbox.y + bbox.height >= frameHeight - 5              (chân bbox bị crop)
4. Z < Config.MIN_RELIABLE_Z hoặc Z > Config.MAX_RELIABLE_Z  (0.2–4.0m)
```

**EMA smooth Z per track:**
```
beta = Config.Z_EMA_BETA (0.35f)
smoothZ[trackId] = beta * rawZ + (1 - beta) * smoothZ[trackId]
```

**Spec:**
```kotlin
class DistanceEstimator(
    private var fy: Double,
    private var cy: Double,
    private val H: Double
) {
    // updateIntrinsics(): gọi 1 lần sau khi CameraX bind xong và intrinsics đã lấy được
    fun updateIntrinsics(fy: Double, cy: Double)

    // estimate(): tiltRad là Double? — tự xử lý null bên trong
    fun estimate(trackId: Int, bbox: Rect, frameHeight: Int, tiltRad: Double?): Double?

    fun removeTrack(trackId: Int)  // dọn smoothZ map, tránh memory leak
    fun clearAll()
}
```

**Camera intrinsics — lấy 1 lần khi camera bind:**
```kotlin
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
    // Fallback: FoV dọc ~ 50° → fy ≈ frameHeight / (2 * tan(25°))
    fy = frameHeight * 1.07
    Log.w(TAG, "Camera intrinsics unavailable, using heuristic fy=$fy")
}
distanceEstimator.updateIntrinsics(fy, cy)
```

---

### 5.9 `AlertManager.kt`

**Mục đích:** Phát cảnh báo TTS tiếng Việt, tránh spam.

**Zone mapping (theo centerX):**
```
centerX < frameWidth * 0.35  → "bên trái"
centerX > frameWidth * 0.65  → "bên phải"
else                          → "phía trước"
```

**Proximity → TTS:**
```
Z < 0.8m  → CRITICAL → "Cảnh báo! Vật cản [zone]"     QUEUE_FLUSH
Z < 1.5m  → NEAR     → "Vật cản [zone], [Z] mét"       QUEUE_ADD
Z < 3.0m  → MID      → "Chú ý [zone]"                  QUEUE_ADD
Z >= 3.0m → FAR      → im lặng
```

**Cooldown per zone:**
```
CRITICAL: 1500ms,  NEAR: 2500ms,  MID: 4000ms
Ưu tiên: nếu nhiều zone cùng kích hoạt, phát zone có Z nhỏ nhất
```

**Spec:**
```kotlin
class AlertManager(context: Context) {
    // TTS init: TextToSpeech(context, onInitListener)
    //   onInit: Locale("vi", "VN") → fallback Locale.getDefault() nếu không support
    //   isReady = true chỉ sau khi onInit SUCCESS
    //   update() chỉ gọi TTS khi isReady == true

    fun update(tracks: List<Track>, distances: Map<Int, Double>, frameWidth: Int)
    fun stop()      // tts.stop()     — gọi ở onPause()
    fun shutdown()  // tts.shutdown() — gọi ở onDestroy()
}
```

---

### 5.10 `CameraOverlayView.kt`

**Mục đích:** Custom View vẽ bbox + label — chỉ hiển thị khi DevMode bật.

**Spec:**
```kotlin
class CameraOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Vẽ mỗi track có Z hợp lệ:
    //   CRITICAL → Color.RED,    stroke 4px
    //   NEAR     → Color(255,165,0), stroke 3px
    //   MID      → Color.YELLOW, stroke 2px
    //   Không có Z → abu-abu, label "ID:[id]  N/A"
    // Label: "ID:[id]  [Z]m  [zone]", font 13sp, bg đen 50% alpha
    // Scale: scaleX = view.width / frameWidth
    //        scaleY = view.height / frameHeight

    fun setData(tracks: List<Track>, distances: Map<Int, Double>, frameWidth: Int, frameHeight: Int)
}
```

---

### 5.11 `DevModePanel.kt`

**Mục đích:** Overlay debug khi DevMode bật.

**Layout:**
```
┌─────────────────────────────┐
│ DEV MODE                    │
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
    val tiltDeg: Float?,
    val inferenceMs: Float,
    val trackDistances: Map<Int, Double?>
)

class DevModePanel(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // Vị trí: góc trái trên, padding 16dp
    // Background: đen 70% alpha, bo góc 8dp
    // Font: monospace 12sp, màu trắng
    fun setStats(stats: DevStats)
}
```

---

### 5.12 `PipelineManager.kt`

**Mục đích:** Orchestrate toàn bộ pipeline, xử lý async giữa MiDaS inference và render.

**Threading — chi tiết dispatch:**
```
onFrame(bitmap, frameWidth, frameHeight, scope) — gọi từ CameraX executor:

  ── Bước A: Launch BG inference (nếu job trước đã xong) ──
  if (inferenceJob?.isActive != true) {
      inferenceJob = scope.launch(Dispatchers.Default) {
          val depthMat = midasInference.infer(bitmap)
          val mask     = DepthMaskProcessor.process(depthMat, ...)
          val rawBoxes = ContourDetector.detect(mask, ...)
          val merged   = BBoxMerger.merge(rawBoxes)
          latestDetections = merged     // @Volatile
          hasNewDetections = true        // @Volatile
          lastInferenceMs  = elapsed     // @Volatile
          // Release depthMat, mask ở đây (caller responsibility)
          depthMat.release()
          mask.release()
      }
  }

  ── Bước B: Dispatch sang Main thread — LUÔN chạy mỗi frame ──
  scope.launch(Dispatchers.Main) {
      if (hasNewDetections) {
          tracker.update(latestDetections)
          hasNewDetections = false
      } else {
          tracker.predictOnly()   // ← Kalman bridge, KHÔNG dùng detection cũ
      }
      // Distance + Alert + UI update
      DistanceEstimator.estimate() per track
      AlertManager.update()
      [devMode] CameraOverlayView.setData()
      [devMode] DevModePanel.setStats()
      FPS counter update
  }
```

> **⚠️ QUAN TRỌNG:** `onFrame()` được gọi trên CameraX executor (background thread).
> Nó PHẢI dispatch Bước B sang `Dispatchers.Main` vì:
> - `tracker`, `distanceEstimator` không thread-safe
> - UI update (`setData`, `setStats`, `invalidate`) bắt buộc chạy trên Main thread
> - TTS (`AlertManager.update`) cần gọi từ Main thread
>
> Bước A và Bước B chạy **song song**: Main thread luôn render mỗi frame,
> BG thread chỉ chạy inference khi rảnh.

**Adaptive inference skip:**
```kotlin
// Chỉ launch BG job khi job trước đã xong
if (inferenceJob?.isActive != true) {
    inferenceJob = scope.launch(Dispatchers.Default) { ... }
}
```

**Scene change detection:**
```kotlin
val ratio = currFgCount.toDouble() / prevFgCount
if (ratio < Config.SCENE_CHANGE_MIN_RATIO || ratio > Config.SCENE_CHANGE_MAX_RATIO) {
    tracker.clearAll()
    distanceEstimator.clearAll()
}
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
    private val devModeProvider: () -> Boolean,
    private val userHeightM: Double
) {
    @Volatile var currentFps: Float = 0f
        private set

    fun onFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int, scope: CoroutineScope)
}
```

---

### 5.13 `MainActivity.kt`

**Spec:**
```kotlin
class MainActivity : AppCompatActivity() {

    // 1. Xin permission CAMERA (ActivityResultContracts.RequestPermission)
    //    Denied → dialog giải thích, không crash

    // 2. UserHeight input:
    //    EditText (cm) + Button "Bắt đầu"
    //    Validate: Config.MIN_USER_HEIGHT_CM <= input <= Config.MAX_USER_HEIGHT_CM
    //    H = userHeightCm * Config.CAMERA_HEIGHT_RATIO / 100.0
    //    Lưu SharedPreferences(Config.PREF_KEY_HEIGHT) để không nhập lại

    // 3. Khởi tạo components (sau khi có userHeight):
    //    TiltEstimator, MiDaSInference, MultiObjectTracker,
    //    DistanceEstimator(fy_heuristic, cy_heuristic, H),
    //    AlertManager, CameraOverlayView, DevModePanel, PipelineManager

    // 4. CameraX:
    //    ImageAnalysis: Resolution 640×480
    //    STRATEGY_KEEP_ONLY_LATEST
    //    Executor: Executors.newSingleThreadExecutor()
    //    analyze(): ImageProxy → Bitmap → pipelineManager.onFrame()

    // 5. Camera intrinsics:
    //    Camera2Interop sau khi bind xong
    //    distanceEstimator.updateIntrinsics(fy, cy)

    // 6. DevMode FAB: FloatingActionButton icon bug_report, góc phải dưới
    //    Toggle devMode → update visibility overlay + panel

    // Lifecycle:
    //   onResume:  tiltEstimator.start()
    //   onPause:   tiltEstimator.stop(), alertManager.stop()
    //   onDestroy: midasInference.close(), alertManager.shutdown(), cameraExecutor.shutdown()
}
```

---

### 5.14 `activity_main.xml`

**Mục đích:** Layout chính — stack camera preview, overlay, debug panel, input form, FAB.

**Spec:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Layer 1: Camera preview (bottom) -->
    <androidx.camera.view.PreviewView
        android:id="@+id/previewView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Layer 2: Bbox overlay (devMode only) -->
    <com.example.blindassist.ui.CameraOverlayView
        android:id="@+id/overlayView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <!-- Layer 3: Debug stats panel (devMode only, top-left) -->
    <com.example.blindassist.ui.DevModePanel
        android:id="@+id/devPanel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|start"
        android:layout_margin="16dp"
        android:visibility="gone" />

    <!-- Layer 4: Height input form (initially VISIBLE, GONE after submit) -->
    <LinearLayout
        android:id="@+id/heightInputGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:padding="32dp"
        android:background="#CC000000">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/editHeight"
            android:layout_width="200dp"
            android:layout_height="wrap_content"
            android:hint="Chiều cao (cm)"
            android:inputType="number"
            android:textColor="@android:color/white"
            android:textColorHint="#AAAAAA" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnStart"
            android:layout_width="200dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Bắt đầu" />
    </LinearLayout>

    <!-- Layer 5: DevMode FAB (bottom-right) -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabDevMode"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:src="@android:drawable/ic_menu_report_image"
        android:contentDescription="Toggle DevMode"
        android:visibility="gone" />
    <!-- FAB visibility=gone ban đầu, chỉ VISIBLE sau khi nhập height và camera bắt đầu -->

</FrameLayout>
```

> **Layer order:** FrameLayout stack từ dưới lên: PreviewView → OverlayView → DevPanel → HeightInput → FAB.
> **HeightInput flow:** Ban đầu `VISIBLE` che camera. Sau khi nhập height hợp lệ → `GONE`, FAB → `VISIBLE`.
> **Nếu đã có height trong SharedPreferences:** `heightInputGroup.visibility = GONE` ngay từ đầu.

---

## 6. Luồng dữ liệu end-to-end

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
        │   BBoxMerger.merge()                                          │
        │        ↓ merged List<Rect>                                    │
        │   @Volatile latestDetections, hasNewDetections, lastInferenceMs│
        └────────────────────────────────────────────────────────────────┘
        │
        ├──[Main Thread: Dispatchers.Main — mỗi frame]──────────────────┐
        │   if hasNewDetections → tracker.update(latestDetections)      │
        │   else                → tracker.predictOnly()                 │
        │   TiltEstimator.getTiltRad()  → Double?                      │
        │   DistanceEstimator.estimate() per track                      │
        │        ↓ Map<trackId, Z>                                      │
        │   AlertManager.update()  → TTS                               │
        │   [devMode] CameraOverlayView.setData()                       │
        │   [devMode] DevModePanel.setStats()                           │
        └────────────────────────────────────────────────────────────────┘
```

---

## 7. Thứ tự implement và test từng bước

> Quy tắc: **mỗi bước phải pass toàn bộ checkpoint trước khi chuyển sang bước tiếp theo**.
> Không có exception. Nếu checkpoint fail, debug và fix trong bước đó.

---

### Bước 0 — Project scaffold + Gradle sync

> **⚠️ Project đã tồn tại:** Project hiện tại đã có scaffold cơ bản (Empty Activity, Java 11).
> Bước này **THAY THẾ HOÀN TOÀN** `gradle/libs.versions.toml` và `app/build.gradle.kts`
> theo spec trong Section 3 — KHÔNG chỉ thêm dependency mà phải thay thế toàn bộ file.
> `AndroidManifest.xml` giữ nguyên, chỉ thêm `<uses-permission android:name="android.permission.CAMERA" />`.

**Việc cần làm:**
1. Thay thế `gradle/libs.versions.toml` hoàn toàn theo Section 3
2. Thay thế `app/build.gradle.kts` hoàn toàn theo Section 3 (bao gồm `minSdk = 26`, `compileSdk`, `noCompress`)
3. Tạo `Config.kt` theo Section 4 — chưa dùng, chỉ compile
4. Thêm `<uses-permission android:name="android.permission.CAMERA" />` vào `AndroidManifest.xml`
5. Tạo thư mục `app/src/main/assets/` (để sau đặt model)
6. Chạy Gradle sync

**Checkpoint:**
- [ ] `java -version` output JDK 17+
- [ ] `./gradlew assembleDebug` thành công, không có unresolved dependency
- [ ] `Config.kt` compile không lỗi
- [ ] `libs.versions.toml` có đầy đủ: camerax, tflite, opencv, coroutines
- [ ] `build.gradle.kts` **KHÔNG có** `kotlin-android` plugin (AGP 9.0 built-in)
- [ ] `build.gradle.kts` có `compileSdk = 36`, `minSdk = 26`, `noCompress += "tflite"`
- [ ] `build.gradle.kts` **KHÔNG có** `kotlinOptions { jvmTarget }` block

---

### Bước 1 — `TiltEstimator`

**Việc cần làm:**
1. Implement `TiltEstimator.kt` theo spec
2. Trong `MainActivity` tạm: gọi `tiltEstimator.start()` ở `onResume()`, log `getTiltRad()` mỗi giây

**Checkpoint:**
- [ ] Đứng yên, phone đứng thẳng → log tiltRad ≈ 0 (±0.05 rad)
- [ ] Nghiêng phone 15° xuống → log tiltRad ≈ +0.26 rad
- [ ] Đi bộ bình thường → dao động < ±0.035 rad (2°)
- [ ] `getTiltRad()` trả `null` trong ~500ms đầu sau khi start (sensor chưa ready)
- [ ] `tiltEstimator.stop()` ở `onPause()` — verify không còn log sau khi app vào background

---

### Bước 2 — `MiDaSInference`

**Việc cần làm:**
1. Đặt `MiDaS_small.tflite` vào `assets/`
2. Implement `MiDaSInference.kt` theo spec
3. Trong `MainActivity` tạm: chụp 1 frame tĩnh (hardcode bitmap từ drawable), gọi `infer()`, log kết quả

**Checkpoint:**
- [ ] GpuDelegate load thành công — log xác nhận delegate đang dùng
- [ ] Nếu GpuDelegate fail (test bằng cách throw tay) → fallback CPU, không crash
- [ ] Output Mat không null, size = frameWidth × frameHeight, type = CV_32F
- [ ] Inference time < 150ms (log `System.currentTimeMillis()` trước và sau)
- [ ] `close()` gọi ở `onDestroy()` không crash

---

### Bước 3 — `DepthMaskProcessor` + `ContourDetector`

**Việc cần làm:**
1. Implement `DepthMaskProcessor.kt` và `ContourDetector.kt`
2. Implement `CameraOverlayView.kt` tối giản: chỉ vẽ bbox (chưa cần màu, label, Z)
3. Kết nối tạm: `MiDaS → DepthMask → Contour → vẽ bbox lên màn hình`

**Checkpoint:**
- [ ] Mask che đúng vùng object gần (kiểm tra bằng mắt khi đứng trước bàn/tường)
- [ ] Không có bbox khi frame trống (không có vật cản gần)
- [ ] Không có bbox nào chiếm > 70% diện tích frame
- [ ] Vùng trên 1/3 frame (trời, trần nhà) không tạo bbox
- [ ] Threshold dynamic hoạt động: thay đổi lighting không làm mask mất hoàn toàn

---

### Bước 4 — `UnionFind` + `BBoxMerger`

**Việc cần làm:**
1. Implement `UnionFind.kt` và `BBoxMerger.kt`
2. Viết unit test riêng (JUnit) với mock bbox list

**Unit test cases:**
```kotlin
// Case 1: 2 bbox overlap IoU > 0.1 → merge thành 1
// Case 2: 2 bbox gap < 25px → merge thành 1
// Case 3: 2 bbox xa nhau (IoU=0, gap>25px) → giữ 2 bbox riêng
// Case 4: 3 bbox thành chuỗi A-B-C (A merge B, B merge C) → 1 bbox
// Case 5: empty list → empty list
// Case 6: 1 bbox → 1 bbox
```

**Checkpoint:**
- [ ] Tất cả unit test pass
- [ ] Sau khi tích hợp vào pipeline tạm: nhìn thấy bbox nhỏ lẻ ở cùng object được merge lại

---

### Bước 5 — `MultiObjectTracker`

**Việc cần làm:**
1. Implement `UnionFind.kt` nếu chưa (dùng cho tracker)
2. Implement `MultiObjectTracker.kt` với `Track` + Kalman + `predictOnly()`
3. Tích hợp vào pipeline tạm: hiển thị track ID + smoothedBox lên màn hình

**Checkpoint:**
- [ ] Track ID ổn định khi đứng yên nhìn vào 1 object > 10 giây — ID không đổi
- [ ] Track ID không bị swap khi có 2 object song song
- [ ] Tạo track mới khi object mới vào frame (ID mới xuất hiện)
- [ ] Track bị xóa đúng sau `MAX_MISSED_FRAMES` (8) frame không detect được
- [ ] `predictOnly()`: khi che camera rồi bỏ che → bbox trôi nhẹ theo Kalman, không bị reset về detection cũ
- [ ] `clearAll()`: sau khi gọi → không còn track nào, frame tiếp theo tạo track mới

---

### Bước 6 — `DistanceEstimator`

**Việc cần làm:**
1. Implement `DistanceEstimator.kt` với `updateIntrinsics()` và `tiltRad: Double?`
2. Lấy camera intrinsics thật từ `CameraManager`
3. Tích hợp với `TiltEstimator` + `MultiObjectTracker`
4. Log Z theo từng track ID ra Logcat

**Test thực tế (cần thước dây hoặc biết khoảng cách):**

| Khoảng cách thật | Z mong đợi | Sai số cho phép |
|-----------------|-----------|----------------|
| 1.0m            | 1.0m      | ±0.2m          |
| 2.0m            | 2.0m      | ±0.4m          |

**Checkpoint:**
- [ ] Z ≈ 1.0m khi cách vật 1m
- [ ] Z ≈ 2.0m khi cách vật 2m
- [ ] Z = null khi bbox chạm cạnh dưới frame (bbox.y + bbox.height >= frameHeight - 5)
- [ ] Z = null khi `getTiltRad()` trả null (test bằng cách giả tiltRad = null)
- [ ] Z = null khi nghiêng phone ngang (totalAngle ≤ 3°) — không crash
- [ ] `removeTrack()` được gọi khi tracker xóa track → map dọn sạch
- [ ] `updateIntrinsics()` được gọi sau khi camera bind xong

---

### Bước 7 — `AlertManager`

**Việc cần làm:**
1. Implement `AlertManager.kt` với TTS + cooldown + `stop()`
2. Test độc lập: tạo mock `Track` và `Map<Int, Double>`, gọi `update()` thủ công từ button

**Checkpoint:**
- [ ] TTS phát tiếng Việt đúng giọng (nghe rõ "Cảnh báo!", "Vật cản", "phía trước", "bên trái", "bên phải")
- [ ] Z < 0.8m → CRITICAL phát ngay lập tức, ngắt câu đang nói
- [ ] Cooldown CRITICAL (1500ms): đứng yên trước vật cản → TTS phát, 1.5s sau phát lại, không spam liên tục
- [ ] Cooldown NEAR (2500ms) và MID (4000ms) hoạt động tương tự
- [ ] Z ≥ 3.0m → hoàn toàn im lặng
- [ ] `isReady = false` trước khi `onInit` → không crash
- [ ] `stop()` ở onPause: TTS dừng phát — gọi lại `update()` ở onResume vẫn hoạt động bình thường
- [ ] `shutdown()` ở onDestroy: không crash khi gọi sau `stop()`

---

### Bước 8 — `DevModePanel` + `CameraOverlayView` hoàn chỉnh

**Việc cần làm:**
1. Hoàn thiện `CameraOverlayView.kt`: màu theo proximity, label đầy đủ, scale đúng
2. Implement `DevModePanel.kt` với `DevStats`
3. Thêm DevMode FAB vào `activity_main.xml` và `MainActivity`

**Checkpoint:**
- [ ] FAB toggle đúng: tap → overlay + panel hiện, tap lại → ẩn
- [ ] Màu bbox đúng: RED khi Z < 0.8m, cam khi < 1.5m, vàng khi < 3.0m, xám khi null
- [ ] Label đúng format: "ID:1  1.2m  phía trước"
- [ ] DevModePanel hiển thị FPS, Objects, Tilt, Inference, từng Track + Z
- [ ] Tilt hiển thị bằng độ (tiltRad × 180/π), null hiển thị "N/A"
- [ ] devMode = false (default): màn hình sạch, không có overlay nào

---

### Bước 9 — `PipelineManager` + tích hợp toàn bộ

**Việc cần làm:**
1. Implement `PipelineManager.kt` với đầy đủ logic threading, `hasNewDetections`, scene change, FPS counter
2. Kết nối tất cả components trong `MainActivity`
3. Bỏ toàn bộ code test tạm từ các bước trước

**Checkpoint:**
- [ ] Kalman bridge hoạt động: che camera → bbox tiếp tục trôi theo predict, không bị đứng yên do detection cũ
- [ ] Scene change reset: đột ngột quay sang scene khác → track cũ bị xóa, track mới tạo lại
- [ ] FPS ≥ 15 khi có 3 object, devMode = false
- [ ] FPS ≥ 12 khi có 3 object, devMode = true
- [ ] Không có `ConcurrentModificationException` hay race condition trong 5 phút chạy liên tục
- [ ] `hasNewDetections` reset về false sau mỗi lần `tracker.update()` được gọi

---

### Bước 10 — `MainActivity` hoàn chỉnh + end-to-end

**Việc cần làm:**
1. Thêm UserHeight input flow (EditText + validate + SharedPreferences)
2. Xin CAMERA permission đúng cách
3. Camera intrinsics: lấy thực từ Camera2Interop, gọi `updateIntrinsics()`
4. Lifecycle đầy đủ: onResume/onPause/onDestroy

**Checkpoint:**
- [ ] App lần đầu mở: hiện input height, validate từ chối < 100cm hoặc > 220cm
- [ ] Mở lại app lần 2: không hiện input height nữa (đã lưu SharedPreferences)
- [ ] Permission denied: hiện dialog, không crash
- [ ] `onPause` → `onResume`: sensor restart đúng, TTS tiếp tục hoạt động
- [ ] `onDestroy`: không có leak (kiểm tra Logcat không có "leaked" warning từ TFLite hay TTS)
- [ ] Chạy end-to-end: đi bộ trong hành lang 3 phút, TTS phát cảnh báo đúng, không crash, không OOM

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
[ ] predictOnly() không correct bằng detection cũ

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
[ ] stop() và shutdown() lifecycle đúng

DEVMODE
[ ] FAB toggle đúng visibility
[ ] FPS hiển thị realtime
[ ] Màu bbox đúng theo proximity
[ ] Tất cả track và Z hiển thị đúng trong panel

PERFORMANCE
[ ] FPS >= 15 khi có 3 object, devMode=false
[ ] FPS >= 12 khi có 3 object, devMode=true
[ ] Không OOM sau 5 phút chạy liên tục
[ ] Battery drain hợp lý (sensor stop khi onPause)
```
