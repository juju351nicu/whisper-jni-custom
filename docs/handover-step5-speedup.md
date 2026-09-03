# 引継ぎ資料: Step 5 高速化の計測と GPU 検証（別 PC で実施）

作成: 2026-09-03。対象リポジトリ: https://github.com/juju351nicu/whisper-jni-custom（`main`、`b7b5ad6` 以降）

この資料は、**開発 PC（i5-1335U / Iris Xe / ストレージ残 20 GB）では負荷試験とストレージ消費を避けたい**ため、
高速化の計測と GPU 検証を別の PC（またはネットカフェ）で行うためのものです。
この資料だけ読めば作業できるように、前提から手順まで一通り書いてあります。

---

## 1. 目的と判断基準

**目的**: transcribe-shell で使う設定（モデル・スレッド数・VAD・GPU）を、実測に基づいて決める。

**指標**: RTF（Real Time Factor）= 処理時間 ÷ 音声長。1.0 未満なら実時間より速い。

**暫定目標**: `large-v3-turbo-q5_0` で **RTF 1.0 未満**（10 分の録音が 10 分以内）。
届かなければ `small-q5_1` で RTF 0.5 未満を次善とする。精度は計測ツールの `text` 列で目視確認し、
速くても日本語が崩れる条件は不採用。

**持ち帰るもの**: `build/benchmark/benchmark-*.csv` と、採用する条件（下の 7 章の表を埋める）。
それを `WINDOWS-BUILD-1.9.3.md` の「5-4 計測結果」に貼り、transcribe-shell の設定に反映する。

---

## 2. 作業 PC に必要なもの

### 方式 A: ビルド済み DLL を GitHub Actions から取得する（推奨。ネットカフェでも可能）

Visual Studio も Vulkan SDK も入れない。必要なのは次の 3 つだけ。管理者権限も不要。

| もの | 入れ方 | サイズ |
|---|---|---|
| Git | https://git-scm.com/download/win の Portable 版（zip を展開するだけ） | 300 MB |
| JDK 25 | https://adoptium.net/ の Temurin 25 **zip 版**（インストーラ不要）。展開して `JAVA_HOME` を設定 | 350 MB |
| ネイティブ DLL | GitHub の Actions タブ → `Windows natives` → `Run workflow` → 完了後、Artifacts から `windows-x64-OFF-natives`（CPU 版）と `windows-x64-ON-natives`（Vulkan 版）をダウンロード | 各 30 MB |

Gradle 本体はラッパーが自動で取得する（約 150 MB、`GRADLE_USER_HOME` に入る）。
モデルは 3 本で約 1.2 GB。**合計 2.5 GB 程度**あれば足りる。

> GPU で試すには、その PC に Vulkan 対応 GPU とドライバが要る。`vulkaninfo` が無くても、
> `C:\Windows\System32\vulkan-1.dll` があれば大抵は使える。

### 方式 B: その PC でネイティブをビルドする

VS 2026 Community + C++ ワークロード（約 9 GB）、CMake 4.x、Vulkan SDK（約 1.5 GB）が要る。
手順は `WINDOWS-BUILD-1.9.3.md` §3 と同じ。Vulkan 版は環境変数 `VULKAN=ON` を付けて
`scripts\build-windows.ps1` を実行する。ネットカフェでは現実的でないので、方式 A を使う。

---

## 3. セットアップ手順（方式 A）

