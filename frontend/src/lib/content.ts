import type { ContentType } from './ipc';

export const TYPES: { id: ContentType; label: string }[] = [
	{ id: 'mods', label: 'Mods' },
	{ id: 'resourcepacks', label: 'Resource Packs' },
	{ id: 'datapacks', label: 'Data Packs' },
	{ id: 'shaders', label: 'Shaders' }
];

export const TYPE_LABEL: Record<ContentType, string> = {
	mods: 'Mods',
	resourcepacks: 'Resource Packs',
	datapacks: 'Data Packs',
	shaders: 'Shaders'
};

export function formatCount(n: number): string {
	if (n >= 1_000_000) return (n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 2) + 'M';
	if (n >= 1_000) return (n / 1_000).toFixed(n >= 10_000 ? 0 : 1) + 'K';
	return String(n);
}

export function timeAgo(iso: string): string {
	if (!iso) return '';
	const then = new Date(iso).getTime();
	if (isNaN(then)) return '';
	const s = Math.floor((Date.now() - then) / 1000);
	if (s < 60) return 'ahora';
	const m = Math.floor(s / 60);
	if (m < 60) return `hace ${m} min`;
	const h = Math.floor(m / 60);
	if (h < 24) return `hace ${h} h`;
	const d = Math.floor(h / 24);
	if (d < 30) return `hace ${d} d`;
	const mo = Math.floor(d / 30);
	if (mo < 12) return `hace ${mo} mes${mo > 1 ? 'es' : ''}`;
	return `hace ${Math.floor(mo / 12)} año${mo >= 24 ? 's' : ''}`;
}

export function formatSize(bytes: number): string {
	if (!bytes) return '';
	if (bytes < 1024) return bytes + ' B';
	const u = ['KB', 'MB', 'GB'];
	let i = -1;
	let n = bytes;
	do {
		n /= 1024;
		i++;
	} while (n >= 1024 && i < u.length - 1);
	return n.toFixed(1) + ' ' + u[i];
}
