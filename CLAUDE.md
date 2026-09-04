# CLAUDE.md — whisper-jni-custom 作業ルール

このリポジトリで作業するときの規約です。Claude Code / Cowork は自動で読み込みます。
人間が読む前提でも書いています。

---

## コーディングルール

### Java

**1. `var`（ローカル変数の型推論）を使わない。必ず明示的な型を書く。**
人間が読んだときに型がすぐ分からないのを避けるためです。

```java
// NG
var ctx = whisper.createContext(model);
var params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);

// OK
WhisperContext ctx = whisper.createContext(model);
WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
```

**2. ファイル操作は `java.nio.file`（`Path` / `Files`）を使う。`java.io.File` は使わない。**
`toFile()` で `File` に落とすのも避ける。`File` しか受け付けない API（例 `AudioSystem`）には
`Files.newInputStream` で開いたストリームを渡す。

```java
// NG
File model = path.toFile();
if(!model.exists() || !model.isFile()) { ... }
AudioSystem.getAudioInputStream(path.toFile());

// OK
if(!Files.isRegularFile(path)) { ... }
AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(path)));
```

**3. 読みづらい `for` / `while` は Stream API に置き換える。**
「配列やリストを走査して、変換・絞り込み・集約する」ループは Stream の方が意図が読み取れる。
逆に、添字を複雑に操作する状態機械（`GbnfGrammarValidator` の字句解析など）や
早期 `return` / `break` が要るループは、無理に Stream にしない。

```java
// NG
StringBuilder builder = new StringBuilder();
for(Segment segment : segments) { builder.append(segment.text()); }
return builder.toString().strip();

// OK
return segments.stream().map(Segment::text).collect(Collectors.joining()).strip();
```

**4. ライブラリの方針**

| ライブラリ | 扱い |
|---|---|
| Lombok | **使う**（`compileOnly`、利用側に影響しない）。ボイラープレートが明確に減る場所で。現在は `WhisperConfig`（`@Value` `@Builder`）と `NativeRuntime`（`@Slf4j`） |
| Apache Commons 等の実行時依存 | **原則入れない**。この jar を使うアプリ（transcribe-shell など）の依存関係を増やすため。Java 17 標準（`String.isBlank` `strip` `Files` `Stream`）で足りることがほとんど |
| SLF4J | 唯一の実行時依存（`api`）。ロガー実装は利用側が選ぶ |

Lombok を使う際の注意:
- JNI が `GetFieldID` で読むクラス（`WhisperTranscriptionParams` `WhisperContextParams` `WhisperToken`）には
  **使わない**。フィールド名・型が C++ と結びついているので、生成コードで隠さない。
- `@Builder` のクラスでは必須項目に `@NonNull` を付ける（未設定なら `build()` で `NullPointerException`）。
- Javadoc はフィールドに書く。Lombok が生成するアクセサ / ビルダーメソッドへ複製される。

**5. 命名**

- 略語や whisper.cpp の C 名をそのまま Java の識別子にしない。読めば意味が分かる名前を付け、
  対応する C 名は Javadoc に併記する（例: `nThreads` → `threads`、`entropyThold` → `entropyThreshold`、
  `fullNSegments` → `segmentCount`、`TokenData.p` → `probability`）。
- 例外は **`private native` メソッド**。これは C++ のシンボル名と結びついているので whisper.cpp の
  関数名に近い名前のままにし、public のラッパー側で読みやすい名前を付ける。
- 単位はメソッド名・フィールド名に含める（`segmentStartCentiseconds`、`vadMinSpeechDurationMs`）。
- 生成・取得の動詞: 新しいネイティブオブジェクトを作るなら `create〜`、既存の値を読むなら名詞（`segmentText`）。

**6. その他**

- 公開 API のバイトコードターゲットは **Java 17**（`build.gradle` の `options.release = 17`）。
  ビルド自体は Java 25 で行うが、利用側に 17 より新しい JDK を要求しない。