```powershell
# 0. JDK と Git を展開したパスに合わせて設定（例）
$env:JAVA_HOME = "D:\tools\jdk-25"
$env:Path = "$env:JAVA_HOME\bin;D:\tools\PortableGit\bin;$env:Path"
$env:GRADLE_USER_HOME = "D:\work\.gradle-home"       # 書き込みできる場所ならどこでもよい

# 1. リポジトリ取得（submodule も。合計 300 MB 程度）
cd D:\work
git clone --recurse-submodules https://github.com/juju351nicu/whisper-jni-custom.git
cd whisper-jni-custom

# 2. GitHub Actions の Artifacts（zip）を展開して配置
#    windows-x64-OFF-natives.zip → .\natives-cpu\    （ggml.dll, whisper.dll, whisper-jni.dll ...）
#    windows-x64-ON-natives.zip  → .\natives-vulkan\ （上に加えて ggml-vulkan.dll）
Expand-Archive ~\Downloads\windows-x64-OFF-natives.zip .\natives-cpu
Expand-Archive ~\Downloads\windows-x64-ON-natives.zip  .\natives-vulkan
Get-ChildItem .\natives-cpu, .\natives-vulkan          # DLL が 6〜7 個ずつあること

# 3. テスト用モデルと VAD モデル（テストを回すなら。計測だけなら省略可）
.\scripts\download-test-model.ps1
.\scripts\download-vad-model.ps1
Move-Item .\ggml-silero-v6.2.0.bin .\src\main\resources\ -Force

# 4. 計測用モデル（models\ に入る。ggml-*.bin は .gitignore 済み）
.\scripts\download-model.ps1 small
.\scripts\download-model.ps1 small-q5_1
.\scripts\download-model.ps1 large-v3-turbo-q5_0

# 5. 動作確認: CPU 版ネイティブでテストが通ること（48 tests）
Copy-Item .\natives-cpu\*.dll .\whisperjni-build\ -Force     # テストは whisperjni-build\ から読む
.\gradlew.bat test
```

> `Could not initialize native services` で Gradle が落ちたら、`GRADLE_USER_HOME` を短いパスの
> 別の場所に変える（`WINDOWS-BUILD-1.9.3.md` §5 参照）。

---

## 4. 計測用音声

日本語の WAV を 1 本用意する。**3〜10 分**が扱いやすい（短いと差が出ず、長いと 1 条件に何分もかかる）。
形式は Java 標準で読めるもの（16 kHz モノラルでなくても自動変換する）。MP3 は読めないので、
必要なら ffmpeg で変換する: `ffmpeg -i in.mp3 -ar 16000 -ac 1 out.wav`

会議録音が無ければ、ニュース原稿などを自分で読んで録音したもので構わない。
以下 `C:\audio\sample-ja.wav` とする。

---

## 5. 計測手順

計測ツールは `src/test/java/jp/clip/whisper/Benchmark.java`、起動は Gradle の `benchmark` タスク。
引数は `-Pbench.<名前>=<値>`。

| 引数 | 意味 | 既定 |
|---|---|---|
| `bench.audio` | 音声ファイル（必須） | — |
| `bench.models` | モデルのパス。`;` 区切りで複数（必須） | — |
| `bench.threads` | スレッド数。`,` 区切りで複数。0 は whisper.cpp 既定（4） | `0` |
| `bench.vad` | `off` / `on` / `both` | `off` |
| `bench.strategy` | `GREEDY` / `BEAM_SEARCH` / `both` | `GREEDY` |
| `bench.repeat` | 同条件の繰り返し回数。min（実力値）と median（実運用の目安）を出す | `2` |
| `bench.natives` | ネイティブ DLL のディレクトリ | `whisperjni-build` |
| `bench.language` | 言語 | `ja` |

結果は標準出力の表と `build\benchmark\benchmark-<日時>.csv` に出る。

**注意**: ノート PC は AC 電源に接続し、他の重い処理は止める。熱で性能が落ちるので、
おかしな値が出たら数分置いて再実行する。

### 5-1. ベースライン（現在の transcribe-shell 相当: small、既定スレッド、VAD なし、CPU）

```powershell
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" "-Pbench.models=models\ggml-small.bin" "-Pbench.natives=natives-cpu" "-Pbench.repeat=3"
```

### 5-2. CPU での最適化（モデル × スレッド × VAD を 1 コマンドで）

3 モデル × 4 スレッド設定 × VAD あり/なし = 24 条件。10 分の音声なら 1〜2 時間かかる。
時間が無ければ `bench.threads` を `0,8` に減らす。

```powershell
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" `
  "-Pbench.models=models\ggml-small.bin;models\ggml-small-q5_1.bin;models\ggml-large-v3-turbo-q5_0.bin" `
  "-Pbench.threads=0,4,8,12" "-Pbench.vad=both" "-Pbench.natives=natives-cpu" "-Pbench.repeat=2"
```

スレッド数はその PC の**物理コア数**（`Get-CimInstance Win32_Processor | Select NumberOfCores`）を
候補に入れる。P コア / E コア混在（Intel 12 世代以降）なら P コア数も候補に入れる。

### 5-3. GPU（Vulkan）

