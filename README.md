# WhisperJNI

A JNI wrapper for [whisper.cpp](https://github.com/ggerganov/whisper.cpp), allows transcribing speech to text in Java 17+. Forked from [whisper-jni by GiviMAD](https://github.com/GiviMAD/whisper-jni).

> **About this fork (`whisper-jni-custom`)**
>
> Personal fork maintained at [juju351nicu/whisper-jni-custom](https://github.com/juju351nicu/whisper-jni-custom).
>
> | | |
> |---|---|
> | whisper.cpp | **v1.9.3** (`src/main/native/whisper` submodule) |
> | ggml | 0.20.2 |
> | Maven coordinate | `jp.clip:whisper-jni-custom` |
> | Version | `1.9.3-1` (`<whisper.cpp version>-<build number of this wrapper>`) |
> | Build JDK | Java 25 |
> | Gradle | 9.7.1 (wrapper) |
> | Produced bytecode | Java 17 - consumers still only need Java 17+ |
>
> The Java API was reorganised in `1.9.3-1` (see `WINDOWS-BUILD-1.9.3.md`): a new
> high-level `jp.clip.whisper` package, and the JNI bridge moved to `jp.clip.whisperjni`
> with camelCase field names. It is **not** source-compatible with the 1.8.3-based
> `io.github.jaffe2718` releases.
> Note that since whisper.cpp v1.9.3 the native build additionally produces a
> `parakeet` shared library (a second ASR model family shipped inside whisper.cpp).
> It is bundled with the natives and loaded by `NativeLibraryLoader`, but no Java API is
> exposed for it yet.

## Platform support

This library aims to support Windows x64, and AMD x64 / arm64 of Mac and Linux.

Default CPU binaries for those platforms are included in the distributed jar. You can utilize your GPU to achieve much faster transcription results by loading custom-built Vulkan natives (see [examples](#examples)).
> To use the Mac Vulkan natives, install [MoltenVK](https://github.com/KhronosGroup/MoltenVK). Note that the default natives for Mac use Metal and can be quite fast already. Vulkan natives make more of a difference on Linux / Windows.

## Installation

This fork is **not published to Maven Central**. Build it locally and install it into
your local Maven repository:

```powershell
# Windows
.\scripts\build-windows.ps1                       # ネイティブをビルド → whisperjni-build\
.\gradlew.bat installNatives publishToMavenLocal   # DLL を src/main/resources/windows-x64 へコピーして jar に同梱し、~/.m2 へ
```

`installNatives` は実行中の OS に合わせて `src/main/resources/<os>-<arch>/` へコピーします。
jar には同梱ファイルの一覧 `natives.list` も入り、Spring Boot の実行可能 jar（jar の中の jar）からでも
ネイティブを取り出せます。

Then depend on it:

### Maven

```xml
<dependency>
    <groupId>jp.clip</groupId>
    <artifactId>whisper-jni-custom</artifactId>
    <version>1.9.3-1</version>
</dependency>
```

### Gradle

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'jp.clip:whisper-jni-custom:1.9.3-1'
}
```

The jar bundles the native libraries for the platforms whose
`src/main/resources/<os>-<arch>/` folders were populated at build time.

## License

Apache License, Version 2.0 - see [LICENSE](LICENSE).

This project is a derivative work of [GiviMAD/whisper-jni](https://github.com/GiviMAD/whisper-jni)
and [Jaffe2718/whisper-jni](https://github.com/Jaffe2718/whisper-jni), both Apache-2.0.
The bundled native libraries are built from [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
which is MIT licensed. See [NOTICE](NOTICE) for the full attribution and the list of
modifications.

## Usage — `jp.clip.whisper` (recommended)

高水準 API 層です。ネイティブのロード、コンテキストの生存管理、パラメータの組み立てを
すべて隠蔽しているので、利用側はこのパッケージだけを見れば済みます。

```java
import java.nio.file.Path;
import jp.clip.whisper.SamplingStrategy;
import jp.clip.whisper.Segment;
import jp.clip.whisper.TranscriptionResult;
import jp.clip.whisper.WhisperConfig;
import jp.clip.whisper.WhisperEngine;

WhisperConfig config = WhisperConfig.builder()
        .model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
        .language("ja")                                    // 既定は "en"
        .threads(Runtime.getRuntime().availableProcessors())
        .vadEnabled(true)                                  // 無音区間を除去して高速化
        .build();

try (WhisperEngine engine = WhisperEngine.open(config))
{
    TranscriptionResult result = engine.transcribe(Path.of("input.wav"));

    System.out.println(result.text());
    System.out.printf("%d ms / RTF %.2f%n", result.elapsedMs(), result.realTimeFactor());

    for (Segment segment : result.segments())
    {
        System.out.printf("[%d-%d] %s%n", segment.startMs(), segment.endMs(), segment.text());
    }
}
```

### クラス構成

| クラス | 役割 |
|---|---|
| `WhisperEngine` | 入口。`AutoCloseable`。`open` / `transcribe` / `close` |
| `WhisperConfig` | 設定。`WhisperConfig.builder()` から組み立てる |
| `TranscriptionResult` | 結果全体。`text()` `segments()` `elapsedMs()` `realTimeFactor()` |
| `Segment` | 1 区間。`startMs()` `endMs()` `text()` `durationMs()` |
| `SamplingStrategy` | `GREEDY` / `BEAM_SEARCH` |
| `WhisperException` | 失敗時の非チェック例外 |

### 補足

- 時刻は**ミリ秒**です（whisper.cpp のセンチ秒からこの層で変換しています）。
- `transcribe(Path)` は 16kHz モノラル 16bit PCM 以外も、Java の標準変換で対応できる
  範囲であれば自動変換します。自前でデコードしている場合は `transcribe(float[])` を
  使ってください（16kHz モノラル、-1.0f〜1.0f 正規化）。
- `vad(true)` を指定すると、VAD モデルは jar 同梱のものが一時ファイルへ自動展開されます。
  自分で用意したモデルを使う場合は `vadModel(path)` を指定してください。
- GPU 版（Vulkan / CUDA）のネイティブを使う場合は `nativeLibraryDirectory(path)` を指定します。
- **`WhisperEngine` はスレッド安全ではありません。** 並列処理したい場合はスレッドごとに
  インスタンスを生成してください。

---

## Low-level API — `jp.clip.whisperjni`

whisper.cpp の関数に 1 対 1 で対応する薄い層です。上の高水準 API で足りない機能
（state を分離した実行、トークン単位の情報など）が必要な場合に使います。
| クラス | 役割 |
|---|---|
| `WhisperJNI` | native 宣言と、その薄いラッパー。各メソッドの Javadoc に対応する whisper.cpp 関数名を併記 |
| `WhisperContext` / `WhisperState` / `WhisperGrammar` | ネイティブオブジェクトのハンドル（`NativeHandle` のサブクラス、`AutoCloseable`） |
| `WhisperContextParams` / `WhisperTranscriptionParams` | C 構造体（`whisper_context_params` / `whisper_full_params`）を写した public フィールドの入れ物 |
| `WhisperSamplingStrategy` | `GREEDY` / `BEAM_SEARCH`（whisper.cpp の enum に対応） |
| `WhisperToken` | トークン単位の情報（`whisper_token_data` + テキスト） |
| `NativeLibraryLoader` | ディレクトリ内のネイティブを依存順に `System.load` する。Vulkan ランタイムの検出も |
| `BundledResources` | jar 同梱のネイティブ / VAD モデルをディスクへ取り出す |
| `Platform` | OS / アーキテクチャの判定と `<os>-<arch>` ディレクトリ名 |
| `GbnfGrammarValidator` | GBNF 文法の純 Java 事前検証 |

### Examples

```java
WhisperJNI.loadBundledLibraries(); // jar 同梱の CPU 版ネイティブを読み込む
WhisperJNI whisper = new WhisperJNI();
float[] samples = AudioFileReader.readSamples(Path.of("jfk.wav"));
try(WhisperContext ctx = whisper.createContext(Path.of("ggml-tiny.bin")))
{
    WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
    int result = whisper.transcribe(ctx, params, samples, samples.length);
    if(result != 0)
    {
        throw new IllegalStateException("Transcription failed with code " + result);
    }
    String text = whisper.segmentText(ctx, 0);
    // " And so my fellow Americans ask not what your country can do for you, ask what you can do for your country."
}
```

### Using the Vulkan Natives

Vulkan 版のネイティブは `scripts/` のビルドスクリプトに `-DGGML_VULKAN=ON` を渡して自分でビルドします。
読み込みは `NativeLibraryLoader` で行います:

```java
Path vulkanNatives = Path.of("path", "to", "whisperjni-vulkan-natives");
if(NativeLibraryLoader.loadVulkanRuntimeIfPresent())
{
    logger.info("Vulkan ランタイムを読み込みました");
}
NativeLibraryLoader.load(logger, vulkanNatives);
```

jar の中にネイティブを同梱している場合は `BundledResources.extractDirectory(logger, Platform.current().nativeLibraryDirectoryName())`
で一時ディレクトリへ取り出してから `NativeLibraryLoader.load` してください（`WhisperJNI.loadBundledLibraries()` がやっているのと同じ手順です）。

## Grammar

This wonderful functionality added in whisper.cpp v1.5.0 was integrated into the wrapper.
It makes use of the grammar parser implementation provided among the whisper.cpp examples,
so you can use the [gbnf grammar](https://github.com/ggerganov/whisper.cpp/blob/master/grammars/) to improve the transcriptions results.
```java
try(WhisperGrammar grammar = whisper.parseGrammar(Path.of("my_grammar.gbnf")))
{
    WhisperTranscriptionParams params = new WhisperTranscriptionParams();
    params.grammar = grammar;
    params.grammarPenalty = 100f;
    int result = whisper.transcribe(ctx, params, samples, samples.length);
}
```

## Building / Testing

Prerequisites: **JDK 25** (`JAVA_HOME` must point at it), CMake 3.21+, and a C/C++ toolchain
(MSVC on Windows, clang on macOS, gcc on Linux). Gradle itself is provided by the wrapper.

1. Submodule whisper.cpp by running `git submodule update --init`.
2. Download the test models using the scripts `scripts/download-test-model` and `scripts/download-vad-model`. Then move `silero-v6.2.0.bin` to `src/main/resources`!
3. Run the appropriate build script for your platform (`scripts/build-linux.sh`, `scripts/build-mac.sh` or `scripts/build-windows.ps1`). It will build the library to `/whisperjni-build`, which the JUnit test file will load from.
> Although this shouldn't cause any problems, if your machine can use Vulkan, the test script will consider the natives in `/whisperjni-build` to be Vulkan natives for CI/CD reasons.
> You can alternatively move the natives from `/whisperjni-build` to its respective subfolder in `src/main/resources` and delete the build directory.
4. `./gradlew test`

### Lombok

`jp.clip.whisper.WhisperConfig` は [Lombok](https://projectlombok.org/)（`@Value` / `@Builder`）で
ビルダーとアクセサを生成しています。Lombok はコンパイル時のみの依存で、生成された jar を使う側には
一切影響しません（`compileOnly`）。Gradle ビルドは `io.freefair.lombok` プラグインが自動設定するので
追加作業は不要ですが、**IDE でソースを開く場合は IDE 側の Lombok 対応が必要**です
（IntelliJ IDEA: 同梱プラグインを有効化 / Eclipse・Spring Tools: `lombok.jar` のインストーラで
`eclipse.ini` にエージェントを登録）。

## Extending the Native API

If you want to add any missing whisper.cpp functionality, you need to:

- Add the native method description in `WhisperJNI.java`.
- Run the `generateHeaders` gradle task to regenerate the `src/main/native/jni/jp_clip_whisperjni_WhisperJNI.h` header file.
- Add the native method implementation in `src/main/native/jni/jp_clip_whisperjni_WhisperJNI.cpp`.
- Add a new test for it in `WhisperJNITest.java`.
