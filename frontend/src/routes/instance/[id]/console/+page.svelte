<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import {
		ipcReady,
		getInstance,
		getInstanceLog,
		listInstanceLogs,
		openInstanceLogs,
		type Instance,
		type InstanceLogFile
	} from '$lib/ipc';
	import { ui } from '$lib/store.svelte';
	import { processLog, type LogLevel } from '$lib/log';
	import Icon from '$lib/Icon.svelte';

	const LATEST = 'logs/latest.log';

	const id = $derived($page.params.id ?? '');
	let inst = $state<Instance | null>(null);
	let logFiles = $state<InstanceLogFile[]>([]);
	let selectedFile = $state(LATEST);
	let fileLines = $state<string[]>([]);
	let loadingFile = $state(false);

	let box = $state<HTMLDivElement>();
	let stick = $state(true);
	let filter = $state<LogLevel | 'all'>('all');

	const running = $derived(ui.status[id] === 'running' || ui.status[id] === 'launching');
	const liveLines = $derived(ui.logs[id] ?? []);

	// Hay log en vivo cuando la instancia corre y se mira su latest.log; si no, disco.
	const isLive = $derived(running && selectedFile === LATEST);
	const rawLines = $derived(isLive ? liveLines : fileLines);
	const lines = $derived(processLog(rawLines));
	const shown = $derived(filter === 'all' ? lines : lines.filter((l) => l.level === filter));

	const counts = $derived({
		warn: lines.filter((l) => l.level === 'warn').length,
		error: lines.filter((l) => l.level === 'error' || l.level === 'fatal').length
	});

	// Crash-report indicado por la navegación automática tras un crash (?crash=).
	const crashParam = $derived($page.url.searchParams.get('crash') ?? '');

	async function loadLogList() {
		try {
			logFiles = await listInstanceLogs(id);
		} catch {
			logFiles = [];
		}
	}

	async function loadFile() {
		if (isLive) {
			fileLines = [];
			return;
		}
		loadingFile = true;
		try {
			fileLines = await getInstanceLog(id, selectedFile);
		} catch {
			fileLines = [];
		} finally {
			loadingFile = false;
		}
	}

	function selectFile(path: string) {
		selectedFile = path;
		stick = true;
		loadFile();
	}

	function optLabel(f: InstanceLogFile): string {
		if (f.path === LATEST) return running ? 'latest.log (en vivo)' : 'latest.log';
		return f.crash ? `${f.name} · crash` : f.name;
	}

	async function openLogs() {
		try {
			await openInstanceLogs(id);
		} catch {
			/* ignore */
		}
	}

	onMount(async () => {
		await ipcReady;
		try {
			inst = await getInstance(id);
		} catch {
			/* ignore */
		}
		await loadLogList();
		if (crashParam) {
			selectedFile = crashParam; // entrada por crash: abrir ese crash-report
			lastCrash = crashParam;
		}
		await loadFile();
	});

	// Reacciona a un crash que llega estando ya en la consola (cambia ?crash=).
	let lastCrash = '';
	$effect(() => {
		if (crashParam && crashParam !== lastCrash) {
			lastCrash = crashParam;
			loadLogList();
			selectFile(crashParam);
		}
	});

	// Al dejar de ejecutarse, refresca la lista y el latest.log recién escrito.
	let wasRunning = false;
	$effect(() => {
		if (wasRunning && !running) {
			loadLogList();
			loadFile();
		}
		wasRunning = running;
	});

	// Auto-scroll si el usuario está al final.
	$effect(() => {
		shown.length;
		if (box && stick) box.scrollTop = box.scrollHeight;
	});

	function onScroll() {
		if (!box) return;
		stick = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
	}
</script>

