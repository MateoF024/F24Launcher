import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	// Despoja TypeScript (incl. parámetros opcionales) de los componentes antes de
	// compilar; sin esto el build de producción (rolldown/Vite 8) falla al parsear.
	preprocess: vitePreprocess(),

	compilerOptions: {
		// Fuerza el modo runes en el proyecto (no en node_modules). Quitable en Svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},

	kit: {
		// SPA estática para Tauri: routing client-side, fallback index.html.
		adapter: adapter({ fallback: 'index.html' })
	}
};

export default config;
