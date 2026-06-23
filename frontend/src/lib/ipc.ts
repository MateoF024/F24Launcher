// Cliente IPC del frontend hacia el backend Java (Javalin).
//
// El backend escucha en 127.0.0.1 con un puerto efímero y exige el token de
// sesión en la cabecera X-F24-Token. En producción esos datos llegan por el
// evento Tauri `backend-ready`; en `pnpm dev` (navegador) se pueden pasar por
// querystring: ?port=#####&token=........

let PORT = 0;
let TOKEN = '';
let ready = false;

export function configureIpc(port: number, token: string) {
	PORT = port;
	TOKEN = token;
	ready = port > 0 && token.length > 0;
}

export function isReady() {
	return ready;
}

const base = () => `http://127.0.0.1:${PORT}`;

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
	if (!ready) throw new Error('IPC no configurado todavía');
	const res = await fetch(base() + path, {
		cache: 'no-store',
		...init,
		headers: {
			'X-F24-Token': TOKEN,
			'Content-Type': 'application/json',
			...(init.headers ?? {})
		}
	});
	if (!res.ok) {
		let detail = '';
		try {
			detail = await res.text();
		} catch {
			/* sin cuerpo */
		}
		throw new Error(detail || `IPC ${path} → ${res.status}`);
	}
	const ct = res.headers.get('content-type') ?? '';
	if (res.status === 204 || !ct.includes('application/json')) return undefined as T;
	return (await res.json()) as T;
}

/** Abre el canal de eventos (progreso, logs, login). */
export function events(onMessage: (e: { type: string; data: unknown }) => void): WebSocket {
	const ws = new WebSocket(`ws://127.0.0.1:${PORT}/events?token=${encodeURIComponent(TOKEN)}`);
	ws.onmessage = (m) => {
		try {
			onMessage(JSON.parse(m.data));
		} catch {
			/* ignorar mensajes no-JSON */
		}
	};
	return ws;
}

/**
 * Resuelve la configuración IPC.
 *
 * 1) Respaldo dev (navegador): parámetros de URL `?port=&token=`.
 * 2) Tauri: consulta el comando `get_handshake` con reintentos hasta que el
 *    backend Java haya arrancado e impreso su handshake (patrón pull, sin
 *    carreras y sin depender de `window.__TAURI__`).
 */
export async function initIpc(): Promise<boolean> {
	const params = new URLSearchParams(location.search);
	const qsPort = Number(params.get('port'));
	const qsToken = params.get('token');
	if (qsPort && qsToken) {
		configureIpc(qsPort, qsToken);
		return true;
	}

	try {
		const { invoke } = await import('@tauri-apps/api/core');
		for (let i = 0; i < 150; i++) {
			const hs = await invoke<{ port: number; token: string } | null>('get_handshake');
			if (hs && hs.port > 0) {
				configureIpc(hs.port, hs.token);
				return true;
			}
			await new Promise((r) => setTimeout(r, 150));
		}
		return false;
	} catch {
		// No estamos dentro de Tauri (p. ej. navegador sin querystring).
		return false;
	}
}

// ── Comandos tipados ──────────────────────────────────────────────
export interface Instance {
	id: string;
	name: string;
	mcVersion: string;
	loader: string;
	loaderVersion: string;
	minMemoryMb: number;
	maxMemoryMb: number;
	windowWidth: number;
	windowHeight: number;
	fullscreen: boolean;
	jvmArgs: string;
	javaPathOverride: string;
	installed: boolean;
	lastPlayed: number;
	sourceModpackId: string;
	icon: string;
	favorite: boolean;
	group: string;
}

export interface VanillaVersion {
	id: string;
	type: string;
	releaseTime: string;
}

export interface Account {
	id: string;
	type: 'microsoft' | 'offline';
	username: string;
	uuid: string;
	skinUrl: string | null;
	active: boolean;
	expired: boolean;
}


// ── Contenido (mods, resourcepacks, shaders, datapacks) ──
export type ContentType = 'mods' | 'resourcepacks' | 'shaders' | 'datapacks';
export type ContentSource = 'modrinth' | 'curseforge';

export interface ContentProject {
	id: string;
	slug: string;
	source: ContentSource;
	type: ContentType;
	name: string;
	description: string;
	author: string;
	iconUrl: string;
	downloads: number;
	follows: number;
	categories: string[];
	dateModified: string;
	clientSide: string;
	serverSide: string;
}

export interface ProjectDetail extends ContentProject {
	body: string;
	bodyFormat: 'markdown' | 'html';
	gallery: string[];
	links: Record<string, string>;
	license: string;
}

