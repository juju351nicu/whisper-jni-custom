# whisper.cpp 1.8.3 → 1.9.3 対応 / Windows 実行手順

このファイルは whisper.cpp 1.8.3 → 1.9.3 対応の作業記録と、今後のロードマップです。
リポジトリに含めているので、別のマシンで clone しても付いてきます。
（ロードマップ Step 3 のリファクタリング時に `docs/` へ移動しても構いません）

---

## 1. 何を変えたか

| 対象 | 変更内容 |
|---|---|
| `src/main/native/whisper`（submodule） | whisper.cpp **v1.8.3 → v1.9.3**（`371b5a7561823ab2bb32142d2751e35e7534727b`）。ggml も 0.9.5 → 0.20.2 |
| `src/main/java/.../LibraryUtils.java` | ネイティブのロード順に `parakeet` を追加（1.9.3 から `parakeet` 共有ライブラリが増えたため） |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **8.14.3 → 9.7.1**。配布元も aliyun ミラー → 公式 `services.gradle.org` |
| `build.gradle` | Gradle 9 / Java 25 対応に移行（詳細は下記） |
| `gradle.properties` | `version=1.0.1` → **`1.9.3-1`**、`junitVersion=5.14.4` を追加 |
| `settings.gradle`（新規） | `rootProject.name = 'whisper-jni-custom'` を明示 |
| `build_windows.ps1` / `build_linux.sh` / `build_mac.sh` / `build_cuda.sh` | `-DWHISPER_BUILD_IS_DEV=OFF` を追加（バージョン文字列が `1.9.3-dev` ではなく `1.9.3` になる） |
| `.github/workflows/main.yml` | `java-version: 24 → 25`、whisper.cpp リリース資産の URL を `v1.8.3 → v1.9.2`、VAD モデルの DL URL 修正 |
| `README.md` | fork 情報テーブルとビルド前提条件を追記 |
| （git 設定） | `core.autocrlf=true` をローカル設定。全ファイルが改行差分だけで `modified` に見えていた状態を解消 |

**変更していないもの**

- `CMakeLists.txt` — 1.9.3 でも無改修でビルド・リンク可能（後述の通り実測確認）
- `src/main/native/*.cpp` / `*.h` — whisper.h の 1.8.3→1.9.3 差分は **追加のみ**（既存関数の削除・シグネチャ変更なし）
- `src/main/java` の公開 API — `LibraryUtils` の内部ロード順以外は無変更
- `src/test/java` — 無変更
- ディレクトリ構成 / パッケージ名 / `.github` の位置

### build.gradle の Gradle 9 移行点

1. `sourceCompatibility` / `targetCompatibility` をプロジェクト直下から `java { }` ブロック内へ移動（`JavaPluginConvention` 廃止対応）
2. `task xxx(type:)` → `tasks.register('xxx', Type)`（遅延生成）
3. `destinationDir` → `destinationDirectory.set(...)`
4. `options.release = 17` を追加。**JDK 25 でビルドしても生成バイトコードは Java 17**
5. `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` を追加
   — Gradle 9 はテストフレームワークの実装依存を自動注入しなくなったため必須
6. JUnit を BOM 経由（`org.junit:junit-bom:5.14.4`）に変更
7. `jreleaser` プラグイン 1.17.0 → 1.26.0
8. `test` タスクに `--enable-native-access=ALL-UNNAMED` を追加（JDK 22 以上のときだけ付与するようガード済み）

---

## 2. このセッションで実測検証したこと / していないこと

### 検証済み（Linux x86_64 / クラウド上）

- whisper.cpp 1.9.3 + 既存 `CMakeLists.txt` で **CMake configure / build がエラー 0 件**
- `cmake --install` の出力レイアウトが 1.8.3 と同一（`prefix/libwhisper-jni.so` + `prefix/lib/*.so`）
- 修正後の `build_linux.sh` を通しで実行 → `whisperjni-build/` に 6 ライブラリ生成
  （`libwhisper-jni.so` `libwhisper.so` `libggml.so` `libggml-base.so` `libggml-cpu.so` **`libparakeet.so`**）
- `libwhisper.so` のバージョン文字列が `1.9.3`（`-dev` が付かない）
- JNI 実行スモークテスト **16 項目すべて成功**
  - `getSystemInfo` / `init` / `initNoState` / `initFromInputStream` / `isMultilingual` / `initState`
  - `full`（GREEDY / BEAM_SEARCH）/ `fullWithState` / トークン取得
  - **VAD**（`params.vad=true`、silero v6.2.0 で jfk.wav から 3 音声区間を検出）/ `vadState`
  - grammar パース（`assistant` / `colors` / `chess` .gbnf）
  - 例外系（`IndexOutOfBoundsException` / closed pointer）
  - C++ → Java(slf4j) ロガーブリッジ
- `LibraryUtils` のロード順が `ggml-base → ggml-cpu → ggml → parakeet → whisper → whisper-jni` になり、未処理ファイル警告が消えた
- **Gradle 9.7.1 + JDK 25.0.4.1 で `tasks` / `jar` / `sourcesJar` / `javadocJar` / `generatePomFileForMavenPublication` が BUILD SUCCESSFUL**
  - `--warning-mode all` で **deprecation 警告 0 件**
  - 生成クラスファイルの major version = **61（Java 17）**
  - 生成 POM = `jp.clip:whisper-jni-custom:1.9.3-1`（Step 1 で groupId 変更後）

### 検証済み（Windows 11 / MSVC）  2026-09-03 完了

- Visual Studio 18 Community (18.4.2) + MSVC 19.50.35728.0 (v14.50) + Windows 11 SDK 10.0.26100
- CMake 4.4.3 / generator `Visual Studio 18 2026` / JNI ヘッダは Corretto JDK 25.0.2
- `build_windows.ps1` でネイティブビルド成功。生成 DLL 6 個:
  `whisper-jni.dll` `whisper.dll` `ggml.dll` `ggml-base.dll` `ggml-cpu.dll` **`parakeet.dll`**
- Windows 版は `/arch:AVX2` でビルドされます（`GGML_AVX2;GGML_FMA;GGML_F16C;GGML_BMI2`）。
  つまり **AVX2 非対応の古い CPU では動きません**。上流からの既存挙動です。
- `gradlew test` → **20 tests, failures=0, errors=0**（Gradle 9.7.1 + JDK 25 Corretto）

BEAM_SEARCH の期待文字列だけ 1.9.3 で変わったため更新しました（`Americans` の後にカンマが入る）。
GREEDY はタイムスタンプまで含めて 1.8.3 と完全一致です。

### 未検証

- `jreleaser` 1.26.0 での Maven Central 公開（`jreleaserDeploy`）
- Vulkan / CUDA ネイティブのビルド（CI 側のみ）
- macOS / Linux での 1.9.3 ビルド（クラウド Linux では検証済み、CI 未実行）
---

## 3. Windows での手順

### 前提

- **JDK 25**（Temurin 推奨）をインストールし `JAVA_HOME` を設定
- Visual Studio 2022 Build Tools（C++ ワークロード）+ CMake 3.21 以上
- `cmake` が MSVC を見つけられるよう、**「x64 Native Tools Command Prompt for VS 2022」** から PowerShell を起動するのが確実

### 手順

