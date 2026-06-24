import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

// La config de Svelte/Kit (adapter, preprocess, compilerOptions) vive en
// svelte.config.js. Aquí solo va lo de Vite/servidor de desarrollo.
export default defineConfig({
	plugins: [sveltekit()],

	// Config requerida por Tauri en dev:
	clearScreen: false,
	server: {
		port: 5173,
		strictPort: true,
		// No vigilar src-tauri/ (target/ tiene DLLs bloqueadas por el linker → EBUSY).
		watch: { ignored: ['**/src-tauri/**'] }
	}
});
