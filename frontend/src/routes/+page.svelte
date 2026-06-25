<script lang="ts">
	import { onMount } from 'svelte';
	import {
		ipcReady,
		listVersions,
		listLoaderVersions,
		createInstance,
		installInstance,
		launchInstance,
		stopInstance,
		deleteInstance,
		getSettings,
		instanceIconUrl,
		importModpack,
		updateInstance,
		listGroups,
		createGroup,
		deleteGroup,
		type VanillaVersion,
		type Instance
	} from '$lib/ipc';
	import { goto } from '$app/navigation';
	import { ui, refreshInstances, setProgress, setStatus, clearError, bumpIconBust } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fade, fly, scale, slide } from 'svelte/transition';

	let allVersions = $state<VanillaVersion[]>([]);
	let showBeta = $state(false);
	const versions = $derived(allVersions.filter((v) => showBeta || v.type === 'release'));
	let showCreate = $state(false);
	let newName = $state('');
	let newIcon = $state(''); // data-URL del icono elegido (vacío = placeholder)
	let newVersion = $state('');
	let newLoader = $state('vanilla');
	let loaderVersions = $state<string[]>([]);
	let newLoaderVersion = $state('');
	let loadingLoaders = $state(false);
	let creating = $state(false);
	let importing = $state(false);
	let importError = $state('');

	// Opciones avanzadas (prerellenadas con los valores por defecto de Ajustes).
	let advOpen = $state(false);
	let adv = $state({
		minMemoryMb: 1024,
		maxMemoryMb: 4096,
		windowWidth: 1280,
		windowHeight: 720,
		jvmArgs: ''
	});

	async function loadDefaults() {
		try {
			const s = await getSettings();
			showBeta = s.showBetaVersions;
			adv = {
				minMemoryMb: s.defaultMinMemoryMb,
				maxMemoryMb: s.defaultMaxMemoryMb,
				windowWidth: s.defaultWindowWidth,
				windowHeight: s.defaultWindowHeight,
				jvmArgs: s.defaultJvmArgs
			};
		} catch {
			/* se mantienen los valores por defecto locales */
		}
	}

	async function loadLoaderVersions() {
		newLoaderVersion = '';
		loaderVersions = [];
		if (newLoader === 'vanilla' || !newVersion) return;
		loadingLoaders = true;
		try {
			loaderVersions = await listLoaderVersions(newLoader, newVersion);
			if (loaderVersions.length) newLoaderVersion = loaderVersions[0];
		} catch {
			loaderVersions = [];
		} finally {
			loadingLoaders = false;
		}
	}

	const activeAccount = $derived(ui.accounts.find((a) => a.active));

	onMount(() => {
		loadVersions();
		loadDefaults();
		loadGroups();
	});

	$effect(() => {
		if (versions.length && !newVersion) newVersion = versions[0].id;
	});

	async function loadVersions() {
		await ipcReady; // esperar a que el IPC esté configurado
		try {
			allVersions = await listVersions();
		} catch {
			/* reintentará al abrir el modal */
		}
	}

	function openCreate() {
		showCreate = true;
		advOpen = false;
		newIcon = '';
		loadDefaults();
		if (versions.length === 0) loadVersions();
	}

	/** Selector de archivo → lee la imagen como data-URL para previsualizar y enviar. */
	function pickIcon() {
		const input = document.createElement('input');
		input.type = 'file';
		input.accept = 'image/png,image/jpeg,image/gif';
		input.onchange = () => {
			const f = input.files?.[0];
			if (!f) return;
			const r = new FileReader();
			r.onload = () => (newIcon = r.result as string);
			r.readAsDataURL(f);
		};
		input.click();
	}

	async function play(id: string) {
		setStatus(id, 'launching'); // feedback inmediato mientras arranca
		try {
			await launchInstance(id, activeAccount?.username ?? 'Player');
		} catch {
			setStatus(id, '');
		}
	}

	function retryInstall(id: string) {
		clearError(id);
		setProgress(id, { phase: 'Iniciando', done: 0, total: 1 });
		installInstance(id);
	}

	async function stop(id: string) {
		setStatus(id, 'stopping'); // feedback inmediato mientras se cierra
		try {
			await stopInstance(id);
		} catch {
			setStatus(id, 'running');
		}
	}

	async function doCreate() {
		if (!newName.trim() || !newVersion) return;
		if (newLoader !== 'vanilla' && !newLoaderVersion) return;
		creating = true;
		try {
			const inst = await createInstance({
				name: newName.trim(),
				mcVersion: newVersion,
				loader: newLoader,
				loaderVersion: newLoader === 'vanilla' ? '' : newLoaderVersion,
				minMemoryMb: adv.minMemoryMb,
				maxMemoryMb: adv.maxMemoryMb,
				windowWidth: adv.windowWidth,
				windowHeight: adv.windowHeight,
				jvmArgs: adv.jvmArgs,
				iconData: newIcon || undefined
			});
			if (newIcon) bumpIconBust();
			showCreate = false;
			newName = '';
			newIcon = '';
			newLoader = 'vanilla';
			newLoaderVersion = '';
			loaderVersions = [];
			setProgress(inst.id, { phase: 'Iniciando', done: 0, total: 1 }); // barra al instante
			await refreshInstances();
			installInstance(inst.id); // sin await: el progreso por WS toma el relevo
		} finally {
			creating = false;
		}
	}

	function pct(p: { done: number; total: number }) {
		return p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
	}

	/** Importa un modpack desde un archivo local elegido en el diálogo. */
	async function importFromFile() {
		importError = '';
		try {
			const { open } = await import('@tauri-apps/plugin-dialog');
			const path = await open({
				multiple: false,
				filters: [{ name: 'Modpack', extensions: ['f24pack', 'mrpack', 'zip'] }]
			});
			if (typeof path !== 'string') return;
			importing = true;
			const inst = await importModpack(path);
			showCreate = false;
			setProgress(inst.id, { phase: 'Importando', done: 0, total: 1 });
			await refreshInstances();
		} catch (e) {
			importError = (e as Error).message;
		} finally {
			importing = false;
		}
	}

	function openInstance(e: Event, id: string) {
		if (suppressClick) return; // veníamos de arrastrar, no abrir
		if ((e.target as HTMLElement).closest('button')) return;
		goto(`/instance/${id}`);
	}

	async function toggleFavorite(e: Event, inst: Instance) {
		e.stopPropagation();
		await updateInstance(inst.id, { favorite: !inst.favorite });
		await refreshInstances();
	}

	// ── Grupos (P14): se gestionan en esta ventana, no por instancia ──
	let groups = $state<string[]>([]);
	let addingGroup = $state(false);
	let newGroupName = $state('');

	const ungrouped = $derived(ui.instances.filter((i) => !i.group));
	const instancesIn = (g: string) => ui.instances.filter((i) => (i.group || '') === g);

	async function loadGroups() {
		try {
			groups = await listGroups();
		} catch {
			/* el backend puede no estar listo aún */
		}
	}

	function startAddGroup() {
		addingGroup = true;
		newGroupName = '';
	}

	async function confirmAddGroup() {
		const n = newGroupName.trim();
		if (!n) {
			addingGroup = false;
			return;
		}
		try {
			groups = await createGroup(n);
		} finally {
			addingGroup = false;
			newGroupName = '';
		}
	}

	async function removeGroup(g: string) {
		await deleteGroup(g);
		await loadGroups();
		await refreshInstances(); // el backend desasigna las instancias que lo usaban
	}

	// Arrastrar y soltar tarjetas entre "Instancias" (grupo '') y los grupos.
	// Se usa con eventos de puntero, NO con HTML5 draggable: el webview de Tauri
	// intercepta el drag-drop nativo (para importar modpacks soltándolos) y eso
	// rompe el HTML5 drag dentro de la página (cursor de "bloqueado").
	let draggedId = $state<string | null>(null);
	let dropTarget = $state<string | null>(null);
	let dragGhost = $state<{ id: string; name: string; icon: string; x: number; y: number } | null>(null);
	let dragPending: { id: string; name: string; icon: string; x: number; y: number } | null = null;
	let suppressClick = false;

	function onCardPointerDown(e: PointerEvent, inst: Instance) {
		if (e.button !== 0) return; // solo botón principal
		if ((e.target as HTMLElement).closest('button')) return; // no arrancar sobre botones internos
		dragPending = { id: inst.id, name: inst.name, icon: inst.icon, x: e.clientX, y: e.clientY };
		window.addEventListener('pointermove', onPointerMove);
		window.addEventListener('pointerup', onPointerUp);
		window.addEventListener('pointercancel', onPointerUp);
	}

	function onPointerMove(e: PointerEvent) {
		if (dragPending && !draggedId) {
			if (Math.hypot(e.clientX - dragPending.x, e.clientY - dragPending.y) < 6) return;
			draggedId = dragPending.id; // supera el umbral → empieza el arrastre real
			dragGhost = { ...dragPending };
		}
		if (draggedId) {
			e.preventDefault();
			if (dragGhost) dragGhost = { ...dragGhost, x: e.clientX, y: e.clientY };
			dropTarget = zoneAt(e.clientX, e.clientY);
		}
	}

	function zoneAt(x: number, y: number): string | null {
		const el = document.elementFromPoint(x, y) as HTMLElement | null;
		const zone = el?.closest('[data-drop]') as HTMLElement | null;
		return zone ? (zone.getAttribute('data-drop') ?? '') : null;
	}

	async function onPointerUp() {
		window.removeEventListener('pointermove', onPointerMove);
		window.removeEventListener('pointerup', onPointerUp);
		window.removeEventListener('pointercancel', onPointerUp);
		const id = draggedId;
		const target = dropTarget;
		const wasDragging = !!draggedId;
		dragPending = null;
		draggedId = null;
		dragGhost = null;
		dropTarget = null;
		if (!wasDragging) return; // fue un clic: openInstance se encarga
		suppressClick = true; // evita que el clic posterior abra la instancia
		setTimeout(() => (suppressClick = false), 0);
		if (id && target !== null) {
			const inst = ui.instances.find((i) => i.id === id);
			if (inst && (inst.group || '') !== target) {
				await updateInstance(id, { group: target });
				await refreshInstances();
			}
		}
	}
