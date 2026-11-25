#Requires -Version 7.0
<#
One-time owner-side setup. This runs on YOUR computer, not GitHub Actions.
It creates (or reuses) a private PKCS12 key, sends four encrypted GitHub
repository secrets and pins its PUBLIC certificate SHA-256 as a repo variable.
NEVER paste the private key, key password, or base64 value into chat or Git.
Requires JDK keytool, GitHub CLI ("gh auth login"), and repo Admin permission.
#>
[CmdletBinding()]
param(
    [string]$Repo = "operationReact/AiTradingSystem",
    [string]$Keystore = (Join-Path (Join-Path $HOME ".minttick") "signing/minttick-release.p12")
)
$ErrorActionPreference = "Stop"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI missing: install gh, then run gh auth login" }
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { throw "JDK keytool missing: install a JDK and add its bin directory to PATH" }
if ($Repo -ne "operationReact/AiTradingSystem") { throw "Refusing to upload a private signing key to an unexpected repository" }

function Save-GitHubSecret([string]$Name, [string]$Value) {
    # Use process stdin, not command-line arguments, files or console output.
    $info = [System.Diagnostics.ProcessStartInfo]::new((Get-Command gh).Source)
    foreach ($arg in @("secret", "set", $Name, "--repo", $Repo)) {
        [void]$info.ArgumentList.Add($arg)
    }
    $info.RedirectStandardInput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $info.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($info)
    try {
        $proc.StandardInput.Write($Value)
        $proc.StandardInput.Close()
        $errorText = $proc.StandardError.ReadToEnd()
        $proc.WaitForExit()
        if ($proc.ExitCode -ne 0) { throw "Failed to store $Name in GitHub: $errorText" }
    } finally {
        $proc.Dispose()
    }
    Write-Host "Configured encrypted GitHub secret: $Name"
}

$folder = Split-Path -Parent $Keystore
[void](New-Item -ItemType Directory -Force -Path $folder)
if (-not (Test-Path -LiteralPath $Keystore)) {
    Write-Host "Creating one permanent MintTick Android release key. Choose a strong store password."
    & keytool -genkeypair -keystore $Keystore -storetype PKCS12 -alias minttick -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=MintTick, OU=Android, O=MintTick, C=IN"
    if ($LASTEXITCODE -ne 0) { throw "keytool did not create the permanent keystore" }
} else {
    Write-Host "Reusing existing signing key at $Keystore. No new signing identity generated."
}

$secure = Read-Host "Enter the existing keystore password" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    $env:MINTTICK_ANDROID_STORE_PASSWORD = $plain
    $keyDetails = & keytool -list -v -keystore $Keystore -alias minttick -storepass:env MINTTICK_ANDROID_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "Invalid password or missing alias in existing keystore" }
    $fingerprint = $null
    foreach ($line in $keyDetails) {
        if ($line -match 'SHA256:\s*([0-9A-Fa-f:]+)') {
            $fingerprint = ($Matches[1] -replace ':', '').ToUpperInvariant()
            break
        }
    }
    if ($null -eq $fingerprint -or $fingerprint.Length -ne 64) { throw "Unable to identify a valid SHA-256 signing certificate" }

    Write-Host "Uploading encrypted repository secrets (values will NOT be printed)..."
    $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore))
    Save-GitHubSecret "MINTTICK_ANDROID_KEYSTORE_BASE64" $encoded
    Save-GitHubSecret "MINTTICK_ANDROID_KEY_ALIAS" "minttick"
    Save-GitHubSecret "MINTTICK_ANDROID_STORE_PASSWORD" $plain
    # A Java PKCS12 key uses the same password for its private key and store.
    Save-GitHubSecret "MINTTICK_ANDROID_KEY_PASSWORD" $plain

    & gh variable set MINTTICK_ANDROID_CERT_SHA256 --repo $Repo --body $fingerprint
    if ($LASTEXITCODE -ne 0) { throw "Unable to pin public certificate SHA-256 as GitHub variable" }
    Write-Host "Pinned public signing certificate SHA-256: $fingerprint"
    Write-Host ""
    Write-Host "IMPORTANT: Back up $Keystore securely. Losing this private key prevents in-place upgrades."
    Write-Host "The first move from an old debug-signed APK requires ONE uninstall; future releases from this key can update in place."
} finally {
    Remove-Item Env:\MINTTICK_ANDROID_STORE_PASSWORD -ErrorAction SilentlyContinue
    if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    Remove-Variable plain, encoded -Scope Local -ErrorAction SilentlyContinue
}
