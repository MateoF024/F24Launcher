<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import {
		ipcReady,
		listModpacks,
		installModpack,
		updateModpackInstance,
		isNewerVersion,
		hasLite,
		modpackUrl,
		type Modpack
	} from '$lib/ipc';
	import { ui, refreshInstances, setProgress, setStatus } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fly } from 'svelte/transition';

	let modpacks = $state<Modpack[]>([]);
	let loading = $state(true);
	let error = $state('');
	// Variante elegida por modpack (id → 'standard' | 'lite'); por defecto estándar.
	let variants = $state<Record<string, 'standard' | 'lite'>>({});
	const variantOf = (mp: Modpack): 'standard' | 'lite' => variants[mp.id] ?? 'standard';

	onMount(load);

	async function load() {
		loading = true;
		error = '';
		try {
			await ipcReady;
			modpacks = await listModpacks();
		} catch (e) {
			error = (e as Error).message || 'No se pudo cargar el catálogo';
			modpacks = [];
		} finally {
			loading = false;
		}
	}

	function instOf(mp: Modpack) {
		return ui.instances.find((i) => i.sourceModpackId === mp.id);
	}

	async function doUpdate(mp: Modpack) {
		const inst = instOf(mp);
		if (!inst) return;
		try {
			await updateModpackInstance(inst.id);
			setStatus(inst.id, 'installing');
			setProgress(inst.id, { phase: 'Actualizando modpack', done: 0, total: 1 });
			await refreshInstances();
		} catch (e) {
			error = (e as Error).message || 'No se pudo actualizar el modpack';
		}
	}

	function pct(p: { done: number; total: number }) {
		return p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
	}

	function openCard(e: Event, mp: Modpack) {
		if ((e.target as HTMLElement).closest('button')) return;
		goto(`/modpacks/${mp.id}`);
	}

	async function install(mp: Modpack) {
		try {
			const v = variantOf(mp);
			const inst = await installModpack({
				id: mp.id,
				name: mp.name,
				downloadUrl: modpackUrl(mp, v),
				mcVersion: mp.mcVersion,
				loader: mp.loader,
				loaderVersion: mp.loaderVersion,
				version: mp.version,
				variant: v,
				icon: mp.icon
			});
			setStatus(inst.id, 'installing');
			setProgress(inst.id, { phase: 'Preparando', done: 0, total: 1 });
			await refreshInstances();
		} catch (e) {
			error = (e as Error).message || 'No se pudo instalar el modpack';
		}
	}
</script>

<header>
	<h1>Modpacks</h1>
	<button class="ghost xs" title="Recargar catálogo" onclick={load} disabled={loading}>
		<Icon name="refresh" size={15} />
	</button>
</header>
<p class="dim sub">Catálogo privado de modpacks. Se actualiza en línea.</p>