- **既存クラスを record に変換しない。** フィールドアクセス `x.field` がアクセサ `x.field()` に
  変わるため、公開 API の破壊的変更になる。新規の値クラスは record でよい（`Segment` など）。
- 波括弧は次の行（Allman）。`if(` `for(` `catch(` は括弧の前にスペースを入れない。インデントはタブ。
- **Javadoc は日本語で書く。**
  - `jp.clip.whisper`（自作 API 層）: 使い方の例を添える
  - `jp.clip.whisperjni`（JNI bridge 層）: 対応する whisper.cpp の関数名・構造体メンバー名を必ず併記する
    （whisper.cpp を更新するときの影響調査資料になる）
- **例外メッセージの文字列を変更しない。** テストが文字列一致で検証している
  （例: `"Index out of range"`, `"Unavailable pointer, object is closed"`）。
- テストの期待値（文字起こし結果の文字列・タイムスタンプ）は whisper.cpp のバージョンと
  `WhisperTranscriptionParams` の既定値（`beamSize = 2` など）に依存する。
  whisper.cpp 更新時にこれらが落ちたら、まず期待値のズレを疑う。

### JNI / C++

Java と C++ は次の 3 種類の文字列で結びついている。**どれも IDE のリネームでは追跡されない。**

| 結びつき | Java 側 | C++ 側（`jp_clip_whisperjni_WhisperJNI.cpp`） | 不一致時の症状 |
|---|---|---|---|
| シンボル名 | `native` 宣言 | `Java_jp_clip_whisperjni_WhisperJNI_<method>` | `UnsatisfiedLinkError` |
| クラス名 | `WhisperToken` `WhisperGrammar` `WhisperTranscriptionParams$VadParams` | `FindClass("jp/clip/whisperjni/...")`、`GetFieldID` の型シグネチャ | `NoClassDefFoundError` / `NoSuchFieldError` |
| フィールド名 | `WhisperTranscriptionParams` `WhisperContextParams` `NativeHandle.nativeId` の各フィールド | `readTranscriptionParams` / `readContextParams` / `applyGrammar` 内の `"threads"` 等 | `NoSuchFieldError`（クラッシュはしない） |

Java のパッケージ名を変えるときは、次の **4 箇所を必ず同時に**変える。

1. `package` 宣言とディレクトリ
2. `gradlew generateHeaders` でヘッダを再生成（`src/main/native/jni/` に出力される）
3. `.cpp` の `Java_<package>_*` シンボル名すべて
4. **`.cpp` 内の文字列リテラル** `"jp/clip/whisperjni/..."`（`FindClass` と `GetFieldID` の型シグネチャ）

`WhisperTranscriptionParams` のフィールド名を変えるときは `.cpp` の `readTranscriptionParams` も変える。
native メソッドを増減したときは 2 を実行し、`.cpp` に実装を足して、下のシンボル突き合わせを行う。

変更後は必ずシンボルの突き合わせで検証する。

```powershell
# Developer Command Prompt で
dumpbin /exports .\whisperjni-build\whisper-jni.dll | Select-String "Java_"
```

Java 側の native メソッド宣言の数と一致すること（現在 **24 個**）。

C++ 側の設計方針は `.cpp` 冒頭のコメントに書いてある（ハンドル表、例外の出し方、UTF-8 の扱い）。

### シェル / PowerShell スクリプト

- すべて `scripts/` に置く。ファイル名は kebab-case（`build-windows.ps1`）。
- **冒頭でプロジェクトルートへ `cd` する。** スクリプトはルートからの相対パスを前提にしている。
  - `.sh`: `cd "$(dirname "$0")/.." || exit 1`
  - `.ps1`: `Set-Location (Join-Path $PSScriptRoot "..")`
