# Genera (UNA sola vez) un certificado autofirmado de firma de código para F24Launcher.
#
# Qué hace:
#   - Crea el certificado en Cert:\CurrentUser\My (signtool lo usa por thumbprint; sin admin).
#   - Exporta el .cer PÚBLICO a frontend/src-tauri/f24launcher.cer (se empaqueta para
#     confiar en él al instalar; no es secreto).
#   - Exporta un .pfx de respaldo (clave privada) a tools/ — NO se sube al repo.
#   - Imprime el thumbprint y los 2 ajustes a hacer en tauri.conf.json para firmar.
#
# Uso:   powershell -ExecutionPolicy Bypass -File tools\make-cert.ps1

$ErrorActionPreference = 'Stop'

$subject  = 'CN=MateoF024, O=F24Launcher'
$friendly = 'F24Launcher Code Signing'

$root    = Split-Path -Parent $PSScriptRoot                       # raíz del repo
$cerPath = Join-Path $root 'frontend\src-tauri\f24launcher.cer'
$pfxPath = Join-Path $PSScriptRoot 'f24launcher-codesign.pfx'

# Reutiliza el certificado si ya existe con ese subject.
$cert = Get-ChildItem Cert:\CurrentUser\My |
    Where-Object { $_.Subject -eq $subject } | Select-Object -First 1

if ($cert) {
    Write-Host "Ya existía un certificado ($($cert.Thumbprint))." -ForegroundColor Yellow
} else {
    $cert = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $subject `
        -FriendlyName $friendly `
        -CertStoreLocation Cert:\CurrentUser\My `
        -KeyUsage DigitalSignature `
        -KeyExportPolicy Exportable `
        -HashAlgorithm SHA256 `
        -NotAfter (Get-Date).AddYears(10)
    Write-Host "Certificado creado ($($cert.Thumbprint))." -ForegroundColor Green
}

# .cer público (para empaquetar y confiar en él al instalar).
Export-Certificate -Cert $cert -FilePath $cerPath -Force | Out-Null
Write-Host "Certificado público -> $cerPath"

# .pfx de respaldo de la clave privada (opcional; nunca subir al repo).
try {
    $pwd = Read-Host -AsSecureString 'Contraseña para el .pfx de respaldo (Enter para omitir)'
    if ($pwd.Length -gt 0) {
        Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $pwd | Out-Null
        Write-Host "Respaldo .pfx  -> $pfxPath  (NO subir al repo)"
    } else {
        Write-Host 'Respaldo .pfx omitido.'
    }
} catch {
    Write-Host "No se pudo exportar el .pfx: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== Para firmar el instalador, edita frontend/src-tauri/tauri.conf.json ===' -ForegroundColor Cyan
Write-Host '1) bundle.windows.certificateThumbprint:'
Write-Host "     `"$($cert.Thumbprint)`""
Write-Host '2) Añade  "f24launcher.cer"  al array  bundle.resources'
Write-Host '3) Compila:  ./gradlew buildInstaller'
Write-Host ''
Write-Host 'Sin estos 2 pasos el build sale SIN firmar (y funciona igual).' -ForegroundColor DarkGray
