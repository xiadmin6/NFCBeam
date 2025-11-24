# NFCBeam - Android NFC+蓝牙文件传输应用

## 项目概述

这是一个Android应用，通过NFC技术快速获取蓝牙配对信息，然后通过蓝牙连接传输照片、视频等文件。被传输方不需要安装该软件，使用Android Beam技术实现快速配对。

## 核心功能

### 1. NFC快速配对
- 使用Android Beam技术通过NFC传输蓝牙设备信息
- 自动建立蓝牙连接，无需手动配对

### 2. 蓝牙文件传输
- 支持照片、视频等多种文件类型传输
- 可靠的文件传输协议，支持大文件传输
- 实时传输进度显示

### 3. 用户界面
- 基于Jetpack Compose的现代化UI
- 四个主要屏幕：
  - 主屏幕：发送和接收选择
  - 文件预览：显示选择的文件和NFC握手提示
  - 传输中：实时进度显示
  - 传输完成：结果摘要

## 技术架构

### 后端核心组件

#### MainActivity.kt
- 主Activity，处理权限请求和NFC/蓝牙初始化
- 实现屏幕导航状态管理
- 处理NFC Intent和蓝牙连接逻辑

#### BluetoothManager.kt
- 蓝牙管理类，处理设备发现、连接、服务器功能
- 实现蓝牙状态监听和设备发现
- 处理NFC接收的蓝牙信息并自动连接

#### FileTransferManager.kt
- 文件传输管理类，实现完整的文件发送协议
- 支持多文件传输和进度跟踪
- 自定义二进制协议（命令+数据格式）

### 前端UI组件

#### HomeScreen.kt
- 主屏幕，包含发送和接收按钮
- 显示应用标题和使用说明

#### FilePreviewScreen.kt
- 文件预览屏幕，显示选择的文件和NFC握手提示
- 包含文件列表和传输摘要

#### TransferInProgressScreen.kt
- 传输中屏幕，显示传输进度和状态
- 支持不同传输状态（连接中、传输中、完成、失败）

#### TransferCompleteScreen.kt
- 传输完成屏幕，显示传输结果摘要
- 支持成功和失败状态显示

## 权限要求

```xml
<!-- NFC权限 -->
<uses-permission android:name="android.permission.NFC" />

<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- 文件存储权限 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- 位置权限（Android 12+蓝牙扫描需要） -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

## 文件传输协议

### 协议格式
```
[命令类型(1字节)][数据长度(4字节)][数据内容]
```

### 命令类型
- `0x01`: 文件信息（文件名、大小、类型）
- `0x02`: 文件数据块
- `0x03`: 传输完成
- `0x04`: 传输错误

## 构建和运行

### 环境要求
- Android Studio
- Gradle 8.0+
- Kotlin 2.0.21
- Android SDK 24+

### 构建命令
```bash
./gradlew assembleDebug
```

### 运行
1. 确保设备支持NFC和蓝牙
2. 安装应用
3. 授予必要的权限
4. 开始使用NFC+蓝牙文件传输

## 使用流程

1. **发送方**：
   - 选择要传输的文件
   - 将设备靠近接收方设备进行NFC握手
   - 等待蓝牙连接建立
   - 开始文件传输

2. **接收方**：
   - 无需安装应用
   - 通过NFC接收蓝牙配对信息
   - 自动接受蓝牙连接请求
   - 接收文件并保存

## 技术特点

- **现代化架构**: 使用Kotlin + Jetpack Compose
- **权限处理**: 完整的Android权限管理
- **多线程**: 文件传输在后台线程执行
- **错误处理**: 完善的错误处理和重试机制
- **用户体验**: 实时进度反馈和状态显示

## 项目结构

```
app/src/main/java/com/example/nfcbeam/
├── MainActivity.kt          # 主Activity
├── BluetoothManager.kt      # 蓝牙管理
├── FileTransferManager.kt   # 文件传输管理
├── TestActivity.kt          # 测试Activity
└── ui/
    ├── theme/               # Compose主题
    └── screens/             # UI屏幕组件
        ├── HomeScreen.kt
        ├── FilePreviewScreen.kt
        ├── TransferInProgressScreen.kt
        └── TransferCompleteScreen.kt
```

## 注意事项

- 确保设备NFC功能已开启
- Android 12+需要位置权限用于蓝牙扫描
- 文件传输速度受蓝牙版本和距离影响
- 大文件传输可能需要较长时间