```powershell
cd C:\pr-work\whisper-jni-custom

# --- 0. 状態確認 -------------------------------------------------
java -version                 # 25 であること
git status
git submodule status          # 371b5a7561823ab2bb32142d2751e35e7534727b (v1.9.3)

# --- 1. テスト用モデル取得（初回のみ） ---------------------------
.\download-test-model.ps1     # → .\ggml-tiny.bin
.\download-vad-model.ps1      # → .\ggml-silero-v6.2.0.bin
Move-Item .\ggml-silero-v6.2.0.bin .\src\main\resources\ -Force

# --- 2. ネイティブ（DLL）ビルド ---------------------------------
.\build_windows.ps1
Get-ChildItem .\whisperjni-build

#   期待される DLL:
#     whisper-jni.dll / whisper.dll / ggml.dll / ggml-base.dll / ggml-cpu.dll
#     parakeet.dll  ← 1.9.3 で新規

# --- 3. テスト実行 ----------------------------------------------
.\gradlew.bat test
```

初回は Gradle 9.7.1 の配布（約 145MB）と依存ライブラリのダウンロードが走ります。

### 参考: 1 コマンドで確認したいとき

```powershell
.\gradlew.bat clean build --warning-mode all
```

---

## 4. transcribe-shell への影響と切り替え

### 影響しない理由

- `C:\pr-work\transcribe-shell` には一切触れていません（このセッションからは参照もできません）
- `~/.m2` にも書き込んでいません
- バージョンを `1.0.1` → `1.9.3-1` に変えたので、
  `publishToMavenLocal` しても **既存の 1.0.1（1.8.3 ベース）JAR を上書きしません**

### 現在 transcribe-shell が使っている座標の確認

```powershell
Get-ChildItem "$env:USERPROFILE\.m2\repository\io\github\jaffe2718" -Recurse -Filter *.jar |
    Select-Object FullName
```

`whisper-jni-custom\1.0.1\...` が出れば artifactId は `whisper-jni-custom` です
（Gradle は `settings.gradle` が無いとフォルダ名を artifactId にするため。今回そこを明示的に固定しました）。
もし `whisper-jni\1.0.1\...` だった場合は `settings.gradle` の
`rootProject.name` を `'whisper-jni'` に直してください。

### 1.9.3 版を transcribe-shell で使う手順

```powershell
cd C:\pr-work\whisper-jni-custom

# DLL を resources に配置（.gitignore 対象なので git は汚れません）
New-Item -ItemType Directory -Force .\src\main\resources\windows-x64 | Out-Null
Copy-Item .\whisperjni-build\*.dll .\src\main\resources\windows-x64\ -Force

.\gradlew.bat publishToMavenLocal
```

そのうえで transcribe-shell の `pom.xml` を書き換えます。

```xml
<dependency>
    <groupId>jp.clip</groupId>
    <artifactId>whisper-jni-custom</artifactId>
    <version>1.9.3-1</version>
</dependency>
```

**戻したいときは pom.xml の version を `1.0.1` に戻すだけ**です。
1.0.1 の JAR は `~/.m2` にそのまま残っています。

---

## 5. 注意点・トラブルシュート

### コミット前に `git submodule update` を実行しないでください

submodule の 1.9.3 へのポインタ変更はまだ**作業ツリー上だけ**です。
`git submodule update` は index に記録された値（= まだ v1.8.3）に巻き戻すため、
コミット前に実行すると 1.8.3 に戻ります。

### `Unsupported class file major version 69`

`JAVA_HOME` が JDK 25 を指しているか、`gradle-wrapper.properties` が
9.7.1 になっているかを確認してください。Gradle が Java 25 をサポートするのは 9.1.0 以降です。

### `Plugin [id: 'org.jreleaser', version: '1.26.0'] was not found`

Maven Central への公開でしか使わないプラグインです。
解決できない場合は `build.gradle` 冒頭の

```groovy
    id 'org.jreleaser' version '1.26.0'
```

と `jreleaser { ... }` ブロック、`deployMaven` タスクの
`finalizedBy 'jreleaserDeploy'` をコメントアウトすれば
`build` / `test` / `publishToMavenLocal` は問題なく動きます。
（実際にこの構成でオフライン検証を通してあります）

### `Could not initialize native services` / `Could not extract native JNI library`

Gradle 自身のキャッシュ破損です。プロジェクトのコードとは無関係です。
`%USERPROFILE%\.gradle` が壊れると発生し、`.gradle\native` や `.gradle\wrapper\dists` を
消すだけでは直らないことがあります。確実な回避策は **Gradle ホームを別の場所にする**ことです。

```powershell
# このウィンドウだけで試す
$env:GRADLE_USER_HOME = "C:\pr-work\.gradle-home"

# 恒久化する（新しいウィンドウでも有効になる）
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", "C:\pr-work\.gradle-home", "User")
```

2026-09-03 に実際にこれで復旧しました。**この環境変数を設定していない新しい PowerShell では
再発します。**別マシンへ移る際も、Gradle ホームは `C:\pr-work\.gradle-home` のように
プロジェクト近くに置いておくと、片付けも楽で事故も減ります。
### `UnsatisfiedLinkError`

`whisperjni-build`（または `src/main/resources/windows-x64`）に
**`parakeet.dll` が入っているか**を確認してください。1.9.3 で増えたライブラリです。

### CMake が MSVC を見つけられない

「x64 Native Tools Command Prompt for VS 2022」から起動し直してください。

---

# 今後のロードマップ

## 決定事項

| 項目 | 決定 |
|---|---|
| Java パッケージ | `jp.clip.whisperjni`（JNI bridge 層）+ `jp.clip.whisper`（高水準 API 層）の **2層構成** |
| groupId | `jp.clip` |
| 本家（Jaffe2718 / GiviMAD）への追従 | **捨てる**。完全に自分のライブラリとして育てる |
| 高速化の方針 | まず **(c) whisper.cpp 側で詰める**（量子化モデル・VAD・スレッド・GPU バックエンド）。CTranslate2 束縛や Python プロセス起動は最後の手段 |
| `dev` ブランチ | 1.9.3 が本番稼働したら **削除**（コミットは失われない。後述） |
| Maven 化 | **やらない**。Gradle を維持 |

---

## Step 0 — Windows でテストを緑にしてコミット（最優先）

これが以降すべての安全網です。テストが緑でない状態でリネームやリファクタリングを始めると、
失敗したときに「1.9.3 が原因か / 自分の変更が原因か」の切り分けができなくなります。

手順は本ファイル前半の「3. Windows での手順」を参照。テストが通ったら：

```powershell
cd C:\pr-work\whisper-jni-custom

git add -A
git restore --staged WINDOWS-BUILD-1.9.3.md     # この作業メモは git に入れない
git status --short --ignore-submodules=all       # 意図した10ファイル + settings.gradle だけか確認

git commit -m "whisper.cpp 1.8.3 -> 1.9.3, Java 25 / Gradle 9.7.1 support"
git push
```

**完了条件**: `gradlew test` が全件パス、push 済み。

---

## Step 1 — ① Java 25 対応の確定  ✅ 2026-09-03 完了

コード変更はほぼ不要です。決めごとと、ついで作業をまとめて片付けます。

### 1-1. バイトコードのターゲットは 17 のまま維持を推奨

`build.gradle` の `options.release = 17` はそのままにします。理由：

- Java 17 バイトコードは **JDK 17〜25 のどれでも動く**ので、ご希望の「Java 21〜25 対応」はこれで達成済みです
- `record` / `sealed` / `switch` 式 / `var` / パターンマッチング（instanceof）は **すべて Java 17 で使える**ので、
  リファクタリング（Step 3）で言語機能に困ることはありません
- ターゲットを上げるのは「JNI を FFM API (Project Panama) に置き換える」ときです。
  それをやるなら 22 以上が必要になります。今回は JNI 継続なので不要

### 1-2. ライセンス表記の不整合を直す（必須）

`LICENSE` は Apache-2.0 なのに `build.gradle` の POM は MIT と書いています。