{#if loading}
	<p class="dim pad">Cargando catálogo…</p>
{:else if error}
	<div class="errbox">
		<p>{error}</p>
		<button onclick={load}>Reintentar</button>
	</div>
{:else if modpacks.length === 0}
	<div class="empty">
		<p>No hay modpacks disponibles por el momento.</p>
		<p class="dim">Cuando se publiquen nuevos, aparecerán acá.</p>
	</div>
{:else}
	<div class="grid">
		{#each modpacks as mp, i (mp.id)}
			{@const inst = instOf(mp)}
			{@const prog = inst ? ui.progress[inst.id] : undefined}
			{@const status = inst ? ui.status[inst.id] : ''}
			{@const installing = !!inst && !inst.installed && status !== 'error'}
			<div
				class="card"
				in:fly={{ y: 12, duration: 260, delay: Math.min(i * 40, 300) }}
				role="button"
				tabindex="0"
				title="Ver detalles"
				onclick={(e) => openCard(e, mp)}
				onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), goto(`/modpacks/${mp.id}`))}
			>
				<div class="top">
					{#if mp.icon}
						<img class="icon" src={mp.icon} alt="" loading="lazy" />
					{:else}
						<div class="icon ph"><Icon name="package" size={26} /></div>
					{/if}
					<div class="info">
						<div class="name">{mp.name}</div>
						{#if mp.summary}<div class="dim summary">{mp.summary}</div>{/if}
						<div class="tags">
							{#if mp.mcVersion}<span class="tag">MC {mp.mcVersion}</span>{/if}
							{#if mp.loader}<span class="tag cap">{mp.loader}</span>{/if}
							{#if !mp.mcVersion && !mp.loader}<span class="tag dim">se detecta al instalar</span>{/if}
						</div>
					</div>
				</div>

				{#if inst && inst.installed && !prog}
					{#if isNewerVersion(mp.version, inst.modpackVersion)}
						<button class="upd" onclick={() => doUpdate(mp)} title="Actualizar a v{mp.version}">
							<Icon name="arrow-up" size={15} />Actualizar
						</button>
					{/if}
					<button class="manage" onclick={() => goto(`/instance/${inst.id}`)}>
						<Icon name="gear" size={15} />Administrar
					</button>
				{:else if installing}
					<div class="prog">
						{#if prog}
							<div class="bar"><div class="fill" style="width:{pct(prog)}%"></div></div>
							<div class="dim small">{prog.phase} · {pct(prog)}%</div>
						{:else}
							<div class="dim small">Preparando…</div>
						{/if}
					</div>
				{:else}
					{#if hasLite(mp)}
						<div class="variants" role="group" aria-label="Variante del modpack">
							<button
								class:sel={variantOf(mp) === 'standard'}
								onclick={() => (variants = { ...variants, [mp.id]: 'standard' })}>Estándar</button
							>
							<button
								class:sel={variantOf(mp) === 'lite'}
								onclick={() => (variants = { ...variants, [mp.id]: 'lite' })}>Lite</button
							>
						</div>
					{/if}
					<button class="primary" disabled={!ui.connected} onclick={() => install(mp)}>
						<Icon name="download" size={15} />Instalar
					</button>
				{/if}
			</div>
		{/each}
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	header h1 {
		font-size: 22px;
	}
	.sub {
		font-size: 13px;
		margin: 2px 0 20px;
	}
	.dim {
		color: var(--text-dim);
	}
	.pad {
		padding: 24px 0;
	}
	.xs {
		padding: 5px 10px;
		font-size: 14px;
	}
	.errbox {
		border: 1px solid rgba(255, 107, 107, 0.4);
		background: rgba(255, 107, 107, 0.1);
		color: #ff9b9b;
		border-radius: var(--radius);
		padding: 20px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		align-items: flex-start;
	}
	.empty {
		border: 1px dashed var(--border);
		border-radius: var(--radius);
		padding: 40px;
		text-align: center;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
		gap: 14px;
		padding-bottom: 24px;
	}
	.card {
		display: flex;
		flex-direction: column;
		gap: 12px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 16px;
		cursor: pointer;
		transition:
			border-color 0.15s ease,
			transform 0.15s ease;
	}
	.card:hover {
		border-color: var(--accent-dim);
		transform: translateY(-2px);
	}
	.card:hover .name {
		color: var(--accent);
	}
	.top {
		display: flex;
		gap: 12px;
	}
	.icon {
		width: 56px;
		height: 56px;
		border-radius: 12px;
		object-fit: cover;
		background: var(--bg-elev);
		flex-shrink: 0;
	}
	.icon.ph {
		display: grid;
		place-items: center;
		font-size: 26px;
	}
	.info {
		flex: 1;
		min-width: 0;
	}
	.name {
		font-weight: 700;
		font-size: 16px;
	}
	.summary {
		font-size: 13px;
		margin: 4px 0 8px;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.tags {
		display: flex;
		gap: 6px;
		flex-wrap: wrap;
	}
	.tag {
		font-size: 11px;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		color: var(--text-dim);
		padding: 2px 8px;
		border-radius: 6px;
	}
	.cap {
		text-transform: capitalize;
	}
	.primary,
	.manage,
	.upd {
		width: 100%;
	}
	.upd {
		background: #3fa34d;
		color: #fff;
		font-weight: 700;
	}
	.upd:hover {
		background: #348540;
	}
	.variants {
		display: flex;
		border: 1px solid var(--border);
		border-radius: 8px;
		overflow: hidden;
	}
	.variants button {
		flex: 1;
		background: var(--bg-elev);
		color: var(--text-dim);
		border: none;
		border-radius: 0;
		padding: 7px 0;
		font-size: 12px;
	}
	.variants button.sel {
		background: var(--accent);
		color: #fff;
		font-weight: 600;
	}
	.manage {
		background: var(--bg-elev);
		color: var(--text);
		border: 1px solid var(--border);
		font-weight: 600;
	}
	.manage:hover {
		border-color: var(--accent);
		color: var(--accent);
	}
	.prog .bar {
		height: 8px;
		background: var(--bg-elev);
		border-radius: 4px;
		overflow: hidden;
	}
	.prog .fill {
		height: 100%;
		background: var(--accent);
		transition: width 0.2s ease;
	}
	.prog .small {
		margin-top: 5px;
	}
</style>
