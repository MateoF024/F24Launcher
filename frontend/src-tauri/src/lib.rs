use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::Manager;

/// Relación de aspecto fija de la ventana (16:9).
const ASPECT: f64 = 16.0 / 9.0;
/// Evita el bucle de realimentación al corregir el tamaño nosotros mismos.
static ADJUSTING: AtomicBool = AtomicBool::new(false);

/// Datos del handshake que el backend Java imprime por stdout.
#[derive(Clone, serde::Serialize)]
struct Handshake {
    port: u16,
    token: String,
}

/// Proceso del backend (para matarlo al cerrar la app).
struct BackendProcess(Mutex<Option<Child>>);

/// Handshake ya capturado; el frontend lo consulta con `get_handshake`.
struct IpcState(Mutex<Option<Handshake>>);

/// Resuelve el ejecutable Java y el jar del backend.
///
/// - En `tauri dev` (debug): usa el `java` del sistema y el jar del repo.
/// - En release (instalado): usa el JRE y el jar empaquetados como recursos,
///   de modo que la app funcione sin depender de un Java instalado en el equipo.
fn resolve_backend(app: &tauri::AppHandle) -> (std::path::PathBuf, std::path::PathBuf) {
    let env_jar = std::env::var("F24_BACKEND_JAR").ok().map(std::path::PathBuf::from);

    if cfg!(debug_assertions) {
        let jar = env_jar.unwrap_or_else(|| {
            std::path::PathBuf::from("../../backend/build/libs/F24Launcher-0.0.1-Release.jar")
        });
        return (std::path::PathBuf::from("java"), jar);
    }

    let res = app
        .path()
        .resource_dir()
        .expect("no se pudo resolver el directorio de recursos");
    let java = strip_unc(res.join("resources/jre/bin/javaw.exe"));
    let jar = env_jar.unwrap_or_else(|| strip_unc(res.join("resources/F24Launcher-backend.jar")));
    (java, jar)
}

/// Quita el prefijo de ruta extendida `\\?\` de Windows. Tauri devuelve el
/// directorio de recursos con ese prefijo, y Java no carga clases de un .jar
/// cuya ruta lo lleva (lee el manifest pero falla el classloader).
fn strip_unc(p: std::path::PathBuf) -> std::path::PathBuf {
    let s = p.to_string_lossy();
    match s.strip_prefix(r"\\?\") {
        Some(rest) => std::path::PathBuf::from(rest),
        None => p,
    }
}

/// El frontend consulta esto (con reintentos) hasta obtener puerto + token.
/// Patrón pull → sin carreras y funciona aunque `withGlobalTauri` sea false.
#[tauri::command]
fn get_handshake(state: tauri::State<IpcState>) -> Option<Handshake> {
    state.0.lock().unwrap().clone()
}

/// Abre una URL en el navegador del sistema (login de Microsoft, etc.).
#[tauri::command]
fn open_external(url: String) -> Result<(), String> {
    Command::new("cmd")
        .args(["/C", "start", "", &url])
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// Lanza el backend Java y, en un hilo, lee su stdout hasta capturar el
/// handshake (puerto + token), que guarda en el estado IpcState.
fn spawn_backend(app: &tauri::AppHandle) -> std::io::Result<Child> {
    let (java, jar) = resolve_backend(app);
    eprintln!("[F24] java = {}", java.display());
    eprintln!("[F24] jar  = {} (existe: {})", jar.display(), jar.exists());
    let mut child = Command::new(&java)
        .arg("-jar")
        .arg(&jar)
        .stdout(Stdio::piped())
        .spawn()?;

    let stdout = child.stdout.take().expect("stdout del backend");
    let handle = app.clone();
    std::thread::spawn(move || {
        let mut port: Option<u16> = None;
        let mut token: Option<String> = None;
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some(v) = line.strip_prefix("F24LAUNCHER_PORT=") {
                port = v.trim().parse().ok();
            }
            if let Some(v) = line.strip_prefix("F24LAUNCHER_TOKEN=") {
                token = Some(v.trim().to_string());
            }
            if let (Some(p), Some(t)) = (port, token.clone()) {
                let state = handle.state::<IpcState>();
                *state.0.lock().unwrap() = Some(Handshake { port: p, token: t });
                break;
            }
        }
    });
    Ok(child)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(BackendProcess(Mutex::new(None)))
        .manage(IpcState(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![get_handshake, open_external])
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }
            match spawn_backend(app.handle()) {
                Ok(child) => {
                    *app.state::<BackendProcess>().0.lock().unwrap() = Some(child);
                }
                Err(e) => eprintln!("No se pudo arrancar el backend Java: {e}"),
            }

            // Bloqueo de relación de aspecto: al arrastrar los bordes, la altura
            // se deriva del ancho para mantener 16:9 (toda la app se adapta).
            // Maximizar/pantalla completa quedan exentos para no romper el encaje
            // de la ventana en la pantalla.
            if let Some(win) = app.get_webview_window("main") {
                let w = win.clone();
                win.on_window_event(move |event| {
                    if let tauri::WindowEvent::Resized(size) = event {
                        if ADJUSTING.load(Ordering::SeqCst) {
                            return;
                        }
                        if w.is_maximized().unwrap_or(false)
                            || w.is_fullscreen().unwrap_or(false)
                        {
                            return;
                        }
                        let width = size.width;
                        let expected = (width as f64 / ASPECT).round() as u32;
                        if expected > 0 && size.height.abs_diff(expected) > 2 {
                            ADJUSTING.store(true, Ordering::SeqCst);
                            let _ = w.set_size(tauri::PhysicalSize::new(width, expected));
                            ADJUSTING.store(false, Ordering::SeqCst);
                        }
                    }
                });
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error al construir la app Tauri")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { .. } = event {
                if let Some(state) = app.try_state::<BackendProcess>() {
                    if let Some(mut child) = state.0.lock().unwrap().take() {
                        let _ = child.kill();
                    }
                }
            }
        });
}
