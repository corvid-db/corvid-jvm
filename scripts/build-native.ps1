# build-native.ps1 -- compile the JNI shim (native\corvid_jni.c) against
# the fetched engine artifacts in deps\current for Windows (MSVC), into
# build\native\, next to a copy of corvid.dll the shim loads. macOS/Linux:
# build-native.sh.
#
# Requirements: a MSVC environment (run from a Developer Command Prompt or
# via an action that calls vcvars) and JAVA_HOME pointing at a JDK. Run
# .\fetch.ps1 first.

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $Root

if (-not (Test-Path "deps\current\corvid.h")) {
    Write-Error "build-native: deps\current\corvid.h missing -- run .\fetch.ps1 first"
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "include\jni.h"))) {
    Write-Error "build-native: JAVA_HOME must point at a JDK (need include\jni.h)"
}

New-Item -ItemType Directory -Force -Path "build\native" | Out-Null

Write-Host "build-native: compiling native\corvid_jni.c -> build\native\corvidjni.dll"
& cl /nologo /O2 /LD /W4 `
    "/I$($env:JAVA_HOME)\include" `
    "/I$($env:JAVA_HOME)\include\win32" `
    "/Ideps\current" `
    native\corvid_jni.c `
    /link deps\current\corvid.dll.lib `
    /OUT:build\native\corvidjni.dll
if ($LASTEXITCODE -ne 0) { Write-Error "build-native: cl failed ($LASTEXITCODE)" }

Copy-Item "deps\current\corvid.dll" "build\native\corvid.dll" -Force
# Intermediates cl drops beside the output; keep the tree tidy.
Remove-Item "build\native\corvidjni.obj", "build\native\corvidjni.lib", `
    "build\native\corvidjni.exp" -ErrorAction SilentlyContinue
Write-Host "build-native: done (build\native\corvidjni.dll + corvid.dll)"
