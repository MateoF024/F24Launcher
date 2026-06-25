import {
	events,
	ipcReady,
	isReady,
	listInstances,
	listAccounts,
	deleteAccount,
	getSettings,
	importContentFiles,
	type Instance,
	type Account,
	type AppSettingsDto
} from './ipc';
import { flog } from './clientLog';

export interface Progress {
	phase: string;
	done: number;
	total: number;
}

export interface AuthFlow {
	stage: '' | 'pending' | 'success' | 'error';
	userCode: string;
	verificationUri: string;
	message: string;
}

/** Estado reactivo global del launcher. */
export const ui = $state({
	connected: false,
	instances: [] as Instance[],
	progress: {} as Record<string, Progress>,
	contentProgress: {} as Record<string, Progress>, // instanceId → progreso de descarga de contenido (mods)
	status: {} as Record<string, string>, // instanceId → estado (installing/running/...)
	logs: {} as Record<string, string[]>, // instanceId → líneas crudas de log en vivo
	errors: {} as Record<string, string>, // instanceId → último error de instalación/lanzamiento
	accounts: [] as Account[],
	settings: null as AppSettingsDto | null,
	auth: { stage: '', userCode: '', verificationUri: '', message: '' } as AuthFlow,
	iconBust: 0, // se incrementa al cambiar un icono de instancia para refrescar los <img>
	contentImported: 0 // se incrementa al soltar mods en una instancia (refresca su contenido)
});

// ── Drag&drop de archivos sueltos en la ventana de una instancia (E4) ──
let contentDrop: { id: string; getType: () => string } | null = null;
/** La página de una instancia se registra para recibir drops de .jar/.zip. */
export function setContentDrop(t: { id: string; getType: () => string }) {
	contentDrop = t;
}
export function clearContentDrop() {
	contentDrop = null;
}
export function hasContentDrop() {
	return contentDrop !== null;
}

/** Si hay una instancia escuchando y los archivos son contenido, los importa a ella. */
export async function importDroppedFiles(paths: string[]): Promise<boolean> {
	if (!contentDrop) return false;
	const files = paths.filter((p) => /\.(jar|zip)$/i.test(p));
	if (!files.length) return false;
	try {
		await importContentFiles(contentDrop.id, files, contentDrop.getType());
		ui.contentImported++;
	} catch {
		/* el usuario verá que no apareció */
	}
	return true;
}

/** Fuerza recarga de los iconos de instancia (cache-bust) tras crear/editar. */
export function bumpIconBust() {
	ui.iconBust++;
}

let ws: WebSocket | null = null;
let started = false;

// #3 — "pasar a segundo plano al lanzar". Seguimos qué instancias están en
// ejecución para ocultar la ventana solo al arrancar la primera y restaurarla
// solo cuando se cierra la última (soporta varios juegos a la vez).
const runningIds = new Set<string>();
let backgroundedForLaunch = false;

// En modo "lanzar acceso directo" (ventana oculta) no automatizamos la ventana:
// el driver de arranque la deja oculta y cierra la app al terminar el juego.
let launchModeActive = false;
export function setLaunchModeActive(v: boolean) {
	launchModeActive = v;
}

export async function initStore() {
	if (started) return;
	started = true;
	await ipcReady;
	ui.connected = isReady();
	if (!ui.connected) return;
	connect(); // abre el WebSocket cuanto antes (no perder eventos tempranos)
	// Carga inicial en paralelo (más rápido que encadenar las tres).
	await Promise.all([refreshSettings(), refreshInstances(), refreshAccounts()]);
	// Red de seguridad: si el WebSocket pierde algún evento, reconciliamos contra
	// el backend — pero solo mientras haya tareas activas (en reposo no hace red).
	setInterval(reconcile, 3000);
}

/** ¿Hay alguna tarea activa que justifique reconciliar (instalación/lanzamiento/juego)? */
function hasActiveWork(): boolean {
	if (Object.keys(ui.progress).length > 0) return true;
	for (const s of Object.values(ui.status)) {
		if (s === 'installing' || s === 'launching' || s === 'running') return true;
	}
	return false;
}

