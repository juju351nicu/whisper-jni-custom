# CLAUDE.md — whisper-jni-custom 作業ルール

このリポジトリで作業するときの規約です。Claude Code / Cowork は自動で読み込みます。
人間が読む前提でも書いています。

---

## コーディングルール

### Java

**`var`（ローカル変数の型推論）を使わない。必ず明示的な型を書く。**
人間が読んだときに型がすぐ分からないのを避けるためです。

```java
// NG
var ctx = whisper.init(model);
var params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);

// OK
WhisperContext ctx = whisper.init(model);
WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
```

その他:

- 公開 API のバイトコードターゲットは **Java 17**（`build.gradle` の `options.release = 17`）。
  ビルド自体は Java 25 で行うが、利用側に 17 より新しい JDK を要求しない。
- **既存クラスを record に変換しない。** フィールドアクセス `x.field` がアクセサ `x.field()` に
  変わるため、公開 API の破壊的変更になる。
- **Javadoc は日本語で書く。**
  - `jp.clip.whisper`（自作 API 層）: 使い方の例を添える
  - `jp.clip.whisperjni`（JNI bridge 層）: 対応する whisper.cpp の関数名を必ず併記する
    （whisper.cpp を更新するときの影響調査資料になる）
- **例外メッセージの文字列を変更しない。** テストが文字列一致で検証している
  （例: `"Index out of range"`, `"Unavailable pointer, object is closed"`）。
- テストの期待値（文字起こし結果の文字列・タイムスタンプ）は whisper.cpp のバージョンに
  依存する。whisper.cpp 更新時にこれらが落ちたら、まず期待値のズレを疑う。

### JNI / C++

Java のパッケージ名を変えるときは、次の **4 箇所を必ず同時に**変える。

1. `package` 宣言とディレクトリ
2. `gradlew generateHeaders` でヘッダを再生成（`src/main/native/jni/` に出力される）
3. `.cpp` の `Java_<package>_*` シンボル名すべて
4. **`.cpp` 内の文字列リテラル** `FindClass("jp/clip/whisperjni/TokenData")`

**4 が最重要。** IDE のリネーム機能では検出されない文字列リテラルなので、
忘れるとビルドは通るのに `getTokens()` を呼んだ瞬間に落ちる。

変更後は必ずシンボルの突き合わせで検証する。

```powershell
# Developer Command Prompt で
dumpbin /exports .\whisperjni-build\whisper-jni.dll | Select-String "Java_"
```

Java 側の native メソッド宣言の数と一致すること（現在 27 個）。

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
├── scripts/                      ビルド・モデル取得スクリプト
├── src/main/java/jp/clip/
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

- ネイティブを作り直したら `build\` を消してから `scripts\build-windows.ps1`
  （`--fresh` を付けてあるが、念のため）
- Gradle が `Could not initialize native services` で落ちる場合は
  `GRADLE_USER_HOME` を `C:\pr-work\.gradle-home` に向ける（詳細は `WINDOWS-BUILD-1.9.3.md`）

---

## 作業の進め方

- **1 項目 1 コミット。** 各コミットの完了条件は `gradlew test` が全件パスすること。
- 構造変更（ファイル移動・リネーム）は `git mv` を使う。履歴を追えるようにするため。
- 本家（GiviMAD / Jaffe2718）への追従は行わない方針。ただし Apache-2.0 の帰属表示は
  `NOTICE` で維持する。変更点を追記すること。
- 詳細な作業記録・検証結果・今後のロードマップは `WINDOWS-BUILD-1.9.3.md` を参照。
