use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager};

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

/// Comportamiento de ventana configurable desde Ajustes (lo empuja el frontend).
struct WindowBehavior {
    /// Si está activo, pulsar la "X" oculta la ventana a la bandeja en vez de salir.
    close_to_background: AtomicBool,
}

/// Ruta de un .f24pack/.mrpack con el que se abrió la app (doble clic / CLI), a la
/// espera de que el frontend la recoja con `take_pending_open` (arranque en frío).
struct PendingOpen(Mutex<Option<String>>);

/// Busca en los argumentos una ruta de modpack a importar (ignora argv[0]).
fn pack_path_in(argv: &[String]) -> Option<String> {
    argv.iter().skip(1).find(|a| {
        let l = a.to_lowercase();
        l.ends_with(".f24pack") || l.ends_with(".mrpack")
    }).cloned()
}

/// Si los argumentos traen un modpack, lo guarda (arranque en frío) y emite un
/// evento (app ya abierta) para que el frontend lo importe.
fn forward_open_file(app: &tauri::AppHandle, argv: &[String]) {
    if let Some(path) = pack_path_in(argv) {
        if let Some(state) = app.try_state::<PendingOpen>() {
            *state.0.lock().unwrap() = Some(path.clone());
        }
        let _ = app.emit("open-f24pack", path);
    }
}

/// El frontend recoge (una vez) la ruta pendiente al arrancar.
#[tauri::command]
fn take_pending_open(state: tauri::State<PendingOpen>) -> Option<String> {
    state.0.lock().unwrap().take()
}

/// Instancia a lanzar en segundo plano (--launch <id>); el frontend la consulta.
struct LaunchTarget(Mutex<Option<String>>);

/// Extrae el id de `--launch <id>` o `--launch=<id>` de los argumentos.
fn launch_id_in(argv: &[String]) -> Option<String> {
    let mut it = argv.iter();
    while let Some(a) = it.next() {
        if a == "--launch" {
            return it.next().filter(|s| !s.is_empty()).cloned();
        }
        if let Some(v) = a.strip_prefix("--launch=") {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
    }
    None
}

/// El frontend consulta si la app arrancó en modo "lanzar instancia" (ventana oculta).
#[tauri::command]
fn get_launch_target(state: tauri::State<LaunchTarget>) -> Option<String> {
    state.0.lock().unwrap().clone()
}

/// Muestra y enfoca la ventana principal (p. ej. fallback de cuenta en modo lanzar).
#[tauri::command]
fn show_window(app: tauri::AppHandle) {
    show_main(&app);
}

/// Cierre real desde el frontend (mata el backend y sale).
#[tauri::command]
fn exit_app(app: tauri::AppHandle) {
    quit_app(&app);
}

/// Crea un acceso directo en el Escritorio que lanza la instancia en segundo plano.
#[tauri::command]
fn create_instance_shortcut(id: String, name: String) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe_str = exe.to_string_lossy().to_string();
    let work_dir = exe
        .parent()
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_default();

    // Icono: si la instancia tiene icon.png en appdata, genera un .ico junto a él.
    let mut icon_location = exe_str.clone();
    if let Ok(appdata) = std::env::var("APPDATA") {
        let png = std::path::PathBuf::from(&appdata)
            .join("F24Launcher")
            .join("instances-data")
            .join(&id)
            .join("icon.png");
        if png.exists() {
            if let Ok(bytes) = std::fs::read(&png) {
                let ico = png.with_file_name("icon.ico");
                if std::fs::write(&ico, png_to_ico(&bytes)).is_ok() {
                    icon_location = ico.to_string_lossy().to_string();
                }
            }
        }
    }

    let safe_name = sanitize_filename(&name);
    let safe_id = id.replace('\'', "");
    let script = format!(
        "$d=[Environment]::GetFolderPath('Desktop'); \
         $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d '{name}.lnk')); \
         $s.TargetPath='{exe}'; $s.Arguments='--launch {id}'; $s.IconLocation='{icon}'; \
         $s.WorkingDirectory='{wd}'; $s.Description='Jugar {name} (F24Launcher)'; $s.Save()",
        name = ps_quote(&safe_name),
        exe = ps_quote(&exe_str),
        id = safe_id,
        icon = ps_quote(&icon_location),
        wd = ps_quote(&work_dir),
    );

    let status = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .status()
        .map_err(|e| e.to_string())?;
    if status.success() {
        Ok(())
    } else {
        Err("PowerShell no pudo crear el acceso directo".into())
    }
}

