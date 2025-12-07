# NFCBeam - Android NFC+蓝牙文件传输应用

## 项目概述

NFCBeam是一个Android应用，通过蓝牙配对机制实现设备间的快速连接，然后通过蓝牙连接传输照片、视频等文件。应用采用现代化的Jetpack Compose UI架构，支持后台文件传输服务，提供完整的文件传输解决方案。

## 核心功能

### 1. 蓝牙OOB快速配对
- **替代原有的NFC配对**：使用蓝牙BLE（低功耗蓝牙）广告和扫描实现设备发现
- **自动设备发现**：通过BLE广告广播设备信息，扫描发现附近设备
- **快速连接建立**：自动建立蓝牙Socket连接，无需手动配对

### 2. 后台文件传输服务
- **前台服务架构**：FileTransferService在后台管理文件传输
- **持续传输支持**：应用切换到后台时传输继续运行
- **进度跟踪**：实时传输进度更新和状态管理

### 3. 多文件传输支持
- **批量文件选择**：支持选择多个照片、视频等文件
- **可靠传输协议**：自定义二进制协议，支持大文件传输
- **传输恢复**：支持传输中断后的恢复机制

### 4. 现代化用户界面
- **Jetpack Compose**：基于声明式UI框架的现代化界面
- **四个主要屏幕**：
  - 主屏幕：发送和接收选择，设备发现状态
  - 文件选择页面：文件预览和选择
  - 传输中屏幕：实时进度显示和状态跟踪
  - 传输完成屏幕：结果摘要和操作选项

## 技术架构

### 前端UI组件

#### HomeScreen.kt
- 主屏幕，包含发送和接收按钮
- 显示设备发现状态和配对进度
- 提供应用使用说明和状态指示

#### FileSelectPage.kt
- 文件选择页面，显示设备存储中的文件
- 支持多文件选择和预览
- 显示文件信息和选择状态

#### TransferInProgressScreen.kt
- 传输中屏幕，显示传输进度和状态
- 支持不同传输状态（连接中、传输中、完成、失败）
- 实时进度条和传输统计

#### TransferCompleteScreen.kt
- 传输完成屏幕，显示传输结果摘要
- 支持成功和失败状态显示
- 提供重新传输和返回主屏幕选项

### 辅助组件

#### BluetoothManager.kt
- 传统蓝牙管理：处理蓝牙设备发现、连接和服务器功能
- 蓝牙状态监听和设备管理
- 与BluetoothOOBPairingManager协同工作

#### DownloadPathManager.kt
- 下载路径管理：处理文件保存位置和路径管理
- 存储权限检查和路径验证
- 文件保存和命名管理

#### HceCardService.kt
- HCE（主机卡模拟）服务：支持NFC卡模拟功能
- APDU命令处理：处理NFC读写器命令
- 遗留的NFC功能支持

## 权限要求

```xml
<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- NFC权限（遗留支持） -->
<uses-permission android:name="android.permission.NFC" />

<!-- 文件存储权限 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

<!-- 位置权限（Android 12+蓝牙扫描需要） -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- 后台服务权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- 网络状态权限 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

<!-- 唤醒锁定权限 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### 数据格式
- **文件信息包**：JSON格式的文件元数据
- **数据块包**：二进制数据块，支持分块传输
- **控制包**：传输控制命令和状态信息

## 构建和运行

### 环境要求
- **Android Studio**：最新稳定版本
- **Gradle**：8.0+（项目使用8.11.0）
- **Kotlin**：2.0.21（带Compose插件）
- **Android SDK**：API 24+（minSdk 24）
- **JDK**：11+（sourceCompatibility JavaVersion.VERSION_11）

### 项目依赖
项目使用Version Catalog管理依赖，主要依赖包括：
- **AndroidX Core**：1.13.0
- **Jetpack Compose BOM**：2024.10.00
- **Material 3**：1.2.1
- **Kotlin Coroutines**：1.8.1
- **Coil Compose**：2.6.0（图片加载）
- **ExoPlayer**：2.19.1（视频播放）

## 使用流程

### 发送端工作流程
1. **启动应用**：打开NFCBeam应用
2. **选择发送**：在主屏幕点击"发送"按钮
3. **设备发现**：应用开始广播BLE广告并扫描附近设备
4. **选择接收设备**：从发现的设备列表中选择接收设备
5. **建立连接**：自动建立蓝牙Socket连接
6. **选择文件**：在文件选择页面选择要传输的文件
7. **开始传输**：确认选择后开始文件传输
8. **传输监控**：在传输中屏幕监控传输进度
9. **传输完成**：在传输完成屏幕查看结果

### 接收端工作流程
1. **启动应用**：打开NFCBeam应用
2. **选择接收**：在主屏幕点击"接收"按钮
3. **等待连接**：应用开始广播BLE广告等待连接
4. **接受连接**：自动接受发送端的蓝牙连接请求
5. **准备接收**：进入文件接收准备状态
6. **接收文件**：自动接收传输的文件
7. **保存文件**：文件保存到设备存储
8. **传输完成**：显示接收完成状态

### 关键状态管理
- **配对状态**：`isPairingCompleted`标记确保在蓝牙Socket连接建立后才跳转到文件选择页面
- **传输状态**：实时跟踪传输进度和状态变化
- **服务状态**：前台服务状态管理和生命周期控制

## 技术特点

### 现代化架构
- **Kotlin优先**：完全使用Kotlin开发
- **Jetpack Compose**：声明式UI框架，现代化界面
- **MVVM模式**：使用ViewModel和LiveData进行状态管理
- **协程异步**：使用Kotlin协程处理异步操作

### 权限和安全
- **动态权限请求**：运行时请求必要权限
- **权限引导**：引导用户授予缺失的权限
- **安全传输**：蓝牙加密传输，文件完整性校验

### 性能和可靠性
- **后台服务**：前台服务确保传输在后台持续运行
- **进度跟踪**：实时传输进度更新和状态反馈
- **错误恢复**：传输错误检测和恢复机制
- **内存管理**：高效的内存使用和资源释放

### 性能优化
1. **内存优化**：优化文件传输时的内存使用
2. **电池优化**：合理使用WakeLock，减少电池消耗
3. **网络优化**：优化蓝牙数据传输效率
4. **UI响应**：确保UI线程不被阻塞，保持界面流畅

## 注意事项

### 设备要求
- **Android版本**：Android 6.0+（API 24+）
- **蓝牙支持**：支持蓝牙4.0+（BLE）
- **存储空间**：足够的存储空间保存传输的文件
- **位置服务**：Android 12+需要开启位置服务用于蓝牙扫描

### 使用限制
- **传输距离**：蓝牙有效传输距离约10米
- **传输速度**：受蓝牙版本和设备性能影响
- **文件大小**：大文件传输可能需要较长时间
- **设备兼容**：某些设备可能有蓝牙兼容性问题

## 许可证

本项目采用MIT许可证。详见LICENSE文件。

## 联系方式

如有问题或建议，请联系qq：3040654314提交。