function connect() {
	ws = events(handleEvent);
	ws.onclose = () => {
		ws = null;
		flog('WARN', 'WebSocket /events cerrado; reintentando en 1.5 s');
		setTimeout(() => {
			if (!ws && isReady()) connect();
		}, 1500);
	};
}

/** Sincroniza el estado real del backend y limpia progresos huérfanos. */
async function reconcile() {
	if (!hasActiveWork()) return; // en reposo no consultamos al backend (menos CPU/red)
	try {
		const fresh = await listInstances();
		ui.instances = fresh;
		for (const inst of fresh) {
			// Si ya está instalada y todavía mostramos barra (evento perdido), la quitamos.
			if (inst.installed && ui.progress[inst.id]) clearProgress(inst.id);
		}
		syncTray();
	} catch {
		/* el backend puede estar ocupado; reintentamos en el próximo tick */
	}
}

function handleEvent(e: { type: string; data: any }) {
	const d = e.data;
	if (e.type === 'progress') {
		setProgress(d.instanceId, { phase: d.phase, done: d.done, total: d.total });
	} else if (e.type === 'state') {
		ui.status = { ...ui.status, [d.instanceId]: d.state };
		if (d.state === 'installing' || d.state === 'launching') clearError(d.instanceId);
		if (d.state === 'launching') ui.logs = { ...ui.logs, [d.instanceId]: [] }; // nueva sesión limpia
		if (d.state === 'error') ui.errors = { ...ui.errors, [d.instanceId]: d.message ?? 'Error desconocido' };
		if (d.state === 'installed' || d.state === 'stopped' || d.state === 'error') {
			clearProgress(d.instanceId);
			refreshInstances();
		}
		handleWindowOnState(d.instanceId, d.state);
	} else if (e.type === 'contentProgress') {
		// Progreso de descarga al instalar/actualizar mods (canal propio, no toca la
		// barra de instalación de la instancia). Lo consume la vista de la instancia.
		ui.contentProgress = {
			...ui.contentProgress,
			[d.instanceId]: { phase: d.phase, done: d.done, total: d.total }
		};
	} else if (e.type === 'crash') {
		handleCrash(d.instanceId, d.file ?? '');
	} else if (e.type === 'log') {
		appendLog(d.instanceId, d.line);
	} else if (e.type === 'auth') {
		ui.auth = {
			...ui.auth,
			stage: d.stage,
			message: d.message ?? ''
		};
		if (d.stage === 'success') refreshAccounts();
	}
}

export async function refreshAccounts() {
	try {
		ui.accounts = await listAccounts();
	} catch {
		/* el backend puede no estar listo aún */
	}
}

/** Aplica el tema (oscuro/claro) al documento. */
export function applyTheme(dark: boolean) {
	if (typeof document !== 'undefined') {
		document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
	}
}

/** Oculta la ventana a segundo plano (igual que el botón cerrar: a la bandeja). */
async function hideToBackground() {
	try {
		const { getCurrentWindow } = await import('@tauri-apps/api/window');
		await getCurrentWindow().hide();
	} catch {
		/* fuera de Tauri */
	}
}

/** Trae la ventana al frente y la deja fija (al terminar la última instancia). */
async function restoreToForeground() {
	try {
		const { getCurrentWindow } = await import('@tauri-apps/api/window');
		const w = getCurrentWindow();
		await w.show();
		await w.unminimize();
		await w.setFocus();
	} catch {
		/* fuera de Tauri */
	}
}

/**
 * #3 — al lanzar, oculta la ventana a segundo plano; al terminar la última
 * instancia en ejecución, la restaura al frente. Por transición: oculta solo al
 * pasar de "ninguna" a "alguna" en ejecución y restaura solo al volver a cero,
 * para que un evento repetido no provoque parpadeos.
 */
