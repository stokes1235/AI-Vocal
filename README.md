# MyApplication1（AI Vocal）

Android 应用源码。预训练 TTS 模型体积较大，**不包含在本仓库中**，克隆后请按下方步骤下载并解压到 `app/src/main/assets/`。

## 环境

- Android Studio（建议最新稳定版）
- JDK 与 `compileSdk` 以 `app/build.gradle.kts` 为准

## 克隆后：下载 TTS 模型

模型来自 [sherpa-onnx 文档 — VITS 预训练模型](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html) 所列发布包。

| 用途 | 下载地址 |
|------|----------|
| 中文多说话人（AISHELL3，174 speakers） | [vits-icefall-zh-aishell3.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2) |
| 英文多说话人（VCTK，109 speakers） | [vits-vctk.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-vctk.tar.bz2) |

将两个压缩包解压到 **`app/src/main/assets/`** 下，使目录结构为：

```text
app/src/main/assets/
  vits-icefall-zh-aishell3/   # 内含 model.onnx、lexicon.txt、tokens.txt、*.fst、rule.far 等
  vits-vctk/                 # 内含 vits-vctk.onnx（或 int8）、lexicon.txt、tokens.txt 等
```

### Windows（PowerShell）示例

在仓库根目录执行（请把 `下载路径` 换成你保存 `.tar.bz2` 的实际路径）：

```powershell
cd app\src\main\assets
tar -xf "下载路径\vits-icefall-zh-aishell3.tar.bz2"
tar -xf "下载路径\vits-vctk.tar.bz2"
cd ..\..\..\..
```

### Linux / macOS 示例

```bash
cd app/src/main/assets
tar -xf /path/to/vits-icefall-zh-aishell3.tar.bz2
tar -xf /path/to/vits-vctk.tar.bz2
cd ../../..
```

解压完成后用 Android Studio 打开工程并构建即可。

## 许可

第三方模型与数据集的说明见仓库根目录 [LICENSE](LICENSE)。
