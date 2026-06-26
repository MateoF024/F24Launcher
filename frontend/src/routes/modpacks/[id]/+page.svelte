<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { marked } from 'marked';
	import {
		ipcReady,
		listModpacks,
		installModpack,
		updateModpackInstance,
		isNewerVersion,
		hasLite,
		modpackUrl,
		getModpackReadme,
		type Modpack
	} from '$lib/ipc';
	import { ui, refreshInstances, setProgress, setStatus } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fade } from 'svelte/transition';

	const id = $derived($page.params.id ?? '');
	let mp = $state<Modpack | null>(null);
	let loading = $state(true);
	let error = $state('');
	let bodyHtml = $state('');
	let bodyLoading = $state(false);
	let variant = $state<'standard' | 'lite'>('standard');

	const inst = $derived(mp ? ui.instances.find((i) => i.sourceModpackId === mp!.id) : undefined);
	const prog = $derived(inst ? ui.progress[inst.id] : undefined);
	const status = $derived(inst ? ui.status[inst.id] : '');
	const canUpdate = $derived(
		!!inst && inst.installed && !!mp && isNewerVersion(mp.version, inst.modpackVersion)
	);

	onMount(async () => {
		try {
			await ipcReady;
			const list = await listModpacks();
			mp = list.find((m) => m.id === id) ?? null;
			if (!mp) {
				error = 'Modpack no encontrado.';
				return;
			}
			if (mp.descriptionUrl) {
				bodyLoading = true;
				try {
					const md = await getModpackReadme(mp.descriptionUrl);
					bodyHtml = await marked.parse(md);
				} catch {
					bodyHtml = '';
				} finally {
					bodyLoading = false;
				}
			}
		} catch (e) {
			error = (e as Error).message || 'No se pudo cargar el modpack';
		} finally {
			loading = false;
		}
	});

	function pct(p: { done: number; total: number }) {
		return p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
	}

	async function doUpdate() {
		if (!inst) return;
		try {
			await updateModpackInstance(inst.id);
			setStatus(inst.id, 'installing');
			setProgress(inst.id, { phase: 'Actualizando modpack', done: 0, total: 1 });
			await refreshInstances();
		} catch (e) {
			error = (e as Error).message || 'No se pudo actualizar';
		}
	}

	async function install() {
		if (!mp) return;
		try {
			const created = await installModpack({
				id: mp.id,
				name: mp.name,
				downloadUrl: modpackUrl(mp, variant),
				mcVersion: mp.mcVersion,
				loader: mp.loader,
				loaderVersion: mp.loaderVersion,
				version: mp.version,
				variant,
				icon: mp.icon
			});
			setStatus(created.id, 'installing');
			setProgress(created.id, { phase: 'Preparando', done: 0, total: 1 });
			await refreshInstances();
		} catch (e) {
			error = (e as Error).message || 'No se pudo instalar';
		}
	}
</script>

<button class="ghost back" onclick={() => goto('/modpacks')}><Icon name="arrow-left" size={15} />Modpacks</button>