function handleWindowOnState(id: string, state: string) {
	if (launchModeActive) return;
	if (state === 'running') {
		runningIds.add(id);
		if (ui.settings?.minimizeOnLaunch && !backgroundedForLaunch) {
			backgroundedForLaunch = true;
			hideToBackground();
		}
	} else if (state === 'stopped' || state === 'error') {
		runningIds.delete(id);
		if (runningIds.size === 0 && backgroundedForLaunch) {
			backgroundedForLaunch = false;
			restoreToForeground();
		}
	}
}

/**
 * P8c — al detectar un crash del juego, trae la app al frente y abre la consola de
 * esa instancia mostrando el crash-report indicado (vía ?crash=). En modo "acceso
 * directo" no se hace nada: la app se cierra al terminar el juego.
 */
async function handleCrash(id: string, file: string) {
	if (launchModeActive) return;
	await restoreToForeground();
	try {
		const { goto } = await import('$app/navigation');
		const q = file ? `?crash=${encodeURIComponent(file)}` : '';
		await goto(`/instance/${id}/console${q}`);
	} catch {
		/* fuera de Tauri / navegación no disponible */
	}
}

/** #2 — informa a Rust si "cerrar" (X) debe ocultar a la bandeja en vez de salir. */
async function applyCloseBehavior(toBackground: boolean) {
	try {
		const { invoke } = await import('@tauri-apps/api/core');
		await invoke('set_close_to_background', { value: toBackground });
	} catch {
		/* fuera de Tauri */
	}
}

/** Aplica el tamaño por defecto de la ventana del launcher (16:9 lo asegura Rust). */
async function applyLauncherSize(width: number, height: number) {
	try {
		const { getCurrentWindow, LogicalSize } = await import('@tauri-apps/api/window');
		await getCurrentWindow().setSize(new LogicalSize(width, height));
	} catch {
		/* fuera de Tauri (navegador dev) */
	}
}

/** Carga los ajustes globales y aplica tema + tamaño de ventana. */
export async function refreshSettings() {
	try {
		ui.settings = await getSettings();
		applyTheme(ui.settings.darkMode);
		applyLauncherSize(ui.settings.launcherWidth, ui.settings.launcherHeight);
		applyCloseBehavior(ui.settings.closeToBackground);
	} catch {
		applyTheme(true);
	}
}

/** Refleja en el estado los ajustes ya persistidos por la página de Ajustes. */
export function setSettings(s: AppSettingsDto) {
	ui.settings = s;
	applyTheme(s.darkMode);
	applyCloseBehavior(s.closeToBackground);
}

export function clearError(id: string) {
	if (ui.errors[id] === undefined) return;
	const { [id]: _drop, ...rest } = ui.errors;
	ui.errors = rest;
}

export function resetAuth() {
	ui.auth = { stage: '', userCode: '', verificationUri: '', message: '' };
}

/** Cierra sesión de la cuenta activa (la elimina del almacén). */
export async function logout() {
	const active = ui.accounts.find((a) => a.active);
	if (!active) return;
	await deleteAccount(active.id);
	await refreshAccounts();
}

// Reasignamos el objeto entero para garantizar reactividad fina en Svelte 5.
export function setProgress(id: string, p: Progress) {
	ui.progress = { ...ui.progress, [id]: p };
}

export function clearProgress(id: string) {
	const { [id]: _drop, ...rest } = ui.progress;
	ui.progress = rest;
}

export function setStatus(id: string, state: string) {
	ui.status = { ...ui.status, [id]: state };
}

export async function refreshInstances() {
	ui.instances = await listInstances();
	syncTray();
}

// Mantiene el submenú "Jugar" de la bandeja al día con las instancias.
let lastTraySig = '';
async function syncTray() {
	const sig = ui.instances.map((i) => i.id + ':' + i.name).join('|');
	if (sig === lastTraySig) return;
	lastTraySig = sig;
	try {
		const { invoke } = await import('@tauri-apps/api/core');
		await invoke('set_tray_instances', {
			items: ui.instances.map((i) => ({ id: i.id, name: i.name }))
		});
	} catch {
		/* fuera de Tauri */
	}
}

export function appendLog(id: string, line: string) {
	const prev = ui.logs[id] ?? [];
	ui.logs = { ...ui.logs, [id]: [...prev, line].slice(-4000) };
}