<header>
	<button class="ghost back" title="Volver" onclick={() => goto(`/instance/${id}`)}>
		<Icon name="arrow-left" size={16} />
	</button>
	<div class="title">
		<h1>Consola · {inst?.name ?? id}</h1>
		<div class="dim sub">
			{#if isLive}
				<span class="livedot"></span>En ejecución · registro en vivo
			{:else}
				{selectedFile.split('/').pop()}
			{/if}
		</div>
	</div>
	<div class="spacer"></div>
	{#if logFiles.length > 0}
		<select class="logsel" value={selectedFile} onchange={(e) => selectFile(e.currentTarget.value)}>
			{#each logFiles as f (f.path)}
				<option value={f.path}>{optLabel(f)}</option>
			{/each}
		</select>
	{/if}
	<button class="ghost" title="Abrir carpeta de logs" onclick={openLogs}><Icon name="folder" size={15} /></button>
	<div class="filters">
		{#each ['all', 'info', 'warn', 'error', 'fatal'] as f}
			<button class="chip {f}" class:on={filter === f} onclick={() => (filter = f as LogLevel | 'all')}>
				{f.toUpperCase()}
			</button>
		{/each}
	</div>
	<button class="ghost" title="Recargar" onclick={() => { loadLogList(); loadFile(); }}>
		<Icon name="refresh" size={15} />
	</button>
</header>

<div class="summary dim">
	{lines.length} líneas · <span class="c-warn">{counts.warn} warn</span> ·
	<span class="c-error">{counts.error} error</span>
</div>

<div class="console" bind:this={box} onscroll={onScroll}>
	{#if loadingFile && !isLive}
		<div class="empty dim">Cargando log…</div>
	{:else if shown.length === 0}
		<div class="empty dim">
			{isLive
				? 'Esperando la salida del juego…'
				: 'Este registro está vacío o todavía no se generó.'}
		</div>
	{:else}
		{#each shown as l}
			<div class="line {l.level}">{l.text}</div>
		{/each}
	{/if}
</div>

<style>
	header {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 12px;
	}
	.back {
		font-size: 18px;
		padding: 6px 12px;
	}
	.title h1 {
		font-size: 19px;
	}
	.sub {
		font-size: 12px;
		margin-top: 2px;
		display: flex;
		align-items: center;
		gap: 7px;
	}
	.spacer {
		flex: 1;
	}
	.livedot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: #3fa34d;
		box-shadow: 0 0 6px #3fa34d;
		animation: pulse 1.4s ease-in-out infinite;
	}
	@keyframes pulse {
		0%,
		100% {
			opacity: 1;
		}
		50% {
			opacity: 0.35;
		}
	}
	.dim {
		color: var(--text-dim);
	}
	.filters {
		display: flex;
		gap: 4px;
	}
	.logsel {
		background: var(--bg-card);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 6px 9px;
		font-family: inherit;
		font-size: 12px;
		max-width: 220px;
	}
	.chip {
		background: var(--bg-card);
		color: var(--text-dim);
		border: 1px solid var(--border);
		font-size: 11px;
		padding: 4px 9px;
		font-weight: 600;
		letter-spacing: 0.4px;
	}
	.chip:hover {
		background: var(--bg-elev);
	}
	.chip.on {
		color: var(--text);
		border-color: var(--accent);
		box-shadow: inset 0 -2px 0 var(--accent);
	}
	.summary {
		font-size: 12px;
		margin-bottom: 10px;
	}
	.c-warn {
		color: #e8c33b;
	}
	.c-error {
		color: #ff6b6b;
	}
	.console {
		background: #0d0e11;
		border: 1px solid var(--border);
		border-radius: var(--radius);
		height: calc(100vh - 190px);
		overflow: auto;
		padding: 10px 14px;
		font-family: 'Cascadia Code', 'Consolas', monospace;
		font-size: 12px;
		line-height: 1.5;
	}
	.empty {
		padding: 24px;
		text-align: center;
	}
	.line {
		white-space: pre-wrap;
		word-break: break-all;
		color: #c2c7d0;
		padding: 1px 0;
	}
	.line.warn {
		color: #e8c33b;
	}
	.line.error {
		color: #ff7b7b;
	}
	.line.fatal {
		color: #fff;
		background: rgba(255, 60, 60, 0.18);
		font-weight: 600;
	}
	.line.f24 {
		color: var(--accent);
	}
</style>