/// Envuelve un PNG en un contenedor .ico (una entrada 256x256; Windows acepta PNG en .ico).
fn png_to_ico(png: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(png.len() + 22);
    out.extend_from_slice(&[0, 0, 1, 0, 1, 0]); // ICONDIR: reservado, tipo=1, count=1
    out.push(0); // width 256 → 0
    out.push(0); // height 256 → 0
    out.push(0); // colores
    out.push(0); // reservado
    out.extend_from_slice(&1u16.to_le_bytes()); // planos
    out.extend_from_slice(&32u16.to_le_bytes()); // bits por píxel
    out.extend_from_slice(&(png.len() as u32).to_le_bytes()); // tamaño de la imagen
    out.extend_from_slice(&22u32.to_le_bytes()); // offset a los datos
    out.extend_from_slice(png);
    out
}

fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if "\\/:*?\"<>|".contains(c) { '_' } else { c })
        .collect();
    let t = cleaned.trim();
    if t.is_empty() {
        "Instancia".to_string()
    } else {
        t.to_string()
    }
}

/// Escapa comillas simples para incrustar en una cadena PowerShell entre comillas simples.
fn ps_quote(s: &str) -> String {
    s.replace('\'', "''")
}

#[derive(serde::Deserialize)]
struct TrayItem {
    id: String,
    name: String,
}

fn e2s<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

/// Reconstruye el menú de la bandeja con un submenú "Jugar" de instancias recientes.
#[tauri::command]
fn set_tray_instances(app: tauri::AppHandle, items: Vec<TrayItem>) -> Result<(), String> {
    let open_i = MenuItem::with_id(&app, "open", "Abrir F24Launcher", true, None::<&str>).map_err(e2s)?;
    let quit_i = MenuItem::with_id(&app, "quit", "Salir", true, None::<&str>).map_err(e2s)?;
    let sep = PredefinedMenuItem::separator(&app).map_err(e2s)?;

    let menu = Menu::new(&app).map_err(e2s)?;
    menu.append(&open_i).map_err(e2s)?;
    if !items.is_empty() {
        let play = Submenu::new(&app, "Jugar", true).map_err(e2s)?;
        for it in items.iter().take(12) {
            let mi = MenuItem::with_id(&app, format!("play:{}", it.id), &it.name, true, None::<&str>)
                .map_err(e2s)?;
            play.append(&mi).map_err(e2s)?;
        }
        menu.append(&play).map_err(e2s)?;
    }
    menu.append(&sep).map_err(e2s)?;
    menu.append(&quit_i).map_err(e2s)?;

    if let Some(tray) = app.tray_by_id("f24-tray") {
        tray.set_menu(Some(menu)).map_err(e2s)?;
    }
    Ok(())
}

