$ErrorActionPreference = 'Stop'

$ProjectDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$PropertiesPath = Join-Path $ProjectDir '.mvn\wrapper\maven-wrapper.properties'

if (-not (Test-Path $PropertiesPath)) {
    throw "Missing $PropertiesPath"
}

$props = ConvertFrom-StringData (Get-Content -Raw $PropertiesPath)
$distributionUrl = $props.distributionUrl
if (-not $distributionUrl) {
    throw 'distributionUrl is not configured'
}

$archiveName = Split-Path $distributionUrl -Leaf
$mavenVersion = $archiveName -replace '^apache-maven-', '' -replace '-bin\.zip$', ''
$mavenUserHome = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $HOME '.m2' }
$installRoot = Join-Path $mavenUserHome "wrapper\dists\apache-maven-$mavenVersion"
$mavenHome = Join-Path $installRoot "apache-maven-$mavenVersion"
$mavenCmd = Join-Path $mavenHome 'bin\mvn.cmd'

if (-not (Test-Path $mavenCmd)) {
    New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("maven-wrapper-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    try {
        $archive = Join-Path $tempDir $archiveName
        $checksumFile = "$archive.sha512"

        Write-Host "Downloading Apache Maven $mavenVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri $distributionUrl -OutFile $archive
        Invoke-WebRequest -UseBasicParsing -Uri "$distributionUrl.sha512" -OutFile $checksumFile

        $expected = (Get-Content -Raw $checksumFile).Trim().ToLowerInvariant()
        $actual = (Get-FileHash -Algorithm SHA512 $archive).Hash.ToLowerInvariant()
        if ($expected -ne $actual) {
            throw 'Maven SHA-512 verification failed'
        }

        if (Test-Path $mavenHome) {
            Remove-Item -Recurse -Force $mavenHome
        }
        Expand-Archive -Path $archive -DestinationPath $installRoot -Force
    }
    finally {
        if (Test-Path $tempDir) {
            Remove-Item -Recurse -Force $tempDir
        }
    }
}

& $mavenCmd @args
exit $LASTEXITCODE
