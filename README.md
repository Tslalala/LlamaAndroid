# LlamaAndroid

在 Android 手机上本地运行大语言模型，基于 llama.cpp + JNI。支持加载 GGUF 模型、流式对话、性能统计，纯端侧推理，无需联网。

## 功能

- 导入和管理本地 GGUF 模型（支持 Qwen3 等模型）
- 流式输出，token-by-token 实时显示
- 多轮对话，增量 KV cache，多轮 prefill 时间仅略有增加
- 思考模式开关、上下文长度、采样参数（开发中）按模型独立配置
- 推理性能统计：TTFT、Decode Speed、Prompt Speed、Token 数、总耗时
- 黑夜模式

## 使用方法

1. 安装 APK 到 arm64-v8a 手机
2. 准备一个 GGUF 格式的模型文件（如 Qwen3.5-9B-Q4_K_M.gguf），放到手机 Download 目录或通过导入功能选择
3. 打开 App，主页自动扫描本地模型
4. 点击右下角加号导入模型文件
5. 点击模型卡片上的「进入对话」开始聊天
6. 在模型卡片的「设置」里可调整思考模式开关和最大上下文长度
7. 输入问题发送，回答下方会附带本次推理的性能指标

## 技术栈

- Android + Kotlin
- NDK 25 / CMake / C++17
- llama.cpp（GGUF 推理）
- JNI 桥接 native 与 Java

## 项目结构

```
app/src/main/
├── cpp/
│   ├── native-lib.cpp      # JNI 推理：加载模型/聊天/流式回调/性能计时
│   └── llama.h             # llama.cpp API 头文件
├── java/com/example/llamaandroid/
│   ├── HomeActivity.kt     # 主页：模型列表/导入/设置
│   ├── MainActivity.kt     # 对话页：流式聊天/性能显示
│   └── ModelManager.kt     # 模型扫描/导入/删除
└── res/
    ├── layout/             # activity_home.xml / activity_main.xml
    └── values/             # 主题/文案/黑夜模式
```

## 构建

```bash
gradlew assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`

