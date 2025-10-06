# ASR 语音键盘

一个基于 Android 平台的语音输入法键盘应用，专注于提供高质量的语音转文字输入体验。

## 功能特性

- 🎤 **长按录音**: 通过长按麦克风按钮开始语音识别
- ⚡ **文件极速识别**: 松开麦克风后整体上传音频并一次性返回结果
- 🧠 **AI文本优化**: 集成LLM后处理，智能修正识别结果
- 🔧 **多引擎支持**: 支持火山引擎等多种ASR服务
- 📱 **简洁界面**: Material3设计风格，最小化界面干扰
- 🔐 **权限管理**: 运行时麦克风权限请求

## 技术架构

### 核心组件

- **输入法服务** (`AsrKeyboardService.kt`): 继承自InputMethodService，管理键盘交互和文本输入
- **ASR引擎接口** (`AsrEngine.kt`): 定义统一的ASR引擎接口
- **文件式ASR引擎** (`VolcFileAsrEngine.kt`): 火山引擎文件识别实现
- **LLM后处理器** (`LlmPostProcessor.kt`): 基于大语言模型的文本修正
- **设置界面** (`SettingsActivity.kt`): 配置ASR服务和LLM参数
- **权限管理** (`PermissionActivity.kt`): 处理麦克风权限请求

### 技术栈

- **开发语言**: Kotlin
- **最低SDK**: API 24 (Android 7.0)
- **目标SDK**: API 34 (Android 14)
- **编译SDK**: API 36
- **网络通信**: OkHttp3 (HTTP)
- **UI框架**: Material3 + ViewBinding
- **并发处理**: Kotlin Coroutines

## 快速开始

### 环境要求

- Android Studio Giraffe 或更高版本
- Android SDK 34
- Java 17
- Kotlin 1.9.24

### 构建步骤

1. 克隆项目到本地
2. 使用 Android Studio 打开项目
3. 等待 Gradle 同步完成
4. 连接 Android 设备或启动模拟器
5. 运行 `app` 模块

```bash
# 命令行构建
./gradlew build

# 安装到设备
./gradlew installDebug

# 运行测试
./gradlew test
```

### 使用指南

1. **启用键盘**:
   - 打开应用，点击"启用键盘"
   - 在系统设置中启用 ASR Keyboard

2. **切换输入法**:
   - 点击"选择键盘"
   - 将当前输入法切换为 ASR Keyboard

3. **授权权限**:
   - 首次使用时会请求麦克风权限
   - 请选择"允许"以使用语音识别功能

4. **语音输入**:
   - 在任何文本输入框中，长按麦克风按钮开始录音
   - 松开按钮后自动结束录音并发起识别
   - 识别结果将自动填入文本框

## 配置说明

### 火山引擎ASR配置

当前版本仅使用非流式（文件）识别，配置更简化：

- **X-Api-App-Key**: 应用密钥（必填）
- **X-Api-Access-Key**: 访问密钥（必填）

资源 ID 与服务端点固定为默认值，无需填写：

- 资源 ID：`volc.bigasr.auc_turbo`
- 端点：`https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash`

### LLM后处理配置

可配置大语言模型对识别结果进行智能修正：

- **API密钥**: LLM服务的API密钥
- **服务端点**: LLM API地址
- **模型名称**: 使用的LLM模型
- **温度参数**: 控制生成文本的随机性 (0-1)
- **系统提示词**: 自定义修正指令

## 识别方式

- 非流式：本地录音为 PCM 16kHz/16-bit/mono，结束后封装为 WAV 一次性通过 HTTP 上传至 `recognize/flash` 接口并返回结果。

## 项目结构

```
app/src/main/java/com/example/asrkeyboard/
├── ime/                    # 输入法服务
│   └── AsrKeyboardService.kt
├── asr/                    # ASR引擎实现
│   ├── AsrEngine.kt
│   ├── VolcFileAsrEngine.kt
│   └── LlmPostProcessor.kt
├── ui/                     # 用户界面
│   ├── SettingsActivity.kt
│   └── PermissionActivity.kt
├── store/                  # 数据存储
│   └── Prefs.kt
└── App.kt                  # 应用入口
```

## 开发注意事项

- 本项目专注于语音输入，界面设计保持简洁
- 避免使用全屏模式，确保键盘体验流畅
- 网络访问需要配置有效的服务凭据
- 音频权限采用运行时请求机制
- 支持多种ASR引擎，便于扩展和测试

## 许可证

本项目采用 MIT 许可证，详见 LICENSE 文件。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进项目。在提交代码前，请确保：

1. 代码通过所有测试
2. 遵循项目代码规范
3. 添加必要的注释和文档
4. 更新相关的README文档
