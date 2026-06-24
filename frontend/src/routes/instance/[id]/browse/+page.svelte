<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { marked } from 'marked';
	import {
		ipcReady,
		getInstance,
		searchContent,
		getContentCategories,
		getContentProject,
		getContentVersions,
		installContent,
		listInstalledContent,
		listVersions,
		openExternal,
		type Instance,
		type ContentProject,
		type ProjectDetail,
		type ContentVersion,
		type ContentType,
		type ContentSource,
		type Category
	} from '$lib/ipc';
	import { TYPES, TYPE_LABEL, formatCount, timeAgo, formatSize } from '$lib/content';
	import Icon from '$lib/Icon.svelte';
	import { fade, fly } from 'svelte/transition';

	const id = $derived($page.params.id ?? '');
	let inst = $state<Instance | null>(null);

	const LOADERS = ['fabric', 'forge', 'neoforge', 'quilt'];

	let type = $state<ContentType>('mods');
	let source = $state<ContentSource>('modrinth');
	let query = $state('');
	let sort = $state('relevance');
	let selectedCats = $state<string[]>([]);
	let selectedEnv = $state<string[]>([]);
	let categories = $state<Category[]>([]);
	let openCats = $state<Record<string, boolean>>({});
	let pageNum = $state(0);

	// Árbol de categorías: en CurseForge algunas son hijas de otras (p. ej. "Addons").
	const rootCats = $derived(categories.filter((c) => !c.parentId));
	const childrenOf = (id: string) => categories.filter((c) => c.parentId === id);
	const hasChildren = (id: string) => categories.some((c) => c.parentId === id);

	function toggleCatGroup(id: string) {
		openCats = { ...openCats, [id]: !openCats[id] };
	}

	let mcLocked = $state(true);
	let loaderLocked = $state(true);
	let mcFilter = $state('');
	let loaderFilter = $state('');
	let gameVersions = $state<string[]>([]);

	const mcEff = $derived(mcLocked ? (inst?.mcVersion ?? '') : mcFilter);
	const loaderEff = $derived(loaderLocked ? (inst?.loader ?? '') : loaderFilter);
	const filtersOff = $derived(!mcLocked || !loaderLocked);

	let hits = $state<ContentProject[]>([]);
	let total = $state(0);
	let loading = $state(false);
	const PER = 20;
	const lastPage = $derived(Math.max(0, Math.ceil(total / PER) - 1));

	let installed = $state<Set<string>>(new Set());
	let busy = $state<Record<string, boolean>>({});

	let open = $state({ version: true, loader: true, env: true, category: true });

	let debounce: ReturnType<typeof setTimeout>;

	onMount(async () => {
		await ipcReady;
		try {
			inst = await getInstance(id);
		} catch {
			/* ignore */
		}
		await loadInstalled();
		try {
			gameVersions = (await listVersions()).filter((v) => v.type === 'release').map((v) => v.id);
		} catch {
			gameVersions = [];
		}

		const openParam = $page.url.searchParams.get('open');
		if (openParam) {
			const [src, t, ...rest] = openParam.split(':');
			const pid = rest.join(':');
			if (src && t && pid) {
				source = src as ContentSource;
				type = t as ContentType;
				await openProjectById(source, type, pid);
			}
		}

		await loadCategories();
		await runSearch();
	});

	function toggleSec(k: 'version' | 'loader' | 'env' | 'category') {
		open = { ...open, [k]: !open[k] };
	}

	function toggleCat(v: string) {
		selectedCats = selectedCats.includes(v)
			? selectedCats.filter((c) => c !== v)
			: [...selectedCats, v];
		reset();
	}

	function toggleEnv(v: string) {
		selectedEnv = selectedEnv.includes(v)
			? selectedEnv.filter((e) => e !== v)
			: [...selectedEnv, v];
		reset();
	}

	function toggleMcLock() {
		mcLocked = !mcLocked;
		reset();
	}

	function toggleLoaderLock() {
		loaderLocked = !loaderLocked;
		reset();
	}

	function setMc(v: string) {
		mcFilter = v;
		reset();
	}

	function setLoader(v: string) {
		loaderFilter = v;
		reset();
	}

	async function loadInstalled() {
		try {
			const list = await listInstalledContent(id);
			installed = new Set(list.filter((i) => i.projectId).map((i) => i.source + ':' + i.projectId));
		} catch {
			/* ignore */
		}
	}

	async function loadCategories() {
		openCats = {};
		try {
			categories = await getContentCategories(source, type);
		} catch {
			categories = [];
		}
	}

	async function runSearch() {
		if (!inst) return;
		loading = true;
		try {
			const r = await searchContent({
				source,
				type,
				mc: mcEff,
				loader: loaderEff,
				q: query,
				categories: selectedCats,
				environments: type === 'mods' ? selectedEnv : [],
				sort,
				page: pageNum
			});
			hits = r.hits;
			total = r.total;
		} catch {
			hits = [];
			total = 0;
		} finally {
			loading = false;
		}
	}

	function reset() {
		pageNum = 0;
		runSearch();
	}

	function onQuery() {
		clearTimeout(debounce);
		debounce = setTimeout(reset, 350);
	}

	async function pickType(t: ContentType) {
		type = t;
		selectedCats = [];
		await loadCategories();
		reset();
	}

	async function pickSource(s: ContentSource) {
		source = s;
		selectedCats = [];
		await loadCategories();
		reset();
	}

	function goPage(p: number) {
		pageNum = Math.min(Math.max(0, p), lastPage);
		runSearch();
		window.scrollTo({ top: 0 });
	}

	async function install(p: ContentProject, versionId = '') {
		const key = p.source + ':' + p.id;
		const force = versionId ? true : filtersOff;
		busy = { ...busy, [key]: true };
		try {
			await installContent(id, { source: p.source, type: p.type, projectId: p.id, versionId, ignore: force });
			installed = new Set([...installed, key]);
		} catch (e) {
			alert('No se pudo instalar: ' + (e as Error).message);
		} finally {
			busy = { ...busy, [key]: false };
		}
	}

	function isInstalled(p: ContentProject) {
		return installed.has(p.source + ':' + p.id);
	}

	// ── Página de proyecto ──
	let detail = $state<ProjectDetail | null>(null);
	let detailLoading = $state(false);
	let versions = $state<ContentVersion[]>([]);
	let bodyHtml = $state('');

	async function openProject(p: ContentProject) {
		source = p.source;
		type = p.type;
		await openProjectById(p.source, p.type, p.id);
	}

	async function openProjectById(src: ContentSource, t: ContentType, pid: string) {
		detail = null;
		detailLoading = true;
		versions = [];
		bodyHtml = '';
		try {
			const d = await getContentProject(src, t, pid);
			detail = d;
			bodyHtml = d.bodyFormat === 'markdown' ? await marked.parse(d.body || '') : d.body || '';
			versions = await getContentVersions(src, t, pid, mcEff, loaderEff, false);
			if (!versions.length) versions = await getContentVersions(src, t, pid, mcEff, loaderEff, true);
		} catch {
			/* ignore */
		} finally {
			detailLoading = false;
		}
	}

	function closeProject() {
		detail = null;
		loadInstalled();
	}

	async function installVersion(v: ContentVersion) {
		if (!detail) return;
		await install(detail as ContentProject, v.id);
	}
