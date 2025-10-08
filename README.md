# ASR 语音键盘

一个基于 Android 平台的语音输入法键盘应用，专注于提供高质量的语音转文字输入体验。

## 功能特性

- 🎤 **长按录音**: 通过长按麦克风按钮开始语音识别
- ⚡ **文件极速识别**: 松开麦克风后整体上传音频并一次性返回结果
- 🧠 **AI 文本优化**: 集成 LLM 后处理，智能修正识别结果
- 🔧 **多引擎支持**: 支持火山引擎等多种 ASR 服务
- 📱 **简洁界面**: Material3 设计风格，最小化界面干扰
- 🔐 **权限管理**: 运行时麦克风权限请求
- 🟣 **悬浮球切换**: 支持悬浮球快速切回 ASR 键盘

## 技术架构

### 核心组件

- **输入法服务** (`AsrKeyboardService.kt`): 继承自 InputMethodService，管理键盘交互和文本输入
- **ASR 引擎接口** (`AsrEngine.kt`): 定义统一的 ASR 引擎接口
- **文件式 ASR 引擎** (`VolcFileAsrEngine.kt`/`SiliconFlowFileAsrEngine.kt`): 火山引擎与 SiliconFlow 文件识别实现
- **LLM 后处理器** (`LlmPostProcessor.kt`): 基于大语言模型的文本修正
- **设置界面** (`SettingsActivity.kt`): 配置 ASR 服务和 LLM 参数
- **权限管理** (`PermissionActivity.kt`): 处理麦克风权限请求

### 技术栈

- **开发语言**: Kotlin
- **最低 SDK**: API 31 (Android 11)
- **目标 SDK**: API 34 (Android 14)
- **编译 SDK**: API 36
- **网络通信**: OkHttp3 (HTTP)
- **UI 框架**: Material3 + ViewBinding
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

快速上手步骤见设置页“查看使用指南”按钮，或参考下方示意图：

![ASR Keyboard 使用教程](app/src/main/res/drawable-nodpi/instruct.png)

小技巧：在设置中开启“启用悬浮球快速切换输入法”，并授予悬浮窗权限；当当前输入法不是本应用时，悬浮球会显示，点击即可唤起系统输入法选择器。

## 配置说明

### ASR 供应商选择与配置

设置页支持按供应商切换配置，所选供应商仅显示对应参数：

- 供应商：`Volcano Engine` 或 `SiliconFlow`

#### 火山引擎 ASR 配置

当前版本仅使用非流式（文件）识别，配置更简化：

- **X-Api-App-Key**: 应用密钥（必填）
- **X-Api-Access-Key**: 访问密钥（必填）

资源 ID 与服务端点固定为默认值，无需填写：

- 资源 ID：`volc.bigasr.auc_turbo`
- 端点：`https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash`

#### SiliconFlow ASR 配置

- **API Key（Bearer）**: SiliconFlow 控制台生成的密钥（必填）
- **Model Name**: 如 `FunAudioLLM/SenseVoiceSmall`（默认值）
- 端点固定为：`https://api.siliconflow.cn/v1/audio/transcriptions`

### LLM 后处理配置

可配置大语言模型对识别结果进行智能修正：

- **API 密钥**: LLM 服务的 API 密钥
- **服务端点**: LLM API 地址
- **模型名称**: 使用的 LLM 模型
- **温度参数**: 控制生成文本的随机性 (0-1)
- **系统提示词**: 自定义修正指令

## 识别方式

- 非流式：本地录音为 PCM 16kHz/16-bit/mono，结束后封装为 WAV 一次性通过 HTTP 上传至 `recognize/flash` 接口并返回结果。

## 项目结构

```
app/src/main/java/com/brycewg/asrkb/
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
- 支持多种 ASR 引擎，便于扩展和测试

## 许可证

本项目采用 MIT 许可证，详见 LICENSE 文件。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进项目。在提交代码前，请确保：

1. 代码通过所有测试
2. 遵循项目代码规范
3. 添加必要的注释和文档
4. 更新相关的 README 文档
