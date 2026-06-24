// Logger del frontend (Fase L3 de la 0.0.3).
//
// Captura los console.* y los errores no controlados del webview y los envía a
// Rust (comando `client_log`), que los persiste en logs/frontend-latest.log. El
// envío va por lotes (cada 500 ms) para no saturar el puente con el backend.
//
// Las líneas se formatean aquí (fecha ISO + nivel + origen) para que coincidan con
// las que escribe Rust. Fuera de Tauri (navegador en dev) el envío falla en
// silencio: la consola ya mostró el mensaje.

type Level = 'INFO' | 'WARN' | 'ERROR' | 'DEBUG';

type InvokeFn = (cmd: string, args?: Record<string, unknown>) => Promise<unknown>;

let queue: string[] = [];
let flushTimer: ReturnType<typeof setTimeout> | null = null;
let invokeFn: InvokeFn | null = null;
let invokeTried = false;
let installed = false;

function stringify(a: unknown): string {
	if (typeof a === 'string') return a;
	if (a instanceof Error) return a.stack || `${a.name}: ${a.message}`;
	try {
		return JSON.stringify(a);
	} catch {
		return String(a);
	}
}

function fmt(level: Level, args: unknown[]): string {
	return `${new Date().toISOString()} [${level}] [ui] ${args.map(stringify).join(' ')}`;
}

/** Registra una línea de log del frontend (se envía por lote). */
export function flog(level: Level, ...args: unknown[]) {
	queue.push(fmt(level, args));
	if (!flushTimer) flushTimer = setTimeout(flush, 500);
}

async function flush() {
	flushTimer = null;
	if (!queue.length) return;
	const lines = queue;
	queue = [];
	try {
		if (!invokeFn && !invokeTried) {
			invokeTried = true;
			try {
				const core = await import('@tauri-apps/api/core');
				invokeFn = core.invoke as unknown as InvokeFn;
			} catch {
				invokeFn = null;
			}
		}
		if (invokeFn) await invokeFn('client_log', { lines });
	} catch {
		/* fuera de Tauri o puente no disponible: se descartan (la consola ya las mostró) */
	}
}

/**
 * Instala (una sola vez) la captura de console.* y de los errores globales del
 * webview. Llamar al arrancar la app (layout raíz).
 */
export function initClientLog() {
	if (installed || typeof window === 'undefined') return;
	installed = true;

	const methods: [keyof Console, Level][] = [
		['log', 'INFO'],
		['info', 'INFO'],
		['warn', 'WARN'],
		['error', 'ERROR'],
		['debug', 'DEBUG']
	];
	for (const [method, level] of methods) {
		const orig = console[method] as (...a: unknown[]) => void;
		(console as unknown as Record<string, unknown>)[method] = (...args: unknown[]) => {
			try {
				flog(level, ...args);
			} catch {
				/* nunca romper la consola */
			}
			orig.apply(console, args);
		};
	}

	window.addEventListener('error', (e) => {
		const where = e.filename ? ` (${e.filename}:${e.lineno}:${e.colno})` : '';
		flog('ERROR', 'window.onerror:', e.message + where);
	});
	window.addEventListener('unhandledrejection', (e) => {
		flog('ERROR', 'unhandledrejection:', (e as PromiseRejectionEvent).reason);
	});
	window.addEventListener('beforeunload', () => {
		void flush();
	});
	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'hidden') void flush();
	});

	flog('INFO', `Logger del frontend iniciado · ${navigator.userAgent}`);
}
