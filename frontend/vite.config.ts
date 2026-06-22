import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},

			// SPA estática para Tauri: todo se sirve como archivos; el routing
			// es client-side (ssr off + fallback index.html, ver +layout.ts).
			adapter: adapter({ fallback: 'index.html' })
		})
	],

	// Config requerida por Tauri en dev:
	clearScreen: false,
	server: {
		port: 5173,
		strictPort: true,
		// No vigilar src-tauri/ (target/ tiene DLLs bloqueadas por el linker → EBUSY).
		watch: { ignored: ['**/src-tauri/**'] }
	}
});