</script>

<header>
	<button class="ghost back" onclick={() => goto(`/instance/${id}`)}><Icon name="arrow-left" size={16} /></button>
	<div class="title">
		<h1>{inst?.name ?? id}</h1>
		<div class="dim sub">
			Instalando contenido · MC {inst?.mcVersion ?? ''} ·
			<span class="cap">{inst?.loader ?? ''}</span>
		</div>
	</div>
</header>

{#if detail || detailLoading}
	<!-- Página de proyecto -->
	<div class="proj">
		<button class="ghost back2" onclick={closeProject}><Icon name="arrow-left" size={15} />Volver al catálogo</button>
		{#if detailLoading}
			<p class="dim pad">Cargando proyecto…</p>
		{:else if detail}
			<div class="phead">
				{#if detail.iconUrl}
					<img class="picon" src={detail.iconUrl} alt="" />
				{:else}
					<div class="picon ph"><Icon name="package" size={30} /></div>
				{/if}
				<div class="pmeta">
					<h2>{detail.name}</h2>
					<div class="dim">{detail.description}</div>
					<div class="pstats dim small">
						<span class="stat"><Icon name="download" size={12} />{formatCount(detail.downloads)}</span>
						{#if detail.follows}<span class="stat"><Icon name="heart" size={12} fill />{formatCount(detail.follows)}</span>{/if}
						{#if detail.author}<span>por {detail.author}</span>{/if}
						{#if detail.license}<span>{detail.license}</span>{/if}
						<span class="cap">{detail.source}</span>
					</div>
				</div>
				<button
					class="install"
					disabled={busy[detail.source + ':' + detail.id] || installed.has(detail.source + ':' + detail.id)}
					onclick={() => install(detail as ProjectDetail)}
				>
					{#if installed.has(detail.source + ':' + detail.id)}
						<Icon name="check" size={15} />Instalado
					{:else if busy[detail.source + ':' + detail.id]}
						…
					{:else}
						<Icon name="download" size={15} />Instalar
					{/if}
				</button>
			</div>

			{#if Object.keys(detail.links).length}
				<div class="links">
					{#each Object.entries(detail.links) as [label, url]}
						<button class="ghost xs" onclick={() => openExternal(url)}>{label}<Icon name="external" size={13} /></button>
					{/each}
				</div>
			{/if}

			{#if detail.gallery.length}
				<div class="gallery">
					{#each detail.gallery.slice(0, 8) as g}
						<img src={g} alt="" loading="lazy" />
					{/each}
				</div>
			{/if}

			<div class="pgrid">
				<div class="body">
					<!-- contenido remoto de Modrinth/CurseForge, app local privada -->
					{@html bodyHtml}
				</div>
				<aside class="vside">
					<h3>Versiones {filtersOff ? '(filtradas)' : 'compatibles'}</h3>
					{#if !versions.length}
						<p class="dim small">Sin versiones.</p>
					{:else}
						{#each versions.slice(0, 30) as v (v.id)}
							<div class="vrow">
								<div class="vinfo">
									<div class="nm">{v.versionNumber}</div>
									<div class="dim small">
										{v.channel} · {v.gameVersions.slice(0, 3).join(', ')}
										{#if v.size}· {formatSize(v.size)}{/if}
									</div>
								</div>
								<button class="xs" onclick={() => installVersion(v)}>Instalar</button>
							</div>
						{/each}
					{/if}
				</aside>
			</div>
		{/if}
	</div>
{:else}
	<!-- Catálogo -->
	<div class="tabs">
		{#each TYPES as t}
			<button class:active={type === t.id} onclick={() => pickType(t.id)}>{t.label}</button>
		{/each}
		<div class="srcsel">
			<button
				class="src"
				class:active={source === 'modrinth'}
				onclick={() => pickSource('modrinth')}>Modrinth</button
			>
			<button
				class="src"
				class:active={source === 'curseforge'}
				onclick={() => pickSource('curseforge')}>CurseForge</button
			>
		</div>
	</div>

	<div class="catalog">
		<div class="main">
			<div class="searchrow">
				<input
					class="search"
					placeholder="Buscar {TYPE_LABEL[type].toLowerCase()}…"
					bind:value={query}
					oninput={onQuery}
				/>
				<select bind:value={sort} onchange={reset}>
					<option value="relevance">Relevancia</option>
					<option value="downloads">Descargas</option>
					<option value="follows">Seguidores</option>
					<option value="newest">Más nuevos</option>
					<option value="updated">Actualizados</option>
				</select>
			</div>

			{#if loading}
				<p class="dim pad">Buscando…</p>
			{:else if !hits.length}
				<p class="dim pad">Sin resultados.</p>
			{:else}
				<div class="results">
					{#each hits as p, i (p.source + ':' + p.id)}
						<div class="rcard" in:fly={{ y: 10, duration: 220, delay: Math.min(i * 25, 250) }}>
							<button class="rclick" onclick={() => openProject(p)} aria-label={p.name}>
								{#if p.iconUrl}
									<img class="ricon" src={p.iconUrl} alt="" loading="lazy" />
								{:else}
									<div class="ricon ph"><Icon name="package" size={30} /></div>
								{/if}
							</button>
							<div class="rinfo">
								<div class="rtop">
									<button class="link rname" onclick={() => openProject(p)}>{p.name}</button>
									{#if p.author}<span class="dim small">por {p.author}</span>{/if}
								</div>
								<div class="dim rdesc">{p.description}</div>
								<div class="rmeta dim small">
									<span class="stat"><Icon name="download" size={12} />{formatCount(p.downloads)}</span>
									{#if p.follows}<span class="stat"><Icon name="heart" size={12} fill />{formatCount(p.follows)}</span>{/if}
									{#if p.dateModified}<span class="stat"><Icon name="clock" size={12} />{timeAgo(p.dateModified)}</span>{/if}
								</div>
							</div>
							<button
								class="install"
								disabled={busy[p.source + ':' + p.id] || isInstalled(p)}
								onclick={() => install(p)}
							>
								{#if isInstalled(p)}
									<Icon name="check" size={15} />
								{:else if busy[p.source + ':' + p.id]}
									…
								{:else}
									<Icon name="download" size={14} />Instalar
								{/if}
							</button>
						</div>
					{/each}
				</div>

				<div class="pager">
					<button class="ghost xs" disabled={pageNum === 0} onclick={() => goPage(pageNum - 1)}>
						<Icon name="chevron-left" size={15} />
					</button>
					<span class="dim small">Página {pageNum + 1} de {lastPage + 1}</span>
					<button class="ghost xs" disabled={pageNum >= lastPage} onclick={() => goPage(pageNum + 1)}>
						<Icon name="chevron-right" size={15} />
					</button>
				</div>
			{/if}
		</div>

		<aside class="sidebar">
			<section class="fsec">
				<button class="fhead" onclick={() => toggleSec('version')}>
					<span>Versión del juego</span><span class="chev"><Icon name={open.version ? 'chevron-down' : 'chevron-right'} size={14} /></span>
				</button>
				{#if open.version}
					<div class="fbody">
						<button class="lockbtn" class:locked={mcLocked} onclick={toggleMcLock}>
							<Icon name={mcLocked ? 'lock' : 'unlock'} size={13} />
							{mcLocked ? inst?.mcVersion : 'Desbloqueado'}
						</button>
						{#if !mcLocked}
							<div class="catlist">
								<button class="cat" class:active={mcFilter === ''} onclick={() => setMc('')}>
									Todas las versiones
								</button>
								{#each gameVersions as v}
									<button class="cat" class:active={mcFilter === v} onclick={() => setMc(v)}>{v}</button>
								{/each}
							</div>
						{/if}
					</div>
				{/if}
			</section>

			{#if type === 'mods'}
				<section class="fsec">
					<button class="fhead" onclick={() => toggleSec('loader')}>
						<span>Loader</span><span class="chev"><Icon name={open.loader ? 'chevron-down' : 'chevron-right'} size={14} /></span>
					</button>
					{#if open.loader}
						<div class="fbody">
							<button class="lockbtn cap" class:locked={loaderLocked} onclick={toggleLoaderLock}>
								<Icon name={loaderLocked ? 'lock' : 'unlock'} size={13} />
								{loaderLocked ? inst?.loader : 'Desbloqueado'}
							</button>
							{#if !loaderLocked}
								<div class="catlist">
									<button class="cat" class:active={loaderFilter === ''} onclick={() => setLoader('')}>
										Todos
									</button>
									{#each LOADERS as l}
										<button class="cat cap" class:active={loaderFilter === l} onclick={() => setLoader(l)}>
											{l}
										</button>
									{/each}
								</div>
							{/if}
						</div>
					{/if}
				</section>

				{#if source === 'modrinth'}
					<section class="fsec">
						<button class="fhead" onclick={() => toggleSec('env')}>
							<span>Entorno</span><span class="chev"><Icon name={open.env ? 'chevron-down' : 'chevron-right'} size={14} /></span>
						</button>
						{#if open.env}
							<div class="fbody catlist">
								<button class="cat" class:active={selectedEnv.includes('client')} onclick={() => toggleEnv('client')}>
<Icon name="monitor" size={14} />Cliente
								</button>
								<button class="cat" class:active={selectedEnv.includes('server')} onclick={() => toggleEnv('server')}>
<Icon name="server" size={14} />Servidor
								</button>
							</div>
						{/if}
					</section>
				{/if}
			{/if}

			<section class="fsec">
				<button class="fhead" onclick={() => toggleSec('category')}>
					<span>Categoría</span><span class="chev"><Icon name={open.category ? 'chevron-down' : 'chevron-right'} size={14} /></span>
				</button>
				{#if open.category}
					<div class="fbody catlist">
						{#each rootCats as c (c.id)}
							{#if hasChildren(c.id)}
								<button class="cat parent" onclick={() => toggleCatGroup(c.id)}>
									<Icon name={openCats[c.id] ? 'chevron-down' : 'chevron-right'} size={13} />{c.name}
								</button>
								{#if openCats[c.id]}
									<div class="children">
										<button class="cat child" class:active={selectedCats.includes(c.id)} onclick={() => toggleCat(c.id)}>
											Todo · {c.name}
										</button>
										{#each childrenOf(c.id) as ch (ch.id)}
											<button class="cat child" class:active={selectedCats.includes(ch.id)} onclick={() => toggleCat(ch.id)}>
												{ch.name}
											</button>
										{/each}
									</div>
								{/if}
							{:else}
								<button class="cat" class:active={selectedCats.includes(c.id)} onclick={() => toggleCat(c.id)}>
									{c.name}
								</button>
							{/if}
						{/each}
					</div>
				{/if}
			</section>
		</aside>
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 14px;
		margin-bottom: 20px;
	}
	.back {
		font-size: 18px;
		padding: 6px 12px;
	}
	.title h1 {
		font-size: 20px;
	}
	.sub {
		font-size: 12px;
		margin-top: 2px;
	}
	.cap {
		text-transform: capitalize;
	}
	.dim {
		color: var(--text-dim);
	}
	.small {
		font-size: 12px;
	}
	.pad {
		padding: 24px 0;
	}
	.tabs {
		display: flex;
		align-items: center;
		gap: 6px;
		margin-bottom: 14px;
		border-bottom: 1px solid var(--border);
		padding-bottom: 12px;
	}
	.tabs > button {
		background: transparent;
		color: var(--text-dim);
		font-weight: 600;
		padding: 7px 14px;
		border-radius: 8px;
	}
	.tabs > button.active {
		background: var(--bg-card);
		color: var(--accent);
	}
	.srcsel {
		margin-left: auto;
		display: flex;
		gap: 4px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: 9px;
		padding: 3px;
	}
	.src {
		background: transparent;
		color: var(--text-dim);
		padding: 5px 12px;
		font-size: 12px;
		border-radius: 6px;
	}
	.src.active {
		background: var(--accent);
		color: #1c1500;
	}
	.src:disabled {
		opacity: 0.35;
	}
	.catalog {
		display: grid;
		grid-template-columns: 1fr 240px;
		gap: 20px;
		align-items: start;
	}
	.searchrow {
		display: flex;
		gap: 10px;
		margin-bottom: 14px;
	}
	.search {
		flex: 1;
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: var(--radius);
		padding: 11px 14px;
		font-family: inherit;
		font-size: 14px;
	}
	select {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 7px 9px;
		font-family: inherit;
		font-size: 13px;
	}
	.sidebar {
		display: flex;
		flex-direction: column;
		gap: 12px;
		position: sticky;
		top: 0;
	}
	.fsec {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		overflow: hidden;
	}
	.fhead {
		width: 100%;
		display: flex;
		align-items: center;
		justify-content: space-between;
		background: transparent;
		border: none;
		color: var(--text);
		font-weight: 600;
		font-size: 13px;
		padding: 12px 14px;
		cursor: pointer;
	}
	.chev {
		color: var(--text-dim);
		font-size: 11px;
	}
	.fbody {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 0 14px 14px;
	}
	.lockbtn {
		width: 100%;
		text-align: left;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 7px 10px;
		font-size: 12px;
		cursor: pointer;
	}
	.lockbtn.locked {
		color: var(--accent);
		border-color: var(--accent-dim);
	}
	.catlist {
		max-height: 320px;
		overflow-y: auto;
	}
	.cat {
		text-align: left;
		background: transparent;
		border: none;
		color: var(--text-dim);
		font-size: 13px;
		padding: 5px 8px;
		border-radius: 6px;
		cursor: pointer;
	}
	.cat:hover {
		background: var(--bg-elev);
		color: var(--text);
	}
	.cat.active {
		background: var(--bg-elev);
		color: var(--accent);
		font-weight: 600;
	}
	.cat.parent {
		display: flex;
		align-items: center;
		gap: 4px;
		font-weight: 600;
		color: var(--text);
	}
	.children {
		display: flex;
		flex-direction: column;
		margin: 2px 0 4px 12px;
		padding-left: 6px;
		border-left: 1px solid var(--border);
	}
	.cat.child {
		font-size: 12px;
	}
	.results {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.rcard {
		display: flex;
		gap: 14px;
		align-items: center;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 14px;
	}
	.rclick {
		background: none;
		padding: 0;
		border-radius: 12px;
	}
	.ricon {
		width: 66px;
		height: 66px;
		border-radius: 12px;
		object-fit: cover;
		background: var(--bg-elev);
	}
	.ricon.ph {
		display: grid;
		place-items: center;
		font-size: 28px;
	}
	.rinfo {
		flex: 1;
		min-width: 0;
	}
	.rtop {
		display: flex;
		align-items: baseline;
		gap: 8px;
	}
	.link {
		background: none;
		border: none;
		padding: 0;
		color: var(--text);
		font: inherit;
		cursor: pointer;
	}
	.rname {
		font-size: 16px;
		font-weight: 700;
	}
	.rname:hover {
		color: var(--accent);
	}
	.rdesc {
		margin: 3px 0 7px;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.rmeta {
		display: flex;
		gap: 14px;
	}
	.stat {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.pstats .stat {
		gap: 5px;
	}
	.install {
		white-space: nowrap;
		align-self: center;
	}
	.install:disabled {
		background: var(--bg-elev);
		color: var(--text-dim);
		border: 1px solid var(--border);
	}
	.pager {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 14px;
		padding: 22px 0;
	}
	.xs {
		padding: 5px 11px;
		font-size: 13px;
	}
	/* Página de proyecto */
	.proj {
		padding-bottom: 30px;
	}
	.back2 {
		margin-bottom: 16px;
	}
	.phead {
		display: flex;
		gap: 16px;
		align-items: flex-start;
	}
	.picon {
		width: 80px;
		height: 80px;
		border-radius: 14px;
		object-fit: cover;
		background: var(--bg-elev);
	}
	.picon.ph {
		display: grid;
		place-items: center;
		font-size: 34px;
	}
	.pmeta {
		flex: 1;
		min-width: 0;
	}
	.pmeta h2 {
		font-size: 24px;
	}
	.pstats {
		display: flex;
		gap: 14px;
		margin-top: 8px;
		flex-wrap: wrap;
	}
	.links {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
		margin: 18px 0;
	}
	.gallery {
		display: flex;
		gap: 10px;
		overflow-x: auto;
		padding-bottom: 8px;
		margin-bottom: 18px;
	}
	.gallery img {
		height: 150px;
		border-radius: 10px;
		border: 1px solid var(--border);
	}
	.pgrid {
		display: grid;
		grid-template-columns: 1fr 300px;
		gap: 24px;
		align-items: start;
	}
	.body {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 20px 24px;
		overflow-x: auto;
		line-height: 1.6;
	}
	.body :global(img) {
		max-width: 100%;
		border-radius: 8px;
	}
	.body :global(h1),
	.body :global(h2),
	.body :global(h3) {
		margin: 18px 0 10px;
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
	.vside {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 16px;
		position: sticky;
		top: 0;
	}
	.vside h3 {
		font-size: 14px;
		margin-bottom: 12px;
	}
	.vrow {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		padding: 8px 0;
		border-top: 1px solid var(--border);
	}
	.vinfo {
		min-width: 0;
	}
	.nm {
		font-weight: 600;
		font-size: 13px;
	}
</style>