{#if loading}
	<p class="dim pad">Cargando…</p>
{:else if error}
	<p class="err">{error}</p>
{:else if mp}
	<div class="head">
		{#if mp.icon}
			<img class="icon" src={mp.icon} alt="" />
		{:else}
			<div class="icon ph"><Icon name="package" size={36} /></div>
		{/if}
		<div class="meta">
			<h1>{mp.name}</h1>
			{#if mp.summary}<p class="dim summary">{mp.summary}</p>{/if}
			<div class="tags">
				{#if mp.mcVersion}<span class="tag">MC {mp.mcVersion}</span>{/if}
				{#if mp.loader}<span class="tag cap">{mp.loader}</span>{/if}
				{#if mp.version}<span class="tag">v{mp.version}</span>{/if}
			</div>
		</div>
		<div class="action">
			{#if inst && inst.installed && !prog}
				{#if canUpdate}
					<button class="primary upd" onclick={doUpdate} title="Actualizar a la versión {mp.version}">
						<Icon name="arrow-up" size={15} />Actualizar
					</button>
				{/if}
				<button class="manage" onclick={() => goto(`/instance/${inst.id}`)}>
					<Icon name="gear" size={15} />Administrar
				</button>
			{:else if inst && !inst.installed && status !== 'error'}
				<div class="prog">
					{#if prog}
						<div class="bar"><div class="fill" style="width:{pct(prog)}%"></div></div>
						<div class="dim small">{prog.phase} · {pct(prog)}%</div>
					{:else}
						<div class="dim small">Preparando…</div>
					{/if}
				</div>
			{:else}
				<div class="installbox">
					{#if hasLite(mp)}
						<div class="variants" role="group" aria-label="Variante del modpack">
							<button class:sel={variant === 'standard'} onclick={() => (variant = 'standard')}>Estándar</button>
							<button class:sel={variant === 'lite'} onclick={() => (variant = 'lite')}>Lite</button>
						</div>
					{/if}
					<button class="primary" disabled={!ui.connected} onclick={install}>
						<Icon name="download" size={15} />Instalar
					</button>
				</div>
			{/if}
		</div>
	</div>

	{#if bodyLoading}
		<p class="dim pad">Cargando descripción…</p>
	{:else if bodyHtml}
		<!-- markdown remoto del modpack (app privada) -->
		<div class="body" in:fade={{ duration: 200 }}>{@html bodyHtml}</div>
	{:else}
		<p class="dim pad">Este modpack no tiene una descripción detallada.</p>
	{/if}
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
	}
	.pad {
		padding: 22px 0;
	}
	.err {
		color: #ff9b9b;
	}
	.head {
		display: flex;
		gap: 16px;
		align-items: flex-start;
		margin-bottom: 24px;
	}
	.icon {
		width: 84px;
		height: 84px;
		border-radius: 16px;
		object-fit: cover;
		background: var(--bg-elev);
		flex-shrink: 0;
	}
	.icon.ph {
		display: grid;
		place-items: center;
		font-size: 36px;
	}
	.meta {
		flex: 1;
		min-width: 0;
	}
	.meta h1 {
		font-size: 26px;
	}
	.summary {
		margin: 6px 0 10px;
		font-size: 14px;
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
	.action {
		min-width: 180px;
		display: flex;
		justify-content: flex-end;
		gap: 8px;
	}
	.primary,
	.manage {
		white-space: nowrap;
	}
	.upd {
		background: #3fa34d;
		color: #fff;
		font-weight: 700;
		white-space: nowrap;
	}
	.upd:hover {
		background: #348540;
	}
	.installbox {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 8px;
	}
	.variants {
		display: inline-flex;
		border: 1px solid var(--border);
		border-radius: 8px;
		overflow: hidden;
	}
	.variants button {
		background: var(--bg-elev);
		color: var(--text-dim);
		border: none;
		border-radius: 0;
		padding: 6px 14px;
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
	.prog {
		width: 180px;
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
	.body {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 24px 28px;
		line-height: 1.65;
		overflow-x: auto;
	}
	.body :global(img) {
		max-width: 100%;
		height: auto;
		border-radius: 8px;
	}
	.body :global(h1),
	.body :global(h2),
	.body :global(h3) {
		margin: 20px 0 10px;
	}
	.body :global(h1):first-child {
		margin-top: 0;
	}
	.body :global(a) {
		color: var(--accent);
	}
	.body :global(pre) {
		background: var(--bg);
		padding: 12px;
		border-radius: 8px;
		overflow-x: auto;
	}
	.body :global(code) {
		background: var(--bg);
		padding: 1px 5px;
		border-radius: 4px;
	}
	.body :global(blockquote) {
		border-left: 3px solid var(--accent-dim);
		margin: 12px 0;
		padding: 4px 14px;
		color: var(--text-dim);
	}
	.body :global(table) {
		border-collapse: collapse;
	}
	.body :global(td),
	.body :global(th) {
		border: 1px solid var(--border);
		padding: 6px 10px;
	}
</style>