```groovy
// build.gradle の licenses ブロック
name = 'Apache License, Version 2.0'
url  = 'https://www.apache.org/licenses/LICENSE-2.0'
```

本家追従を捨てても、**コードの由来が Apache-2.0 なので Apache-2.0 を維持する義務があります**
（ライセンス本文の保持と、変更した旨の明示）。`NOTICE` ファイルを追加して
「GiviMAD/whisper-jni および Jaffe2718/whisper-jni から派生」と書いておくと綺麗です。
POM の `developers` / `scm` / `url` も自分のものに直しておきます。

### 1-3. Gradle wrapper 本体を 9.7.1 のものに再生成

今は `distributionUrl` だけを書き換えた状態です。`gradlew` / `gradlew.bat` /
`gradle-wrapper.jar` も 9.7.1 付属のものに揃えておきます。

```powershell
.\gradlew.bat wrapper --gradle-version 9.7.1
```

### 1-4. `.gitattributes` に改行ルールを追記（改行問題の再発防止）

今回「全ファイルが改行差分だけで modified」になっていた原因への恒久対策です。

```
*.sh   text eol=lf
*.ps1  text eol=crlf
*.cmd  text eol=crlf
```

**実施結果（2026-09-03）**

| 項目 | 内容 |
|---|---|
| バイトコード | Java 17 のまま維持（`options.release = 17`）。変更なし |
| ライセンス表記 | POM を MIT → **Apache License, Version 2.0** に修正。上流2つ（GiviMAD / Jaffe2718）とも Apache-2.0 であることを確認済み |
| `NOTICE`（新規） | 由来・変更点・同梱ネイティブ（whisper.cpp は **MIT**）・同梱 VAD モデルの出所を明記。Apache-2.0 §4(b)(d) 対応 |
| jar 同梱 | `META-INF/LICENSE` と `META-INF/NOTICE` を jar に含めるようにした（実際に含まれることを確認済み） |
| POM メタデータ | `url` / `developers` / `scm` を自分のリポジトリへ。開発者はメールアドレスなしで GitHub ハンドルのみ |
| **groupId** | `io.github.jaffe2718` → **`jp.clip`**（Step 2 から前倒し。理由は下記） |
| Gradle wrapper | `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` を 9.7.1 付属のものに再生成 |
| wrapper 検証 | `distributionSha256Sum` を追加。zip が壊れていたら検証エラーで落ちる（今回ハマった `Could not initialize native services` の予防）|
| wrapper リトライ | `retries=3` に設定（既定は 0） |
| `.gitattributes` | 改行ルールは Step 0 で実施済み |

**groupId を前倒しした理由**: `io.github.jaffe2718` は所有していない GitHub アカウントの
名前空間です。ライセンスと `scm` を自分のものに直すのに groupId だけ他人のままでは矛盾し、
Maven Central 公開時にも名前空間の所有証明ができません。また今のうちに変えておけば、
transcribe-shell の `pom.xml` 書き換えが 1 回で済みます（Step 2 の package リネームでは
座標は変わりません）。

検証: Gradle 9.7.1 + JDK 25 で `jar` / `generatePomFileForMavenPublication` が BUILD SUCCESSFUL、
deprecation 警告 0 件、生成 POM とjar 内 `META-INF` の内容を確認済み。

---

## Step 2 — ⑤ 完全リネーム + 2層化  ✅ 2026-09-03 完了

### 2-1. JNI bridge 層のリネーム `io.github.jaffe2718.whisperjni` → `jp.clip.whisperjni`

**必ずこの順番で**やってください。順番を守れば機械的な作業になります。

1. **Java 側をリネーム**
   - `src/main/java/io/github/jaffe2718/whisperjni/*.java`（11ファイル）
     → `src/main/java/jp/clip/whisperjni/*.java`、`package` 宣言も変更
   - `src/test/java/io/github/jaffe2718/whisperjni/WhisperJNITest.java` も同様
   - IDE のパッケージリネーム機能を使うのが確実

2. **JNI ヘッダを再生成**

   ```powershell
   .\gradlew.bat generateHeaders
   ```

   `src/main/native/jp_clip_whisperjni_WhisperJNI.h` が新規生成されます。
   旧 `io_github_jaffe2718_whisperjni_WhisperJNI.h` は削除。

3. **C++ 側をリネーム**
   - ファイル名: `io_github_jaffe2718_whisperjni_WhisperJNI.cpp`
     → `jp_clip_whisperjni_WhisperJNI.cpp`
   - `#include` を新ヘッダ名へ
   - 全シンボル: `Java_io_github_jaffe2718_whisperjni_` → `Java_jp_clip_whisperjni_`（60箇所以上）
   - **★ここが一番忘れやすい★** 507行目付近の文字列リテラル

     ```cpp
     jclass cls = env->FindClass("io/github/jaffe2718/whisperjni/TokenData");
     ```

     これは **IDE のリネーム機能では検出されません**。手で
     `"jp/clip/whisperjni/TokenData"` に直します。
     直し忘れると「ビルドは通るのに `getTokens()` を呼んだ瞬間に落ちる」という
     一番デバッグしづらい壊れ方をします。

4. **ビルド設定を追随**
   - `CMakeLists.txt` の `add_library(whisper-jni SHARED src/main/native/io_github_...cpp)` を新ファイル名へ
   - ~~`build.gradle` の `group = 'io.github.jaffe2718'` → `'jp.clip'`~~ → **Step 1 で実施済み**
   - `README.md` の座標記述

5. **検証**

   ```powershell
   .\build_windows.ps1
   # VS の Developer Command Prompt で、旧シンボルが残っていないか確認
   dumpbin /exports .\whisperjni-build\whisper-jni.dll | Select-String "Java_io_github"
   #   → 何も出なければ OK
   dumpbin /exports .\whisperjni-build\whisper-jni.dll | Select-String "Java_jp_clip" | Measure-Object
   #   → Java 側の native メソッド数と一致するか

   .\gradlew.bat test
   ```

   さらに強い安全網として、`build.gradle` の `test` タスクに一時的に
   `jvmArgs '-Xcheck:jni'` を足すと、JNI の不正な使い方をその場で検出できます。
   リネーム作業中はこれを付けたままテストするのがおすすめです。

### 2-2. 高水準 API 層 `jp.clip.whisper` を追加

`transcribe-shell` から見えるのはこの層だけになります。pointer / native state /
ライブラリロードの都合を全部ここで隠します。

```
src/main/java/jp/clip/
├── whisperjni/                 ← 低レイヤ JNI bridge（既存クラスをリネームしたもの）
│   ├── WhisperJNI.java
│   ├── LibraryUtils.java
│   └── ...
└── whisper/                    ← 新規：自作の公開 API
    ├── WhisperEngine.java          AutoCloseable。ライブラリロード + context/state を内包
    ├── WhisperConfig.java          モデルパス・言語・スレッド数・VAD有無・sampling戦略・grammar
    ├── TranscriptionResult.java    全文 + List<Segment>
    ├── Segment.java                startMs / endMs / text / tokens（record）
    └── WhisperException.java
```

使い方のイメージ：

```java
var config = WhisperConfig.builder()
        .model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
        .language("ja")
        .threads(Runtime.getRuntime().availableProcessors())
        .vad(true)
        .build();

try (var engine = WhisperEngine.open(config)) {
    TranscriptionResult result = engine.transcribe(Path.of("input.wav"));
    System.out.println(result.text());
}
```

この層があると **Step 5 の高速化を transcribe-shell に一切影響させずに差し替えられます**。
量子化モデルへの変更、VAD の有効化、GPU natives への切り替えは、すべて
`WhisperConfig` と `WhisperEngine` の内部で完結します。

