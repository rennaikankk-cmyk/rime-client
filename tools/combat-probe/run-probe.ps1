param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Name,
    [Parameter(Mandatory)][string]$WorldPath,
    [ValidateSet('SPEAR','MACE','MXS','ARMOR')][string]$Mode='SPEAR',
    [ValidateSet('true','false')][string]$Armor='false',
    [ValidateSet('true','false')][string]$Air='false',
    [ValidateSet('true','false')][string]$High='false',
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
[IO.File]::WriteAllText("$run\options.txt","onboardAccessibility:false`nrenderDistance:4`nsimulationDistance:4`npauseOnLostFocus:false`n")
[IO.File]::WriteAllText("$run\guizhouware\telemetry.json",'{"enabled":false}')
$env:JAVA_HOME=$JavaHome
Push-Location -LiteralPath $repo
try {
    & .\gradlew.bat -I "$base\probe.gradle" "-PprobeRun=$Name" "-PprobeMode=$Mode" "-PprobeArmor=$Armor" "-PprobeAir=$Air" "-PprobeHigh=$High" runClient --console=plain *> "$base\$Name.log"
    $result=$LASTEXITCODE
} finally { Pop-Location }
if($result -ne 0){throw "Probe failed; inspect $base\$Name.log"}
Select-String -LiteralPath "$base\$Name.log" -Pattern 'PROBE_START|PROBE_DAMAGE|PROBE_ARMOR|PROBE_DONE' | ForEach-Object Line