- 改行コードは `.gitattributes` で強制。`.sh` は LF、`.ps1` / `.cmd` / `.bat` は CRLF。
- **`.ps1` は UTF-8 BOM 付きで保存する。** PowerShell 5.1 で日本語が化けないため。
- スクリプトの失敗は必ず非ゼロ終了させる。`.ps1` では `$LASTEXITCODE` を明示的に確認する
  （`$ErrorActionPreference = 'Stop'` はネイティブコマンドの終了コードを見ない）。

---

## プロジェクト構成

```
whisper-jni-custom/
├── .github/workflows/            CI。main.yml（全 OS）と windows-natives.yml（Windows の DLL だけ。手動実行）
├── docs/                         引継ぎ資料など（handover-step5-speedup.md）
├── scripts/                      ビルド・モデル取得スクリプト
├── models/                       計測用モデルの置き場所（ggml-*.bin は gitignore）
├── lombok.config                 Lombok 設定
├── src/main/java/jp/clip/
│   ├── whisper/                  高水準 API（WhisperEngine / WhisperConfig / …）
│   └── whisperjni/               低レイヤ JNI bridge
├── src/main/native/
│   ├── jni/                      自作 JNI 実装 (.cpp / .h)
│   └── whisper/                  whisper.cpp submodule（v1.9.3 固定）
├── src/main/resources/<os>-<arch>/   ビルド済みネイティブの配置先（gitignore）
├── CMakeLists.txt
├── build.gradle / settings.gradle / gradle.properties
├── LICENSE  NOTICE               Apache-2.0（同梱ネイティブは MIT）
└── WINDOWS-BUILD-1.9.3.md        作業記録とロードマップ
```

- 2 層構成。利用側は原則 `jp.clip.whisper` だけを使う。`jp.clip.whisper` → `jp.clip.whisperjni` の
  依存は一方向で、逆方向の import は禁止。
- Maven 座標: `jp.clip:whisper-jni-custom:<version>`
- バージョン規約: `<whisper.cpp のバージョン>-<このラッパーのビルド番号>`（例 `1.9.3-1`）
- `settings.gradle` で `rootProject.name` を固定している。**消さないこと。**
  無いとフォルダ名が artifactId になり、座標がクローン場所に依存する。
- `.gitmodules` の submodule パスは変更しない。

---

## ビルドとテスト

前提: **JDK 25**、CMake 3.21+、C++ ツールチェーン（Windows は MSVC）。
Gradle はラッパー（9.7.1）が提供する。

```powershell
.\scripts\download-test-model.ps1
.\scripts\download-vad-model.ps1
Move-Item .\ggml-silero-v6.2.0.bin .\src\main\resources\ -Force
.\scripts\build-windows.ps1
.\gradlew.bat test
```

- jar に同梱して配布するときは `gradlew installNatives publishToMavenLocal`
  （`whisperjni-build\` → `src/main/resources/<os>-<arch>/`、`natives.list` を自動生成）
- ネイティブを作り直したら `build\` を消してから `scripts\build-windows.ps1`
  （`--fresh` を付けてあるが、念のため）
- 高速化の計測は `gradlew benchmark "-Pbench.audio=..." "-Pbench.models=..."`（`Benchmark.java` の Javadoc 参照）。
  モデルの取得は `scripts\download-model.ps1 <モデル名>` → `models\` に置かれる
- Gradle が `Could not initialize native services` で落ちる場合は
  `GRADLE_USER_HOME` を `C:\pr-work\.gradle-home` に向ける（詳細は `WINDOWS-BUILD-1.9.3.md`）

---

## 作業の進め方

- **1 項目 1 コミット。** 各コミットの完了条件は `gradlew test` が全件パスすること。
- 構造変更（ファイル移動・リネーム）は `git mv` を使う。履歴を追えるようにするため。
- 本家（GiviMAD / Jaffe2718）への追従は行わない方針。ただし Apache-2.0 の帰属表示は
  `NOTICE` で維持する。変更点を追記すること。
- 詳細な作業記録・検証結果・今後のロードマップは `WINDOWS-BUILD-1.9.3.md` を参照。
