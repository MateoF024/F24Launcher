; Hooks del instalador NSIS de F24Launcher.
;
; Tras instalar, si el certificado autofirmado viene empaquetado
; ($INSTDIR\f24launcher.cer), se importa al almacén del USUARIO (sin admin) en
; Root + TrustedPublisher. Así el ejecutable firmado abre sin el aviso de
; "Editor desconocido" a partir de la instalación.
;
; Si la app no se firmó (no hay .cer empaquetado), los bloques son no-ops.

!macro NSIS_HOOK_POSTINSTALL
  IfFileExists "$INSTDIR\f24launcher.cer" 0 f24_no_cert
    DetailPrint "Estableciendo confianza en el certificado de F24Launcher..."
    nsExec::ExecToLog 'certutil -user -addstore -f "Root" "$INSTDIR\f24launcher.cer"'
    nsExec::ExecToLog 'certutil -user -addstore -f "TrustedPublisher" "$INSTDIR\f24launcher.cer"'
  f24_no_cert:
!macroend