`natives-vulkan` に切り替えるだけ。`useGpu` は既定で true。

```powershell
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" `
  "-Pbench.models=models\ggml-small.bin;models\ggml-large-v3-turbo-q5_0.bin" `
  "-Pbench.vad=both" "-Pbench.natives=natives-vulkan" "-Pbench.repeat=2"
```

GPU が実際に使われたかは出力冒頭の `system :` 行と、`-Pbench.` を付けずにログレベルを上げたときの
whisper.cpp のログ（`ggml_vulkan: Found 1 Vulkan devices` / `use gpu = 1`）で確認する。
ログを見るには `build.gradle` の `benchmark` タスクの `defaultLogLevel` を一時的に `info` にする。

Vulkan 版で **エラーになる・結果の文字列が CPU 版と大きく違う・遅い**場合はそのドライバとの相性なので、
無理に追わず「この GPU では不採用」として記録する。

### 5-4. BEAM_SEARCH との精度比較（任意）

```powershell
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\sample-ja.wav" "-Pbench.models=models\ggml-large-v3-turbo-q5_0.bin" "-Pbench.strategy=both" "-Pbench.natives=natives-cpu"
```

`text` 列（先頭 40 文字）だけでは足りないので、精度をきちんと見たいときは transcribe-shell で
両方の全文を出して比べる。

---

## 6. 結果の読み方と判断

1. **まずモデル**: `large-v3-turbo-q5_0` の RTF が目標（1.0）を切っていれば、それを採用（精度は small より明確に良い）。
   切らなければ `small-q5_1`。
2. **次に VAD**: 会議録音のように無音が多い音声では `on` が大きく効く。`text` 列で語頭・語尾の欠けが無いか確認。
3. **スレッド数**: 一番速い値を採用。ただし差が 10% 以内なら小さい方（他の処理と共存しやすい）。
4. **GPU**: CPU 最良値に対して **1.5 倍以上**速く、かつ結果文字列が CPU と同等なら採用。
   それ未満なら DLL 配布の手間に見合わないので CPU 版のまま。

---

## 7. 持ち帰り用テンプレート

```
計測 PC: （CPU / GPU / メモリ / OS）
音声: （ファイル名 / 長さ / 内容）
日付:

| 条件 | model | threads | vad | natives | minRTF | medRTF | 精度メモ |
|---|---|---|---|---|---|---|---|
| ベースライン | small | 0 | off | cpu | | | |
| 採用候補 1 | | | | | | | |
| 採用候補 2 | | | | | | | |

採用: model=            threads=      vad=       natives=
理由:
```

`build\benchmark\*.csv` を USB か GitHub Gist で開発 PC へ持ち帰り、`WINDOWS-BUILD-1.9.3.md` の 5-4 に貼る。

---

## 8. 開発 PC 側で後からやること

- 採用した設定を transcribe-shell の `WhisperConfig.builder()` に反映する
  （`model` / `threads` / `vadEnabled` / `nativeLibraryDirectory`）
- GPU を採用する場合: Vulkan 版 DLL を transcribe-shell からどう配布するか決める
  （jar 同梱の `windows-x64` は CPU 版のまま、GPU 版は別ディレクトリに置いて
  `nativeLibraryDirectory` で指す、が最も単純）
- この資料の結果を `WINDOWS-BUILD-1.9.3.md` に反映し、この資料は `docs/` に残す

---

## 付録: よくある失敗

| 症状 | 原因と対処 |
|---|---|
| `UnsatisfiedLinkError: ... whisper-jni.dll` | DLL のディレクトリ指定ミス、または Vulkan 版なのに Vulkan ランタイムが無い。`natives-cpu` で動くか先に確認 |
| `音声ファイルが見つかりません` | パスの誤り。PowerShell では `"-Pbench.audio=..."` 全体を引用符で囲む |
| `対応していない音声ファイル形式です` | MP3 など。ffmpeg で WAV に変換 |
| 同じ条件で値が 2 倍以上ぶれる | 熱・電源。AC 接続、数分待って `bench.repeat=3` で再実行 |
| Vulkan 版で結果が空・文字化け | ドライバ相性。CPU 版に戻して記録だけ残す |
| `Could not initialize native services` | Gradle のキャッシュ破損。`GRADLE_USER_HOME` を別の短いパスへ |
