# Keeps the ALOS Earth mod in a Minecraft "mods" folder up to date.
#
# Downloads the latest beta build published by CI (NeoForge) and installs it as
# alos-earth-neoforge.jar, replacing any older copy (including the old Fabric
# alos-earth.jar). If you are offline or GitHub is
# unreachable it leaves the current copy alone and still exits 0, so it never
# blocks a launch.
#
# Prism Launcher (Edit instance -> Settings -> Custom commands -> Pre-launch
# command), no file needed; Prism provides INST_MC_DIR:
#
#   powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol='Tls12'; & ([scriptblock]::Create((New-Object Net.WebClient).DownloadString('https://github.com/Lazytive/cuddly-giggle/releases/download/alos-earth-beta/update-alos-earth.ps1'))) } catch { Write-Host 'ALOS Earth update skipped' }"
#
# Or run it by hand with the mods folder as the argument:
#   powershell -ExecutionPolicy Bypass -File update-alos-earth.ps1 "C:\path\to\minecraft\mods"

param(
    [string]$ModsDir = $(if ($env:INST_MC_DIR) { Join-Path $env:INST_MC_DIR "mods" } else { "." })
)

$ErrorActionPreference = "Stop"
$url = "https://github.com/Lazytive/cuddly-giggle/releases/download/alos-earth-beta/alos-earth-neoforge.jar"
$target = Join-Path $ModsDir "alos-earth-neoforge.jar"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "alos-earth-download.jar"

try {
    New-Item -ItemType Directory -Force -Path $ModsDir | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -TimeoutSec 30

    $new = (Get-FileHash $tmp -Algorithm SHA256).Hash
    $old = if (Test-Path $target) { (Get-FileHash $target -Algorithm SHA256).Hash } else { "" }
    if ($new -eq $old) {
        Write-Host "ALOS Earth is up to date."
        Remove-Item $tmp -Force
        exit 0
    }
    # remove any other copies (e.g. alos-earth-0.1.0-beta.jar installed by hand, or the old Fabric alos-earth.jar)
    Get-ChildItem -Path $ModsDir -Filter "alos-earth*.jar" | Where-Object { $_.Name -ne "alos-earth-neoforge.jar" } |
        Remove-Item -Force
    Move-Item -Force $tmp $target
    Write-Host "ALOS Earth updated -> $target"
} catch {
    Write-Host "ALOS Earth update skipped: $($_.Exception.Message)"
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}
exit 0
