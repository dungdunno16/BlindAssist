# 🦯 BlindAssist — Ứng Dụng Hỗ Trợ Người Khiếm Thị

> Ứng dụng Android nhận diện vật cản và ước lượng khoảng cách trong thời gian thực, sử dụng AI và Computer Vision để giúp người khiếm thị di chuyển an toàn hơn.

---

## 📋 Mục Lục

- [Tổng Quan](#-tổng-quan)
- [Tính Năng Chính](#-tính-năng-chính)
- [Kiến Trúc Hệ Thống](#-kiến-trúc-hệ-thống)
- [Công Nghệ Sử Dụng](#-công-nghệ-sử-dụng)
- [Cấu Trúc Dự Án](#-cấu-trúc-dự-án)
- [Yêu Cầu Hệ Thống](#-yêu-cầu-hệ-thống)
- [Cài Đặt & Chạy](#-cài-đặt--chạy)
- [Hướng Dẫn Sử Dụng](#-hướng-dẫn-sử-dụng)
- [Cấu Hình](#-cấu-hình)
- [Pipeline Xử Lý](#-pipeline-xử-lý)
- [Giấy Phép](#-giấy-phép)

---

## 🌟 Tổng Quan

**BlindAssist** là ứng dụng Android được phát triển nhằm hỗ trợ người khiếm thị phát hiện và tránh vật cản trong quá trình di chuyển. Thay vì sử dụng các mô hình Object Detection truyền thống (YOLO, SSD...), ứng dụng áp dụng phương pháp **Depth Estimation** kết hợp với **Optical Flow** và **Kalman Filter** để đạt hiệu suất real-time trên thiết bị di động.

### Điểm nổi bật

- 🧠 **Không phụ thuộc Object Detection** — Phát hiện *mọi loại* vật cản, kể cả vật thể mà YOLO không nhận diện được
- ⚡ **Real-time** — Hybrid Tracking giảm tải AI, chỉ chạy inference định kỳ, tracking bằng Optical Flow ở các frame trung gian
- 📏 **Ước lượng khoảng cách thực tế** — Kết hợp trigonometry, camera intrinsics và cảm biến IMU
- 🗣️ **Cảnh báo bằng giọng nói tiếng Việt** — Text-to-Speech với hệ thống cooldown chống spam
- 🤖 **Mô tả cảnh bằng AI** — Tích hợp Gemini API để mô tả bối cảnh xung quanh khi double-tap

---

## ✨ Tính Năng Chính

| Tính năng | Mô tả |
|---|---|
| **Phát hiện vật cản** | Sử dụng MiDaS depth estimation + OpenCV edge detection để phát hiện mọi loại chướng ngại vật |
| **Theo dõi đa đối tượng** | Lucas-Kanade Optical Flow + Kalman Filter giúp tracking mượt mà giữa các inference frame |
| **Ước lượng khoảng cách** | Trigonometry kết hợp góc nghiêng điện thoại (IMU) và focal length camera → khoảng cách thực (mét) |
| **Cảnh báo TTS** | Cảnh báo tiếng Việt theo 3 mức: Nguy hiểm (<0.8m), Gần (<1.5m), Chú ý (<3.0m) |
| **Mô tả cảnh (Gemini)** | Double-tap để Gemini AI mô tả ngắn gọn cảnh xung quanh qua giọng nói |
| **Chế độ Developer** | Overlay bbox, depth map, FPS, tilt angle, khoảng cách từng vật cản |
| **Rung cảnh báo** | Haptic feedback bổ sung cho cảnh báo giọng nói |

---

## 🏗️ Kiến Trúc Hệ Thống

```
┌─────────────────────────────────────────────────────────────────┐
│                         CameraX Frame                           │
│                   (ImageProxy → Bitmap, 640×480)                │
└──────────────┬───────────────────────────────┬──────────────────┘
               │                               │
    ┌──────────▼──────────┐         ┌──────────▼──────────┐
    │   Background Thread │         │     Main Thread     │
    │   (Dispatchers.     │         │  (Dispatchers.Main) │
    │    Default)          │         │                     │
    │                     │         │  ┌─────────────────┐ │
    │  MiDaS Inference    │         │  │ Optical Flow    │ │
    │       ↓             │         │  │ Tracking        │ │
    │  Depth Mask (OpenCV)│         │  │ (Lucas-Kanade)  │ │
    │       ↓             │  ────►  │  └────────┬────────┘ │
    │  Contour Detection  │         │           ↓          │
    │       ↓             │         │  Kalman Filter       │
    │  BBox Merger        │         │       ↓              │
    │                     │         │  Distance Estimator  │
    └─────────────────────┘         │       ↓              │
                                    │  Alert Manager (TTS) │
                                    │       ↓              │
                                    │  UI Overlay (DevMode)│
                                    └─────────────────────┘

    ┌─────────────────────────────────────────────────────────────┐
    │              User Event: Double Tap                         │
    │  SceneDescribeController → GeminiDescriber → TTS output     │
    └─────────────────────────────────────────────────────────────┘
```

### Mô hình Hybrid Tracking

Ứng dụng **không chạy AI trên mọi frame** để tránh quá nhiệt và giảm FPS. Thay vào đó:

1. **Inference Frame** (định kỳ): MiDaS → Edge Detection → Contour → BBox → Cập nhật tracker
2. **Intermediate Frame** (mỗi frame): Optical Flow (Lucas-Kanade) → Dịch chuyển BBox → Kalman Filter hiệu chỉnh

---

## 🛠️ Công Nghệ Sử Dụng

| Công nghệ | Version | Vai trò |
|---|---|---|
| **Kotlin** | Built-in (AGP 9.x) | Ngôn ngữ chính |
| **Android Gradle Plugin** | 9.2.1 | Build system (tích hợp sẵn Kotlin compiler) |
| **CameraX** | 1.6.1 | Camera API (ImageAnalysis) |
| **TensorFlow Lite** | 2.16.1 | Chạy model MiDaS depth estimation |
| **OpenCV** | 4.10.0 | Computer Vision (Canny Edge, Morphology, Optical Flow, Kalman) |
| **Gemini API** | 0.9.0 | Mô tả cảnh bằng AI |
| **Coroutines** | 1.9.0 | Async/concurrency |
| **Material Design** | 1.14.0 | UI Components |
| **IMU Sensors** | — | `TYPE_ROTATION_VECTOR` cho góc nghiêng |
| **TextToSpeech** | Android API | Cảnh báo giọng nói tiếng Việt |

---

## 📁 Cấu Trúc Dự Án

```
app/src/main/
├── assets/
│   └── MiDaS_small.tflite          # Model ước lượng độ sâu
├── java/com/example/blindassist/
│   ├── Config.kt                    # Hằng số cấu hình tập trung
│   ├── MainActivity.kt             # Activity chính, lifecycle, CameraX
│   ├── PipelineManager.kt          # Điều phối toàn bộ pipeline xử lý
│   │
│   ├── sensor/
│   │   └── TiltEstimator.kt        # Đo góc nghiêng điện thoại (IMU)
│   │
│   ├── depth/
│   │   ├── MiDaSInference.kt       # TFLite inference (MiDaS small)
│   │   ├── DepthMaskProcessor.kt   # OpenCV: Canny Edge + Morphology → mask
│   │   ├── ContourDetector.kt      # Trích xuất contour → bounding box
│   │   ├── DistanceEstimator.kt    # Tính khoảng cách Z (mét) bằng trigonometry
│   │   ├── BBoxMerger.kt           # Hợp nhất bbox chồng lấn (Union-Find)
│   │   └── UnionFind.kt            # Cấu trúc dữ liệu Union-Find
│   │
│   ├── tracking/
│   │   ├── MultiObjectTracker.kt   # Tracker đa đối tượng (Optical Flow + Kalman)
│   │   ├── BoxKalmanFilter.kt      # Bộ lọc Kalman 8 biến trạng thái
│   │   └── TrackerEntry.kt         # Data class cho mỗi tracked object
│   │
│   ├── alert/
│   │   └── AlertManager.kt         # Quản lý TTS + rung + cooldown
│   │
│   ├── gemini/
│   │   ├── GeminiDescriber.kt      # Gọi Gemini API mô tả cảnh
│   │   ├── SceneDescribeController.kt  # Xử lý double-tap, timeout, suppress
│   │   └── SceneMetadata.kt        # Data class metadata cảnh
│   │
│   └── ui/
│       └── CameraOverlayView.kt    # Custom View vẽ bbox overlay (DevMode)
│
└── res/
    └── layout/
        └── activity_main.xml       # Layout chính
```

---

## 📱 Yêu Cầu Hệ Thống

### Thiết bị

- **Android**: API 26+ (Android 8.0 Oreo trở lên)
- **Camera**: Camera sau có hỗ trợ CameraX
- **Cảm biến**: Accelerometer + Gyroscope + Magnetometer (Rotation Vector sensor)
- **TTS**: Google Text-to-Speech engine (hỗ trợ tiếng Việt)
- **Mạng**: Internet (tùy chọn, cho tính năng Gemini mô tả cảnh)

### Môi trường phát triển

- **JDK**: 17+ (21 khuyến nghị)
- **Android Studio**: Phiên bản hỗ trợ AGP 9.x
- **Android SDK**: compileSdk 36, targetSdk 36

---

## 🚀 Cài Đặt & Chạy

### 1. Clone dự án

```bash
git clone https://github.com/dungdunno16/BlindAssist.git
cd BlindAssist
```

### 2. Chuẩn bị model AI

Đặt file `MiDaS_small.tflite` vào thư mục:
```
app/src/main/assets/MiDaS_small.tflite
```

> **Nguồn model**: [intel-isl/MiDaS](https://github.com/isl-org/MiDaS) — phiên bản small, input `[1, 3, 256, 256]` CHW format.

### 3. Cấu hình Gemini API (tùy chọn)

Để sử dụng tính năng mô tả cảnh bằng AI, cần có Gemini API key:

1. Lấy API key tại [Google AI Studio](https://aistudio.google.com/)
2. Thêm key vào dự án theo cách phù hợp (BuildConfig, local.properties, hoặc SharedPreferences)

### 4. Build & chạy

```bash
# Kiểm tra JDK version
java -version  # Cần JDK 17+

# Sync Gradle
./gradlew --refresh-dependencies

# Build debug APK
./gradlew assembleDebug

# Hoặc mở bằng Android Studio và Run trực tiếp
```

> ⚠️ **Lưu ý quan trọng**:
> - Project sử dụng **AGP 9.x** với Kotlin compiler tích hợp sẵn — **KHÔNG** cần plugin `org.jetbrains.kotlin.android`
> - File `.tflite` được cấu hình `noCompress` trong Gradle để hỗ trợ memory-mapped loading

---

## 📖 Hướng Dẫn Sử Dụng

### Lần đầu mở app

1. **Cấp quyền Camera** — App yêu cầu quyền truy cập camera
2. **Nhập chiều cao** — Nhập chiều cao của người dùng (cm) để tính khoảng cách chính xác
   - Giá trị hợp lệ: 100cm – 220cm
   - Chiều cao được lưu lại, không cần nhập lại lần sau

### Sử dụng hàng ngày

| Thao tác | Chức năng |
|---|---|
| **Cầm điện thoại hướng phía trước** | Camera tự động quét và cảnh báo vật cản qua giọng nói |
| **Double-tap màn hình** | Gemini AI mô tả cảnh xung quanh (cần Internet) |
| **Nút Cài đặt** | Chỉnh sửa chiều cao hoặc bật/tắt chế độ Developer |

### Hệ thống cảnh báo

| Khoảng cách | Mức cảnh báo | Nội dung TTS | Cooldown |
|---|---|---|---|
| < 0.8m | 🔴 **CRITICAL** | *"Cảnh báo! Vật cản [vị trí]"* | 1.5s |
| < 1.5m | 🟠 **NEAR** | *"Vật cản [vị trí], [X] mét"* | 2.5s |
| < 3.0m | 🟡 **MID** | *"Chú ý [vị trí]"* | 4.0s |
| ≥ 3.0m | ⚪ **FAR** | Im lặng | — |

Vị trí được xác định theo vùng: **bên trái**, **phía trước**, **bên phải**.

---

## ⚙️ Cấu Hình

Tất cả hằng số cấu hình được tập trung trong [`Config.kt`](app/src/main/java/com/example/blindassist/Config.kt):

| Nhóm | Tham số | Giá trị | Ý nghĩa |
|---|---|---|---|
| **MiDaS** | `MIDAS_INPUT_SIZE` | 256 | Kích thước đầu vào model |
| **Detection** | `MIN_CONTOUR_AREA` | 800 px² | Diện tích tối thiểu contour |
| | `MAX_CONTOUR_RATIO` | 0.7 | Tỷ lệ diện tích tối đa so với frame |
| | `MERGE_IOU_THRESHOLD` | 0.1 | Ngưỡng IoU để merge bbox |
| **Tracking** | `MAX_MISSED_FRAMES` | 15 | Số frame tối đa trước khi xóa track |
| | `IOU_MATCH_THRESHOLD` | 0.3 | Ngưỡng IoU để ghép track |
| **Distance** | `MIN_RELIABLE_Z` | 0.2m | Khoảng cách tối thiểu tin cậy |
| | `MAX_RELIABLE_Z` | 4.0m | Khoảng cách tối đa tin cậy |
| | `Z_EMA_BETA` | 0.35 | Hệ số EMA làm mượt khoảng cách |
| **Alert** | `THRESHOLD_CRITICAL_M` | 0.8m | Ngưỡng cảnh báo nguy hiểm |
| | `THRESHOLD_NEAR_M` | 1.5m | Ngưỡng cảnh báo gần |
| | `THRESHOLD_MID_M` | 3.0m | Ngưỡng chú ý |
| **Gemini** | `GEMINI_MODEL_NAME` | gemini-2.5-flash | Model AI mô tả cảnh |

---

## 🔄 Pipeline Xử Lý

### Luồng dữ liệu End-to-End

```
CameraX Frame (640×480)
       │
       ├── [Background Thread] ────────────────────────────┐
       │   1. MiDaS Inference (TFLite, 256×256)            │
       │   2. Normalize → Gaussian Blur → Canny Edge       │
       │   3. Dilate → Contour Detection → BBox            │
       │   4. Union-Find BBox Merger                        │
       │   → @Volatile latestDetections                     │
       └───────────────────────────────────────────────────┘
       │
       ├── [Main Thread — Mỗi frame] ─────────────────────┐
       │   IF có detection mới:                            │
       │     → tracker.updateWithDetections()              │
       │   ELSE:                                           │
       │     → tracker.updateWithOpticalFlow()             │
       │   → DistanceEstimator.estimate() per track        │
       │   → AlertManager.update() → TTS                   │
       │   → [DevMode] UI overlay update                   │
       └──────────────────────────────────────────────────┘
```

### Tính toán khoảng cách

Sử dụng công thức trigonometry:

```
Z = H / tan(tiltRad + alpha)

Trong đó:
  H        = chiều cao camera (chiều cao người × 0.6)
  tiltRad  = góc nghiêng điện thoại (từ IMU sensor)
  alpha    = atan((v_bottom - cy) / fy)
  v_bottom = tọa độ đáy của bounding box
  fy, cy   = camera intrinsics (focal length, principal point)
```

---

## 🎯 Hiệu Năng Mục Tiêu

| Chỉ số | Mục tiêu |
|---|---|
| FPS (DevMode OFF) | ≥ 15 fps |
| FPS (DevMode ON) | ≥ 12 fps |
| MiDaS Inference | < 150ms |
| Sai số khoảng cách (1m) | ±0.2m |
| Sai số khoảng cách (2m) | ±0.4m |
| Chạy liên tục | ≥ 5 phút, không OOM |

---

## 📄 Giấy Phép

### Thư viện bên thứ ba

- **MiDaS** (Intel ISL) — [MIT License](https://github.com/isl-org/MiDaS/blob/master/LICENSE)
- **OpenCV** — [Apache 2.0 License](https://opencv.org/license/)
- **TensorFlow Lite** — [Apache 2.0 License](https://www.tensorflow.org/lite)
- **CameraX** — [Apache 2.0 License](https://developer.android.com/jetpack/androidx/releases/camera)
- **Gemini API** — [Google AI Terms](https://ai.google.dev/terms)

---

<p align="center">
  Được phát triển để hỗ trợ cộng đồng người khiếm thị Việt Nam
</p>
