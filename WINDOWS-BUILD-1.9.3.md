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
  - 生成 POM = `io.github.jaffe2718:whisper-jni-custom:1.9.3-1`

### 未検証（Windows 側でお願いしたいこと）

- MSVC での DLL ビルド（`build_windows.ps1`）
- 実モデル `ggml-tiny.bin` を使った `./gradlew test` の全 20 テスト
  （クラウド／デバイス双方から Hugging Face と Maven Central に到達できないため、
  ダミーモデルでの API 疎通確認までしか実施できていません）
- `jreleaser` 1.26.0 プラグインの実解決（plugins.gradle.org に到達できず）

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
    <groupId>io.github.jaffe2718</groupId>
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

## Step 1 — ① Java 25 対応の確定（小さい仕上げ作業）

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

**完了条件**: `gradlew test` が緑のまま、`gradlew build` の警告が増えていない。

---

## Step 2 — ⑤ 完全リネーム + 2層化（今回の山場）

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
   - `build.gradle` の `group = 'io.github.jaffe2718'` → `'jp.clip'`
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

## Step 3 — ②/⑥ リファクタリング

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

**完了条件**: 各項目のコミットごとに `gradlew test` が緑。CI も一度手動実行して通すこと。

---

## Step 4 — ⑧ Javadoc の完全日本語化

本家追従を捨てたので、全クラス・全メソッドを日本語化して構いません。

### 4-1. Javadoc タスクの文字コード設定（これをやらないと文字化けします）

```groovy
tasks.withType(Javadoc).configureEach {
    options.encoding = 'UTF-8'
    options.source = '17'
    options.locale = 'ja_JP'
    if (options instanceof StandardJavadocDocletOptions) {
        options.charSet = 'UTF-8'
        options.docEncoding = 'UTF-8'
    }
}
```

`encoding`（ソースの文字コード）だけでなく **`charSet` と `docEncoding`（出力HTMLの文字コード）**
の3つを揃える必要があります。1つ欠けると生成された HTML で日本語が化けます。

### 4-2. 現状の警告 21 件を潰す

`javadocJar` 生成時に「no comment」警告が 21 件出ています（`WhisperVADContextParams` /
`WhisperVADSegment` など）。日本語化のついでに全部埋めて、警告 0 を目指します。
Maven Central 公開時に javadoc jar が必須なので、ここを綺麗にしておくと後が楽です。

### 4-3. 書き分けの指針

- `jp.clip.whisper`（自作 API 層）: 日本語で、使い方の例を `{@snippet}` か `<pre>` で入れる
- `jp.clip.whisperjni`（JNI bridge 層）: 日本語で、**「対応する whisper.cpp の関数名」を必ず併記**する。
  例: `whisper_full_with_state に対応`。将来 whisper.cpp を上げるとき、
  この対応表がそのまま影響範囲の調査資料になります

**完了条件**: `gradlew javadoc` が警告 0、生成 HTML をブラウザで開いて日本語が正常表示。

---

## Step 5 — ③/⑦ 高速化（方針 (c)：whisper.cpp 側で詰める）

**測定 → 1つずつ変更 → 再測定** を守ってください。まとめて変えると何が効いたか分かりません。

### 5-1. まずベースラインを記録する

Step 2 で作った `WhisperEngine` に簡易ベンチ（音声長 / 処理時間 / RTF = 処理時間÷音声長）を入れて、
現在の transcribe-shell と同条件で測ります。記録項目：

- モデル（サイズ・量子化）/ 音声の長さ / スレッド数 / バックエンド（CPU or GPU）
- 処理時間、RTF、体感の精度

### 5-2. 効果が大きい順に試す

| # | 手段 | 期待できる効果 | 備考 |
|---|---|---|---|
| 1 | **GPU バックエンド（Vulkan / CUDA）** | 最大。数倍〜十数倍 | CI で既に Vulkan natives をビルド済み。Windows に GPU があれば最優先 |
| 2 | **モデルを `large-v3-turbo` 系の量子化版へ** | 大。速度/精度比が非常に良い | `ggml-large-v3-turbo-q5_0.bin` など。`q8_0` は精度寄り、`q5_0` は速度寄り |
| 3 | **VAD で無音を落とす** | 長い録音ほど大。無音が半分なら処理も半分 | `params.vad = true` + silero v6.2.0。今回 jfk.wav で 3 区間検出を実測済み |
| 4 | **スレッド数を物理コア数に合わせる** | 中。デフォルトは 4 なので余裕があることが多い | `WhisperFullParams.nThreads` |
| 5 | **BEAM_SEARCH → GREEDY** | 中。精度とのトレードオフ | 日本語の精度低下が許容できるか要判断 |
| 6 | **`audio_ctx` の縮小** | 短い音声で中 | 30秒より短い音声なら encoder のコンテキストを削れる |
| 7 | **flash attention** | — | **1.9.3 では既に有効**（起動ログに `flash attn = 1` が出ています）。作業不要 |

### 5-3. それでも足りなければ

- **parakeet**: 1.9.3 から whisper.cpp 本体に同梱された別系統の ASR（NVIDIA Parakeet TDT）。
  現在ビルドはされていますが Java API は未公開です。日本語対応の状況を確認のうえ検討
- **CTranslate2（本物の faster-whisper）に JNI/FFM で束縛**: 実質もう1本ライブラリを作るのと同じ工数
- **Python の faster-whisper をプロセス起動**: 実装は軽いが、Python 環境の配布が課題

Step 2 の API 層があるので、どの手段を選んでも **transcribe-shell 側の import は変わりません**。

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
