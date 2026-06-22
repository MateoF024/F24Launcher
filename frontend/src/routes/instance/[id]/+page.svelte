<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import {
		ipcReady,
		getInstance,
		listInstalledContent,
		toggleContent,
		removeContent,
		getContentVersions,
		getContentUpdates,
		identifyContent,
		installContent,
		launchInstance,
		stopInstance,
		updateInstance,
		duplicateInstance,
		openInstanceFolder,
		type Instance,
		type InstalledItem,
		type ContentType,
		type ContentVersion,
		type UpdateInfo
	} from '$lib/ipc';
	import { TYPES, TYPE_LABEL } from '$lib/content';
	import { canonicalSet, matchesCanonical } from '$lib/categories';
	import { ui, refreshInstances } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fade, fly, scale } from 'svelte/transition';

	const id = $derived($page.params.id ?? '');
	let inst = $state<Instance | null>(null);
	let items = $state<InstalledItem[]>([]);
	let loading = $state(true);
	let filter = $state<'all' | ContentType>('all');
	let query = $state('');
	let catSel = $state('');
	let envFilter = $state<string[]>([]);

	let updates = $state<Record<string, UpdateInfo>>({});
	let updating = $state<Record<string, boolean>>({});
	let updatingAll = $state(false);

	const activeAccount = $derived(ui.accounts.find((a) => a.active));
	const runState = $derived(inst ? ui.status[inst.id] : '');

	// Categorías (unificadas y canónicas) presentes entre los elementos del tipo seleccionado.
	const typed = $derived(items.filter((i) => filter === 'all' || i.type === filter));
	const typeCats = $derived(canonicalSet(typed.flatMap((i) => i.categories ?? [])));
	const updateCount = $derived(Object.keys(updates).length);

	// Si la categoría elegida deja de existir al cambiar de pestaña, se limpia.
	$effect(() => {
		if (catSel && !typeCats.includes(catSel)) catSel = '';
	});

	function keyOf(i: { type: string; fileName: string }) {
		return i.type + '/' + i.fileName;
	}

	function envMatches(i: InstalledItem) {
		return envFilter.every((e) => {
			if (e === 'client') return i.clientSide === 'required' || i.clientSide === 'optional';
			if (e === 'server') return i.serverSide === 'required' || i.serverSide === 'optional';
			return true;
		});
	}

	const visible = $derived(
		items.filter(
			(i) =>
				(filter === 'all' || i.type === filter) &&
				(!query.trim() || i.name.toLowerCase().includes(query.toLowerCase())) &&
				(!catSel || matchesCanonical(i.categories ?? [], catSel)) &&
				(envFilter.length === 0 || envMatches(i))
		)
	);
	const counts = $derived(
		items.reduce<Record<string, number>>((acc, i) => ((acc[i.type] = (acc[i.type] ?? 0) + 1), acc), {})
	);

	function toggleEnv(e: string) {
		envFilter = envFilter.includes(e) ? envFilter.filter((x) => x !== e) : [...envFilter, e];
	}

	onMount(async () => {
		await ipcReady;
		try {
			inst = await getInstance(id);
		} catch {
			/* no existe */
		}
		await refresh();
		maybeIdentify();
	});

	let identifying = $state(false);

	async function refresh() {
		loading = true;
		try {
			items = await listInstalledContent(id);
		} catch {
			items = [];
		} finally {
			loading = false;
		}
		loadUpdates();
	}

	/** Identifica en segundo plano los mods sin metadatos (añadidos a mano). */
	async function maybeIdentify() {
		if (identifying || !items.some((i) => !i.projectId)) return;
		identifying = true;
		try {
			items = await identifyContent(id);
			loadUpdates();
		} catch {
			/* se mantienen como Unknown */
		} finally {
			identifying = false;
		}
	}

	async function loadUpdates() {
		try {
			const list = await getContentUpdates(id);
			updates = Object.fromEntries(list.map((u) => [keyOf(u), u]));
		} catch {
			updates = {};
		}
	}

	async function update(it: InstalledItem) {
		const u = updates[keyOf(it)];
		if (!u || !it.projectId) return;
		const k = keyOf(it);
		updating = { ...updating, [k]: true };
		try {
			await installContent(id, {
				source: it.source,
				type: it.type,
				projectId: it.projectId,
				versionId: u.versionId,
				ignore: true
			});
			await refresh();
		} finally {
			updating = { ...updating, [k]: false };
		}
	}

	async function updateAll() {
		updatingAll = true;
		try {
			for (const it of items) {
				const u = updates[keyOf(it)];
				if (u && it.projectId) {
					await installContent(id, {
						source: it.source,
						type: it.type,
						projectId: it.projectId,
						versionId: u.versionId,
						ignore: true
					});
				}
			}
			await refresh();
		} finally {
			updatingAll = false;
		}
	}

	function openItem(e: Event, it: InstalledItem) {
		if ((e.target as HTMLElement).closest('button')) return;
		if (!it.projectId) return;
		goto(`/instance/${id}/browse?open=${it.source}:${it.type}:${it.projectId}`);
	}

	async function toggle(it: InstalledItem) {
		await toggleContent(id, it.type, it.fileName);
		await refresh();
	}

	async function remove(it: InstalledItem) {
		await removeContent(id, it.type, it.fileName);
		await refresh();
	}

	async function play() {
		if (!inst) return;
		ui.status = { ...ui.status, [inst.id]: 'launching' };
		try {
			await launchInstance(inst.id, activeAccount?.username ?? 'Player');
		} catch {
			ui.status = { ...ui.status, [inst.id]: '' };
		}
	}

	async function stop() {
		if (!inst) return;
		ui.status = { ...ui.status, [inst.id]: 'stopping' };
		try {
			await stopInstance(inst.id);
		} catch {
			ui.status = { ...ui.status, [inst.id]: 'running' };
		}
	}

	// ── Ajustes de la instancia ──
	let showSettings = $state(false);
	let savingSettings = $state(false);
	let form = $state({
		name: '',
		minMemoryMb: 1024,
		maxMemoryMb: 4096,
		windowWidth: 1280,
		windowHeight: 720,
		fullscreen: false,
		jvmArgs: '',
		javaPathOverride: ''
	});

	function openSettings() {
		if (!inst) return;
		form = {
			name: inst.name,
			minMemoryMb: inst.minMemoryMb,
			maxMemoryMb: inst.maxMemoryMb,
			windowWidth: inst.windowWidth,
			windowHeight: inst.windowHeight,
			fullscreen: inst.fullscreen,
			jvmArgs: inst.jvmArgs ?? '',
			javaPathOverride: inst.javaPathOverride ?? ''
		};
		showSettings = true;
	}

	async function saveSettings() {
		if (!inst) return;
		savingSettings = true;
		try {
			inst = await updateInstance(inst.id, { ...form });
			await refreshInstances();
			showSettings = false;
		} finally {
			savingSettings = false;
		}
	}

	let duplicating = $state(false);
	async function duplicate() {
		if (!inst) return;
		duplicating = true;
		try {
			const copy = await duplicateInstance(inst.id);
			await refreshInstances();
			showSettings = false;
			goto(`/instance/${copy.id}`);
		} finally {
			duplicating = false;
		}
	}

	// ── Cambiar versión ──
	let swapItem = $state<InstalledItem | null>(null);
	let swapVersions = $state<ContentVersion[]>([]);
	let swapLoading = $state(false);
	let swapBusy = $state('');

	async function openSwap(it: InstalledItem) {
		if (!inst || !it.projectId) return;
		swapItem = it;
		swapVersions = [];
		swapLoading = true;
		try {
			swapVersions = await getContentVersions(it.source, it.type, it.projectId, inst.mcVersion, inst.loader);
			if (!swapVersions.length)
				swapVersions = await getContentVersions(it.source, it.type, it.projectId, inst.mcVersion, inst.loader, true);
		} catch {
			swapVersions = [];
		} finally {
			swapLoading = false;
		}
	}

	async function applySwap(v: ContentVersion) {
		if (!swapItem) return;
		swapBusy = v.id;
		try {
			await installContent(id, {
				source: swapItem.source,
				type: swapItem.type,
				projectId: swapItem.projectId,
				versionId: v.id,
				ignore: true
			});
			swapItem = null;
			await refresh();
		} finally {
			swapBusy = '';
		}
	}