### 2-3. 座標とバージョン

リネーム後の座標：

```xml
<dependency>
    <groupId>jp.clip</groupId>
    <artifactId>whisper-jni-custom</artifactId>
    <version>1.9.3-2</version>
</dependency>
```

- `artifactId` は `whisper-jni-custom` のままを推奨（`settings.gradle` で固定済み）。
  変えたければこのタイミングが唯一のチャンスです
- バージョンは `<whisper.cpp のバージョン>-<このラッパーのビルド番号>` の規約を継続。
  groupId が変わるので、`pom.xml` を書き換えないと切り替わらない = 事故が起きません

**完了条件**: `dumpbin /exports` に旧シンボルが 0 件、`gradlew test` が全件パス、
`-Xcheck:jni` 付きでも警告なし。

---

### 2-1 / 2-2 実施結果（2026-09-03）

**2-1 完全リネーム** `io.github.jaffe2718.whisperjni` → `jp.clip.whisperjni`（115箇所）

- `git mv` を使用したため履歴は `git log --follow` で追える
- JNI ヘッダを `javac -h` で正規再生成して比較 → シンボル 65 個・include guard 完全一致
- Java の native 宣言 vs 共有ライブラリのエクスポートシンボル → **27 / 27 完全一致**
- 旧シンボル `Java_io_github_jaffe2718_*` の残存 **0 件**
- `.cpp` 内の FindClass / GetMethodID 文字列リテラルを全件監査。プロジェクトのパッケージを
  参照する文字列は `"jp/clip/whisperjni/TokenData"` の 1 箇所のみで、正しく置換済み
- Windows で `gradlew test` 全件パス。`testTokens` の TOKEN 出力により、静的検証できない
  `FindClass` の経路も実行時に確認

**2-2 高水準 API 層** `jp.clip.whisper` を新規追加（純粋な追加。既存 API は無変更）

| クラス | 役割 |
|---|---|
| `WhisperEngine` | 入口。`AutoCloseable`。ネイティブのロード・コンテキスト管理・パラメータ組み立てを隠蔽 |
| `WhisperConfig` | 設定 + Builder。モデル / 言語 / スレッド数 / VAD / 文法 / ネイティブ配置先 |
| `TranscriptionResult` | `text()` `segments()` `elapsedMs()` `realTimeFactor()` |
| `Segment` | `startMs()` `endMs()` `text()` `durationMs()`（**ミリ秒**に変換済み） |
| `SamplingStrategy` | `GREEDY` / `BEAM_SEARCH`（int 定数を型安全に包む） |
| `WhisperException` | 非チェック例外 |

設計上の判断:

- **低レイヤの型を公開 API に露出させない。** `WhisperContext` / `WhisperState` /
  `WhisperFullParams` / `WhisperGrammar` はすべて `WhisperEngine` の内部に隠している。
  transcribe-shell 側の import は `jp.clip.whisper.*` だけで済む
- VAD モデルは jar 同梱のものを一時ファイルへ自動展開（プロセス内で 1 回だけ）
- ネイティブのロードは JVM 内で 1 回だけ（static ガード）
- 進捗ログは既定で無効（`printNativeProgress(true)` で有効化）
- `WhisperEngine` はスレッド安全ではない（Javadoc に明記）

テスト: `src/test/java/jp/clip/whisper/WhisperEngineTest.java`（15 テスト）。
低レイヤの `WhisperJNITest` が whisper.cpp の出力を完全一致で検証するのに対し、
こちらは **API 層の振る舞い**（構造・時刻の単位・例外・後始末）を検証し、文字列は部分一致で
確認する。これにより whisper.cpp 更新時に句読点の揺れで落ちない。

検証（クラウド Linux、実機ネイティブ）: API 層スモークテスト **14 / 14 PASS**。
`readAudioSamples` が jfk.wav を 176,000 サンプル = 11.0 秒 @16kHz で読めること、
VAD モデルの自動展開が動作することを確認。

**コーディングルールを追加**

`var`（型推論）を使わない方針に決定。既存コード 44 箇所（`WhisperGrammar` 4 / 
`WhisperJNITest` 40）を明示型に置換し、`CLAUDE.md` にルールとして記載した。
`CLAUDE.md` には他に JNI リネーム時の手順、スクリプトの規約、作業の進め方もまとめてある。

---
## Step 3 — ②/⑥ リファクタリング  ✅ 3-1〜3-3 完了 / 3-4 Windows 検証待ち

Step 2 が緑になってから着手します。**1項目ずつコミット**してください。
まとめてやると、壊れたときに原因が分からなくなります。

### 3-1. `scripts/` へ集約

```
scripts/
├── build-windows.ps1
├── build-linux.sh
├── build-mac.sh
├── build-cuda.sh
├── download-test-model.ps1 / .sh
└── download-vad-model.ps1 / .sh
```

**要注意**: `download-*.ps1` / `download-*.sh` は「プロジェクトルートで実行される」前提で
`.\src\main\native\whisper\models\...` という相対パスを持っています。1階層下がるので、
スクリプト冒頭で

```powershell
Set-Location (Join-Path $PSScriptRoot '..')
```

のようにルートへ移動するか、`$PSScriptRoot\..` 基準にパスを書き換える必要があります。

同時に直すもの：`.github/workflows/main.yml`（`./build_linux.sh` など複数箇所）、`README.md`。

### 3-2. `src/main/native/` の自作コードと submodule を分離

```
src/main/native/
├── jni/
│   └── jp_clip_whisperjni_WhisperJNI.cpp / .h
└── whisper/          ← submodule。パスは変えない
```

同時に直すもの：
- `CMakeLists.txt` の `add_library` のソースパス
- `CMakeLists.txt` の `target_include_directories` の `src/main/native` → `src/main/native/jni`
- `build.gradle` の `options.headerOutputDirectory.set(file('src/main/native'))` → `.../native/jni`

**`.gitmodules` の `path` は絶対に変えないでください。** submodule を移動すると
`.git/modules` 以下の再配置が必要になり、事故のもとです。

### 3-3. コードの近代化

- `TokenData` / `WhisperVADSegment` / `Segment` を `record` にする（Java 17 で可能）
  - **注意**: `TokenData` は JNI から
    `GetMethodID(cls, "<init>", "(Ljava/lang/String;IIFFFFJJJF)V")` で生成しています。
    record の正規コンストラクタが同じシグネチャになれば動きますが、
    **フィールドの順番を変えると壊れます**。順番は絶対に維持すること
- `WhisperContextParams`（22行）/ `WhisperVADContextParams`（9行）は
  Javadoc が空なので Step 4 とまとめて埋める
- `LibraryUtils` の `loadOrder` を単なる文字列リストから enum か定数クラスへ
- 重複している `full` / `fullWithState` 系のオーバーロードを整理

**実施結果（2026-09-03）**

| 項目 | 内容 |
|---|---|
| `scripts/` 集約 | 8スクリプトを `scripts/` へ移動し kebab-case に統一（`build-windows.ps1` `build-linux.sh` `build-mac.sh` `build-cuda.sh` `download-test-model.{sh,ps1}` `download-vad-model.{sh,ps1}`） |
| 相対パス対策 | 全スクリプト冒頭にルートへの `cd` を追加（`cd "$(dirname "$0")/.."` / `Set-Location (Join-Path $PSScriptRoot "..")`）。**どのディレクトリから呼んでも動く**ようになった |
| shebang 補完 | `build-mac.sh` と `download-test-model.sh` に `#!/bin/bash` が無かったので追加 |
| `src/main/native/jni/` 分離 | 自作 JNI コードを `src/main/native/jni/` へ。submodule の `src/main/native/whisper/` はそのまま（`.gitmodules` は変更なし） |
| 参照の追随 | `CMakeLists.txt`（ソースパス + include ディレクトリ）、`build.gradle`（`headerOutputDirectory`）、`.github/workflows/main.yml`（8箇所）、`README.md` |
| 生成物の掃除 | ルートの `bin/`（旧 `io/github/jaffe2718` の `.class` が残っていた IDE 生成物）を削除 |

