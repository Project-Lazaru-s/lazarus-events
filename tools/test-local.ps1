param(
    [string]$Instance = (Join-Path $env:USERPROFILE 'curseforge\minecraft\Instances\Project Lazarus - Alpha 1.4.5'),
    [string]$Jdk = $env:JAVA_HOME,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $Jdk) {
    $portable = Get-ChildItem -LiteralPath (Join-Path (Split-Path $projectRoot -Parent) '.local-tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portable) { $Jdk = $portable.FullName }
}
if (-not $Jdk -or -not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) {
    throw 'Informe -Jdk com a pasta de um JDK 17 (ou configure JAVA_HOME).'
}
$dependencies = @{
    'lazarusnpcs-1.16.0.jar' = 'lazarusnpcs-1.16.0.jar'
    'apocalypsenow-3.0.5.jar' = 'apocalypsenow-3.0.5-forge-1.20.1.jar'
    'curios-5.14.1.jar' = 'curios-forge-5.14.1+1.20.1.jar'
    'tacz-1.1.8.jar' = 'tacz-1.20.1-1.1.8-hotfix2.jar'
    'dyairdrop-1.0.0.jar' = 'dyairdrop-1.0.0-1.20.1-hotfix3.0.jar'
    'geckolib-4.8.4.jar' = 'geckolib-forge-1.20.1-4.8.4.jar'
}
$libs = Join-Path $projectRoot 'libs'
New-Item -ItemType Directory -Path $libs -Force | Out-Null
foreach ($name in $dependencies.Keys) {
    $destination = Join-Path $libs $name
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath (Join-Path (Join-Path $Instance 'mods') $dependencies[$name]) -Destination $destination
    }
}
$previousJava = $env:JAVA_HOME
$previousGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $Jdk
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
    Push-Location $projectRoot
    try {
        $gradleArgs = @('build', '--console=plain')
        if (-not $BuildOnly) { $gradleArgs += @('runGameTestServer', '-x', 'downloadAssets') }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle terminou com codigo $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousGradle
}
