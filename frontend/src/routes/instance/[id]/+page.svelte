<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
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
		instanceIconUrl,
		listVersions,
		listLoaderVersions,
		getContentCompat,
		changeInstanceVersion,
		exportInstance,
		listInstanceFiles,
		repairInstance,
		type FileEntry,
		type Instance,
		type InstalledItem,
		type ContentType,
		type ContentVersion,
		type UpdateInfo,
		type VanillaVersion,
		type CompatItem
	} from '$lib/ipc';
	import { TYPES, TYPE_LABEL } from '$lib/content';
	import { canonicalSet, matchesCanonical } from '$lib/categories';
	import {
		ui,
		refreshInstances,
		bumpIconBust,
		setProgress,
		setContentDrop,
		clearContentDrop
	} from '$lib/store.svelte';
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

	// Drag&drop de archivos sueltos sobre la ventana → contenido de esta instancia (E4).
	onMount(() => setContentDrop({ id, getType: () => (filter === 'all' ? '' : filter) }));
	onDestroy(() => clearContentDrop());

	// Refresca el contenido al soltar archivos (el store avisa con un contador).
	let lastContentImported = $state(0);
	$effect(() => {
		if (ui.contentImported !== lastContentImported) {
			lastContentImported = ui.contentImported;
			refresh();
		}
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
	// editIcon: null = sin cambios · '' = quitar · data-URL = nuevo icono.
	let editIcon = $state<string | null>(null);
	let form = $state({
		name: '',
		group: '',
		minMemoryMb: 1024,
		maxMemoryMb: 4096,
		windowWidth: 1280,
		windowHeight: 720,
		fullscreen: false,
		jvmArgs: '',
		javaPathOverride: ''
	});

	// ── Cambio de versión / loader de la instancia ──
	let mcVersions = $state<VanillaVersion[]>([]);
	let verForm = $state({ mcVersion: '', loader: 'vanilla', loaderVersion: '' });
	let verLoaderVers = $state<string[]>([]);
	let verLoadingLoaders = $state(false);
	let compatReport = $state<CompatItem[] | null>(null);
	let checkingCompat = $state(false);
	let applyingChange = $state(false);
	let awaitingReinstall = $state(false);

	const verChanged = $derived(
		!!inst &&
			(verForm.mcVersion !== inst.mcVersion ||
				verForm.loader !== inst.loader ||
				verForm.loaderVersion !== (inst.loaderVersion ?? ''))
	);
	const verReady = $derived(verForm.loader === 'vanilla' || !!verForm.loaderVersion);
	const compatCounts = $derived.by(() => {
		const c = { compatible: 0, updatable: 0, incompatible: 0, unknown: 0 };
		for (const it of compatReport ?? []) c[it.status]++;
		return c;
	});

	// Al terminar la reinstalación por cambio de versión, recarga instancia y contenido.
	$effect(() => {
		if (!inst) return;
		const st = ui.status[inst.id];
		if (awaitingReinstall && (st === 'installed' || st === 'error')) {
			awaitingReinstall = false;
			if (st === 'installed') reloadAfterInstall();
		}
	});

	async function reloadAfterInstall() {
		try {
			inst = await getInstance(id);
		} catch {
			/* sin conexión */
		}
		await refresh();
	}

	async function openSettings() {
		if (!inst) return;
		form = {
			name: inst.name,
			group: inst.group ?? '',
			minMemoryMb: inst.minMemoryMb,
			maxMemoryMb: inst.maxMemoryMb,
			windowWidth: inst.windowWidth,
			windowHeight: inst.windowHeight,
			fullscreen: inst.fullscreen,
			jvmArgs: inst.jvmArgs ?? '',
			javaPathOverride: inst.javaPathOverride ?? ''
		};
		editIcon = null;
		verForm = { mcVersion: inst.mcVersion, loader: inst.loader, loaderVersion: inst.loaderVersion ?? '' };
		compatReport = null;
		showSettings = true;
		if (!mcVersions.length) {
			try {
				mcVersions = await listVersions();
			} catch {
				/* reintenta al reabrir */
			}
		}
		await loadVerLoaderVersions(true);
	}

	/** Relista las versiones del loader objetivo. keepCurrent conserva la actual si sigue disponible. */
	async function loadVerLoaderVersions(keepCurrent = false) {
		const cur = verForm.loaderVersion;
		compatReport = null; // cambió el objetivo: el informe anterior ya no vale
		verLoaderVers = [];
		if (verForm.loader === 'vanilla' || !verForm.mcVersion) {
			verForm.loaderVersion = '';
			return;
		}
		verLoadingLoaders = true;
		try {
			verLoaderVers = await listLoaderVersions(verForm.loader, verForm.mcVersion);
			verForm.loaderVersion =
				keepCurrent && cur && verLoaderVers.includes(cur) ? cur : (verLoaderVers[0] ?? '');
		} catch {
			verLoaderVers = [];
			verForm.loaderVersion = '';
		} finally {
			verLoadingLoaders = false;
		}
	}

	async function checkCompat() {
		if (!inst) return;
		checkingCompat = true;
		try {
			compatReport = await getContentCompat(inst.id, verForm.mcVersion, verForm.loader);
		} catch {
			compatReport = [];
		} finally {
			checkingCompat = false;
		}
	}

	async function applyChange() {
		if (!inst || !verChanged || !verReady) return;
		applyingChange = true;
		try {
			awaitingReinstall = true;
			inst = await changeInstanceVersion(inst.id, {
				mcVersion: verForm.mcVersion,
				loader: verForm.loader,
				loaderVersion: verForm.loader === 'vanilla' ? '' : verForm.loaderVersion
			});
			setProgress(inst.id, { phase: 'Iniciando', done: 0, total: 1 });
			await refreshInstances();
			showSettings = false;
		} finally {
			applyingChange = false;
		}
	}

	async function duplicateAndMigrate() {
		if (!inst || !verChanged || !verReady) return;
		applyingChange = true;
		try {
			const copy = await duplicateInstance(inst.id);
			const migrated = await changeInstanceVersion(copy.id, {
				mcVersion: verForm.mcVersion,
				loader: verForm.loader,
				loaderVersion: verForm.loader === 'vanilla' ? '' : verForm.loaderVersion
			});
			setProgress(migrated.id, { phase: 'Iniciando', done: 0, total: 1 });
			await refreshInstances();
			showSettings = false;
			goto(`/instance/${migrated.id}`);
		} finally {
			applyingChange = false;
		}
	}

	function pct(p: { done: number; total: number }) {
		return p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
	}

	// ── Exportar instancia ──
	// El contenido (mods/recursos/shaders/datapacks) se maneja aparte; el selector
	// muestra el resto de carpetas/archivos para incluir en overrides.
	const CONTENT_FOLDERS = ['mods', 'resourcepacks', 'shaderpacks', 'datapacks'];
	type TreeNode = { entry: FileEntry; children: TreeNode[] | null; expanded: boolean };

	let showExport = $state(false);
	let exporting = $state(false);
	let exportMsg = $state('');
	let fileTree = $state<TreeNode[]>([]);
	let treeLoading = $state(false);
	let selectedPaths = $state<Record<string, boolean>>({});
	let exportForm = $state({
		name: '',
		minMemoryMb: 1024,
		maxMemoryMb: 4096,
		windowWidth: 1280,
		windowHeight: 720,
		jvmArgs: '',
		includeIcon: true,
		format: 'f24pack' as 'f24pack' | 'mrpack'
	});

	async function openExport() {
		if (!inst) return;
		exportForm = {
			name: inst.name,
			minMemoryMb: inst.minMemoryMb,
			maxMemoryMb: inst.maxMemoryMb,
			windowWidth: inst.windowWidth,
			windowHeight: inst.windowHeight,
			jvmArgs: inst.jvmArgs ?? '',
			includeIcon: !!inst.icon,
			format: 'f24pack'
		};
		exportMsg = '';
		selectedPaths = {};
		fileTree = [];
		showExport = true;
		await loadTree();
		if (fileTree.some((n) => n.entry.path === 'config')) selectedPaths = { config: true };
	}

	async function loadTree() {
		treeLoading = true;
		try {
			const entries = await listInstanceFiles(id, '');
			fileTree = entries
				.filter((e) => !CONTENT_FOLDERS.includes(e.name))
				.map((e) => ({ entry: e, children: null, expanded: false }));
		} catch {
			fileTree = [];
		} finally {
			treeLoading = false;
		}
	}

	async function toggleExpand(node: TreeNode) {
		node.expanded = !node.expanded;
		if (node.expanded && node.children === null) {
			try {
				const entries = await listInstanceFiles(id, node.entry.path);
				node.children = entries.map((e) => ({ entry: e, children: null, expanded: false }));
			} catch {
				node.children = [];
			}
		}
	}

	function toggleSel(path: string) {
		selectedPaths = { ...selectedPaths, [path]: !selectedPaths[path] };
	}

	async function doExport() {
		if (!inst) return;
		exportMsg = '';
		try {
			const { save } = await import('@tauri-apps/plugin-dialog');
			const ext = exportForm.format;
			const path = await save({
				defaultPath: `${exportForm.name || inst.name}.${ext}`,
				filters: [{ name: ext === 'mrpack' ? 'Modrinth modpack' : 'F24 modpack', extensions: [ext] }]
			});
			if (typeof path !== 'string') return;
			exporting = true;
			await exportInstance(inst.id, {
				outputPath: path,
				...exportForm,
				includePaths: Object.keys(selectedPaths).filter((p) => selectedPaths[p])
			});
			showExport = false;
		} catch (e) {
			exportMsg = (e as Error).message;
		} finally {
			exporting = false;
		}
	}

	// ── Acceso directo de escritorio ──
	let shortcutMsg = $state('');
	async function createShortcut() {
		if (!inst) return;
		try {
			const { invoke } = await import('@tauri-apps/api/core');
			await invoke('create_instance_shortcut', { id: inst.id, name: inst.name });
			shortcutMsg = 'Acceso directo creado en el escritorio.';
		} catch (e) {
			shortcutMsg = 'No se pudo crear el acceso directo: ' + (e as Error).message;
		}
		setTimeout(() => (shortcutMsg = ''), 4500);
	}

	// ── Reparar / verificar contenido ──
	async function repair() {
		if (!inst) return;
		try {
			await repairInstance(inst.id);
			setProgress(inst.id, { phase: 'Verificando', done: 0, total: 1 });
			shortcutMsg = 'Verificación de archivos iniciada…';
		} catch (e) {
			shortcutMsg = 'No se pudo reparar: ' + (e as Error).message;
		}
		setTimeout(() => (shortcutMsg = ''), 4000);
	}

	// Grupos existentes (para el datalist del campo de grupo en Ajustes).
	const groupNames = $derived([...new Set(ui.instances.map((i) => i.group).filter(Boolean))]);

	function pickSettingsIcon() {
		const input = document.createElement('input');
		input.type = 'file';
		input.accept = 'image/png,image/jpeg,image/gif';
		input.onchange = () => {
			const f = input.files?.[0];
			if (!f) return;
			const r = new FileReader();
			r.onload = () => (editIcon = r.result as string);
			r.readAsDataURL(f);
		};
		input.click();
	}

	async function saveSettings() {
		if (!inst) return;
		savingSettings = true;
		try {
			const body = { ...form } as Record<string, unknown>;
			if (editIcon !== null) body.iconData = editIcon;
			inst = await updateInstance(inst.id, body);
			if (editIcon !== null) bumpIconBust();
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
	{#if inst?.icon}
		<img class="thumb" src={instanceIconUrl(inst.id, ui.iconBust)} alt="" />
	{:else}
		<div class="thumb ph"><Icon name="package" size={24} /></div>
	{/if}
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
		<button class="ghost gear" title="Exportar instancia (.f24pack / .mrpack)" onclick={openExport}>
			<Icon name="package" size={15} />Exportar
		</button>
		<button class="ghost gear" title="Crear acceso directo en el escritorio" onclick={createShortcut}>
			<Icon name="external" size={15} />Acceso directo
		</button>
		<button class="ghost gear" title="Verificar y reparar archivos" onclick={repair}>
			<Icon name="wrench" size={15} />Reparar
		</button>
		<button class="ghost gear" title="Ajustes de la instancia" onclick={openSettings}>
			<Icon name="gear" size={15} />Ajustes
		</button>
	{/if}
	{#if inst && ui.progress[inst.id]}
		<div class="hprog" title={ui.progress[inst.id].phase}>
			<div class="hbar"><div class="hfill" style="width:{pct(ui.progress[inst.id])}%"></div></div>
			<span class="dim small">{ui.progress[inst.id].phase} · {pct(ui.progress[inst.id])}%</span>
		</div>
	{:else if runState === 'running'}
		<button class="stop" onclick={stop}><Icon name="stop" size={13} />Detener</button>
	{:else if runState === 'launching'}
		<button class="busy" disabled>Iniciando…</button>
	{:else if inst?.installed}
		<button class="play" onclick={play}><Icon name="play" size={13} />Jugar</button>
	{/if}
</header>

{#if shortcutMsg}
	<div class="stoast" transition:fade={{ duration: 120 }}>{shortcutMsg}</div>
{/if}

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
			<div class="iconpick">
				{#if editIcon}
					<img class="prev" src={editIcon} alt="Icono elegido" />
				{:else if editIcon === null && inst.icon}
					<img class="prev" src={instanceIconUrl(inst.id, ui.iconBust)} alt="Icono actual" />
				{:else}
					<div class="prev ph"><Icon name="package" size={24} /></div>
				{/if}
				<div class="iconbtns">
					<button type="button" class="ghost" onclick={pickSettingsIcon}>
						<Icon name="download" size={14} />Cambiar icono…
					</button>
					{#if editIcon || (editIcon === null && inst.icon)}
						<button type="button" class="ghost danger" onclick={() => (editIcon = '')}>Quitar</button>
					{/if}
					<span class="dim small">PNG · se recorta a cuadrado</span>
				</div>
			</div>
			<div class="sgrid">
				<label class="full">
					Nombre
					<input bind:value={form.name} />
				</label>

				<label class="full">
					Grupo <span class="dim">(para organizar el grid)</span>
					<input list="f24-groups" bind:value={form.group} placeholder="Sin grupo" />
				</label>
				<datalist id="f24-groups">
					{#each groupNames as g}<option value={g}></option>{/each}
				</datalist>

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

			<div class="versec">
				<div class="vshead"><Icon name="swap" size={15} /><span>Versión y loader</span></div>
				<div class="vsgrid">
					<label>
						Minecraft
						<select bind:value={verForm.mcVersion} onchange={() => loadVerLoaderVersions()}>
							{#each mcVersions as v (v.id)}<option value={v.id}>{v.id}</option>{/each}
						</select>
					</label>
					<label>
						Cargador
						<select bind:value={verForm.loader} onchange={() => loadVerLoaderVersions()}>
							<option value="vanilla">Vanilla</option>
							<option value="fabric">Fabric</option>
							<option value="quilt">Quilt</option>
							<option value="neoforge">NeoForge</option>
							<option value="forge">Forge</option>
						</select>
					</label>
					{#if verForm.loader !== 'vanilla'}
						<label>
							Versión de {verForm.loader}
							<select bind:value={verForm.loaderVersion} disabled={verLoadingLoaders || !verLoaderVers.length}>
								{#if verLoadingLoaders}
									<option>Cargando…</option>
								{:else if !verLoaderVers.length}
									<option>Sin versiones</option>
								{:else}
									{#each verLoaderVers as lv}<option value={lv}>{lv}</option>{/each}
								{/if}
							</select>
						</label>
					{/if}
				</div>

				{#if verChanged}
					<div class="vsactions">
						<button class="ghost xs" onclick={checkCompat} disabled={checkingCompat}>
							<Icon name="check" size={14} />{checkingCompat ? 'Comprobando…' : 'Comprobar compatibilidad'}
						</button>
						{#if compatReport}
							<span class="dim small counts">
								{compatCounts.compatible} ok · {compatCounts.updatable} actualizables ·
								{compatCounts.incompatible} incompatibles{compatCounts.unknown
									? ` · ${compatCounts.unknown} sin identificar`
									: ''}
							</span>
						{/if}
					</div>

					{#if compatReport && compatReport.length}
						<div class="compatlist">
							{#each compatReport as it (it.type + '/' + it.fileName)}
								<div class="crow">
									<span class="cdot {it.status}"></span>
									<span class="cnm">{it.name}</span>
									<span class="cstat {it.status}">
										{it.status === 'compatible'
											? 'Compatible'
											: it.status === 'updatable'
												? 'Actualizable'
												: it.status === 'incompatible'
													? 'Incompatible'
													: 'Sin identificar'}
									</span>
								</div>
							{/each}
						</div>
					{:else if compatReport}
						<p class="dim small">No hay contenido instalado que comprobar.</p>
					{/if}

					<p class="dim small warn">
						Al aplicar se reinstalará la base. El contenido incompatible queda instalado pero podría no
						cargar; usa “Actualizar todos” o desactívalo después.
					</p>
					<div class="vsapply">
						<button class="ghost xs" onclick={duplicateAndMigrate} disabled={applyingChange || !verReady}>
							<Icon name="copy" size={14} />Duplicar y migrar
						</button>
						<button class="xs apply" onclick={applyChange} disabled={applyingChange || !verReady}>
							{applyingChange ? 'Aplicando…' : 'Aplicar cambio'}
						</button>
					</div>
				{/if}
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

{#snippet treeNode(node: TreeNode, depth: number)}
	<div class="trow" style="padding-left:{depth * 16}px">
		{#if node.entry.dir}
			<button type="button" class="texp" onclick={() => toggleExpand(node)}>
				<Icon name={node.expanded ? 'chevron-down' : 'chevron-right'} size={13} />
			</button>
		{:else}
			<span class="texp ph"></span>
		{/if}
		<label class="tlabel">
			<input
				type="checkbox"
				checked={!!selectedPaths[node.entry.path]}
				onchange={() => toggleSel(node.entry.path)}
			/>
			<Icon name={node.entry.dir ? 'folder' : 'package'} size={14} />
			<span class="tname">{node.entry.name}</span>
		</label>
	</div>
	{#if node.entry.dir && node.expanded && node.children}
		{#each node.children as child (child.entry.path)}
			{@render treeNode(child, depth + 1)}
		{/each}
	{/if}
{/snippet}

{#if showExport && inst}
	<div class="overlay" onclick={() => (showExport = false)} role="presentation" transition:fade={{ duration: 130 }}>
		<div
			class="modal settings"
			onclick={(e) => e.stopPropagation()}
			role="dialog"
			tabindex="-1"
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			<h2>Exportar · {inst.name}</h2>
			<div class="sgrid">
				<label class="full">
					Nombre del pack
					<input bind:value={exportForm.name} />
				</label>
				<label class="full">
					Memoria máxima · <strong>{exportForm.maxMemoryMb} MB</strong>
					<input type="range" min="1024" max="16384" step="512" bind:value={exportForm.maxMemoryMb} />
				</label>
				<label>
					Memoria mínima (MB)
					<input type="number" min="256" max="16384" step="256" bind:value={exportForm.minMemoryMb} />
				</label>
				<label>
					Formato
					<select bind:value={exportForm.format}>
						<option value="f24pack">.f24pack (con ajustes e icono)</option>
						<option value="mrpack">.mrpack (estándar Modrinth)</option>
					</select>
				</label>
				<label>
					Ancho
					<input type="number" min="640" bind:value={exportForm.windowWidth} />
				</label>
				<label>
					Alto
					<input type="number" min="480" bind:value={exportForm.windowHeight} />
				</label>
				<label class="full">
					Argumentos JVM
					<textarea rows="2" placeholder="-XX:+UseG1GC …" bind:value={exportForm.jvmArgs}></textarea>
				</label>
			</div>
			<div class="exincl">
				<div class="dim small">Carpetas y archivos a incluir (el contenido se añade automáticamente):</div>
				<div class="treebox">
					{#if treeLoading}
						<p class="dim small">Cargando…</p>
					{:else if fileTree.length === 0}
						<p class="dim small">No hay archivos extra que incluir.</p>
					{:else}
						{#each fileTree as node (node.entry.path)}
							{@render treeNode(node, 0)}
						{/each}
					{/if}
				</div>
				<label class="exchk"><input type="checkbox" bind:checked={exportForm.includeIcon} disabled={!inst.icon} />Incluir icono de la instancia</label>
			</div>
			{#if exportForm.format === 'mrpack'}
				<p class="dim small">El formato .mrpack no guarda RAM/JVM/icono (solo mods y overrides).</p>
			{/if}
			<p class="dim small">
				Los mods con origen conocido se guardan como descargas; el resto (configs, mods manuales…) va
				dentro del paquete.
			</p>
			{#if exportMsg}<div class="experr" title={exportMsg}>{exportMsg}</div>{/if}
			<div class="modal-actions">
				<button class="ghost" onclick={() => (showExport = false)}>Cancelar</button>
				<button onclick={doExport} disabled={exporting}>
					{exporting ? 'Exportando…' : 'Exportar…'}
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
		flex-shrink: 0;
		object-fit: cover;
	}
	.thumb.ph {
		display: grid;
		place-items: center;
		font-size: 24px;
		color: var(--text-dim);
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
	.iconpick {
		display: flex;
		align-items: center;
		gap: 14px;
	}
	.prev {
		width: 56px;
		height: 56px;
		border-radius: 12px;
		object-fit: cover;
		background: var(--bg-card);
		border: 1px solid var(--border);
		flex-shrink: 0;
	}
	.prev.ph {
		display: grid;
		place-items: center;
		color: var(--text-dim);
	}
	.iconbtns {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 6px;
	}
	/* Progreso de reinstalación en la cabecera */
	.hprog {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 170px;
	}
	.hbar {
		height: 8px;
		background: var(--bg-elev);
		border-radius: 4px;
		overflow: hidden;
	}
	.hfill {
		height: 100%;
		background: var(--accent);
		transition: width 0.2s ease;
	}
	/* Sección de cambio de versión / loader */
	.versec {
		border-top: 1px solid var(--border);
		padding-top: 14px;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.vshead {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 14px;
		font-weight: 600;
		color: var(--text);
	}
	.vsgrid {
		display: grid;
		grid-template-columns: 1fr 1fr 1fr;
		gap: 10px;
	}
	.vsgrid label {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 12px;
		color: var(--text-dim);
	}
	.vsgrid select {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 8px 10px;
		font-family: inherit;
		font-size: 13px;
	}
	.vsactions {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
	}
	.counts {
		line-height: 1.4;
	}
	.compatlist {
		display: flex;
		flex-direction: column;
		gap: 4px;
		max-height: 160px;
		overflow: auto;
	}
	.crow {
		display: flex;
		align-items: center;
		gap: 8px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 6px 10px;
	}
	.cnm {
		flex: 1;
		min-width: 0;
		font-size: 13px;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.cdot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		flex-shrink: 0;
	}
	.cstat {
		font-size: 11px;
		font-weight: 600;
	}
	.cdot.compatible {
		background: #3fa34d;
	}
	.cstat.compatible {
		color: #3fa34d;
	}
	.cdot.updatable {
		background: var(--accent);
	}
	.cstat.updatable {
		color: var(--accent);
	}
	.cdot.incompatible {
		background: #ff6b6b;
	}
	.cstat.incompatible {
		color: #ff6b6b;
	}
	.cdot.unknown {
		background: var(--text-dim);
	}
	.cstat.unknown {
		color: var(--text-dim);
	}
	.warn {
		line-height: 1.45;
	}
	.vsapply {
		display: flex;
		justify-content: flex-end;
		gap: 10px;
	}
	.apply {
		font-weight: 700;
	}
	/* Modal de exportación */
	.exincl {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.exchk {
		display: flex;
		flex-direction: row;
		align-items: center;
		gap: 9px;
		font-size: 13px;
		color: var(--text);
	}
	.exchk input[type='checkbox'] {
		accent-color: var(--accent);
		width: 16px;
		height: 16px;
	}
	.experr {
		background: rgba(255, 107, 107, 0.12);
		border: 1px solid rgba(255, 107, 107, 0.4);
		color: #ff9b9b;
		border-radius: 8px;
		padding: 7px 9px;
		font-size: 12px;
		word-break: break-word;
	}
	.treebox {
		max-height: 200px;
		overflow: auto;
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 6px;
		background: var(--bg-card);
	}
	.trow {
		display: flex;
		align-items: center;
		gap: 4px;
	}
	.texp {
		background: transparent;
		border: none;
		color: var(--text-dim);
		padding: 2px;
		cursor: pointer;
		display: flex;
		width: 20px;
		flex-shrink: 0;
	}
	.texp.ph {
		display: inline-block;
	}
	.texp:hover {
		color: var(--text);
	}
	.tlabel {
		display: flex;
		align-items: center;
		gap: 7px;
		font-size: 13px;
		color: var(--text);
		flex: 1;
		min-width: 0;
		cursor: pointer;
		padding: 2px 0;
	}
	.tlabel input[type='checkbox'] {
		accent-color: var(--accent);
		width: 15px;
		height: 15px;
		flex-shrink: 0;
	}
	.tname {
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.stoast {
		position: fixed;
		bottom: 22px;
		left: 50%;
		transform: translateX(-50%);
		z-index: 60;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 11px 18px;
		font-size: 13px;
		color: var(--text);
		box-shadow: 0 10px 26px rgba(0, 0, 0, 0.4);
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
