$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$jar = Join-Path (Get-Location) "gradle/wrapper/gradle-wrapper.jar"
New-Item -ItemType Directory -Force -Path (Split-Path $jar) | Out-Null
$url = "https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
Invoke-WebRequest -Uri $url -OutFile $jar
$actual = (& git hash-object $jar).Trim()
$expected = "a4b76b9530d66f5e68d973ea569d8e19de379189"
if ($actual -ne $expected) {
    Remove-Item $jar -Force
    throw "Gradle wrapper JAR did not match official Git blob checksum."
}
Write-Host "Verified official Gradle wrapper JAR restored."