**record 化は実施しませんでした（意図的）**

`TokenData` / `WhisperVADSegment` を record にすると、フィールドアクセス
（`token.token` など）がアクセサ（`token.token()`）に変わり、**公開 API の破壊的変更**に
なります。既存テストも transcribe-shell も壊れる可能性があるため、得られる利益に対して
リスクが見合いません。将来 API を意図的に作り直すときに一緒に検討するのが適切です。

検証: `scripts/build-linux.sh` を **/tmp から実行**して通しでビルド成功（cd 処理の確認）。
生成ライブラリ 6 個、JNI シンボル **27/27 完全一致**を維持。

### 3-4. クラス構成の全面見直し（徹底リファクタリング）  ✅ 2026-09-03 クラウド検証完了・Windows 検証待ち

「高速化は後回しでよいのでリファクタリングを徹底的に。クラス構成を細かく見直す。
lombok 等を入れてもよい。File は nio に。読みづらい for は stream に」という指示で実施。
コードベース全体（Java 1,400 行 + C++ 776 行）を精読し、以下を行った。

#### 発見したバグ（すべて修正済み）

| # | 場所 | 内容 | 影響 |
|---|---|---|---|
| A1 | C++ `freeGrammar` | `new` したオブジェクトを `free()` で解放（デストラクタが走らず未定義動作）+ `grammarMap` ではなく **`stateMap` を erase** していた | 文法を使うたびにリーク。ID が偶然一致すると無関係な `whisper_state` がマップから消える。`testFullWithGrammar` が毎回この経路を通っていた |
| A2 | C++ `vadState` | `freeWhisperFullParams` を呼ばず UTF 文字列 3 本を毎回リーク | メモリリーク |
| A3 | C++ `vadState` | VAD コンテキストの NULL 未検査 | モデル不正でクラッシュ |
| A4 | C++ 全体 | 3 つのグローバル map + `rand()` にミューテックス無し | 複数スレッドから context を作るとデータ競合 |
| A5 | C++ 全体 | `contextMap.at()` が C++ 例外を JVM へ投げる | 解放済みハンドルで未定義動作 |
| A6 | C++ ログ | 毎行 `AttachCurrentThread` / `DetachCurrentThread` | **アタッチ済みの Java スレッドをデタッチする危険** + オーバーヘッド |
| A7 | C++ 全体 | `NewStringUTF` / `GetStringUTFChars` は「修正 UTF-8」 | 補助面の文字（絵文字・一部の漢字）を含む文字起こし結果やパスが化ける可能性 |
| A8 | C++ `full` | `numSamples > samples.length` を検査していない | 配列外読み出し |
| A9 | C++ セグメント系 | 負の添字を検査していない | 配列外読み出し |
| A10 | C++ `loadGrammar` | `grammar_parser::parse` は失敗を握りつぶして空を返すのに、それを検出していない | 不正な文法が黙って無視される |
| A11 | C++ `initFromInputStream` | `InputStream.read` が投げた例外を検査せず無限ループの可能性 | ハング |
| B1 | Java `GbnfGrammarValidator` | 「root はピリオドで終わる」検査が閉じ引用符のトークンで一度も動かない | `root ::= " hello"` が通っていた（新規テストで発覚） |
| B2 | Java `WhisperGrammar` | 356 行のうち 300 行が純 Java の GBNF バリデータ（単一責任違反） | 別クラスへ分離済み（3-3 で着手） |
| B3 | Java | `WhisperVADSegment`（死にコード）、未使用フィールド `grammarText` `WhisperState.context` | 削除 |
| B4 | Java `getTokens` | セグメント 1 つにつき JNI 呼び出しが 2N+1 回 | 1 回に |

#### 新しいクラス構成

```
jp.clip.whisper（高水準 API。利用側はここだけ使う）
├── WhisperEngine        入口（open / transcribe / close）。316 行 ← 436 行
├── WhisperConfig        設定。Lombok @Value @Builder。148 行 ← 435 行
├── AudioFileReader      音声ファイル → 16kHz float[]（WhisperEngine から分離）
├── NativeRuntime        JVM 内で 1 度だけの初期化（ネイティブ読込・VAD モデル展開）（同じく分離。package-private）
├── TranscriptionResult / Segment（record）
├── SamplingStrategy     ← WhisperSamplingStrategy を包む
└── WhisperException

jp.clip.whisperjni（JNI bridge。whisper.cpp と 1 対 1）
├── WhisperJNI           native 宣言 24 個 + 薄いラッパー
├── NativeHandle         ハンドル基底（WhisperJNI の内部クラスからトップレベルへ。多重 close 防止をここに集約）
│   ├── WhisperContext / WhisperState / WhisperGrammar
├── WhisperTranscriptionParams  ← WhisperFullParams。フィールドを読める名前に（vad_model_path → vadModelPath、nThreads → threads、entropyThold → entropyThreshold 等）、VADParams → VadParams
├── WhisperContextParams useGPU → useGpu
├── WhisperSamplingStrategy  定数インタフェース → enum
├── WhisperToken         ← TokenData。p / plog / t0 / tid 等の暗号的なフィールドを probability / logProbability / startCentiseconds / timestampTokenId に + isSpecial()
├── NativeLibraryLoader  ← LibraryUtils を分割（ロード順・Vulkan）
├── BundledResources     ← LibraryUtils を分割（jar 同梱リソースの取り出し）
├── Platform             ← LibraryUtils を分割（OS / arch 判定。enum）
└── GbnfGrammarValidator
```

削除: `LibraryUtils`（3 分割）、`WhisperVADSegment`、`WhisperVADContextParams`、`WhisperJNI#vadState`
（whisper.cpp 1.9.3 の組み込み VAD `params.vad = true` と重複、かつ A2/A3 のバグ持ち）。

#### C++ の書き直し（`jp_clip_whisperjni_WhisperJNI.cpp`）

- ハンドル表を `HandleTable<T>` テンプレート化（ミューテックス付き、連番採番、型ごとに独立）
- `requireContext` / `requireState`: 未知のハンドルは `IllegalStateException` として Java へ
- `FieldReader`: Java フィールドを名前で読む。**Java 側とフィールド名が食い違うと `NoSuchFieldError` として Java に伝わる**（以前はクラッシュ）
- `Utf8Strings` / `FloatArrayView`: RAII で解放漏れを構造的に防ぐ
- 文字列は `String(byte[], "UTF-8")` / `getBytes("UTF-8")` 経由（A7 対策）
- ログは `GetEnv` を先に試し、自分でアタッチしたスレッドだけデタッチ。メソッド ID はキャッシュ、末尾の空白は C++ 側で除去
- 6 回重複していたセグメント添字チェックをテンプレート 2 本に、`full` / `fullWithState` の文法設定重複を `applyGrammar` に
- `getSegmentTokens`: トークン列を 1 回の JNI 呼び出しで `WhisperToken[]` にして返す
- `// START SUPASULLEY EPIC METHODS` 等の落書きコメントを除去し、冒頭に設計方針を記載

#### Java 側の方針変更

- **Lombok 導入**（`io.freefair.lombok` 9.5.0 / Lombok 1.18.48、compileOnly）。`WhisperConfig` の 20 項目は
  「フィールド + Javadoc」を 1 箇所書けばビルダーとアクセサが揃う（以前は 5 箇所）。`toBuilder()` も生えた
