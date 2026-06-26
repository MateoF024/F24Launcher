<script lang="ts">
	// Render 3D de la skin (skinview3d sobre Three.js), rotable con el mouse. Reemplaza al
	// render frontal 2D (SkinView) en la vista grande de la cuenta. skinview3d se importa de
	// forma dinámica para no cargar Three.js hasta que esta vista se usa.
	import { onMount, onDestroy } from 'svelte';
	import type { SkinViewer } from 'skinview3d';

	interface Props {
		skin: string;
		cape?: string;
		slim?: boolean;
		width?: number;
		height?: number;
	}
	let { skin, cape = '', slim = false, width = 200, height = 300 }: Props = $props();

	let canvas: HTMLCanvasElement;
	let viewer = $state<SkinViewer | null>(null);

	onMount(async () => {
		const { SkinViewer } = await import('skinview3d');
		const v = new SkinViewer({ canvas, width, height });
		v.controls.enableZoom = false; // solo rotar (no acercar/alejar ni desplazar)
		v.controls.enablePan = false;
		v.autoRotate = false;
		viewer = v;
	});

	// Carga/actualiza skin, capa y modelo al cambiar las props (ya creado el visor).
	$effect(() => {
		const v = viewer;
		if (!v) return;
		const s = skin;
		const c = cape;
		const sl = slim;
		if (s) v.loadSkin(s, { model: sl ? 'slim' : 'default' }).catch(() => {});
		if (c) v.loadCape(c).catch(() => {});
		else v.resetCape();
	});

	onDestroy(() => {
		viewer?.dispose();
		viewer = null;
	});
</script>

<canvas bind:this={canvas} class="skin3d" aria-label="Vista 3D de la skin"></canvas>

<style>
	.skin3d {
		display: block;
		cursor: grab;
		touch-action: none;
	}
	.skin3d:active {
		cursor: grabbing;
	}
</style>
