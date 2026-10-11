function Get-ProjectJdk {
    param([Parameter(Mandatory=$true)][string]$JavaPath)
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { return $null }
    $Probe = New-Object System.Diagnostics.Process
    $Probe.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $Probe.StartInfo.FileName = $JavaPath
    $Probe.StartInfo.Arguments = '-XshowSettings:properties -version'
    $Probe.StartInfo.UseShellExecute = $false
    $Probe.StartInfo.CreateNoWindow = $true
    $Probe.StartInfo.RedirectStandardOutput = $true
    $Probe.StartInfo.RedirectStandardError = $true
    try {
        if (-not $Probe.Start()) { return $null }
        $ProbeOutput = $Probe.StandardOutput.ReadToEndAsync()
        $ProbeError = $Probe.StandardError.ReadToEndAsync()
        if (-not $Probe.WaitForExit(10000)) {
            $Probe.Kill()
            $Probe.WaitForExit()
            return $null
        }
        if ($Probe.ExitCode -ne 0) { return $null }
        $Details = $ProbeOutput.Result + [Environment]::NewLine + $ProbeError.Result
        $HomeMatch = [regex]::Match($Details, '(?m)^\s*java\.home\s*=\s*(.+)$')
        $VersionMatch = [regex]::Match($Details, '(?m)^\s*java\.specification\.version\s*=\s*(\d+)\s*$')
        if (-not $HomeMatch.Success -or -not $VersionMatch.Success) { return $null }
        $DetectedHome = $HomeMatch.Groups[1].Value.Trim()
        $DetectedJava = Join-Path $DetectedHome 'bin/java.exe'
        $DetectedJavac = Join-Path $DetectedHome 'bin/javac.exe'
        if ([int]$VersionMatch.Groups[1].Value -ne 21 -or
            -not (Test-Path -LiteralPath $DetectedJava -PathType Leaf) -or
            -not (Test-Path -LiteralPath $DetectedJavac -PathType Leaf)) { return $null }
        return [pscustomobject]@{Home=$DetectedHome; JavaExe=$DetectedJava; MajorVersion=21}
    } catch {
        return $null
    } finally {
        $Probe.Dispose()
    }
}

function Resolve-ProjectJava {
    $SelectedJdk = $null
    $SelectionSource = 'JAVA_HOME'
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $ConfiguredHome = $env:JAVA_HOME.Trim().Trim('"')
        $SelectedJdk = Get-ProjectJdk -JavaPath (Join-Path $ConfiguredHome 'bin/java.exe')
        if (-not $SelectedJdk) {
            Write-Warning 'JAVA_HOME does not identify a usable full JDK 21. Trying java.exe on PATH.'
        }
    }
    if (-not $SelectedJdk) {
        $SelectionSource = 'PATH'
        $PathJavaCommands = @(Get-Command java.exe -CommandType Application -All -ErrorAction SilentlyContinue)
        foreach ($PathJavaCommand in $PathJavaCommands) {
            $SelectedJdk = Get-ProjectJdk -JavaPath $PathJavaCommand.Source
            if ($SelectedJdk) { break }
        }
    }
    if (-not $SelectedJdk) {
        throw 'A full JDK 21 could not be found. Install JDK 21 (java.exe and javac.exe), set JAVA_HOME to its installation directory, or add its bin directory to PATH. JAVA_HOME must not point to bin or java.exe. Then run scripts/start.ps1 again.'
    }
    # Set only this PowerShell process and its children, never machine/user settings.
    $env:JAVA_HOME = $SelectedJdk.Home
    $env:PATH = (Join-Path $SelectedJdk.Home 'bin') + ';' + $env:PATH
    Write-Host ('Java preflight: JDK 21 via ' + $SelectionSource + '; JAVA_HOME=' + $SelectedJdk.Home)
    return $SelectedJdk
}
