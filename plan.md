# 蓝牙耳机电量增强采集

- [x] 为耳机电量工具层增加 `BluetoothDevice.getBatteryLevel()` 反射兜底与 Battery Service 解析能力
- [x] 为前台服务增加耳机电量主动探测器，连接后定时轮询反射电量，并对支持 LE Battery Service 的设备建立 GATT 读取/订阅
- [x] 补充耳机电量工具层单元测试，并记录当前仍受本机 Android SDK build-tools 34.0.0 缺失 AAPT 阻塞，无法完成 Gradle 编译验证
