// Unifica los nombres de categoría de Modrinth y CurseForge a una etiqueta
// canónica, para no mostrar duplicados como "Optimization" (Modrinth) y
// "Performance" (CurseForge), que son la misma cosa.

const UNIFY: Record<string, string> = {
	optimization: 'Rendimiento',
	performance: 'Rendimiento',

	utility: 'Utilidades',
	'utility & qol': 'Utilidades',
	miscellaneous: 'Utilidades',
	misc: 'Utilidades',

	'adventure and rpg': 'Aventura',
	adventure: 'Aventura',

	technology: 'Tecnología',
	tech: 'Tecnología',
	automation: 'Tecnología',

	magic: 'Magia',

	storage: 'Almacenamiento',

	'world generation': 'Generación de mundo',
	worldgen: 'Generación de mundo',
	'map and information': 'Mapa e información',
	'map based': 'Mapa e información',

	'api and library': 'Librerías',
	library: 'Librerías',
	libraries: 'Librerías',

	decoration: 'Decoración',

	'mobs and animals': 'Criaturas',
	mobs: 'Criaturas',

	'armor, tools, and weapons': 'Equipo',
	'equipment': 'Equipo',

	food: 'Comida',
	cursed: 'Cursed',
	'social': 'Social',
	'game mechanics': 'Mecánicas'
};

function titleCase(s: string): string {
	return s
		.split(/\s+/)
		.map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
		.join(' ');
}

/** Etiqueta canónica unificada para una categoría cruda de cualquier fuente. */
export function canonicalCategory(raw: string): string {
	if (!raw) return '';
	const key = raw.trim().toLowerCase();
	return UNIFY[key] ?? titleCase(raw.trim());
}

/** Conjunto único de etiquetas canónicas, ordenado alfabéticamente. */
export function canonicalSet(raws: string[]): string[] {
	return [...new Set(raws.map(canonicalCategory).filter(Boolean))].sort((a, b) =>
		a.localeCompare(b)
	);
}

/** ¿Coincide la categoría canónica de un elemento con la etiqueta seleccionada? */
export function matchesCanonical(itemCats: string[], canonLabel: string): boolean {
	return itemCats.some((c) => canonicalCategory(c) === canonLabel);
}