</script>

<header>
	<h1>Instancias</h1>
	<div class="actions">
		{#if activeAccount}
			<button onclick={openCreate} disabled={!ui.connected}><Icon name="plus" />Crear instancia</button>
		{:else}
			<button onclick={() => goto('/accounts')}><Icon name="key" />Iniciar sesión</button>
		{/if}
	</div>
</header>

{#if !ui.connected}
	<p class="dim">No hay conexión con el servicio. Esperá unos segundos a que inicie.</p>
{:else if ui.instances.length === 0}
	<div class="empty">
		<p>Todavía no creaste ninguna instancia.</p>
		<p class="dim">Creá una nueva y se descargará e instalará automáticamente.</p>
	</div>
{:else}
	<!-- P14: secciones fijas "Instancias" (sin grupo) y "Grupos", con arrastrar y soltar -->
	<section
		class="zone"
		class:over={dropTarget === ''}
		role="group"
		aria-label="Instancias sin grupo"
		data-drop=""
	>
		<h2 class="secthdr">Instancias</h2>
		{#if ungrouped.length}
			<div class="grid">
				{#each ungrouped as inst, i (inst.id)}
					{@render card(inst, i)}
				{/each}
			</div>
		{:else}
			<p class="dim hint">Arrastra instancias aquí para quitarlas de su grupo.</p>
		{/if}
	</section>

	<section class="groupssec">
		<div class="secthead">
			<h2 class="secthdr">Grupos</h2>
			{#if addingGroup}
				<span class="addform">
					<input
						class="ginput"
						placeholder="Nombre del grupo"
						bind:value={newGroupName}
						onkeydown={(e) => {
							if (e.key === 'Enter') confirmAddGroup();
							else if (e.key === 'Escape') addingGroup = false;
						}}
					/>
					<button class="xs" onclick={confirmAddGroup}>Crear</button>
					<button class="ghost xs" onclick={() => (addingGroup = false)}>Cancelar</button>
				</span>
			{:else}
				<button class="ghost addgroup" onclick={startAddGroup}>
					<Icon name="plus" size={14} />Agregar grupo
				</button>
			{/if}
		</div>

		{#if groups.length === 0}
			<p class="dim hint">No hay grupos todavía. Crea uno y arrastra instancias dentro.</p>
		{:else}
			{#each groups as g (g)}
				<div
					class="groupbox"
					class:over={dropTarget === g}
					role="group"
					aria-label={g}
					data-drop={g}
					transition:slide={{ duration: 160 }}
				>
					<div class="grouphead">
						<span class="gname">{g}</span>
						<span class="gcount dim">{instancesIn(g).length}</span>
						<div class="gspacer"></div>
						<button class="ghost xs danger" title="Eliminar grupo" onclick={() => removeGroup(g)}>
							<Icon name="trash" size={14} />
						</button>
					</div>
					{#if instancesIn(g).length}
						<div class="grid">
							{#each instancesIn(g) as inst, i (inst.id)}
								{@render card(inst, i)}
							{/each}
						</div>
					{:else}
						<p class="dim hint">Vacío · arrastra instancias aquí.</p>
					{/if}
				</div>
			{/each}
		{/if}
	</section>
{/if}

{#snippet card(inst: Instance, i: number)}
	{@const prog = ui.progress[inst.id]}
	{@const state = ui.status[inst.id]}
	<div
		class="card"
		class:dragging={draggedId === inst.id}
		in:fly={{ y: 12, duration: 260, delay: Math.min(i * 35, 280) }}
		role="button"
		tabindex="0"
		title="Gestionar contenido"
		onpointerdown={(e) => onCardPointerDown(e, inst)}
		onclick={(e) => openInstance(e, inst.id)}
		onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), goto(`/instance/${inst.id}`))}
	>
				<div class="head">
					{#if inst.icon}
						<img class="thumb" src={instanceIconUrl(inst.id, ui.iconBust)} alt="" />
					{:else}
						<div class="thumb ph"><Icon name="package" size={20} /></div>
					{/if}
					<div class="htext">
						<span class="name">{inst.name}</span>
						<div class="dim meta">
							MC {inst.mcVersion}{inst.loader && inst.loader !== 'vanilla' ? ` · ${inst.loader}` : ''}
						</div>
					</div>
					<button
						class="starbtn"
						class:on={inst.favorite}
						title={inst.favorite ? 'Quitar de favoritas' : 'Marcar como favorita'}
						onclick={(e) => toggleFavorite(e, inst)}
					>
						<Icon name="star" size={16} fill={inst.favorite} />
					</button>
				</div>

				{#if prog}
					<div class="prog">
						<div class="bar"><div class="fill" style="width:{pct(prog)}%"></div></div>
						<div class="dim small">{prog.phase} · {pct(prog)}%</div>
					</div>
				{:else if !inst.installed}
					{#if ui.errors[inst.id]}
						<div class="err-box" title={ui.errors[inst.id]}>{ui.errors[inst.id]}</div>
					{/if}
					<div class="row">
						<button onclick={() => retryInstall(inst.id)}>
							{ui.errors[inst.id] ? 'Reintentar' : 'Instalar'}
						</button>
						<button
							class="ghost danger"
							onclick={() => deleteInstance(inst.id).then(refreshInstances)}
							title="Borrar instancia"><Icon name="trash" size={15} /></button
						>
					</div>
				{:else if state === 'launching'}
					<div class="row">
						<button class="busy" disabled>Iniciando…</button>
					</div>
				{:else if state === 'stopping'}
					<div class="row">
						<button class="busy" disabled>Deteniendo…</button>
					</div>
				{:else if state === 'running'}
					<div class="row">
						<button class="stop" onclick={() => stop(inst.id)}><Icon name="stop" size={13} />Detener</button>
					</div>
				{:else}
					{#if ui.errors[inst.id]}
						<div class="err-box" title={ui.errors[inst.id]}>{ui.errors[inst.id]}</div>
					{/if}
					<div class="row">
						<button class="play" onclick={() => play(inst.id)}><Icon name="play" size={13} />Jugar</button>
						<button
							class="ghost danger"
							onclick={() => deleteInstance(inst.id).then(refreshInstances)}
							title="Borrar instancia"><Icon name="trash" size={15} /></button
						>
					</div>
				{/if}
			</div>
{/snippet}

{#if dragGhost}
	<div class="dragghost" style="left:{dragGhost.x}px; top:{dragGhost.y}px">
		{#if dragGhost.icon}
			<img src={instanceIconUrl(dragGhost.id, ui.iconBust)} alt="" />
		{:else}
			<span class="gph"><Icon name="package" size={15} /></span>
		{/if}
		<span class="gname">{dragGhost.name}</span>
	</div>
{/if}

<!-- Modal crear -->
{#if showCreate}
	<div class="overlay" onclick={() => (showCreate = false)} role="presentation" transition:fade={{ duration: 130 }}>
		<div
			class="modal"
			onclick={(e) => e.stopPropagation()}
			role="dialog"
			tabindex="-1"
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			<h2>Nueva instancia</h2>
			<div class="iconpick">
				{#if newIcon}
					<img class="prev" src={newIcon} alt="Icono elegido" />
				{:else}
					<div class="prev ph"><Icon name="package" size={24} /></div>
				{/if}
				<div class="iconbtns">
					<button type="button" class="ghost" onclick={pickIcon}>
						<Icon name="download" size={14} />Elegir icono…
					</button>
					{#if newIcon}
						<button type="button" class="ghost danger" onclick={() => (newIcon = '')}>Quitar</button>
					{/if}
					<span class="dim small">PNG · se recorta a cuadrado</span>
				</div>
			</div>
			<label>Nombre<input bind:value={newName} placeholder="Mi instancia" /></label>
			<label>
				Versión de Minecraft
				<select bind:value={newVersion} onchange={loadLoaderVersions}>
					{#each versions as v}<option value={v.id}>{v.id}</option>{/each}
				</select>
			</label>
			<label>
				Cargador de mods
				<select bind:value={newLoader} onchange={loadLoaderVersions}>
					<option value="vanilla">Vanilla (sin mods)</option>
					<option value="fabric">Fabric</option>
					<option value="quilt">Quilt</option>
					<option value="neoforge">NeoForge</option>
					<option value="forge">Forge</option>
				</select>
			</label>
			{#if newLoader !== 'vanilla'}
				<label>
					Versión de {newLoader}
					<select bind:value={newLoaderVersion} disabled={loadingLoaders || !loaderVersions.length}>
						{#if loadingLoaders}
							<option>Cargando…</option>
						{:else if !loaderVersions.length}
							<option>Sin versiones para {newVersion}</option>
						{:else}
							{#each loaderVersions as lv}<option value={lv}>{lv}</option>{/each}
						{/if}
					</select>
				</label>
			{/if}

			<button type="button" class="advtoggle" onclick={() => (advOpen = !advOpen)}>
				<Icon name="sliders" size={14} />Opciones avanzadas
				<Icon name={advOpen ? 'chevron-down' : 'chevron-right'} size={14} />
			</button>
			{#if advOpen}
				<div class="adv" transition:slide={{ duration: 160 }}>
					<label class="full">
						Memoria máxima · <strong>{adv.maxMemoryMb} MB</strong>
						<input type="range" min="1024" max="16384" step="512" bind:value={adv.maxMemoryMb} />
					</label>
					<div class="advrow">
						<label>
							Memoria mínima (MB)
							<input type="number" min="256" max="16384" step="256" bind:value={adv.minMemoryMb} />
						</label>
						<label>
							Ancho
							<input type="number" min="640" step="1" bind:value={adv.windowWidth} />
						</label>
						<label>
							Alto
							<input type="number" min="480" step="1" bind:value={adv.windowHeight} />
						</label>
					</div>
					<label class="full">
						Argumentos JVM
						<input placeholder="-XX:+UseG1GC …" bind:value={adv.jvmArgs} />
					</label>
				</div>
			{/if}
			{#if importError}
				<div class="err-box" title={importError}>{importError}</div>
			{/if}
			<div class="modal-actions">
				<button type="button" class="ghost" onclick={importFromFile} disabled={importing}>
					<Icon name="download" size={14} />{importing ? 'Importando…' : 'Desde archivo…'}
				</button>
				<div class="aspacer"></div>
				<button class="ghost" onclick={() => (showCreate = false)}>Cancelar</button>
				<button
					onclick={doCreate}
					disabled={creating ||
						!newName.trim() ||
						!newVersion ||
						(newLoader !== 'vanilla' && !newLoaderVersion)}
				>
					{creating ? 'Creando…' : 'Crear e instalar'}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 22px;
		gap: 16px;
	}
	.actions {
		display: flex;
		align-items: center;
		gap: 12px;
	}
	input,
	select {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 7px 9px;
		font-family: inherit;
		font-size: 13px;
	}
	.dim {
		color: var(--text-dim);
	}
	.small {
		font-size: 11px;
	}
	.empty {
		border: 1px dashed var(--border);
		border-radius: var(--radius);
		padding: 40px;
		text-align: center;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
		gap: 14px;
		padding-bottom: 24px;
	}
	.card {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 16px;
		display: flex;
		flex-direction: column;
		gap: 10px;
		cursor: pointer;
		user-select: none;
		transition:
			border-color 0.15s ease,
			transform 0.15s ease;
	}
	.card:hover {
		border-color: var(--accent-dim);
		transform: translateY(-2px);
	}
	.head {
		display: flex;
		align-items: center;
		gap: 11px;
		min-width: 0;
	}
	.thumb {
		width: 40px;
		height: 40px;
		border-radius: 9px;
		object-fit: cover;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		flex-shrink: 0;
	}
	.thumb.ph {
		display: grid;
		place-items: center;
		color: var(--text-dim);
	}
	.htext {
		min-width: 0;
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.starbtn {
		background: transparent;
		border: none;
		padding: 4px;
		border-radius: 6px;
		color: var(--text-dim);
		cursor: pointer;
		flex-shrink: 0;
		align-self: flex-start;
	}
	.starbtn:hover {
		color: var(--accent);
		background: var(--bg-elev);
	}
	.starbtn.on {
		color: var(--accent);
	}
	.secthdr {
		font-size: 12px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.6px;
		color: var(--text-dim);
		margin: 0 0 10px;
	}
	/* P14 — zonas de arrastre y secciones de grupos */
	.zone {
		border-radius: var(--radius);
		padding: 8px;
		margin: 0 -8px 8px;
		min-height: 56px;
		transition:
			box-shadow 0.15s ease,
			background 0.15s ease;
	}
	.zone.over {
		box-shadow: inset 0 0 0 2px var(--accent);
		background: rgba(127, 127, 127, 0.05);
	}
	.groupssec {
		margin-top: 22px;
	}
	.secthead {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 10px;
	}
	.secthead .secthdr {
		margin: 0;
	}
	.addgroup {
		font-size: 12px;
		padding: 6px 11px;
	}
	.addform {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.ginput {
		min-width: 180px;
	}
	.groupbox {
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 12px;
		margin-bottom: 12px;
		transition:
			border-color 0.15s ease,
			box-shadow 0.15s ease;
	}
	.groupbox.over {
		border-color: var(--accent);
		box-shadow: inset 0 0 0 1px var(--accent);
	}
	.grouphead {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: 10px;
	}
	.gname {
		font-weight: 600;
	}
	.gcount {
		font-size: 12px;
	}
	.gspacer {
		flex: 1;
	}
	.hint {
		font-size: 13px;
		padding: 10px 4px;
	}
	.card.dragging {
		opacity: 0.5;
	}
	.dragghost {
		position: fixed;
		z-index: 200;
		pointer-events: none;
		transform: translate(10px, 10px);
		display: flex;
		align-items: center;
		gap: 8px;
		background: var(--bg-elev);
		border: 1px solid var(--accent);
		border-radius: 8px;
		padding: 6px 10px;
		box-shadow: 0 8px 20px rgba(0, 0, 0, 0.4);
		font-size: 13px;
		font-weight: 600;
		max-width: 220px;
	}
	.dragghost img,
	.dragghost .gph {
		width: 20px;
		height: 20px;
		border-radius: 5px;
		object-fit: cover;
		flex-shrink: 0;
		display: grid;
		place-items: center;
		color: var(--text-dim);
		background: var(--bg-card);
	}
	.dragghost .gname {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.name {
		font-weight: 600;
		color: var(--text);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.card:hover .name {
		color: var(--accent);
	}
	.meta {
		font-size: 12px;
	}
	/* Selector de icono en el modal de creación */
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
	.row {
		display: flex;
		gap: 8px;
		align-items: center;
	}
	.row button:first-child {
		flex: 1;
	}
	.danger:hover {
		color: #ff6b6b;
	}
	.err-box {
		background: rgba(255, 107, 107, 0.12);
		border: 1px solid rgba(255, 107, 107, 0.4);
		color: #ff9b9b;
		border-radius: 8px;
		padding: 7px 9px;
		font-size: 11px;
		line-height: 1.4;
		max-height: 76px;
		overflow: auto;
		word-break: break-word;
	}
	.play {
		font-weight: 700;
	}
	.stop {
		background: #e8483f;
		color: #fff;
		font-weight: 700;
		width: 100%;
	}
	.stop:hover {
		background: #c93a32;
	}
	.busy {
		width: 100%;
		background: var(--bg-elev);
		color: var(--text-dim);
		border: 1px solid var(--border);
		cursor: progress;
	}
	.prog .bar {
		height: 8px;
		background: var(--bg-elev);
		border-radius: 4px;
		overflow: hidden;
	}
	.prog .fill {
		height: 100%;
		background: linear-gradient(90deg, var(--accent-dim), var(--accent), var(--accent-dim));
		background-size: 200% 100%;
		animation: f24-bar-shine 1.8s linear infinite;
		transition: width 0.2s ease;
	}
	.prog .small {
		margin-top: 5px;
	}
	/* Modal */
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
		padding: 24px;
		width: 360px;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.modal label {
		display: flex;
		flex-direction: column;
		gap: 5px;
		font-size: 13px;
		color: var(--text-dim);
	}
	.advtoggle {
		background: var(--bg-card);
		color: var(--text);
		border: 1px solid var(--border);
		justify-content: flex-start;
		gap: 8px;
		font-size: 13px;
	}
	.advtoggle:hover {
		background: var(--bg-elev);
	}
	.advtoggle :global(.icon:last-child) {
		margin-left: auto;
	}
	.adv {
		display: flex;
		flex-direction: column;
		gap: 12px;
		padding: 4px 2px;
	}
	.adv .advrow {
		display: flex;
		gap: 10px;
	}
	.adv .advrow label {
		flex: 1;
		min-width: 0;
	}
	.adv input[type='range'] {
		accent-color: var(--accent);
	}
	.modal-actions {
		display: flex;
		justify-content: flex-end;
		align-items: center;
		gap: 10px;
		margin-top: 4px;
	}
	.aspacer {
		flex: 1;
	}
	button:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
</style>
