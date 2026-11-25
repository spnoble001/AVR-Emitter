param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @("test")
)
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-25"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_OPTS = "-Xmx256m"
$mvn = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
if (-not (Test-Path $mvn)) {
    throw "No se encontro mvn.cmd en $mvn"
}
Push-Location $PSScriptRoot
try {
    & $mvn -o @MavenArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
