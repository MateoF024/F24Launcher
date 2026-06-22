// Procesado de líneas de log de Minecraft / del launcher.

export type LogLevel = 'info' | 'warn' | 'error' | 'fatal' | 'f24';
export interface LogLine {
	text: string;
	level: LogLevel;
}

/** Deduce el nivel de una línea de log. */
export function classify(line: string): LogLevel {
	if (line.startsWith('[F24]')) return 'f24';
	if (line.includes('/FATAL]')) return 'fatal';
	if (line.includes('/ERROR]') || line.includes('Exception') || /^\s+at\s/.test(line))
		return 'error';
	if (line.includes('/WARN]')) return 'warn';
	return 'info';
}

// Mensajes de error que SOLO aparecen en modo offline (la cuenta no se puede
// autenticar contra los servicios de Mojang). Son ruido y se suprimen.
const NOISE = [
	'Failed to fetch user properties',
	'Could not authorize you against Realms',
	'Failed to fetch Realms feature flags',
	'Failed to fetch Realms',
	"Couldn't connect to realms"
];

const ENTRY = /^\[\d{2}:\d{2}:\d{2}\]/;

/**
 * Suprime los bloques de error de autenticación offline, incluida su traza
 * completa: al detectar una línea de log "ruido", descarta esa entrada y todas
 * las líneas de continuación (stacktrace) hasta la siguiente entrada de log.
 */
export function filterOfflineNoise(lines: string[]): string[] {
	const out: string[] = [];
	let suppressing = false;
	for (const line of lines) {
		if (line.startsWith('[F24]')) {
			suppressing = false;
			out.push(line);
			continue;
		}
		if (ENTRY.test(line)) {
			suppressing = NOISE.some((n) => line.includes(n));
			if (!suppressing) out.push(line);
		} else if (!suppressing) {
			out.push(line); // línea de continuación (stacktrace) de una entrada visible
		}
	}
	return out;
}

/** Filtra el ruido offline y clasifica cada línea para mostrarla. */
export function processLog(lines: string[]): LogLine[] {
	return filterOfflineNoise(lines).map((text) => ({ text, level: classify(text) }));
}