export interface ContentVersion {
	id: string;
	name: string;
	versionNumber: string;
	channel: string;
	fileName: string;
	downloadUrl: string;
	size: number;
	gameVersions: string[];
	loaders: string[];
	datePublished: string;
}

export interface InstalledItem {
	fileName: string;
	type: ContentType;
	enabled: boolean;
	source: string;
	projectId: string;
	slug: string;
	name: string;
	iconUrl: string;
	author: string;
	versionId: string;
	versionName: string;
	categories: string[];
	clientSide: string;
	serverSide: string;
}

export interface UpdateInfo {
	fileName: string;
	type: ContentType;
	versionId: string;
	versionName: string;
}

export interface SearchResult {
	hits: ContentProject[];
	total: number;
}

export interface SearchParams {
	source: ContentSource;
	type: ContentType;
	mc: string;
	loader: string;
	q?: string;
	categories?: string[];
	environments?: string[];
	sort?: string;
	page?: number;
	ignore?: boolean;
}

export const searchContent = (p: SearchParams) => {
	const q = new URLSearchParams({
		source: p.source,
		type: p.type,
		mc: p.mc,
		loader: p.loader,
		page: String(p.page ?? 0)
	});
	if (p.q) q.set('q', p.q);
	if (p.categories?.length) q.set('category', p.categories.join(','));
	if (p.environments?.length) q.set('env', p.environments.join(','));
	if (p.sort) q.set('sort', p.sort);
	if (p.ignore) q.set('ignore', 'true');
	return api<SearchResult>(`/content/search?${q}`);
};

export const getContentProject = (source: string, type: ContentType, id: string) =>
	api<ProjectDetail>(`/content/project?source=${source}&type=${type}&id=${encodeURIComponent(id)}`);

export const getContentVersions = (
	source: string,
	type: ContentType,
	id: string,
	mc: string,
	loader: string,
	ignore = false
) =>
	api<ContentVersion[]>(
		`/content/versions?source=${source}&type=${type}&id=${encodeURIComponent(id)}` +
			`&mc=${encodeURIComponent(mc)}&loader=${encodeURIComponent(loader)}${ignore ? '&ignore=true' : ''}`
	);

export interface Category {
	id: string;
	name: string;
	parentId: string;
}

export const getContentCategories = (source: string, type: ContentType) =>
	api<Category[]>(`/content/categories?source=${source}&type=${type}`);

export const listInstalledContent = (instanceId: string) =>
	api<InstalledItem[]>(`/instances/${instanceId}/content`);

export const getContentUpdates = (instanceId: string) =>
	api<UpdateInfo[]>(`/instances/${instanceId}/content/updates`);

export interface CompatItem {
	fileName: string;
	type: ContentType;
	name: string;
	status: 'compatible' | 'updatable' | 'incompatible' | 'unknown';
	versionId: string;
	versionName: string;
}

/** Informe de compatibilidad del contenido frente a una versión/loader objetivo (preview). */
export const getContentCompat = (instanceId: string, mc: string, loader: string) =>
	api<CompatItem[]>(
		`/instances/${instanceId}/content/compat?mc=${encodeURIComponent(mc)}&loader=${encodeURIComponent(loader)}`
	);

/** Cambia versión de MC / loader y reinstala (progreso por WS). Devuelve la instancia actualizada. */
export const changeInstanceVersion = (
	id: string,
	body: { mcVersion: string; loader: string; loaderVersion: string }
) => api<Instance>(`/instances/${id}/change-version`, { method: 'POST', body: JSON.stringify(body) });

/** Identifica mods añadidos manualmente (hash Modrinth / nombre CurseForge). */
export const identifyContent = (instanceId: string) =>
	api<InstalledItem[]>(`/instances/${instanceId}/content/identify`, { method: 'POST' });

export const installContent = (
	instanceId: string,
	body: { source: string; type: ContentType; projectId: string; versionId?: string; ignore?: boolean }
) => api<InstalledItem>(`/instances/${instanceId}/content/install`, { method: 'POST', body: JSON.stringify(body) });

export const toggleContent = (instanceId: string, type: ContentType, fileName: string) =>
	api<void>(`/instances/${instanceId}/content/toggle`, {
		method: 'POST',
		body: JSON.stringify({ type, fileName })
	});

export const removeContent = (instanceId: string, type: ContentType, fileName: string) =>
	api<void>(`/instances/${instanceId}/content/remove`, {
		method: 'POST',
		body: JSON.stringify({ type, fileName })
	});

