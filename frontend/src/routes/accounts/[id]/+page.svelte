<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import {
		ipcReady,
		getAccountProfile,
		changeSkinUrl,
		uploadSkin,
		resetSkin,
		setCape,
		getSkinHistory,
		type MinecraftProfile,
		type SkinHistoryEntry
	} from '$lib/ipc';
	import { refreshAccounts } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import SkinView from '$lib/SkinView.svelte';
	import Skin3D from '$lib/Skin3D.svelte';
	import { fade, fly } from 'svelte/transition';

	// Skins por defecto del juego, empaquetadas en static/skins.
	const DEFAULTS = [
		{ name: 'Steve', file: 'steve.png', slim: false },
		{ name: 'Alex', file: 'alex.png', slim: true },
		{ name: 'Ari', file: 'ari.png', slim: false },
		{ name: 'Efe', file: 'efe.png', slim: true },
		{ name: 'Kai', file: 'kai.png', slim: false },
		{ name: 'Makena', file: 'makena.png', slim: true },
		{ name: 'Noor', file: 'noor.png', slim: true },
		{ name: 'Sunny', file: 'sunny.png', slim: false },
		{ name: 'Zuri', file: 'zuri.png', slim: false }
	];

	const id = $derived($page.params.id ?? '');
	let profile = $state<MinecraftProfile | null>(null);
	let recents = $state<SkinHistoryEntry[]>([]);
	let loading = $state(true);
	let error = $state('');
	let busy = $state(false);
	let variant = $state<'classic' | 'slim'>('classic');
	let urlInput = $state('');
	let fileEl = $state<HTMLInputElement>();

	const activeSkin = $derived(profile?.skins.find((s) => s.state === 'ACTIVE'));
	const activeCape = $derived(profile?.capes.find((c) => c.state === 'ACTIVE'));
	const skinTex = $derived(activeSkin?.url ?? '/skins/steve.png');

	onMount(async () => {
		await ipcReady;
		await load();
	});

	async function load() {
		loading = true;
		error = '';
		try {
			profile = await getAccountProfile(id);
			if (activeSkin?.variant) variant = activeSkin.variant.toLowerCase() === 'slim' ? 'slim' : 'classic';
			recents = await getSkinHistory(id);
		} catch (e) {
			error = (e as Error).message || 'No se pudo cargar el perfil';
		} finally {
			loading = false;
		}
	}

	async function apply(p: MinecraftProfile) {
		profile = p;
		refreshAccounts();
		try {
			recents = await getSkinHistory(id);
		} catch {
			/* historial opcional */
		}
	}

	async function run(fn: () => Promise<MinecraftProfile>) {
		busy = true;
		error = '';
		try {
			await apply(await fn());
		} catch (e) {
			error = (e as Error).message || 'Operación fallida';
		} finally {
			busy = false;
		}
	}

	async function toBase64(buf: ArrayBuffer) {
		const bytes = new Uint8Array(buf);
		let bin = '';
		for (const b of bytes) bin += String.fromCharCode(b);
		return btoa(bin);
	}

	async function onFile(e: Event) {
		const file = (e.target as HTMLInputElement).files?.[0];
		if (!file) return;
		const b64 = await toBase64(await file.arrayBuffer());
		await run(() => uploadSkin(id, b64, file.name, variant));
		(e.target as HTMLInputElement).value = '';
	}

	function changeUrl() {
		const u = urlInput.trim();
		if (!u) return;
		run(() => changeSkinUrl(id, u, variant)).then(() => (urlInput = ''));
	}

	function applyDefault(d: { file: string; slim: boolean }) {
		variant = d.slim ? 'slim' : 'classic';
		run(async () => {
			const res = await fetch(`/skins/${d.file}`);
			const b64 = await toBase64(await res.arrayBuffer());
			return uploadSkin(id, b64, d.file, d.slim ? 'slim' : 'classic');
		});
	}

	function applyRecent(r: SkinHistoryEntry) {
		variant = r.variant === 'slim' ? 'slim' : 'classic';
		run(() => changeSkinUrl(id, r.url, r.variant));
	}

	function capeFace(url: string) {
		const s = 5;
		return `background-image:url(${url});background-size:${64 * s}px ${32 * s}px;background-position:-${s}px -${s}px;`;
	}
