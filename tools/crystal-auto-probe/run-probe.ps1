param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Name,
    [Parameter(Mandatory)][string]$WorldPath,
    [Parameter(Mandatory)][string]$JavaHome,
    [switch]$Baseline
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$source=(Resolve-Path -LiteralPath $WorldPath).Path
if (!(Test-Path -LiteralPath (Join-Path $source 'level.dat'))) { throw 'Provide a disposable test world containing level.dat' }
if (!(Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'Provide a Java 21 JDK' }
$lab=[IO.Path]::GetFullPath((Join-Path $repo '..\crystal-auto-lab'))
$run=Join-Path $lab $Name
if (Test-Path -LiteralPath $run) { throw 'Choose a new run name to preserve evidence' }
New-Item -ItemType Directory -Force -Path "$run\saves" | Out-Null
Copy-Item -LiteralPath $source -Destination "$run\saves\ProbeWorld" -Recurse
[IO.File]::WriteAllText("$run\options.txt","onboardAccessibility:false`nrenderDistance:4`nsimulationDistance:5`npauseOnLostFocus:false`nmaxFps:60`n")
$env:JAVA_HOME=$JavaHome
Push-Location -LiteralPath $repo
try {
    & .\gradlew.bat --offline --console=plain -I tools/crystal-auto-probe/probe.gradle crystalAutoProbeJar *> "$lab\$Name-fixture.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
    $expect=if ($Baseline) { 'false' } else { 'true' }
    & .\gradlew.bat --offline --console=plain -PgzwDevelopmentBuild=true -I tools/crystal-auto-probe/probe.gradle "-PprobeRun=$Name" "-PexpectCrystals=$expect" -Pextended=true runClient *> "$lab\$Name.log"
    if ($LASTEXITCODE -ne 0) { throw 'Client launch failed' }
} finally { Pop-Location }
if ((Test-Path -LiteralPath "$run\failure.txt") -or !(Test-Path -LiteralPath "$run\complete.txt")) {
    throw "Probe did not complete; inspect $lab\$Name.log"
}
Get-Content -LiteralPath "$run\complete.txt"
Get-Content -LiteralPath "$run\results.json"
