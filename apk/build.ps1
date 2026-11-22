# 作业哨兵 APK 构建脚本（不依赖 Gradle：aapt2 -> javac -> d8 -> zipalign -> apksigner）
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk   = if ($env:JAVA_HOME)    { $env:JAVA_HOME }    else { 'D:\tools\jdk17' }
$sdk   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\android-sdk' }

# 兼容解压后的子目录
if (-not (Test-Path "$jdk\bin\javac.exe")) {
    $cand = Get-ChildItem $jdk -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Select-Object -First 1
    if ($cand) { $jdk = $cand.FullName }
}

$bt        = "$sdk\build-tools\34.0.0"
$aapt2     = "$bt\aapt2.exe"
$zipalign  = "$bt\zipalign.exe"
$apksigner = "$bt\apksigner.bat"
$d8        = "$bt\d8.bat"
$androidJar = "$sdk\platforms\android-34\android.jar"

foreach ($p in @($aapt2, $zipalign, $apksigner, $d8, $androidJar, "$jdk\bin\javac.exe")) {
    if (-not (Test-Path $p)) { throw "缺少构建组件：$p" }
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

$build = Join-Path $root 'build'
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$build\gen", "$build\classes", "$build\dex" | Out-Null

Write-Host "[1/6] aapt2 compile 资源" -ForegroundColor Cyan
& $aapt2 compile --dir (Join-Path $root 'app\res') -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host "[2/6] aapt2 link 生成基础 APK 与 R.java" -ForegroundColor Cyan
& $aapt2 link -o "$build\base.apk" `
    -I $androidJar `
    --manifest (Join-Path $root 'app\AndroidManifest.xml') `
    --java "$build\gen" `
    -R "$build\res.zip" `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host "[3/6] javac 编译 Java 源码" -ForegroundColor Cyan
$srcs = @(Get-ChildItem (Join-Path $root 'app\src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath $androidJar -d "$build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

Write-Host "[4/6] d8 生成 classes.dex" -ForegroundColor Cyan
$classes = @(Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& $d8 --release --min-api 23 --lib $androidJar --output "$build\dex" $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host "[5/6] 打包 dex 并 zipalign" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = (Resolve-Path "$build\base.apk").Path
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Update')
$dex = (Resolve-Path "$build\dex\classes.dex").Path
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, $dex, 'classes.dex',
    [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
$zip.Dispose()

& $zipalign -f -p 4 "$build\base.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host "[6/6] 签名" -ForegroundColor Cyan
$ksDir = Join-Path $root 'keystore'
New-Item -ItemType Directory -Force $ksDir | Out-Null
$ks = Join-Path $ksDir 'debug.keystore'
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}

$outApk = Join-Path $root 'cx-todo.apk'
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out $outApk "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

& $apksigner verify --print-certs $outApk | Select-Object -First 4

$size = [Math]::Round((Get-Item $outApk).Length / 1KB, 1)
Write-Host "`n构建完成：$outApk  ($size KB)" -ForegroundColor Green