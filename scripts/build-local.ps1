$ErrorActionPreference = 'Stop'

$jdkCandidates = @(
    'C:\Program Files\Android\Android Studio\jbr',
    'C:\Program Files\Eclipse Adoptium\jdk-21*',
    'C:\Program Files\Java\jdk-21*',
    "$env:USERPROFILE\.jdks\*"
)

function Resolve-JavaHome {
    param([string[]]$Candidates)

    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }

    foreach ($candidate in $Candidates) {
        $expanded = Get-ChildItem -Path $candidate -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
        foreach ($dir in $expanded) {
            $javaExe = Join-Path $dir.FullName 'bin\java.exe'
            if (Test-Path $javaExe) {
                return $dir.FullName
            }
        }

        if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
            return $candidate
        }
    }

    return $null
}

$javaHome = Resolve-JavaHome -Candidates $jdkCandidates
if (-not $javaHome) {
    Write-Error 'Khong tim thay JDK. Cai Android Studio (JBR) hoac JDK 21 roi chay lai.'
}

$env:JAVA_HOME = $javaHome
if (-not ($env:Path -split ';' | Where-Object { $_ -eq "$javaHome\bin" })) {
    $env:Path = "$javaHome\bin;$env:Path"
}

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
& "$PSScriptRoot\..\gradlew.bat" :app:compileDebugKotlin
