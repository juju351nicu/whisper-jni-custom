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
> The public Java API is unchanged from the 1.8.3-based `1.0.1` release.
> Note that since whisper.cpp v1.9.3 the native build additionally produces a
> `parakeet` shared library (a second ASR model family shipped inside whisper.cpp).
> It is bundled with the natives and loaded by `LibraryUtils`, but no Java API is
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
.\scripts\build-windows.ps1
New-Item -ItemType Directory -Force .\src\main\resources\windows-x64 | Out-Null
Copy-Item .\whisperjni-build\*.dll .\src\main\resources\windows-x64\ -Force
.\gradlew.bat publishToMavenLocal
```

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

## Examples

```java
var whisper = new WhisperJNI();
whisper.loadLibrary(); // loads the built-in CPU natives
float[] samples = readJFKFileSamples();
var ctx = whisper.init(Path.of('ggml-tiny.bin'));
var params = new WhisperFullParams();
int result = whisper.full(ctx, params, samples, samples.length);
if(result != 0) {
    throw new RuntimeException("Transcription failed with code " + result);
}
int numSegments = whisper.fullNSegments(ctx);
assertEquals(1, numSegments);
String text = whisper.fullGetSegmentText(ctx,0);
assertEquals(" And so my fellow Americans ask not what your country can do for you ask what you can do for your country.", text);
ctx.close(); // free native memory, should be called when we don't need the context anymore
```

### Using the Vulkan Natives

You can find the Vulkan natives in [releases](https://github.com/jaffe2718/whisper-jni/releases). You'll need to download and load them using `LibraryUtils`:

```java
Path vulkanNatives = Path.of("path", "to", "whisperjni-vulkan-natives");
if(LibraryUtils.findAndLoadVulkanRuntime()) {
    logger.info("Found the Vulkan runtime! Loading the Vulkan natives");
    LibraryUtils.loadLibrary(logger, vulkanNatives);
} else {
    logger.info("Loading standard natives");
    LibraryUtils.loadLibrary(logger, vulkanNatives);
}
```

If you depend on whisper-jni and need to extract the Vulkan natives from a folder within the JAR, `LibraryUtils` has helper methods for this to extract and load them to/from a temporary folder. If you need to know which natives to load based on the machine's OS / architecture, there's methods for that too. See the `LibraryUtils` Javadoc!

## Grammar

This wonderful functionality added in whisper.cpp v1.5.0 was integrated into the wrapper.
It makes use of the grammar parser implementation provided among the whisper.cpp examples,
so you can use the [gbnf grammar](https://github.com/ggerganov/whisper.cpp/blob/master/grammars/) to improve the transcriptions results.
```java
try (WhisperGrammar grammar = whisper.parseGrammar(Paths.of("/my_grammar.gbnf"))) {
    var params = new WhisperFullParams();
    params.grammar = grammar;
    params.grammarPenalty = 100f;
    ...
    int result = whisper.full(ctx, params, samples, samples.length);
    ...
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

## Extending the Native API

If you want to add any missing whisper.cpp functionality, you need to:

- Add the native method description in `WhisperJNI.java`.
- Run the `generateHeaders` gradle task to regenerate the `src/main/native/jni/jp_clip_whisperjni_WhisperJNI.h` header file.
- Add the native method implementation in `src/main/native/jni/jp_clip_whisperjni_WhisperJNI.cpp`.
- Add a new test for it in `WhisperJNITest.java`.
