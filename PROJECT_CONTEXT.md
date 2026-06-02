# Tổng Quan Ngữ Cảnh Dự Án BlindAssist (Project Context)

Tài liệu này cung cấp toàn bộ ngữ cảnh kỹ thuật, kiến trúc và tiến độ của dự án **BlindAssist** để bất kỳ AI Agent nào đọc vào cũng có thể hiểu và làm việc ngay lập tức mà không cần phải duyệt lại toàn bộ codebase. 

---

## 1. Mục Tiêu Dự Án
**BlindAssist** là ứng dụng Android hỗ trợ người khiếm thị nhận diện và tránh vật cản trong thời gian thực (Real-time). Thay vì dựa vào các mô hình Object Detection nặng nề (như YOLO), ứng dụng sử dụng mô hình Ước lượng độ sâu (Depth Estimation) kết hợp với các thuật toán theo dõi luồng quang học (Optical Flow) và phân tích hình ảnh (OpenCV) để phát hiện và cảnh báo mọi dạng chướng ngại vật phía trước.

---

## 2. Ngăn Xếp Công Nghệ (Tech Stack)
- **Ngôn ngữ:** Kotlin
- **Camera API:** CameraX (ImageAnalysis)
- **AI / Deep Learning:** TensorFlow Lite (MiDaS_small depth estimation model)
- **Computer Vision:** OpenCV (Canny Edge, Morphology, Optical Flow, Kalman Filter)
- **UI:** XML ViewBinding (Vừa được chuyển đổi từ Jetpack Compose để tối ưu tích hợp OpenCV & CameraX)
- **Cảm biến:** IMU (Accelerometer, Magnetic Field) để tính toán góc nghiêng điện thoại (Pitch angle).

---

## 3. Kiến Trúc Pipeline Cốt Lõi
Pipeline xử lý luồng hình ảnh của ứng dụng không chạy AI trên mọi khung hình để tránh hiện tượng quá nhiệt và giảm FPS. Thay vào đó, nó sử dụng **Mô hình Tracking Lai (Hybrid Tracking):**

### A. Inference Frames (Khung hình có chạy AI)
Được kích hoạt định kỳ (Ví dụ: 1 lần mỗi `TRACK_INTERVAL = 5` frames):
1. **MiDaS Inference:** Tạo bản đồ độ sâu (Depth Map) từ ảnh RGB của camera.
2. **Depth Mask Processor:** Không dùng ngưỡng cắt (thresholding) đơn thuần. Dùng **Gaussian Blur + Canny Edge Detection + Heavy Dilation (Morphology)** để tạo mask lấp đầy các đường viền thành các khối vật cản vật lý rõ ràng.
3. **Contour Detector & BBox Merger:** Trích xuất viền từ Mask để tạo Bounding Box (BBox) và hợp nhất các BBox bị đè lên nhau (bằng thuật toán Union-Find).
4. **Kalman + Optical Flow (Init):** Ghép các BBox tìm được vào các tracker hiện tại (bằng IoU). Trích xuất các điểm đặc trưng (Feature Points - Shi-Tomasi/goodFeaturesToTrack) bên trong BBox để chuẩn bị cho các frame tiếp theo.

### B. Intermediate Frames (Khung hình không chạy AI)
1. **Lucas-Kanade Optical Flow:** Cập nhật vị trí điểm ảnh (Feature Points) từ frame trước sang frame hiện tại bằng OpenCV (`calcOpticalFlowPyrLK`).
2. **Robust Box Update:** Lấy trung vị (Median) của vector dịch chuyển (dx, dy) để dời vị trí BBox.
3. **Kalman Filter Correction:** Lấy vị trí BBox mới làm Measurement đưa vào phương trình **BoxKalmanFilter** (8 biến trạng thái) để lọc nhiễu, chống rung giật.

### C. Tính Toán Khoảng Cách (Distance Estimation)
- Thuật toán lượng giác ánh xạ tọa độ đáy của BBox (Bottom-Center) xuống mặt đất.
- Sử dụng **TiltEstimator** (Cảm biến gia tốc & từ trường) để đo góc chúi của điện thoại kết hợp với tiêu cự (Focal Length) và chiều cao cầm máy của người dùng (Height) để tính khoảng cách thực tế (Z-distance) ra mét.

---

## 4. Cấu Trúc Mã Nguồn Chính
Mã nguồn nằm ở: `app/src/main/java/com/example/blindassist/`

- `PipelineManager.kt`: Trái tim điều phối toàn bộ quá trình (CameraX -> TFLite -> OpenCV -> Alert). Quản lý coroutine và luồng song song.
- `tracking/`: Chứa module theo dõi đa đối tượng.
  - `MultiObjectTracker.kt`: Điều phối chuyển đổi giữa nhận diện AI và Optical Flow.
  - `BoxKalmanFilter.kt` / `TrackerEntry.kt`: Quản lý bộ lọc dự đoán tọa độ khung hình.
- `depth/`: Chứa module nhận diện bằng AI.
  - `MiDaSInference.kt`: Chạy TFLite model.
  - `DepthMaskProcessor.kt`: OpenCV xử lý Edge và Morphology.
  - `DistanceEstimator.kt`: Tính toán lượng giác ra khoảng cách thực.
- `sensor/TiltEstimator.kt`: Đo góc cầm máy (Pitch).

---

## 5. Tình Trạng Hiện Tại & Lịch Sử Quan Trọng
- **Sự thay đổi quan trọng:** Ứng dụng ban đầu định dùng SORT-lite, nhưng do độ nhiễu khung hình cao, dự án đã chuyển hẳn sang **Optical Flow + Kalman Filter** kết hợp với **Canny Edge Detection** (thay cho thresholding) để đạt tốc độ Real-time và ổn định hơn.
- Dự án **KHÔNG** làm việc trên các file Python nguyên mẫu (ví dụ: `DATN-test/distance.py`). Mọi nỗ lực và tập trung hiện tại nằm trong môi trường Android Studio của `BlindAssist`.

**Agent Instruction:** Bạn có thể bám sát kiến trúc được mô tả ở đây khi được yêu cầu sửa lỗi, tối ưu hiệu năng hoặc thêm tính năng mà không cần đọc lại toàn bộ từng file `.kt`.