</script>

<button class="ghost back" onclick={() => goto('/')}><Icon name="arrow-left" size={15} />Inicio</button>

{#if loading}
	<p class="dim pad">Cargando perfil…</p>
{:else if error && !profile}
	<p class="err">{error}</p>
{:else if profile}
	<header in:fade={{ duration: 160 }}>
		<h1>{profile.name}</h1>
		<p class="dim">Gestioná la skin y las capas de tu cuenta de Minecraft.</p>
	</header>

	{#if error}<p class="err">{error}</p>{/if}

	<div class="grid">
		<!-- Vista previa -->
		<section class="card preview">
			<div class="render" class:busy>
				<Skin3D skin={skinTex} cape={activeCape?.url ?? ''} slim={variant === 'slim'} width={210} height={300} />
			</div>
			<div class="hint dim small">Arrastra para rotar</div>
			<div class="badge-variant">{variant === 'slim' ? 'Slim' : 'Clásico'}</div>
		</section>

		<!-- Skin -->
		<section class="card">
			<h2>Skin</h2>
			<div class="vtoggle">
				<button class:on={variant === 'classic'} disabled={busy} onclick={() => (variant = 'classic')}>
					Clásico
				</button>
				<button class:on={variant === 'slim'} disabled={busy} onclick={() => (variant = 'slim')}>
					Slim
				</button>
			</div>
			<p class="dim small">El modelo se aplica al subir o cambiar la skin.</p>

			<button class="primary" disabled={busy} onclick={() => fileEl?.click()}>
				<Icon name="download" size={15} />Subir archivo .png
			</button>
			<input
				bind:this={fileEl}
				type="file"
				accept="image/png"
				class="hidden"
				onchange={onFile}
			/>

			<div class="urlrow">
				<input
					bind:value={urlInput}
					placeholder="…o pegá una URL de skin (.png)"
					spellcheck="false"
					disabled={busy}
					onkeydown={(e) => e.key === 'Enter' && changeUrl()}
				/>
				<button class="ghost" disabled={busy || !urlInput.trim()} onclick={changeUrl}>Aplicar</button>
			</div>

			<div class="defaults">
				<span class="dim small">Skins por defecto</span>
				<div class="defrow">
					{#each DEFAULTS as d (d.name)}
						<button class="defopt" disabled={busy} title={d.name} onclick={() => applyDefault(d)}>
							<SkinView url={`/skins/${d.file}`} slim={d.slim} head height={40} />
							<span class="dname">{d.name}</span>
						</button>
					{/each}
				</div>
			</div>

			<button class="ghost danger reset" disabled={busy} onclick={() => run(() => resetSkin(id))}>
				<Icon name="refresh" size={15} />Restablecer skin por defecto
			</button>
		</section>
	</div>

	<!-- Skins usadas recientemente (historial local) -->
	{#if recents.length > 0}
		<section class="card recents">
			<h2>Usadas recientemente</h2>
			<div class="recgrid">
				{#each recents as r, i (r.url)}
					<button
						class="defopt"
						class:on={activeSkin?.url === r.url}
						disabled={busy}
						onclick={() => applyRecent(r)}
						in:fly={{ y: 8, duration: 170, delay: i * 25 }}
					>
						<SkinView url={r.url} slim={r.variant === 'slim'} height={64} />
					</button>
				{/each}
			</div>
		</section>
	{/if}

	<!-- Capas -->
	<section class="card capes">
		<h2>Capas</h2>
		{#if profile.capes.length === 0}
			<p class="dim">Esta cuenta no tiene capas.</p>
		{:else}
			<div class="capegrid">
				<button
					class="capeopt none"
					class:on={!activeCape}
					disabled={busy}
					onclick={() => run(() => setCape(id, null))}
				>
					<span class="nocape"><Icon name="package" size={22} /></span>
					<span class="cname">Sin capa</span>
				</button>
				{#each profile.capes as cape, i (cape.id)}
					<button
						class="capeopt"
						class:on={cape.state === 'ACTIVE'}
						disabled={busy}
						onclick={() => run(() => setCape(id, cape.id))}
						in:fly={{ y: 8, duration: 180, delay: i * 30 }}
					>
						<span class="capeimg" style={capeFace(cape.url)}></span>
						<span class="cname">{cape.alias || 'Capa'}</span>
						{#if cape.state === 'ACTIVE'}<span class="dot"><Icon name="check" size={13} /></span>{/if}
					</button>
				{/each}
			</div>
		{/if}
	</section>
{/if}

<style>
	.back {
		margin-bottom: 18px;
		font-size: 14px;
	}
	.dim {
		color: var(--text-dim);
	}
	.small {
		font-size: 12px;
		margin: 0;
	}
	.pad {
		padding: 22px 0;
	}
	.err {
		color: #ff8b8b;
		margin: 0 0 14px;
		font-size: 13px;
	}
	header {
		margin-bottom: 22px;
	}
	header h1 {
		font-size: 26px;
	}
	header p {
		margin-top: 6px;
		font-size: 14px;
	}
	.grid {
		display: grid;
		grid-template-columns: 280px 1fr;
		gap: 16px;
		margin-bottom: 16px;
	}
	.card {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 20px;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.card h2 {
		font-size: 16px;
	}
	.preview {
		align-items: center;
		justify-content: center;
	}
	.render {
		min-height: 280px;
		display: grid;
		place-items: center;
		transition: opacity 0.2s ease;
	}
	.render.busy {
		opacity: 0.45;
	}
	.hint {
		margin-top: 2px;
		user-select: none;
	}
	.badge-variant {
		font-size: 12px;
		color: var(--text-dim);
		background: var(--bg-elev);
		border: 1px solid var(--border);
		padding: 4px 12px;
		border-radius: 20px;
	}
	.vtoggle {
		display: inline-flex;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 9px;
		padding: 3px;
		gap: 3px;
		align-self: flex-start;
	}
	.vtoggle button {
		background: transparent;
		border: none;
		color: var(--text-dim);
		padding: 6px 18px;
		border-radius: 6px;
		font-size: 13px;
	}
	.vtoggle button.on {
		background: var(--accent);
		color: #1c1500;
		font-weight: 600;
	}
	.hidden {
		display: none;
	}
	.urlrow {
		display: flex;
		gap: 8px;
	}
	input:not([type='file']):not(.hidden) {
		flex: 1;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 9px 11px;
		font-family: inherit;
		font-size: 13px;
	}
	.defaults {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-top: 2px;
	}
	.defrow {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}
	.defopt {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 10px 14px;
		cursor: pointer;
	}
	.defopt:hover {
		border-color: var(--accent);
	}
	.defopt.on {
		border-color: var(--accent);
		box-shadow: 0 0 0 1px var(--accent);
	}
	.dname {
		font-size: 11px;
		color: var(--text-dim);
	}
	.recents {
		margin-top: 16px;
	}
	.recgrid {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}
	.reset {
		align-self: flex-start;
		margin-top: 2px;
	}
	.danger:hover {
		color: #ff6b6b;
		border-color: #ff6b6b;
	}
	.capes {
		gap: 16px;
	}
	.capegrid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
		gap: 12px;
	}
	.capeopt {
		position: relative;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 12px;
		padding: 14px 8px 10px;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		cursor: pointer;
	}
	.capeopt:hover {
		border-color: var(--accent-dim);
	}
	.capeopt.on {
		border-color: var(--accent);
		box-shadow: 0 0 0 1px var(--accent);
	}
	.capeimg {
		width: 50px;
		height: 80px;
		border-radius: 4px;
		image-rendering: pixelated;
		background-repeat: no-repeat;
	}
	.nocape {
		width: 50px;
		height: 80px;
		display: grid;
		place-items: center;
		color: var(--text-dim);
		border: 1px dashed var(--border);
		border-radius: 4px;
	}
	.cname {
		font-size: 12px;
		color: var(--text-dim);
		text-align: center;
	}
	.dot {
		position: absolute;
		top: 8px;
		right: 8px;
		background: var(--accent);
		color: #1c1500;
		width: 20px;
		height: 20px;
		border-radius: 50%;
		display: grid;
		place-items: center;
	}
	@media (max-width: 720px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