- **`java.io.File` 全廃**（`Files.isRegularFile` / `Files.newInputStream` / `Files.list` に置換）
- 変換・集約のループを Stream に（`TranscriptionResult.text()`、`NativeLibraryLoader` の並べ替え、
  `WhisperEngine.collectSegments`、`AudioFileReader` など）
- コーディング規約を `CLAUDE.md` に追記（var 禁止 / nio / stream / ライブラリ方針 / JNI との結びつき表）
- 実行時依存は追加していない（Apache Commons は不要と判断: `String.isBlank` 等の Java 17 標準で足りる。
  transcribe-shell の依存を増やさない）
- whisper.cpp 自身のログは SLF4J ロガー名 **`whisper.cpp`** に出す（`logging.level.whisper.cpp=WARN` で抑制可能）

#### 命名の見直し（2 回目の指示「わかりづらいクラス名・メソッド名はリネーム」で追加）

方針: 略語と whisper.cpp の C 名（`fullNSegments` `nThreads` `TokenData.p`）を Java 側の識別子から追い出し、
対応する C 名は Javadoc に併記する。`private native` メソッドだけは C++ シンボルと結びついているので
whisper.cpp 寄りの名前のまま（`CLAUDE.md` の命名ルール参照）。Apache Commons IO は使わない
（`Files.copy` / `readAllBytes` / `Path.getFileName` で全部足りる。実行時依存が transcribe-shell に伝播する）。

#### 公開 API の変更点（1.9.3-1 は未公開なので互換性の問題なし）

| 旧 | 新 |
|---|---|
| `whisper.loadLibrary()` | `WhisperJNI.loadBundledLibraries()`（static） |
| `LibraryUtils.loadLibrary(logger, dir)` | `NativeLibraryLoader.load(logger, dir)` |
| `LibraryUtils.findAndLoadVulkanRuntime()` | `NativeLibraryLoader.loadVulkanRuntimeIfPresent()` |
| `LibraryUtils.exportVADModel(logger, path)` | `BundledResources.exportVadModel(logger, path)` |
| `LibraryUtils.getOS()` / `getArchitecture()` | `Platform.current()` / `Platform.architecture()` |
| `params.vad_model_path`、`vadParams.min_speech_duration_ms` 等 | `params.vadModelPath`、`vadParams.minSpeechDurationMs` 等（camelCase） |
| `WhisperFullParams` / `.VADParams` | `WhisperTranscriptionParams` / `.VadParams` |
| `TokenData`（`token` `p` `plog` `pt` `ptsum` `t0` `t1` `t_dtw` `vlen` `tid`） | `WhisperToken`（`text` `probability` `logProbability` `timestampProbability` `timestampProbabilitySum` `startCentiseconds` `endCentiseconds` `dtwCentiseconds` `voiceLength` `timestampTokenId`） |
| `WhisperJNI.init` / `initNoState` / `initState` / `initOpenVINO` | `createContext` / `createContextWithoutState` / `createState` / `initOpenVinoEncoder` |
| `WhisperJNI.full` / `fullWithState` | `transcribe` / `transcribeWithState` |
| `fullNSegments` / `fullGetSegmentTimestamp0` / `fullGetSegmentTimestamp1` / `fullGetSegmentText` / `getTokens`（+ `FromState`） | `segmentCount` / `segmentStartCentiseconds` / `segmentEndCentiseconds` / `segmentText` / `segmentTokens`（+ `FromState`） |
| `WhisperFullParams.nThreads` `audioCtx` `nMaxTextCtx` `maxInitialTs` `temperatureInc` `entropyThold` `logprobThold` `noSpeechThold` `beamSearchBeamSize` `beamSearchPatience` `vad` | `threads` `audioContextSize` `maxTextContextTokens` `maxInitialTimestampSeconds` `temperatureIncrement` `entropyThreshold` `logProbabilityThreshold` `noSpeechThreshold` `beamSize` `beamPatience` `vadEnabled` |
| `WhisperConfig.vad(boolean)` / `translate(boolean)` | `vadEnabled(boolean)` / `translateToEnglish(boolean)` |
| `Platform.resourceDirectory()` | `nativeLibraryDirectoryName()` |
| `NativeHandle.ref` | `nativeId` |
| `new WhisperFullParams(int)` | `new WhisperTranscriptionParams(WhisperSamplingStrategy)`。引数なしは GREEDY（旧 BEAM_SEARCH） |
| `WhisperContextParams.useGPU` | `useGpu` |
| `WhisperEngine.readAudioSamples(path)` | `AudioFileReader.readSamples(path)` |
| `WhisperConfig.Builder` | `WhisperConfig.WhisperConfigBuilder`（Lombok 生成） |
| `WhisperConfig.builder().build()`（model 未設定） | `WhisperException` → `NullPointerException`（Lombok `@NonNull`） |
| 解放済みハンドル使用時の例外 | `RuntimeException` → `IllegalStateException`（サブクラスなのでテストは通る。メッセージ不変） |

#### 検証結果（クラウド Linux x86_64、JDK 21、実モデル ggml-tiny.bin）

| 項目 | 結果 |
|---|---|
| `javac -Xlint:all --release 17` | 警告 0（クラウド JDK 21 と、PC 側 VM の JDK 25.0.4.1 + slf4j-api 2.0.16 実物の両方で確認） |
| `javadoc`（delombok 後、`-Xdoclint:all,-missing`） | エラー 0・警告 0 |
| JNI シンボル（ヘッダ vs `nm -D`） | **24 / 24 一致** |
| `GbnfGrammarValidatorTest` | 13 / 13 |
| `WhisperJNITest` | 19 / 19（新規 3: 負の添字、`numSamples` 超過、不正文法の拒否） |
| `WhisperEngineTest` | 16 / 16（新規 1: `toBuilder`） |
| 文字起こし文字列の完全一致 | 旧実装と同一（`" And so my fellow Americans ask not ..."` GREEDY / BEAM_SEARCH / grammar / state 版すべて） |
| UTF-8 往復 | `モデル𠮷野家😀.bin` というパスのモデルを読み、whisper.cpp のログ経由で同じ文字列が Java に戻ることを確認（補助面の文字含む） |
| 並行性 | 8 スレッド × 5 回の context/state/grammar 生成・実行・解放 = 40 / 40 成功 |
| ログ経路 | whisper.cpp のログが SLF4J `whisper.cpp` ロガーに届き、末尾の空白・改行が除去されている |

Windows での検証手順は「Step 3-4 の Windows 手順」（本ファイル末尾）を参照。

---

## Step 4 — ⑧ Javadoc の完全日本語化  ✅ 2026-09-03 クラウド検証完了・Windows で `gradlew javadoc` の確認待ち

Step 3-4 のリファクタリングで全クラスを書き直した時点で、Javadoc はすべて日本語になっています。
残りは仕上げだけを行いました。

| 項目 | 状態 |
|---|---|
| 文字コード設定 | `build.gradle` の `Javadoc` タスクに `encoding` / `docEncoding` / `charSet` = UTF-8 を設定済み（Step 3-4 で実施） |
| 警告 | delombok 後のソースに対して `-Xdoclint:all,-missing` で**エラー 0・警告 0**。`missing` を外しているのは Lombok が生成するアクセサに `@return` が付かないため（`build.gradle` で同じ設定にしてある） |
| パッケージ概要 | `package-info.java` を両パッケージに追加。`jp.clip.whisperjni` の方には Java クラスと whisper.cpp の構造体の対応表を入れた |
| 書き分け | `jp.clip.whisper` は使用例付き、`jp.clip.whisperjni` は対応する whisper.cpp の関数名・構造体メンバー名を併記（`CLAUDE.md` にルール化） |
| 英文の残り | `grep` で英文 Javadoc の残存を確認、0 件 |

