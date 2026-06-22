import {
	events,
	ipcReady,
	isReady,
	listInstances,
	listAccounts,
	deleteAccount,
	getSettings,
	type Instance,
	type Account,
	type AppSettingsDto
} from './ipc';

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
	status: {} as Record<string, string>, // instanceId → estado (installing/running/...)
	logs: {} as Record<string, string[]>, // instanceId → líneas crudas de log en vivo
	errors: {} as Record<string, string>, // instanceId → último error de instalación/lanzamiento
	accounts: [] as Account[],
	settings: null as AppSettingsDto | null,
	auth: { stage: '', userCode: '', verificationUri: '', message: '' } as AuthFlow
});

let ws: WebSocket | null = null;
let started = false;

export async function initStore() {
	if (started) return;
	started = true;
	await ipcReady;
	ui.connected = isReady();
	if (!ui.connected) return;
	await refreshSettings();
	await refreshInstances();
	await refreshAccounts();
	connect();
	// Red de seguridad: si el WebSocket pierde algún evento (p. ej. una ráfaga
	// justo al arrancar), reconciliamos contra el backend cada pocos segundos.
	setInterval(reconcile, 3000);
}

function connect() {
	ws = events(handleEvent);
	ws.onclose = () => {
		ws = null;
		setTimeout(() => {
			if (!ws && isReady()) connect();
		}, 1500);
	};
}

/** Sincroniza el estado real del backend y limpia progresos huérfanos. */
async function reconcile() {
	try {
		const fresh = await listInstances();
		ui.instances = fresh;
		for (const inst of fresh) {
			// Si ya está instalada y todavía mostramos barra (evento perdido), la quitamos.
			if (inst.installed && ui.progress[inst.id]) clearProgress(inst.id);
		}
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
	} catch {
		applyTheme(true);
	}
}

/** Refleja en el estado los ajustes ya persistidos por la página de Ajustes. */
export function setSettings(s: AppSettingsDto) {
	ui.settings = s;
	applyTheme(s.darkMode);
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
}

export function appendLog(id: string, line: string) {
	const prev = ui.logs[id] ?? [];
	ui.logs = { ...ui.logs, [id]: [...prev, line].slice(-4000) };
}