</script>

<header>
	<button class="ghost back" title="Volver" onclick={() => goto('/')}><Icon name="arrow-left" size={16} /></button>
	<div class="thumb"><Icon name="package" size={24} /></div>
	<div class="title">
		<h1>{inst?.name ?? id}</h1>
		<div class="dim sub">
			{#if inst}
				{inst.loader !== 'vanilla' ? inst.loader : 'Vanilla'}
				{inst.loaderVersion} · MC {inst.mcVersion}
			{/if}
		</div>
	</div>
	<div class="spacer"></div>
	{#if inst}
		<button class="ghost gear" title="Consola de la instancia" onclick={() => goto(`/instance/${id}/console`)}>
			<Icon name="terminal" size={15} />Consola
		</button>
		<button class="ghost gear" title="Abrir carpeta de la instancia" onclick={() => openInstanceFolder(inst!.id)}>
			<Icon name="folder" size={15} />Carpeta
		</button>
		<button class="ghost gear" title="Ajustes de la instancia" onclick={openSettings}>
			<Icon name="gear" size={15} />Ajustes
		</button>
	{/if}
	{#if runState === 'running'}
		<button class="stop" onclick={stop}><Icon name="stop" size={13} />Detener</button>
	{:else if runState === 'launching'}
		<button class="busy" disabled>Iniciando…</button>
	{:else if inst?.installed}
		<button class="play" onclick={play}><Icon name="play" size={13} />Jugar</button>
	{/if}
</header>

<div class="toolbar">
	<input class="search" placeholder="Buscar en {items.length} elementos…" bind:value={query} />
	{#if updateCount > 0}
		<button class="upd" disabled={updatingAll} onclick={updateAll}>
			{updatingAll ? 'Actualizando…' : `Actualizar todos (${updateCount})`}
		</button>
	{/if}
	<button class="primary" onclick={() => goto(`/instance/${id}/browse`)}><Icon name="plus" size={15} />Instalar contenido</button>
</div>

<div class="chips">
	<button class:active={filter === 'all'} onclick={() => (filter = 'all')}>Todos ({items.length})</button>
	{#each TYPES as t}
		<button class:active={filter === t.id} onclick={() => (filter = t.id)}>
			{t.label} ({counts[t.id] ?? 0})
		</button>
	{/each}
	{#if identifying}<span class="dim small ident">Identificando mods…</span>{/if}
	<button class="ghost xs refresh" title="Refrescar" onclick={refresh}><Icon name="refresh" size={15} /></button>
</div>

<div class="filterbar">
	<div class="fgroup">
		<span class="flabel">Entorno</span>
		<button class="fchip" class:active={envFilter.includes('client')} onclick={() => toggleEnv('client')}>
			<Icon name="monitor" size={14} />Cliente
		</button>
		<button class="fchip" class:active={envFilter.includes('server')} onclick={() => toggleEnv('server')}>
			<Icon name="server" size={14} />Servidor
		</button>
	</div>
	{#if typeCats.length}
		<div class="fgroup">
			<span class="flabel">Categoría</span>
			<select class="catsel" bind:value={catSel}>
				<option value="">Todas</option>
				{#each typeCats as c}
					<option value={c}>{c}</option>
				{/each}
			</select>
			{#if catSel}
				<button class="fchip clear" onclick={() => (catSel = '')}>
					<Icon name="close" size={13} />Limpiar
				</button>
			{/if}
		</div>
	{/if}
</div>

{#if loading}
	<p class="dim pad">Cargando…</p>
{:else if visible.length === 0}
	<div class="empty">
		<p>No hay contenido {filter !== 'all' ? `de ${TYPE_LABEL[filter]}` : 'instalado'}.</p>
		<button class="primary" onclick={() => goto(`/instance/${id}/browse`)}><Icon name="plus" size={15} />Instalar contenido</button>
	</div>
{:else}
	<div class="list">
		{#each visible as it (it.type + '/' + it.fileName)}
			<div
				class="item"
				in:fade={{ duration: 160 }}
				class:off={!it.enabled}
				class:clickable={!!it.projectId}
				role={it.projectId ? 'button' : undefined}
				tabindex={it.projectId ? 0 : undefined}
				title={it.projectId ? 'Ver página' : undefined}
				onclick={(e) => openItem(e, it)}
				onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && openItem(e, it)}
			>
				{#if it.iconUrl}
					<img class="icon" src={it.iconUrl} alt="" loading="lazy" />
				{:else}
					<div class="icon ph"><Icon name="package" size={20} /></div>
				{/if}
				<div class="info">
					<div class="row1">
						<span class="nm">{it.name}</span>
						<span class="tag">{TYPE_LABEL[it.type]}</span>
						{#if it.source}<span class="tag src">{it.source}</span>{/if}
					</div>
					<div class="dim small">
						{it.versionName || it.fileName}{it.author ? ` · ${it.author}` : ''}
					</div>
				</div>
				<div class="actions">
					{#if updates[keyOf(it)]}
						<button
							class="updbtn xs"
							disabled={updating[keyOf(it)]}
							title="Actualizar a {updates[keyOf(it)].versionName}"
							onclick={() => update(it)}
						>
							<Icon name="arrow-up" size={13} />{updating[keyOf(it)] ? '…' : 'Actualizar'}
						</button>
					{/if}
					{#if it.projectId}
						<button class="ghost xs" title="Cambiar versión" onclick={() => openSwap(it)}>
							<Icon name="swap" size={14} />
						</button>
					{/if}
					<button
						class="switch"
						class:on={it.enabled}
						role="switch"
						aria-checked={it.enabled}
						title={it.enabled ? 'Desactivar' : 'Activar'}
						onclick={() => toggle(it)}
					>
						<span class="knob"></span>
					</button>
					<button class="ghost xs danger" title="Eliminar" onclick={() => remove(it)}>
						<Icon name="trash" size={15} />
					</button>
				</div>
			</div>
		{/each}
	</div>
{/if}

{#if swapItem}
	<div class="overlay" onclick={() => (swapItem = null)} role="presentation" transition:fade={{ duration: 130 }}>
		<div
			class="modal"
			onclick={(e) => e.stopPropagation()}
			role="dialog"
			tabindex="-1"
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			<h2>Versiones · {swapItem.name}</h2>
			{#if swapLoading}
				<p class="dim">Cargando versiones…</p>
			{:else if !swapVersions.length}
				<p class="dim">Sin versiones disponibles.</p>
			{:else}
				<div class="vlist">
					{#each swapVersions as v (v.id)}
						<div class="vrow">
							<div>
								<div class="nm">{v.versionNumber}</div>
								<div class="dim small">{v.channel} · {v.gameVersions.join(', ')}</div>
							</div>
							<button class="xs" disabled={swapBusy === v.id} onclick={() => applySwap(v)}>
								{swapBusy === v.id ? '…' : 'Usar'}
							</button>
						</div>
					{/each}
				</div>
			{/if}
			<div class="modal-actions">
				<button class="ghost" onclick={() => (swapItem = null)}>Cerrar</button>
			</div>
		</div>
	</div>
{/if}

{#if showSettings && inst}
	<div class="overlay" onclick={() => (showSettings = false)} role="presentation" transition:fade={{ duration: 130 }}>
		<div
			class="modal settings"
			onclick={(e) => e.stopPropagation()}
			role="dialog"
			tabindex="-1"
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			<h2>Ajustes · {inst.name}</h2>
			<div class="sgrid">
				<label class="full">
					Nombre
					<input bind:value={form.name} />
				</label>

				<label class="full">
					Memoria máxima · <strong>{form.maxMemoryMb} MB</strong>
					<input
						type="range"
						min="1024"
						max="16384"
						step="512"
						bind:value={form.maxMemoryMb}
					/>
				</label>

				<label>
					Memoria mínima (MB)
					<input type="number" min="256" max="16384" step="256" bind:value={form.minMemoryMb} />
				</label>
				<label class="chk">
					<input type="checkbox" bind:checked={form.fullscreen} />
					Pantalla completa
				</label>

				<label>
					Ancho
					<input type="number" min="640" step="1" bind:value={form.windowWidth} disabled={form.fullscreen} />
				</label>
				<label>
					Alto
					<input type="number" min="480" step="1" bind:value={form.windowHeight} disabled={form.fullscreen} />
				</label>

				<label class="full">
					Argumentos JVM
					<textarea rows="2" placeholder="-XX:+UseG1GC …" bind:value={form.jvmArgs}></textarea>
				</label>

				<label class="full">
					Ruta de Java (JRE) — vacío = automático
					<input placeholder="C:\\…\\bin\\javaw.exe" bind:value={form.javaPathOverride} />
				</label>
			</div>
			<div class="modal-actions">
				<button class="ghost dup" onclick={duplicate} disabled={duplicating}>
					<Icon name="copy" size={14} />{duplicating ? 'Duplicando…' : 'Duplicar'}
				</button>
				<div class="spacer"></div>
				<button class="ghost" onclick={() => (showSettings = false)}>Cancelar</button>
				<button onclick={saveSettings} disabled={savingSettings}>
					{savingSettings ? 'Guardando…' : 'Guardar'}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 14px;
		margin-bottom: 22px;
	}
	.back {
		font-size: 18px;
		padding: 6px 12px;
	}
	.thumb {
		width: 52px;
		height: 52px;
		border-radius: 12px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		display: grid;
		place-items: center;
		font-size: 24px;
	}
	.title h1 {
		font-size: 22px;
	}
	.sub {
		font-size: 13px;
		margin-top: 2px;
		text-transform: capitalize;
	}
	.spacer {
		flex: 1;
	}
	.play {
		font-weight: 700;
		padding: 10px 22px;
	}
	.stop {
		background: #e8483f;
		color: #fff;
		font-weight: 700;
		padding: 10px 22px;
	}
	.stop:hover {
		background: #c93a32;
	}
	.busy {
		background: var(--bg-elev);
		color: var(--text-dim);
		border: 1px solid var(--border);
		cursor: progress;
	}
	.toolbar {
		display: flex;
		gap: 12px;
		margin-bottom: 14px;
	}
	.search {
		flex: 1;
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: var(--radius);
		padding: 10px 14px;
		font-family: inherit;
		font-size: 14px;
	}
	.primary {
		white-space: nowrap;
	}
	.chips {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: 16px;
		flex-wrap: wrap;
	}
	.chips button {
		background: var(--bg-card);
		color: var(--text-dim);
		border: 1px solid var(--border);
		border-radius: 20px;
		padding: 5px 13px;
		font-size: 12px;
		font-weight: 500;
	}
	.chips button.active {
		background: var(--accent);
		color: #1c1500;
		border-color: var(--accent);
		font-weight: 600;
	}
	.ident {
		margin-left: auto;
	}
	.ident + .refresh {
		margin-left: 8px;
	}
	.refresh {
		margin-left: auto;
		border-radius: var(--radius);
	}
	.upd {
		background: #3fa34d;
		color: #fff;
		font-weight: 600;
		white-space: nowrap;
	}
	.upd:hover {
		background: #348540;
	}
	.upd:disabled {
		opacity: 0.6;
		cursor: progress;
	}
	.filterbar {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-bottom: 16px;
	}
	.fgroup {
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
	}
	.flabel {
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 0.5px;
		color: var(--text-dim);
		margin-right: 4px;
	}
	.fchip {
		background: var(--bg-card);
		color: var(--text-dim);
		border: 1px solid var(--border);
		border-radius: 16px;
		padding: 4px 11px;
		font-size: 12px;
		cursor: pointer;
	}
	.fchip:hover {
		color: var(--text);
	}
	.fchip.active {
		background: var(--accent);
		color: #1c1500;
		border-color: var(--accent);
		font-weight: 600;
	}
	.catsel {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 16px;
		padding: 4px 10px;
		font-family: inherit;
		font-size: 12px;
	}
	.fchip.clear {
		gap: 5px;
	}
	.updbtn {
		background: #3fa34d;
		color: #fff;
		font-weight: 600;
		white-space: nowrap;
	}
	.updbtn:hover {
		background: #348540;
	}
	.updbtn:disabled {
		opacity: 0.6;
		cursor: progress;
	}
	.pad {
		padding: 20px 0;
	}
	.empty {
		border: 1px dashed var(--border);
		border-radius: var(--radius);
		padding: 48px;
		text-align: center;
		display: flex;
		flex-direction: column;
		gap: 16px;
		align-items: center;
	}
	.list {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding-bottom: 24px;
	}
	.item {
		display: flex;
		align-items: center;
		gap: 13px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 11px 14px;
	}
	.item.off {
		opacity: 0.5;
	}
	.item.clickable {
		cursor: pointer;
		transition: border-color 0.15s ease;
	}
	.item.clickable:hover {
		border-color: var(--accent-dim);
	}
	.item.clickable:hover .nm {
		color: var(--accent);
	}
	.icon {
		width: 42px;
		height: 42px;
		border-radius: 9px;
		object-fit: cover;
		background: var(--bg-elev);
		flex-shrink: 0;
	}
	.icon.ph {
		display: grid;
		place-items: center;
		font-size: 20px;
	}
	.info {
		flex: 1;
		min-width: 0;
	}
	.row1 {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.nm {
		font-weight: 600;
	}
	.tag {
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 0.4px;
		color: var(--text-dim);
		background: var(--bg-elev);
		border: 1px solid var(--border);
		padding: 1px 6px;
		border-radius: 5px;
	}
	.tag.src {
		text-transform: capitalize;
	}
	.small {
		font-size: 12px;
	}
	.dim {
		color: var(--text-dim);
	}
	.actions {
		display: flex;
		gap: 6px;
	}
	.xs {
		padding: 5px 9px;
		font-size: 13px;
		line-height: 1;
	}
	.switch {
		width: 40px;
		height: 22px;
		border-radius: 11px;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		padding: 0;
		position: relative;
		cursor: pointer;
		flex-shrink: 0;
		transition: background 0.15s ease;
	}
	.switch .knob {
		position: absolute;
		top: 2px;
		left: 2px;
		width: 16px;
		height: 16px;
		border-radius: 50%;
		background: var(--text-dim);
		transition:
			left 0.15s ease,
			background 0.15s ease;
	}
	.switch.on {
		background: var(--accent);
		border-color: var(--accent);
	}
	.switch.on .knob {
		left: 20px;
		background: #1c1500;
	}
	.danger:hover {
		color: #ff6b6b;
	}
	.overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.55);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 50;
	}
	.modal {
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 14px;
		padding: 22px;
		width: 440px;
		max-height: 70vh;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.vlist {
		overflow: auto;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.vrow {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 10px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 8px 12px;
	}
	.modal-actions {
		display: flex;
		justify-content: flex-end;
		gap: 10px;
	}
	.gear {
		padding: 9px 14px;
		font-weight: 600;
	}
	.modal.settings {
		width: 520px;
	}
	.sgrid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 14px;
		overflow: auto;
	}
	.sgrid label {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 12px;
		color: var(--text-dim);
	}
	.sgrid label.full {
		grid-column: 1 / -1;
	}
	.sgrid label.chk {
		flex-direction: row;
		align-items: center;
		gap: 8px;
		align-self: end;
		padding-bottom: 8px;
	}
	.sgrid input:not([type]),
	.sgrid input[type='number'],
	.sgrid textarea {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 8px 10px;
		font-family: inherit;
		font-size: 13px;
	}
	.sgrid textarea {
		resize: vertical;
	}
	.sgrid input[type='range'] {
		accent-color: var(--accent);
	}
	.sgrid input:disabled {
		opacity: 0.5;
	}
</style>