`options.locale = 'ja_JP'` は設定していません。これは javadoc が生成する定型文（"Method Summary" など）を
日本語化する設定で、あると便利ですが、JDK 25 の日本語リソースは翻訳の抜けがあり英日混在になることがあります。
必要なら `build.gradle` に 1 行足すだけです。

**Windows での確認**

```powershell
cd C:\pr-work\whisper-jni-custom
.\gradlew.bat javadoc
#   期待: BUILD SUCCESSFUL、警告 0（Lombok の Unsafe 警告が出ることはあるが javadoc とは無関係）
Start-Process .\build\docs\javadoc\index.html     # ブラウザで日本語が正常に表示されること
git add -A
git commit -m "Step 4: package-info.java を追加し Javadoc の日本語化を完了"
git push
```

---

## Step 5 — ③/⑦ 高速化（方針 (c)：whisper.cpp 側で詰める）  ▶ 5-1 計測ツール準備済み（2026-09-03）

**測定 → 1つずつ変更 → 再測定** を守ってください。まとめて変えると何が効いたか分かりません。

### 対象マシン（2026-09-03 確認）

| 項目 | 値 | 高速化への意味 |
|---|---|---|
| CPU | Core i5-1335U（P コア 2 + E コア 8、12 スレッド） | whisper.cpp の既定は 4 スレッド。**P コアが 2 つしかない**ので、スレッドを増やしても伸びは限定的。4 / 6 / 8 / 12 を実測して決める |
| GPU | Intel Iris Xe（内蔵、共有メモリ 2 GB） | **CUDA は不可**（NVIDIA 専用）。Vulkan は動くが、内蔵 GPU は CPU とメモリ帯域を共有するため whisper では CPU と同等〜1.5 倍程度にとどまることが多い。Intel 向けには **OpenVINO（エンコーダのみ）**という選択肢もある |
| メモリ | 未確認 | large-v3-turbo-q5_0 は読み込みに約 1 GB、small は約 0.5 GB |
| 電源 | ノート PC | U シリーズは熱で落ちるので、**計測は AC 電源で・同じ条件を 2 回以上** |
| 手元のモデル | `ggml-tiny.bin`（74 MB）、`ggml-small.bin`（465 MB） | 量子化版と large-v3-turbo は未取得 |

### 5-1. ベースライン計測  ✅ ツール準備済み

`src/test/java/jp/clip/whisper/Benchmark.java` と Gradle の `benchmark` タスクを追加しました。
モデル × スレッド数 × VAD × 戦略の組み合わせを回し、RTF（処理時間 ÷ 音声長）を表と CSV
（`build/benchmark/benchmark-<日時>.csv`）に出します。

```powershell
# 0. 日本語の計測用音声を 1 本用意する（WAV。数分あるとよい。会議録音など）。以下 C:\audio\sample-ja.wav とする

# 1. モデルを取得（models\ に置く。ggml-*.bin は .gitignore 済み）
.\scripts\download-model.ps1 small-q5_1
.\scripts\download-model.ps1 large-v3-turbo-q5_0
Copy-Item "$env:USERPROFILE\whisper-models\ggml-small.bin" .\models\

# 2. ベースライン（現状の条件: small、既定スレッド、VAD なし、GREEDY）
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" "-Pbench.models=models\ggml-small.bin" "-Pbench.repeat=3"

# 3. 効果測定を 1 コマンドで（モデル 3 種 × スレッド 4 種 × VAD あり/なし = 24 条件。数十分かかる）
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" `
  "-Pbench.models=models\ggml-small.bin;models\ggml-small-q5_1.bin;models\ggml-large-v3-turbo-q5_0.bin" `
  "-Pbench.threads=4,6,8,12" "-Pbench.vad=both" "-Pbench.repeat=2"
```

出力の見方: `minRTF` がその条件の実力値、`medRTF` が実運用の目安。`text` 列で日本語の精度が
崩れていないかも一緒に見てください（速くても文字化けや脱落があれば不採用）。CSV をこのファイルの
「5-4 計測結果」に貼っていきます。

### 5-2. 効果が大きい順に試す（この PC 向けに並べ直し）

| # | 手段 | 期待できる効果 | 工数 | 備考 |
|---|---|---|---|---|
| 1 | **モデルを `large-v3-turbo-q5_0` へ** | 大。small より精度が良く、速度は同等〜やや遅い程度 | 計測のみ | CPU のみの環境では本命 |
| 2 | **VAD で無音を落とす** | 長い録音ほど大。無音が半分なら処理も半分 | 計測のみ | `vadEnabled(true)`。実装済み |
| 3 | **スレッド数** | 中。P コア 2 なので過度な期待はしない | 計測のみ | 4 / 6 / 8 / 12 を比較 |
| 4 | **GREEDY 固定** | 中 | 計測のみ | 既定が GREEDY。BEAM_SEARCH との精度差を `text` 列で確認 |
| 5 | **Vulkan（Iris Xe）** | 不明。CPU と同等〜1.5 倍の見込み | 2〜4 セッション、ディスク +1〜2 GB（Vulkan SDK） | 1〜4 で足りなければ試す。`CMakeLists.txt` は `-DGGML_VULKAN=ON` に対応済み、`NativeLibraryLoader` はランタイム検出済み |
| 6 | **OpenVINO エンコーダ** | 中〜大（Intel 向けに最適化。エンコーダのみ） | 3〜5 セッション。OpenVINO ランタイム + モデル変換（Python 環境が必要） | `WhisperJNI#initOpenVinoEncoder` は実装済みだがビルドが `WHISPER_OPENVINO=OFF` |
| 7 | `audio_ctx` の縮小 | 短い音声で中 | 小 | 30 秒より短い入力向け |
| — | flash attention | — | 不要 | **1.9.3 で既定有効**（起動ログ `flash attn = 1`） |

工数の目安: 1〜4 は合わせて **2〜3 セッション**（ほぼ計測時間）。5 まで含めると **5〜7 セッション**。
CPU のみで目標に届くなら 5 以降はやらない。

### 5-3. それでも足りなければ

- **parakeet**: 1.9.3 から whisper.cpp 本体に同梱された別系統の ASR（NVIDIA Parakeet TDT）。
  現在ビルドはされていますが Java API は未公開です。日本語対応の状況を確認のうえ検討
- **CTranslate2（本物の faster-whisper）に JNI/FFM で束縛**: 実質もう1本ライブラリを作るのと同じ工数
- **Python の faster-whisper をプロセス起動**: 実装は軽いが、Python 環境の配布が課題

Step 2 の API 層があるので、どの手段を選んでも **transcribe-shell 側の import は変わりません**。

### 5-4. 計測結果

（`gradlew benchmark` の CSV をここに貼る）

---

## Step 6 — ⑨ `dev` ブランチの削除とブランチ整理

### 6-1. `dev` を削除しても何も失われません

確認済みの事実：`origin/dev` と `feature/whisper-cpp-1.9.3` は **同じコミット
`b555ce720990c30438973318c63f03a306669856` を指しています**。
つまり `dev` は単なるラベルで、削除してもこのコミットは
`feature/whisper-cpp-1.9.3` の履歴に永久に残ります。

**実行条件**: 1.9.3 が Windows でテスト緑 + transcribe-shell が新座標で安定稼働を確認したあと。

```powershell
# ラベルとして残しておきたい場合はタグを打つ（任意）
git tag baseline-whisper-cpp-1.8.3 b555ce720990c30438973318c63f03a306669856
git push origin baseline-whisper-cpp-1.8.3

# dev を削除
git push origin --delete dev
git remote prune origin
```