/// Resuelve el ejecutable Java y el jar del backend.
///
/// - En `tauri dev` (debug): usa el `java` del sistema y el jar del repo.
/// - En release (instalado): usa el JRE y el jar empaquetados como recursos,
///   de modo que la app funcione sin depender de un Java instalado en el equipo.
fn resolve_backend(app: &tauri::AppHandle) -> (std::path::PathBuf, std::path::PathBuf) {
    let env_jar = std::env::var("F24_BACKEND_JAR").ok().map(std::path::PathBuf::from);

    if cfg!(debug_assertions) {
        let jar = env_jar.unwrap_or_else(|| {
            std::path::PathBuf::from("../../backend/build/libs/F24Launcher-0.0.2-Release.jar")
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

/// El frontend informa si "cerrar" (X) debe ocultar la ventana en vez de salir.
#[tauri::command]
fn set_close_to_background(value: bool, state: tauri::State<WindowBehavior>) {
    state.close_to_background.store(value, Ordering::SeqCst);
}

/// Muestra, restaura y enfoca la ventana principal.
fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// Cierre real: mata el backend y termina la app.
fn quit_app(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackendProcess>() {
        if let Some(mut child) = state.0.lock().unwrap().take() {
            let _ = child.kill();
        }
    }
    app.exit(0);
}

/// Crea el icono de la bandeja con menú Abrir / Salir y toggle al clic.
fn build_tray(app: &tauri::AppHandle) -> tauri::Result<()> {
    let open_i = MenuItem::with_id(app, "open", "Abrir F24Launcher", true, None::<&str>)?;
    let quit_i = MenuItem::with_id(app, "quit", "Salir", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_i, &quit_i])?;

    let mut builder = TrayIconBuilder::with_id("f24-tray")
        .tooltip("F24Launcher")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| {
            let id = event.id.as_ref();
            match id {
                "open" => show_main(app),
                "quit" => quit_app(app),
                other => {
                    if let Some(inst) = other.strip_prefix("play:") {
                        let _ = app.emit("launch-instance", inst.to_string());
                    }
                }
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(w) = app.get_webview_window("main") {
                    let shown = w.is_visible().unwrap_or(false) && !w.is_minimized().unwrap_or(false);
                    if shown {
                        let _ = w.hide();
                    } else {
                        show_main(app);
                    }
                }
            }
        });

    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder.build(app)?;
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
    // `mut` solo se usa en release (al añadir el plugin single-instance).
    #[cfg_attr(debug_assertions, allow(unused_mut))]
    let mut builder = tauri::Builder::default();

    // Instancia única: solo en release (la app instalada). Debe registrarse
    // primero; una segunda invocación trae al frente la ventana existente en vez
    // de abrir otra. En dev se omite para no bloquear los reinicios de la app
    // (p. ej. si quedó oculta en la bandeja al cortar `pnpm tauri dev`).
    #[cfg(not(debug_assertions))]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            // Acceso directo de instancia con la app ya abierta: lanza sin tocar la ventana.
            if let Some(id) = launch_id_in(&argv) {
                let _ = app.emit("launch-instance", id);
            } else {
                show_main(app);
                forward_open_file(app, &argv);
            }
        }));
    }

    builder
        .plugin(tauri_plugin_dialog::init())
        .manage(BackendProcess(Mutex::new(None)))
        .manage(IpcState(Mutex::new(None)))
        .manage(WindowBehavior {
            close_to_background: AtomicBool::new(false),
        })
        .manage(PendingOpen(Mutex::new(None)))
        .manage(LaunchTarget(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            get_handshake,
            open_external,
            set_close_to_background,
            take_pending_open,
            get_launch_target,
            show_window,
            exit_app,
            create_instance_shortcut,
            set_tray_instances
        ])
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

            build_tray(app.handle())?;

            // Modo de arranque: si viene `--launch <id>` (acceso directo), la ventana
            // queda OCULTA y el frontend lanza esa instancia en segundo plano. Si no,
            // se muestra la ventana normal y se atiende un posible .f24pack (doble clic).
            let argv: Vec<String> = std::env::args().collect();
            let target = launch_id_in(&argv);
            *app.state::<LaunchTarget>().0.lock().unwrap() = target.clone();
            if target.is_none() {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                }
                forward_open_file(app.handle(), &argv);
            }

            // Ventana: bloqueo de relación de aspecto 16:9 (al arrastrar los bordes,
            // la altura se deriva del ancho) y cierre-a-bandeja. Maximizar/pantalla
            // completa quedan exentos del ajuste de aspecto.
            if let Some(win) = app.get_webview_window("main") {
                let w = win.clone();
                win.on_window_event(move |event| match event {
                    tauri::WindowEvent::CloseRequested { api, .. } => {
                        let behavior = w.app_handle().state::<WindowBehavior>();
                        if behavior.close_to_background.load(Ordering::SeqCst) {
                            api.prevent_close();
                            let _ = w.hide();
                        }
                    }
                    tauri::WindowEvent::Resized(size) => {
                        if ADJUSTING.load(Ordering::SeqCst) {
                            return;
                        }
                        if w.is_maximized().unwrap_or(false) || w.is_fullscreen().unwrap_or(false) {
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
                    _ => {}
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