/** Importa archivos sueltos (.jar/.zip) arrastrados a la instancia. type opcional = carpeta forzada. */
export const importContentFiles = (instanceId: string, filePaths: string[], type?: string) =>
	api<InstalledItem[]>(`/instances/${instanceId}/content/import-file`, {
		method: 'POST',
		body: JSON.stringify({ filePaths, type: type || undefined })
	});

export const getInstance = (id: string) => api<Instance>(`/instances/${id}`);

// ── Modpacks privados ──
export interface Modpack {
	id: string;
	name: string;
	downloadUrl: string;
	format: string;
	mcVersion: string;
	loader: string;
	loaderVersion: string;
	icon: string;
	summary: string;
	descriptionUrl: string;
}

export const listModpacks = () => api<Modpack[]>('/modpacks');

/** Markdown de descripción del modpack (proxy backend a la URL remota). */
export async function getModpackReadme(url: string): Promise<string> {
	if (!ready) throw new Error('IPC no configurado todavía');
	const res = await fetch(base() + '/modpacks/readme?url=' + encodeURIComponent(url), {
		cache: 'no-store',
		headers: { 'X-F24-Token': TOKEN }
	});
	if (!res.ok) throw new Error((await res.text()) || `readme → ${res.status}`);
	return res.text();
}

export const installModpack = (body: {
	id?: string;
	name: string;
	downloadUrl: string;
	mcVersion?: string;
	loader?: string;
	loaderVersion?: string;
}) => api<Instance>('/modpacks/install', { method: 'POST', body: JSON.stringify(body) });

/** Importa un modpack desde un archivo local (.f24pack/.mrpack/.zip) → instancia nueva. */
export const importModpack = (filePath: string) =>
	api<Instance>('/modpacks/import', { method: 'POST', body: JSON.stringify({ filePath }) });

export interface ExportOptions {
	outputPath: string;
	name?: string;
	minMemoryMb?: number;
	maxMemoryMb?: number;
	windowWidth?: number;
	windowHeight?: number;
	jvmArgs?: string;
	includePaths?: string[];
	includeIcon?: boolean;
	format?: 'f24pack' | 'mrpack';
}

/** Exporta una instancia a un archivo .f24pack/.mrpack en la ruta indicada. */
export const exportInstance = (id: string, opt: ExportOptions) =>
	api<void>(`/instances/${id}/export`, { method: 'POST', body: JSON.stringify(opt) });

export interface FileEntry {
	name: string;
	path: string;
	dir: boolean;
	size: number;
}

/** Lista carpetas/archivos de la instancia bajo `path` (relativo; vacío = raíz). */
export const listInstanceFiles = (id: string, path = '') =>
	api<FileEntry[]>(`/instances/${id}/files${path ? `?path=${encodeURIComponent(path)}` : ''}`);

/** Verifica y repara el contenido de la instancia (re-descarga lo corrupto/faltante). */
export const repairInstance = (id: string) =>
	api<void>(`/instances/${id}/repair`, { method: 'POST' });

export const listInstances = () => api<Instance[]>('/instances');
export const listVersions = () => api<VanillaVersion[]>('/versions/vanilla');
export const listLoaderVersions = (type: string, mc: string) =>
	api<string[]>(`/loaders/${type}/versions?mc=${encodeURIComponent(mc)}`);

export const createInstance = (body: {
	name: string;
	mcVersion: string;
	loader?: string;
	loaderVersion?: string;
	minMemoryMb?: number;
	maxMemoryMb?: number;
	windowWidth?: number;
	windowHeight?: number;
	fullscreen?: boolean;
	jvmArgs?: string;
	javaPathOverride?: string;
	/** PNG en data-URL/base64; el backend lo normaliza a 256×256. */
	iconData?: string;
}) => api<Instance>('/instances', { method: 'POST', body: JSON.stringify(body) });

export interface InstanceSettings {
	name?: string;
	minMemoryMb?: number;
	maxMemoryMb?: number;
	windowWidth?: number;
	windowHeight?: number;
	fullscreen?: boolean;
	jvmArgs?: string;
	javaPathOverride?: string;
	/** PNG en data-URL/base64 = establecer · "" = quitar · ausente = sin cambios. */
	iconData?: string;
	favorite?: boolean;
	group?: string;
}

export const updateInstance = (id: string, body: InstanceSettings) =>
	api<Instance>(`/instances/${id}`, { method: 'PATCH', body: JSON.stringify(body) });

/** URL del icono de la instancia para usar en <img> (token por query-param). */
export const instanceIconUrl = (id: string, bust = 0) =>
	`${base()}/instances/${id}/icon?token=${encodeURIComponent(TOKEN)}&t=${bust}`;

