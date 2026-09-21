$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation first.' }
Push-Location $repoRoot
try {
    & ./gradlew.bat writeAuraFixtureClasspath -PgzwDevelopmentBuild=true -I tools/aura-fixture/init.gradle --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Fixture classpath build failed.' }
    $fixtureClasses = Join-Path $repoRoot 'build/aura-fixture/classes'
    New-Item -ItemType Directory -Force $fixtureClasses | Out-Null
    $fixtureClasspath = Get-Content -Raw build/aura-fixture/classpath.txt
    & "$env:JAVA_HOME/bin/javac.exe" -proc:none -encoding UTF-8 -cp $fixtureClasspath -d $fixtureClasses tools/aura-fixture/AuraFixture.java
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
    Copy-Item -LiteralPath tools/aura-fixture/fabric.mod.json -Destination $fixtureClasses
    & "$env:JAVA_HOME/bin/jar.exe" cf build/aura-fixture/fixture.jar -C $fixtureClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Fixture packaging failed.' }
    & ./gradlew.bat runClient -PgzwDevelopmentBuild=true -PauraFixtureRun -I tools/aura-fixture/init.gradle --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Aura integration tests failed.' }
} finally {
    # Restore the ordinary generated flag even after a failing development-only test.
    & ./gradlew.bat compileJava --console=plain
    Pop-Location
}
