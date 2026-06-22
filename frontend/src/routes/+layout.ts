// SPA estática para Tauri: sin SSR ni prerender; el routing es client-side y
// el adapter-static genera un fallback index.html (ver vite.config.ts).
export const ssr = false;
export const prerender = false;
