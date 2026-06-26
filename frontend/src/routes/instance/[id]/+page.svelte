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
		cancelInstall,
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
		getModpackStatus,
		updateModpackInstance,
		type ModpackStatus,
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
	import { fade, fly, scale, slide } from 'svelte/transition';

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

	// P4 — orden de la lista de contenido (se recuerda durante la sesión).
	type SortKey = 'name' | 'status' | 'added' | 'updated';
	let sortBy = $state<SortKey>('name');
	let sortDir = $state<'asc' | 'desc'>('asc');

	// P6 — modal "Actualizar todos".
	let showUpdateAll = $state(false);
	let updateSel = $state<Record<string, boolean>>({});
	let updateProg = $state({ done: 0, total: 0 });

	const activeAccount = $derived(ui.accounts.find((a) => a.active));
	const runState = $derived(inst ? ui.status[inst.id] : '');
	// Admin restringida (0.0.5): una instancia gestionada por un modpack no admite añadir/
	// quitar/actualizar mods ni cambiar versión/loader. Sí se puede habilitar/deshabilitar.
	const isModpack = $derived(!!inst?.sourceModpackId);

	// Tipos de contenido visibles: en vanilla solo resourcepacks y datapacks (mods/shaders
	// requieren un loader). Se oculta también el filtro de entorno (mod-céntrico).
	const isVanilla = $derived(inst?.loader === 'vanilla');
	const shownTypes = $derived(
		isVanilla ? TYPES.filter((t) => t.id === 'resourcepacks' || t.id === 'datapacks') : TYPES
	);
	$effect(() => {
		if (filter !== 'all' && !shownTypes.some((t) => t.id === filter)) filter = 'all';
	});

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

	// P4 — lista visible ya ordenada según el criterio elegido.
	const sorted = $derived.by(() => {
		const arr = [...visible];
		const byName = (a: InstalledItem, b: InstalledItem) =>
			a.name.localeCompare(b.name, 'es', { sensitivity: 'base' });
		let cmp: (a: InstalledItem, b: InstalledItem) => number;
		switch (sortBy) {
			case 'status':
				cmp = (a, b) => Number(b.enabled) - Number(a.enabled) || byName(a, b);
				break;
			case 'added':
				cmp = (a, b) => (b.addedAt ?? 0) - (a.addedAt ?? 0) || byName(a, b);
				break;
			case 'updated':
				cmp = (a, b) => (b.datePublished || '').localeCompare(a.datePublished || '') || byName(a, b);
				break;
			default:
				cmp = byName;
		}
		arr.sort(cmp);
		if (sortDir === 'desc') arr.reverse();
		return arr;
	});

	// P6 — elementos con actualización disponible y cuántos hay seleccionados en el modal.
	const updatableItems = $derived(items.filter((i) => i.projectId && updates[keyOf(i)]));
	const selectedCount = $derived(updatableItems.filter((it) => updateSel[keyOf(it)]).length);

	function toggleEnv(e: string) {
		envFilter = envFilter.includes(e) ? envFilter.filter((x) => x !== e) : [...envFilter, e];
	}

	// Estado de actualización del modpack (solo para instancias de modpack).
	let modpackStatus = $state<ModpackStatus | null>(null);
	let updatingModpack = $state(false);

	async function loadModpackStatus() {
		if (!inst?.sourceModpackId) {
			modpackStatus = null;
			return;
		}
		try {
			modpackStatus = await getModpackStatus(id);
		} catch {
			modpackStatus = null;
		}
	}

	async function updateModpack() {
		if (updatingModpack) return;
		updatingModpack = true;
		try {
			await updateModpackInstance(id);
			setProgress(id, { phase: 'Actualizando modpack', done: 0, total: 1 });
		} catch (e) {
			updatingModpack = false;
			// el estado de error llega por WS; no hace falta nada más aquí
		}
	}

	// Al terminar una instalación/actualización (desaparece la barra), refrescar el estado.
	let lastProgressActive = false;
	$effect(() => {
		const active = !!(inst && ui.progress[inst.id]);
		if (lastProgressActive && !active) {
			updatingModpack = false;
			loadModpackStatus();
			refresh();
		}
		lastProgressActive = active;
	});

	onMount(async () => {
		nowTs = Date.now(); // recalcula "desde la última sesión" al entrar
		await ipcReady;
		try {
			inst = await getInstance(id);
		} catch {
			/* no existe */
		}
		await refresh();
		loadModpackStatus();
		maybeIdentify();
	});

	// Drag&drop de archivos sueltos sobre la ventana → contenido de esta instancia (E4).
	// En instancias de modpack se desactiva (añadir mods no está permitido).
	$effect(() => {
		if (inst && !inst.sourceModpackId) setContentDrop({ id, getType: () => (filter === 'all' ? '' : filter) });
		else clearContentDrop();
	});
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

	// P6 — abre el modal de confirmación con todas las actualizaciones marcadas.
	function openUpdateAll() {
		updateSel = Object.fromEntries(updatableItems.map((it) => [keyOf(it), true]));
		updateProg = { done: 0, total: 0 };
		showUpdateAll = true;
	}

	// Actualiza solo los elementos marcados; muestra el avance (P9) por el contador.
	async function confirmUpdateAll() {
		const sel = updatableItems.filter((it) => updateSel[keyOf(it)]);
		if (!sel.length) {
			showUpdateAll = false;
			return;
		}
		updatingAll = true;
		updateProg = { done: 0, total: sel.length };
		try {
			for (const it of sel) {
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
				updateProg = { ...updateProg, done: updateProg.done + 1 };
			}
			await refresh();
		} finally {
			updatingAll = false;
			showUpdateAll = false;
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

	async function cancel() {
		if (!inst) return;
		try {
			await cancelInstall(inst.id);
		} catch {
			/* puede haber terminado ya */
		}
	}

	// ── Menú "⋮" de la cabecera y estadísticas de tiempo (P1/P5) ──
	let menuOpen = $state(false);
	function runMenu(fn: () => void) {
		menuOpen = false;
		fn();
	}

	// Instante en que se abrió la instancia: el "desde la última sesión" se calcula
	// contra este momento (se refresca en onMount cada vez que se entra a la instancia).
	let nowTs = $state(Date.now());

	const totalPlayLabel = $derived(inst ? fmtHours(inst.totalPlayMs ?? 0) : '');
	const lastSessionLabel = $derived(
		inst && inst.lastPlayed > 0 ? fmtSince(inst.lastPlayed, nowTs) : ''
	);

	/** Tiempo de juego total, siempre en horas (p. ej. "12 h", "0,5 h"). */
	function fmtHours(ms: number): string {
		const h = ms / 3_600_000;
		if (h <= 0) return '0 h';
		const s = h >= 10 ? Math.round(h).toString() : h.toFixed(1);
		return s.replace('.', ',') + ' h';
	}

	/** Tiempo transcurrido desde {@code ts} en la unidad que corresponda (s/min/h/d). */
	function fmtSince(ts: number, now: number): string {
		const sec = Math.max(0, Math.floor((now - ts) / 1000));
		if (sec < 60) return `hace ${sec} s`;
		const min = Math.floor(sec / 60);
		if (min < 60) return `hace ${min} min`;
		const hrs = Math.floor(min / 60);
		if (hrs < 24) return `hace ${hrs} h`;
		const days = Math.floor(hrs / 24);
		return `hace ${days} ${days === 1 ? 'día' : 'días'}`;
	}

	// ── Ajustes de la instancia ──
	let showSettings = $state(false);
	let settingsAdvOpen = $state(false); // "Opciones avanzadas" (cerrado por defecto)
	let savingSettings = $state(false);
	// editIcon: null = sin cambios · '' = quitar · data-URL = nuevo icono.
	let editIcon = $state<string | null>(null);
	let form = $state({
		name: '',
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
	// Toggle local "ver betas/snapshots" (0.0.6: vive en las avanzadas del modal, no en
	// Ajustes). Se conserva siempre la versión actual de la instancia aunque sea
	// snapshot/beta para no perderla del selector.
	let editShowBeta = $state(false);
	const mcVersionsShown = $derived(
		editShowBeta
			? mcVersions
			: mcVersions.filter((v) => v.type === 'release' || v.id === inst?.mcVersion)
	);
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
			maxMemoryMb: inst.maxMemoryMb,
			windowWidth: inst.windowWidth,
			windowHeight: inst.windowHeight,
			fullscreen: inst.fullscreen,
			jvmArgs: inst.jvmArgs ?? '',
			javaPathOverride: inst.javaPathOverride ?? ''
		};
		settingsAdvOpen = false;
		editShowBeta = false; // por defecto solo releases (toggle local en avanzadas)
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
	let shortcutBusy = $state(false);
	async function createShortcut() {
		if (!inst || shortcutBusy) return;
		shortcutBusy = true;
		shortcutMsg = '';
		const started = Date.now();
		try {
			const { invoke } = await import('@tauri-apps/api/core');
			await invoke('create_instance_shortcut', { id: inst.id, name: inst.name });
			// La creación es casi instantánea; aseguramos que la tarjeta de progreso se vea.
			const wait = 600 - (Date.now() - started);
			if (wait > 0) await new Promise((r) => setTimeout(r, wait));
			shortcutMsg = 'Acceso directo creado en el escritorio.';
		} catch (e) {
			shortcutMsg = 'No se pudo crear el acceso directo: ' + (e as Error).message;
		} finally {
			shortcutBusy = false;
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

<svelte:window onclick={() => (menuOpen = false)} />

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
		{#if inst}
			<div class="dim playstats">
				<Icon name="clock" size={12} /><span>{totalPlayLabel}</span>
				{#if lastSessionLabel}<span class="sep">·</span><span>Última sesión {lastSessionLabel}</span>{/if}
			</div>
		{/if}
	</div>
	<div class="spacer"></div>
	{#if inst}
		<button class="ghost gear" title="Consola de la instancia" onclick={() => goto(`/instance/${id}/console`)}>
			<Icon name="terminal" size={15} />Consola
		</button>
		<div class="menuwrap">
			<button
				class="ghost gear menubtn"
				class:active={menuOpen}
				title="Más acciones"
				aria-haspopup="menu"
				aria-expanded={menuOpen}
				onclick={(e) => { e.stopPropagation(); menuOpen = !menuOpen; }}
			>
				<Icon name="more" size={16} />
			</button>
			{#if menuOpen}
				<div class="menu" transition:scale={{ start: 0.95, duration: 130 }}>
					<button onclick={() => runMenu(() => openInstanceFolder(inst!.id))}>
						<Icon name="folder" size={15} />Carpeta
					</button>
					<button onclick={() => runMenu(openExport)}>
						<Icon name="export" size={15} />Exportar
					</button>
					<button onclick={() => runMenu(createShortcut)}>
						<Icon name="external" size={15} />Acceso directo
					</button>
				</div>
			{/if}
		</div>
		<button class="ghost gear" title="Ajustes de la instancia" onclick={openSettings}>
			<Icon name="gear" size={15} />Ajustes
		</button>
	{/if}
	{#if inst && ui.progress[inst.id]}
		<div class="hprog" title={ui.progress[inst.id].phase}>
			<div class="hbar"><div class="hfill" style="width:{pct(ui.progress[inst.id])}%"></div></div>
			<div class="hprow">
				<span class="dim small">{ui.progress[inst.id].phase} · {pct(ui.progress[inst.id])}%</span>
				<button class="hcancel" title="Cancelar instalación" onclick={cancel}>
					<Icon name="close" size={12} />Cancelar
				</button>
			</div>
		</div>
	{:else if runState === 'running'}
		<button class="stop" onclick={stop}><Icon name="stop" size={13} />Detener</button>
	{:else if runState === 'launching'}
		<button class="busy" disabled>Iniciando…</button>
	{:else if inst?.installed}
		{#if modpackStatus?.updateAvailable}
			<button
				class="mpupd"
				onclick={updateModpack}
				disabled={updatingModpack}
				title="Actualizar el modpack a la versión {modpackStatus.latest}"
			>
				<Icon name="arrow-up" size={13} />{updatingModpack ? 'Actualizando…' : 'Actualizar modpack'}
			</button>
		{/if}
		<button class="play" onclick={play}><Icon name="play" size={13} />Jugar</button>
	{/if}
</header>

{#if shortcutBusy || shortcutMsg}
	<div class="stoast" transition:fade={{ duration: 120 }}>
		{#if shortcutBusy}
			<div class="screating">
				<span>Creando acceso directo…</span>
				<div class="sbar"><div class="sfill"></div></div>
			</div>
		{:else}
			{shortcutMsg}
		{/if}
	</div>
{/if}

<div class="toolbar">
	<input class="search" placeholder="Buscar en {items.length} elementos…" bind:value={query} />
	{#if isModpack}
		<span class="mpnote" title="Esta instancia está gestionada por un modpack"
			><Icon name="package" size={14} />Gestionada por modpack</span
		>
	{:else}
	{#if updateCount > 0}
		<button class="upd" disabled={updatingAll} onclick={openUpdateAll}>
			{updatingAll ? 'Actualizando…' : `Actualizar todos (${updateCount})`}
		</button>
	{/if}
	<button class="primary" onclick={() => goto(`/instance/${id}/browse`)}><Icon name="plus" size={15} />Instalar contenido</button>
	{/if}
</div>

<div class="chips">
	<button class:active={filter === 'all'} onclick={() => (filter = 'all')}>Todos ({items.length})</button>
	{#each shownTypes as t}
		<button class:active={filter === t.id} onclick={() => (filter = t.id)}>
			{t.label} ({counts[t.id] ?? 0})
		</button>
	{/each}
	{#if identifying}<span class="dim small ident">Identificando mods…</span>{/if}
	<button class="ghost xs refresh" title="Refrescar" onclick={refresh}><Icon name="refresh" size={15} /></button>
</div>

<div class="filterbar">
	{#if !isVanilla}
		<div class="fgroup">
			<span class="flabel">Entorno</span>
			<button class="fchip" class:active={envFilter.includes('client')} onclick={() => toggleEnv('client')}>
				<Icon name="monitor" size={14} />Cliente
			</button>
			<button class="fchip" class:active={envFilter.includes('server')} onclick={() => toggleEnv('server')}>
				<Icon name="server" size={14} />Servidor
			</button>
		</div>
	{/if}
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
	<div class="fgroup ordergroup">
		<span class="flabel"><Icon name="sort" size={13} />Orden</span>
		<select class="catsel" bind:value={sortBy}>
			<option value="name">Nombre (A-Z)</option>
			<option value="status">Estado</option>
			<option value="added">Fecha agregado</option>
			<option value="updated">Actualizados recientemente</option>
		</select>
		<button
			class="fchip dirbtn"
			class:active={sortDir === 'desc'}
			title={sortDir === 'asc' ? 'Invertir orden' : 'Invertir orden (descendente)'}
			onclick={() => (sortDir = sortDir === 'asc' ? 'desc' : 'asc')}
		>
			<Icon name="updown" size={14} />
		</button>
	</div>
</div>

{#if loading}
	<p class="dim pad">Cargando…</p>
{:else if visible.length === 0}
	<div class="empty">
		<p>No hay contenido {filter !== 'all' ? `de ${TYPE_LABEL[filter]}` : 'instalado'}.</p>
		{#if !isModpack}
			<button class="primary" onclick={() => goto(`/instance/${id}/browse`)}><Icon name="plus" size={15} />Instalar contenido</button>
		{/if}
	</div>
{:else}
	<div class="list">
		{#each sorted as it (it.type + '/' + it.fileName)}
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
						{#if updating[keyOf(it)] && ui.contentProgress[id]}
							<span class="updphase">{ui.contentProgress[id].phase}…</span>
						{:else}
							{it.versionName || it.fileName}{it.author ? ` · ${it.author}` : ''}
						{/if}
					</div>
				</div>
				<div class="actions">
					{#if !isModpack}
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
					{#if !isModpack}
					<button class="ghost xs danger" title="Eliminar" onclick={() => remove(it)}>
						<Icon name="trash" size={15} />
					</button>
					{/if}
				</div>
			</div>
		{/each}
	</div>
{/if}

{#if showUpdateAll}
	<div
		class="overlay"
		onclick={() => !updatingAll && (showUpdateAll = false)}
		role="presentation"
		transition:fade={{ duration: 130 }}
	>
		<div
			class="modal updmodal"
			onclick={(e) => e.stopPropagation()}
			role="dialog"
			tabindex="-1"
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			<h2>Actualizar contenido</h2>
			{#if updatableItems.length === 0}
				<p class="dim">No hay actualizaciones disponibles.</p>
			{:else if updatingAll}
				<div class="updprog">
					<div class="hbar">
						<div
							class="hfill"
							style="width:{updateProg.total ? Math.round((updateProg.done / updateProg.total) * 100) : 0}%"
						></div>
					</div>
					<p class="dim small">
						Actualizando {updateProg.done} de {updateProg.total}…{#if ui.contentProgress[id]}
							· {ui.contentProgress[id].phase}{/if}
					</p>
				</div>
			{:else}
				<p class="dim small">
					Se actualizarán los elementos marcados. Desmarca los que quieras conservar.
				</p>
				<div class="updlist">
					{#each updatableItems as it (keyOf(it))}
						<label class="updrow">
							<input type="checkbox" bind:checked={updateSel[keyOf(it)]} />
							<div class="updinfo">
								<span class="nm">{it.name}</span>
								<span class="dim small">{it.versionName} → {updates[keyOf(it)].versionName}</span>
							</div>
						</label>
					{/each}
				</div>
			{/if}
			<div class="modal-actions">
				<button class="ghost" onclick={() => (showUpdateAll = false)} disabled={updatingAll}>Cancelar</button>
				{#if updatableItems.length > 0}
					<button onclick={confirmUpdateAll} disabled={updatingAll || selectedCount === 0}>
						{updatingAll ? 'Actualizando…' : `Actualizar (${selectedCount})`}
					</button>
				{/if}
			</div>
		</div>
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
					<span class="dim small">.PNG o .GIF</span>
				</div>
			</div>
			<div class="sgrid">
				<label class="full">
					Nombre
					<input bind:value={form.name} />
				</label>
			</div>

			{#if isModpack}
				<p class="dim small mpversec">
					<Icon name="package" size={13} />La versión, el loader y los mods los gestiona el modpack;
					no se pueden cambiar aquí.
				</p>
			{:else}
			<div class="versec">
				<div class="vsgrid">
					<label>
						Minecraft
						<select bind:value={verForm.mcVersion} onchange={() => loadVerLoaderVersions()}>
							{#each mcVersionsShown as v (v.id)}<option value={v.id}>{v.id}</option>{/each}
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
			{/if}

			<button type="button" class="advtoggle" onclick={() => (settingsAdvOpen = !settingsAdvOpen)}>
				<Icon name="sliders" size={14} />Opciones avanzadas
				<Icon name={settingsAdvOpen ? 'chevron-down' : 'chevron-right'} size={14} />
			</button>
			{#if settingsAdvOpen}
				<div class="sgrid sadv" transition:slide={{ duration: 160 }}>
					<label class="full">
						Memoria máxima · <strong>{form.maxMemoryMb} MB</strong>
						<input type="range" min="1024" max="16384" step="512" bind:value={form.maxMemoryMb} />
					</label>
					<label>
						Ancho
						<input type="number" min="640" step="1" bind:value={form.windowWidth} disabled={form.fullscreen} />
					</label>
					<label>
						Alto
						<input type="number" min="480" step="1" bind:value={form.windowHeight} disabled={form.fullscreen} />
					</label>
					<label class="full chk">
						<input type="checkbox" bind:checked={form.fullscreen} />
						Pantalla completa
					</label>
					{#if !isModpack}
						<label class="full chk">
							<input type="checkbox" bind:checked={editShowBeta} />
							Mostrar versiones beta y snapshots
						</label>
					{/if}
					<label class="full">
						JVM
						<input placeholder="-XX:+UseG1GC …" bind:value={form.jvmArgs} />
					</label>
					<label class="full">
						Ruta de Java (JRE) — vacío = automático
						<input placeholder="C:\\…\\bin\\javaw.exe" bind:value={form.javaPathOverride} />
					</label>
					<div class="full repairrow">
						<button type="button" class="ghost" onclick={repair}>
							<Icon name="wrench" size={14} />Verificar y reparar archivos
						</button>
					</div>
				</div>
			{/if}

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
		flex-shrink: 0;
		object-fit: contain;
	}
	.thumb.ph {
		background: var(--bg-card);
		border: 1px solid var(--border);
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
	.playstats {
		display: flex;
		align-items: center;
		gap: 5px;
		font-size: 12px;
		margin-top: 3px;
	}
	.playstats .sep {
		opacity: 0.55;
	}
	/* Menú "⋮" de la cabecera */
	.menuwrap {
		position: relative;
		display: inline-flex;
	}
	.menubtn {
		padding: 9px 11px;
	}
	.menubtn.active {
		background: var(--bg-elev);
		color: var(--text);
	}
	.menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		z-index: 30;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 6px;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 168px;
		box-shadow: 0 12px 28px rgba(0, 0, 0, 0.4);
		transform-origin: top right;
	}
	.menu button {
		display: flex;
		align-items: center;
		gap: 10px;
		justify-content: flex-start;
		background: transparent;
		border: none;
		color: var(--text);
		padding: 8px 10px;
		border-radius: 7px;
		font-size: 13px;
		width: 100%;
		text-align: left;
	}
	.menu button:hover {
		background: var(--bg-card);
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
	.mpupd {
		background: #3fa34d;
		color: #fff;
		font-weight: 700;
		padding: 10px 18px;
		white-space: nowrap;
	}
	.mpupd:hover {
		background: #348540;
	}
	.mpupd:disabled {
		opacity: 0.6;
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
	/* Aviso "gestionada por modpack" (admin restringida) */
	.mpnote {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		white-space: nowrap;
		font-size: 12px;
		color: var(--text-dim);
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 7px 11px;
	}
	.mpversec {
		display: flex;
		align-items: center;
		gap: 7px;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 10px 12px;
		line-height: 1.4;
	}
	.filterbar {
		display: flex;
		flex-direction: row;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px 20px;
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
		gap: 15px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 14px 16px;
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
		width: 48px;
		height: 48px;
		border-radius: 10px;
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
		overflow-y: auto;
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
		object-fit: contain;
		flex-shrink: 0;
	}
	.prev.ph {
		background: var(--bg-card);
		border: 1px solid var(--border);
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
	.hprow {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 10px;
	}
	.hcancel {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 1px solid var(--border);
		color: var(--text-dim);
		border-radius: 6px;
		padding: 3px 8px;
		font-size: 11px;
		white-space: nowrap;
	}
	.hcancel:hover {
		color: var(--text);
		border-color: var(--text-dim);
	}
	.hfill {
		height: 100%;
		background: linear-gradient(90deg, var(--accent-dim), var(--accent), var(--accent-dim));
		background-size: 200% 100%;
		animation: f24-bar-shine 1.8s linear infinite;
		transition: width 0.2s ease;
	}
	/* Sección de cambio de versión / loader */
	.versec {
		display: flex;
		flex-direction: column;
		gap: 12px;
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
	.screating {
		display: flex;
		flex-direction: column;
		gap: 8px;
		min-width: 220px;
	}
	.sbar {
		height: 6px;
		background: var(--bg-card);
		border-radius: 4px;
		overflow: hidden;
	}
	.sfill {
		height: 100%;
		width: 40%;
		border-radius: 4px;
		background: linear-gradient(90deg, var(--accent-dim), var(--accent), var(--accent-dim));
		animation: shortcut-slide 1s ease-in-out infinite;
	}
	@keyframes shortcut-slide {
		0% {
			transform: translateX(-120%);
		}
		100% {
			transform: translateX(320%);
		}
	}
	.sgrid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 14px;
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
	/* "Opciones avanzadas" plegable dentro de Ajustes */
	.advtoggle {
		background: var(--bg-card);
		color: var(--text);
		border: 1px solid var(--border);
		justify-content: flex-start;
		gap: 8px;
		font-size: 13px;
		padding: 9px 12px;
	}
	.advtoggle:hover {
		background: var(--bg-elev);
	}
	.advtoggle :global(.icon:last-child) {
		margin-left: auto;
	}
	.sadv {
		margin-top: 2px;
	}
	.repairrow {
		display: flex;
		justify-content: flex-start;
	}
	/* P4 — selector de orden */
	.ordergroup .flabel {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.dirbtn {
		padding: 5px 8px;
	}
	/* P6 — modal "Actualizar contenido" */
	.updmodal {
		width: 480px;
	}
	.updlist {
		display: flex;
		flex-direction: column;
		gap: 4px;
		overflow: auto;
		max-height: 46vh;
	}
	.updrow {
		display: flex;
		align-items: center;
		gap: 11px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 8px 12px;
		cursor: pointer;
		transition: border-color 0.15s ease;
	}
	.updrow:hover {
		border-color: var(--accent-dim);
	}
	.updinfo {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.updprog {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 8px 0;
	}
	.updphase {
		color: var(--accent);
	}
</style>
