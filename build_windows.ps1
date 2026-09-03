# whisper-jni ネイティブライブラリ (Windows / MSVC) ビルドスクリプト
$ErrorActionPreference = 'Stop'

$vulkanEnabled = if ($env:VULKAN) { $env:VULKAN } else { 'OFF' }
$TMP_DIR    = "tmp-build"
$TARGET_DIR = "whisperjni-build"

function Resolve-CMake {
    # まず PATH を見る
    $cmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # PATH に無ければ Visual Studio 同梱の CMake を探す
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $vsPaths = & $vswhere -latest -prerelease -products * -property installationPath
        foreach ($vsPath in @($vsPaths)) {
            if (-not $vsPath) { continue }
            $candidate = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

$cmake = Resolve-CMake
if (-not $cmake) {
    Write-Host ""
    Write-Host "[ERROR] cmake が見つかりません。次のいずれかで導入してください。" -ForegroundColor Red
    Write-Host ""
    Write-Host "  1) winget install --id Kitware.CMake -e" -ForegroundColor Yellow
    Write-Host "     PATH に入るので確実です（推奨）。導入後は PowerShell を開き直してください。"
    Write-Host ""
    Write-Host "  2) Visual Studio Installer で「C++ によるデスクトップ開発」ワークロードを追加" -ForegroundColor Yellow
    Write-Host "     CMake が同梱されます。PATH には入りませんが本スクリプトが自動検出します。"
    Write-Host ""
    exit 1
}

Write-Host "[INFO] cmake: $cmake" -ForegroundColor Cyan
& $cmake --version | Select-Object -First 1

if ($vulkanEnabled -eq "ON") {
    Write-Host "[INFO] Building with Vulkan" -ForegroundColor Cyan
}

New-Item -Path $TARGET_DIR -ItemType Directory -Force | Out-Null

& $cmake -B build -DCMAKE_BUILD_TYPE=Release "-DCMAKE_INSTALL_PREFIX=$TMP_DIR" -DGGML_STATIC=1 -DWHISPER_BUILD_IS_DEV=OFF "-DGGML_VULKAN=$vulkanEnabled"
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] cmake configure が失敗しました" -ForegroundColor Red; exit $LASTEXITCODE }

& $cmake --build build --config Release
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] cmake build が失敗しました" -ForegroundColor Red; exit $LASTEXITCODE }

& $cmake --install build
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] cmake install が失敗しました" -ForegroundColor Red; exit $LASTEXITCODE }

if (-not (Test-Path $TMP_DIR)) {
    Write-Host "[ERROR] $TMP_DIR が生成されていません。cmake --install が何も出力していません。" -ForegroundColor Red
    exit 1
}

# tmp-build 配下の DLL を whisperjni-build へ集約する
Write-Host "[INFO] Recursively copying all DLLs from $TMP_DIR to $TARGET_DIR" -ForegroundColor Cyan
$dlls = @(Get-ChildItem -Path $TMP_DIR -Filter "*.dll" -Recurse -File)
foreach ($dll in $dlls) {
    Copy-Item -Path $dll.FullName -Destination $TARGET_DIR -Force
    Write-Host "Copied: $($dll.Name)"
}

if ($dlls.Count -eq 0) {
    Write-Host "[ERROR] DLL が 1 つもコピーされませんでした。$TMP_DIR の中身を確認してください。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[SUCCESS] $($dlls.Count) DLL(s) copied to $TARGET_DIR" -ForegroundColor Green
Get-ChildItem $TARGET_DIR | Select-Object Name, Length | Format-Table
