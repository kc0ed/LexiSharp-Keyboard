# ASR 语音键盘

一个基于 Kotlin 的 Android 平台的语音输入法键盘应用，专注于提供高质量的语音转文字输入体验。

## 功能特性

- 🎤 **长按录音**: 通过长按麦克风按钮开始语音识别
- ⚡ **文件极速识别**: 松开麦克风后整体上传音频并一次性返回结果
- 🧠 **AI 文本优化**: 集成 LLM 后处理，智能修正识别结果
- 🔧 **多引擎支持**: 支持火山引擎、OpenAI、SiliconFlow、ElevenLabs、阿里云百炼、Google Gemini 等多种 ASR 服务
- 📱 **简洁界面**: Material3 Monet 设计风格，最小化界面干扰
- 🟣 **悬浮球切换**: 支持悬浮球快速切回 ASR 键盘

## 技术架构

### 核心组件

- **输入法服务** (`AsrKeyboardService.kt`): 继承自 InputMethodService，管理键盘交互和文本输入
- **ASR 引擎接口** (`AsrEngine.kt`): 定义统一的 ASR 引擎接口
- **ASR 供应商管理** (`AsrVendor.kt`): 管理多种 ASR 服务供应商
- **文件式 ASR 引擎**:
  - `VolcFileAsrEngine.kt`: 火山引擎文件识别实现
  - `SiliconFlowFileAsrEngine.kt`: SiliconFlow 文件识别实现
  - `OpenAiFileAsrEngine.kt`: OpenAI Whisper 兼容实现
  - `ElevenLabsFileAsrEngine.kt`: ElevenLabs 语音识别实现
  - `DashscopeFileAsrEngine.kt`: 阿里云百炼语音识别实现
  - `GeminiFileAsrEngine.kt`: Google Gemini 语音理解（通过提示词转写）
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

### 使用指南

快速上手步骤见设置页“查看使用指南”按钮，或参考下方示意图：

![ASR Keyboard 使用教程](app/src/main/res/drawable-nodpi/instruct.png)

小技巧：在设置中开启“启用悬浮球快速切换输入法”，并授予悬浮窗权限；当当前输入法不是本应用时，悬浮球会显示，点击即可唤起系统输入法选择器。

## 配置说明

### ASR 供应商选择与配置

设置页支持按供应商切换配置，所选供应商仅显示对应参数：

- 供应商：`Volcano Engine`、`SiliconFlow`、`OpenAI`、`ElevenLabs`、`DashScope`、`Google Gemini`

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

#### OpenAI ASR 配置

- **API Key**: OpenAI API 密钥（以 `sk-` 开头，必填）
- **Endpoint**: 完整 API 地址，如 `https://api.openai.com/v1/audio/transcriptions`
- **Model**: 模型名称，支持 `gpt-4o-mini-transcribe`、`gpt-4o-transcribe` 或 `whisper-1`

注意：OpenAI 接口单次上传上限为 25MB，长段语音建议分次识别。

#### ElevenLabs ASR 配置

- **API Key**: ElevenLabs 控制台生成的 API 密钥（必填）
- **Model ID**: 语音识别模型 ID（默认值）
- 端点固定为：`https://api.elevenlabs.io/v1/speech-to-text`

#### 阿里云百炼（DashScope）ASR 配置

- **API Key**: 阿里云百炼控制台生成的 API Key（必填）
- **Model**: 模型名称，如 `qwen3-asr-flash`（默认值）

特殊说明：DashScope 采用临时上传 + 生成接口模式，需要通过 OSS 中转音频文件。

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
│   ├── AsrVendor.kt
│   ├── VolcFileAsrEngine.kt
│   ├── SiliconFlowFileAsrEngine.kt
│   ├── OpenAiFileAsrEngine.kt
│   ├── ElevenLabsFileAsrEngine.kt
│   ├── DashscopeFileAsrEngine.kt
│   └── LlmPostProcessor.kt
├── ui/                     # 用户界面
│   ├── SettingsActivity.kt
│   ├── PermissionActivity.kt
│   ├── FloatingImeSwitcherService.kt
│   └── ImePickerActivity.kt
├── store/                  # 数据存储
│   ├── Prefs.kt
│   └── PromptPreset.kt
└── App.kt                  # 应用入口
```

## 许可证

本项目采用 MIT 许可证，详见 LICENSE 文件。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进项目。在提交代码前，请确保：

1. 代码通过所有测试
2. 遵循项目代码规范
3. 添加必要的注释和文档
4. 更新相关的 README 文档
