<script lang="ts">
	// Iconos vectoriales propios (sin emojis). Trazo de 24x24, hereda currentColor.
	let {
		name,
		size = 16,
		stroke = 2,
		fill = false
	}: { name: string; size?: number; stroke?: number; fill?: boolean } = $props();
</script>

<svg
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill={fill ? 'currentColor' : 'none'}
	stroke="currentColor"
	stroke-width={stroke}
	stroke-linecap="round"
	stroke-linejoin="round"
	aria-hidden="true"
	class="icon"
>
	{#if name === 'arrow-left'}
		<path d="M19 12H5M12 19l-7-7 7-7" />
	{:else if name === 'gear'}
		<circle cx="12" cy="12" r="3" />
		<path
			d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
		/>
	{:else if name === 'play'}
		<path d="M8 5v14l11-7z" fill="currentColor" stroke="none" />
	{:else if name === 'stop'}
		<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" />
	{:else if name === 'refresh'}
		<path d="M23 4v6h-6M1 20v-6h6" />
		<path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
	{:else if name === 'package'}
		<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
		<path d="m3.27 6.96 8.73 5.05 8.73-5.05M12 22.08V12" />
	{:else if name === 'arrow-up'}
		<path d="M12 19V5M5 12l7-7 7 7" />
	{:else if name === 'download'}
		<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
	{:else if name === 'trash'}
		<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
		<path d="M10 11v6M14 11v6" />
	{:else if name === 'swap'}
		<path d="M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3" />
	{:else if name === 'lock'}
		<rect x="3" y="11" width="18" height="11" rx="2" />
		<path d="M7 11V7a5 5 0 0 1 10 0v4" />
	{:else if name === 'unlock'}
		<rect x="3" y="11" width="18" height="11" rx="2" />
		<path d="M7 11V7a5 5 0 0 1 9.9-1" />
	{:else if name === 'monitor'}
		<rect x="2" y="3" width="20" height="14" rx="2" />
		<path d="M8 21h8M12 17v4" />
	{:else if name === 'server'}
		<rect x="2" y="2" width="20" height="8" rx="2" />
		<rect x="2" y="14" width="20" height="8" rx="2" />
		<path d="M6 6h.01M6 18h.01" />
	{:else if name === 'plus'}
		<path d="M12 5v14M5 12h14" />
	{:else if name === 'chevron-down'}
		<path d="M6 9l6 6 6-6" />
	{:else if name === 'chevron-right'}
		<path d="M9 18l6-6-6-6" />
	{:else if name === 'chevron-left'}
		<path d="M15 18l-6-6 6-6" />
	{:else if name === 'check'}
		<path d="M20 6L9 17l-5-5" />
	{:else if name === 'heart'}
		<path
			d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
			fill={fill ? 'currentColor' : 'none'}
		/>
	{:else if name === 'star'}
		<path
			d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
			fill={fill ? 'currentColor' : 'none'}
		/>
	{:else if name === 'wrench'}
		<path
			d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18v3h3l6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.3-2.3 2.8-2.7z"
		/>
	{:else if name === 'clock'}
		<circle cx="12" cy="12" r="10" />
		<path d="M12 6v6l4 2" />
	{:else if name === 'external'}
		<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
	{:else if name === 'logout'}
		<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
	{:else if name === 'key'}
		<circle cx="7.5" cy="15.5" r="5.5" />
		<path d="M11.4 11.6 21 2M16 7l3 3" />
	{:else if name === 'user'}
		<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
		<circle cx="12" cy="7" r="4" />
	{:else if name === 'copy'}
		<rect x="9" y="9" width="13" height="13" rx="2" />
		<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
	{:else if name === 'search'}
		<circle cx="11" cy="11" r="8" />
		<path d="M21 21l-4.35-4.35" />
	{:else if name === 'close'}
		<path d="M18 6 6 18M6 6l12 12" />
	{:else if name === 'sun'}
		<circle cx="12" cy="12" r="4" />
		<path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
	{:else if name === 'moon'}
		<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
	{:else if name === 'sliders'}
		<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6" />
	{:else if name === 'folder'}
		<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
	{:else if name === 'terminal'}
		<path d="M4 17l6-6-6-6M12 19h8" />
	{:else}
		<circle cx="12" cy="12" r="9" />
	{/if}
</svg>

<style>
	.icon {
		display: inline-block;
		vertical-align: -0.15em;
		flex-shrink: 0;
	}
</style>