### 6-2. ブランチ戦略を整える（併せてやると綺麗）

現在 GitHub のデフォルトブランチが `feature/whisper-cpp-1.9.3` になっています
（`origin/HEAD -> origin/feature/whisper-cpp-1.9.3`）。
feature ブランチがデフォルトのままだと今後わかりにくいので、
1.9.3 が確定したら `main` に統合するのがおすすめです。

```powershell
git branch -m feature/whisper-cpp-1.9.3 main
git push -u origin main
# GitHub の Settings > Branches でデフォルトブランチを main に変更してから
git push origin --delete feature/whisper-cpp-1.9.3
```

以降は Step ごとに `feature/step2-rename-jp-clip` のようなブランチを切って
`main` にマージしていく流れにすると、失敗したときに戻しやすくなります。

### 6-3. `~/.m2` の旧成果物の掃除（任意）

transcribe-shell が新座標で安定稼働してから：

```powershell
Remove-Item -Recurse "$env:USERPROFILE\.m2\repository\io\github\jaffe2718" 
```

急ぐ必要はありません。ディスクを食っているだけで害はないので、
しばらく残して「戻せる状態」を保っておく方が安全です。

---

## やらないこと — ④ Maven 化

判断の根拠を残しておきます。

- **消費側から見て Gradle 製 jar と Maven 製 jar は区別できません。** 座標が合っていれば同じです
- 「Java 21〜25 対応」は `options.release` の設定の話で、**ビルドツールとは無関係**。
  現在の Gradle 設定（bytecode 17）で既に達成済みです
- CMake・ネイティブビルド・OS別分岐・複数成果物（CPU / Vulkan / CUDA / musl）は
  Gradle の方が素直に書けます。Maven だと `exec-maven-plugin` と profile で
  OS 分岐を書くことになり、確実に今より長くなります
- CI（`main.yml` 400行超）・jreleaser 公開・native packaging を全部書き直すコストに対して、
  得られるものがほぼありません

Maven の学習は transcribe-shell 側で十分できるので、
**「Maven のプロジェクトと Gradle のプロジェクトを両方持っている」状態を活かす**方が得です。

---

## 全体の流れ（まとめ）

```
Step 0  Windows テスト緑 + commit/push            ← 今ここ
   │
Step 1  Java 25 方針確定 / ライセンス修正 / wrapper再生成 / .gitattributes
   │
Step 2  jp.clip への完全リネーム + jp.clip.whisper API層 追加   ★山場
   │      └─ dumpbin で旧シンボル 0 件を確認、-Xcheck:jni でテスト
   │
Step 3  リファクタリング（scripts/ 集約、native/jni 分離、record化）
   │      └─ 1項目1コミット
   │
Step 4  Javadoc 完全日本語化（charSet / docEncoding を UTF-8 に）
   │
Step 5  高速化 (c)：GPU → 量子化モデル → VAD → スレッド数
   │      └─ 測定 → 1つ変更 → 再測定
   │
Step 6  dev 削除 / main へ統合 / ~/.m2 掃除
```

各 Step の完了条件は「`gradlew test` が全件パス」です。ここを毎回守れば、
どこで壊れたかが必ず1 Step 以内に絞れます。

---

## Step 3-4 の Windows 手順（リファクタリング後の検証とコミット）

クラウド側では Linux で全テストが通っています。Windows では **ネイティブを作り直してから** テストします
（C++ を書き直しているので、古い `whisper-jni.dll` のままだと `UnsatisfiedLinkError` になります）。
リネーム（`git mv`）と `LibraryUtils.java` の `git rm` はクラウド側から実行済みなので、手元では
ビルド → テスト → コミットだけです。

```powershell
cd C:\pr-work\whisper-jni-custom

# 1. 状態確認。R（リネーム）2 件、D 3 件、新規 ?? 9 件、M 多数 が見えるはず
git status --short

# 2. ネイティブを作り直す
Remove-Item -Recurse -Force .\build -ErrorAction SilentlyContinue
.\scripts\build-windows.ps1

# 3. シンボルの突き合わせ（Developer PowerShell で）。Count が 24 になること
dumpbin /exports .\whisperjni-build\whisper-jni.dll | Select-String "Java_" | Measure-Object

# 4. テスト。初回は Lombok と io.freefair.lombok プラグインをダウンロードするので少し時間がかかる
$env:GRADLE_USER_HOME = "C:\pr-work\.gradle-home"
.\gradlew.bat clean test
#   期待: 13 + 19 + 16 = 48 tests, 0 failures

# 5. コミットとプッシュ
git add -A
git status --short          # 全部ステージされたこと（先頭 2 文字目が空白）を確認
git commit -F .git\COMMIT_MSG.txt   # ← 下のコミット文を保存して使う。または -m で 1 行目だけでもよい
git push
```

コミット文（`.git\COMMIT_MSG.txt` に保存するか、1 行目だけ `-m` で）:

```
Step 3-4: クラス構成の全面見直しと JNI C++ の書き直し

Java
- LibraryUtils を NativeLibraryLoader / BundledResources / Platform に分割
- WhisperEngine から AudioFileReader / NativeRuntime を分離
- ハンドル基底を NativeHandle としてトップレベル化（多重 close 防止を集約）
- WhisperFullParams -> WhisperTranscriptionParams、TokenData -> WhisperToken。
  フィールド・メソッド名を読める名前に統一（nThreads -> threads、fullNSegments -> segmentCount 等）
- WhisperSamplingStrategy を定数インタフェースから enum に
- Lombok 導入（WhisperConfig を @Value @Builder に。435 行 -> 148 行）
- java.io.File を全廃し java.nio.file に統一、変換・集約のループを Stream に
- 死にコード削除: WhisperVADSegment、WhisperVADContextParams、vadState
- GbnfGrammarValidator: 閉じ引用符トークンで終端検査が動いていなかった不具合を修正

C++（全面書き直し）
- freeGrammar: free() -> delete、stateMap ではなく grammarMap から erase
- ハンドル表をミューテックス付き HandleTable<T> に。未知のハンドルは IllegalStateException
- 文字列を String(byte[], "UTF-8") 経由に（NewStringUTF の修正 UTF-8 問題を回避）
- ログ転送: GetEnv を先に試し、自分でアタッチしたスレッドだけデタッチ
- numSamples 超過・負の添字・不正文法・InputStream の例外を検査
- トークン列を 1 回の JNI 呼び出しで返す getSegmentTokens

ビルド・ドキュメント
- io.freefair.lombok 9.5.0 / Lombok 1.18.48、lombok.config、javadoc の doclint 設定
- CLAUDE.md に nio / stream / ライブラリ / 命名のルールを追記
- README と WINDOWS-BUILD-1.9.3.md を更新

検証: Linux で 48 テスト全件パス（実モデル）、JNI シンボル 24/24、UTF-8 往復、8 スレッド並行 40/40
```

**IDE（STS / Eclipse）で赤線が出る場合**: `WhisperConfig` の `builder()` や `model()` が見つからないのは
IDE に Lombok が入っていないためで、ビルドの問題ではありません。`~/.m2/repository/org/projectlombok/lombok/1.18.46/lombok-1.18.46.jar`
を `java -jar` で実行してインストーラから STS を指定してください。

**もし `Plugin [id: 'io.freefair.lombok', version: '9.5.0'] was not found` で落ちたら**: jreleaser プラグインと
同じ Gradle Plugin Portal から取得するので、ネットワーク（プロキシ）の問題です。§5 の jreleaser の項と同じ対処をしてください。