export const deleteInstanceIcon = (id: string) =>
	api<void>(`/instances/${id}/icon`, { method: 'DELETE' });

export const installInstance = (id: string) =>
	api<void>(`/instances/${id}/install`, { method: 'POST' });

export const launchInstance = (id: string, username: string) =>
	api<void>(`/instances/${id}/launch`, { method: 'POST', body: JSON.stringify({ username }) });

export const stopInstance = (id: string) => api<void>(`/instances/${id}/stop`, { method: 'POST' });

export const deleteInstance = (id: string) => api<void>(`/instances/${id}`, { method: 'DELETE' });

export const duplicateInstance = (id: string) =>
	api<Instance>(`/instances/${id}/duplicate`, { method: 'POST' });

/** Abre la carpeta de la instancia en el explorador de archivos. */
export const openInstanceFolder = (id: string) =>
	api<void>(`/instances/${id}/open`, { method: 'POST' });

/** Últimas líneas del latest.log de la instancia. */
export const getInstanceLog = (id: string) => api<string[]>(`/instances/${id}/log`);

// ── Ajustes globales ──
export interface AppSettingsDto {
	darkMode: boolean;
	showBetaVersions: boolean;
	instancesPath: string;
	defaultInstancesPath: string;
	defaultMinMemoryMb: number;
	defaultMaxMemoryMb: number;
	defaultWindowWidth: number;
	defaultWindowHeight: number;
	defaultJvmArgs: string;
	launcherWidth: number;
	launcherHeight: number;
	closeToBackground: boolean;
	minimizeOnLaunch: boolean;
}
export const getSettings = () => api<AppSettingsDto>('/settings');
export const updateSettings = (body: Partial<AppSettingsDto>) =>
	api<AppSettingsDto>('/settings', { method: 'PATCH', body: JSON.stringify(body) });

// ── Cuentas ──
export const listAccounts = () => api<Account[]>('/accounts');
export const addOfflineAccount = (username: string) =>
	api<Account>('/accounts/offline', { method: 'POST', body: JSON.stringify({ username }) });
/** Inicia el login con Microsoft (device code); devuelve el código y la URL. */
export const beginMicrosoftLogin = () =>
	api<{ userCode: string; verificationUri: string; expiresIn: number }>(
		'/accounts/microsoft/begin',
		{ method: 'POST' }
	);
export const getMicrosoftConfig = () => api<{ configured: boolean }>('/config/microsoft');
export const deleteAccount = (id: string) => api<void>(`/accounts/${id}`, { method: 'DELETE' });

// ── Skins y capas (cuenta Microsoft) ──
export interface ProfileSkin {
	id: string;
	url: string;
	variant: string;
	state: string;
}
export interface ProfileCape {
	id: string;
	url: string;
	alias: string;
	state: string;
}
export interface MinecraftProfile {
	uuid: string;
	name: string;
	skins: ProfileSkin[];
	capes: ProfileCape[];
}
export const getAccountProfile = (id: string) => api<MinecraftProfile>(`/accounts/${id}/profile`);
export const changeSkinUrl = (id: string, url: string, variant: string) =>
	api<MinecraftProfile>(`/accounts/${id}/skin`, {
		method: 'POST',
		body: JSON.stringify({ url, variant })
	});
export const uploadSkin = (id: string, data: string, fileName: string, variant: string) =>
	api<MinecraftProfile>(`/accounts/${id}/skin/upload`, {
		method: 'POST',
		body: JSON.stringify({ data, fileName, variant })
	});
export const resetSkin = (id: string) =>
	api<MinecraftProfile>(`/accounts/${id}/skin`, { method: 'DELETE' });
export const setCape = (id: string, capeId: string | null) =>
	api<MinecraftProfile>(`/accounts/${id}/cape`, {
		method: 'PUT',
		body: JSON.stringify({ capeId })
	});
export interface SkinHistoryEntry {
	url: string;
	variant: string;
	ts: number;
}
export const getSkinHistory = (id: string) =>
	api<SkinHistoryEntry[]>(`/accounts/${id}/skins`);

/** Abre una URL en el navegador del sistema (comando Tauri). */
export async function openExternal(url: string): Promise<void> {
	const { invoke } = await import('@tauri-apps/api/core');
	await invoke('open_external', { url });
}

export const health = () => fetch(`${base()}/health`).then((r) => r.ok);

// Se resuelve una sola vez al cargar el módulo (cliente). true si el IPC quedó configurado.
export const ipcReady: Promise<boolean> = initIpc();
