<script lang="ts">
	// Render frontal de una skin a partir de su textura (64×64), por recortes CSS.
	// Fiable en el WebView (las texturas de Mojang sí cargan) y se actualiza al
	// instante al cambiar la URL. `head` muestra solo la cabeza (válido también
	// para texturas legacy 64×32, donde la cara está en la misma posición).
	interface Props {
		url: string;
		slim?: boolean;
		height?: number;
		head?: boolean;
	}
	let { url, slim = false, height = 256, head = false }: Props = $props();

	const unit = $derived(head ? height / 8 : height / 32);
	const aw = $derived(slim ? 3 : 4);
	const total = $derived(head ? 8 : 8 + 2 * aw);

	type Part = { l: number; t: number; w: number; h: number; sx: number; sy: number };
	const parts = $derived.by((): Part[] => {
		if (head) {
			return [
				{ l: 0, t: 0, w: 8, h: 8, sx: 8, sy: 8 },
				{ l: 0, t: 0, w: 8, h: 8, sx: 40, sy: 8 }
			];
		}
		const a = aw;
		return [
			{ l: a, t: 0, w: 8, h: 8, sx: 8, sy: 8 }, // cabeza
			{ l: a, t: 8, w: 8, h: 12, sx: 20, sy: 20 }, // torso
			{ l: 0, t: 8, w: a, h: 12, sx: 44, sy: 20 }, // brazo der.
			{ l: a + 8, t: 8, w: a, h: 12, sx: 36, sy: 52 }, // brazo izq.
			{ l: a, t: 20, w: 4, h: 12, sx: 4, sy: 20 }, // pierna der.
			{ l: a + 4, t: 20, w: 4, h: 12, sx: 20, sy: 52 }, // pierna izq.
			{ l: a, t: 0, w: 8, h: 8, sx: 40, sy: 8 }, // sombrero
			{ l: a, t: 8, w: 8, h: 12, sx: 20, sy: 36 }, // chaqueta
			{ l: 0, t: 8, w: a, h: 12, sx: 44, sy: 36 }, // manga der.
			{ l: a + 8, t: 8, w: a, h: 12, sx: 52, sy: 52 }, // manga izq.
			{ l: a, t: 20, w: 4, h: 12, sx: 4, sy: 36 }, // pantalón der.
			{ l: a + 4, t: 20, w: 4, h: 12, sx: 4, sy: 52 } // pantalón izq.
		];
	});

	function layer(p: Part) {
		return (
			`left:${p.l * unit}px;top:${p.t * unit}px;width:${p.w * unit}px;height:${p.h * unit}px;` +
			`background-image:url("${url}");background-size:${64 * unit}px ${64 * unit}px;` +
			`background-position:-${p.sx * unit}px -${p.sy * unit}px;`
		);
	}
</script>

<div class="skin" style="width:{total * unit}px;height:{(head ? 8 : 32) * unit}px;">
	{#each parts as p}<span class="layer" style={layer(p)}></span>{/each}
</div>

<style>
	.skin {
		position: relative;
	}
	.layer {
		position: absolute;
		image-rendering: pixelated;
		background-repeat: no-repeat;
	}
</style>
