param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Name,
    [Parameter(Mandatory)][string]$WorldPath,
    [ValidateSet('SPEAR','MACE','MXS','ARMOR','UI','DEFENSE')][string]$Mode='SPEAR',
    [ValidateSet('true','false')][string]$Armor='false',
    [ValidateSet('true','false')][string]$Air='false',
    [ValidateSet('true','false')][string]$High='false',
    [ValidateSet('true','false')][string]$Ground='false',
    [int]$Ticks=800,
    [ValidateSet('HOTBAR','INVENTORY','EMPTY','EMPTY_INVENTORY')][string]$Gear='HOTBAR',
    [double]$Height=10,
    [ValidateSet('true','false')][string]$Terrain='true',
    [ValidateSet('true','false')][string]$RequireDamage='false',
    [ValidateSet('true','false')][string]$Adaptive='false',
    [string]$FontDirectory,
    [string]$JavaHome=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
$base=$PSScriptRoot
$repo=[IO.Path]::GetFullPath((Join-Path $base '..\..'))
$world=(Resolve-Path -LiteralPath $WorldPath).Path
if(!(Test-Path -LiteralPath (Join-Path $world 'level.dat'))){throw 'WorldPath must be a Minecraft world directory'}
if(!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))){throw 'Provide a Java 21 JDK with -JavaHome or JAVA_HOME'}
$run=Join-Path $base "runs\$Name"
if(Test-Path -LiteralPath $run){throw 'Use a fresh run name; previous evidence is never overwritten'}
New-Item -ItemType Directory -Force -Path "$run\saves","$run\guizhouware" | Out-Null
Copy-Item -LiteralPath $world -Destination "$run\saves\ProbeWorld" -Recurse
if($FontDirectory){
    $fontSource=(Resolve-Path -LiteralPath $FontDirectory).Path
    New-Item -ItemType Directory -Force -Path "$run\guizhouware\fonts" | Out-Null
    Get-ChildItem -LiteralPath $fontSource -File -Filter '*.ttf' | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination "$run\guizhouware\fonts" }
}
[IO.File]::WriteAllText("$run\options.txt","onboardAccessibility:false`nrenderDistance:4`nsimulationDistance:5`npauseOnLostFocus:false`nguiScale:2`ntutorialStep:none`nmaxFps:90`n")
# This development probe has no launcher capability, so automatic telemetry cannot upload.
$env:JAVA_HOME=$JavaHome
Push-Location -LiteralPath $repo
try {
    & .\gradlew.bat --offline -I "$base\probe.gradle" runtimeProbeJar --console=plain *> "$base\$Name-build.log"
    if($LASTEXITCODE -ne 0){throw "Probe fixture build failed: $base\$Name-build.log"}
    & .\gradlew.bat --offline -PgzwDevelopmentBuild=true -I "$base\probe.gradle" "-PprobeRun=$Name" "-PprobeMode=$Mode" "-PprobeArmor=$Armor" "-PprobeAir=$Air" "-PprobeHigh=$High" "-PprobeGround=$Ground" "-PprobeTicks=$Ticks" "-PprobeGear=$Gear" "-PprobeHeight=$Height" "-PprobeTerrain=$Terrain" "-PprobeRequireDamage=$RequireDamage" "-PprobeAdaptive=$Adaptive" runClient --console=plain *> "$base\$Name.log"
    $result=$LASTEXITCODE
} finally { Pop-Location }
if($result -ne 0){throw "Probe failed; inspect $base\$Name.log"}
if($Mode -eq 'UI' -and (!(Test-Path -LiteralPath "$run\ui-complete.txt") -or (Test-Path -LiteralPath "$run\ui-failure.txt"))){throw 'UI verification did not complete'}
if($Mode -eq 'DEFENSE' -and (!(Test-Path -LiteralPath "$run\defense-complete.txt") -or (Test-Path -LiteralPath "$run\defense-failure.txt"))){throw 'Defense verification did not complete'}
Select-String -LiteralPath "$base\$Name.log" -Pattern 'PROBE_START|PROBE_DAMAGE|PROBE_ARMOR|PROBE_DONE' | ForEach-Object Line
