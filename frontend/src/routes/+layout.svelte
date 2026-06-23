<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import {
		ui,
		initStore,
		logout,
		refreshInstances,
		setProgress,
		importDroppedFiles,
		hasContentDrop,
		setLaunchModeActive
	} from '$lib/store.svelte';
	import { importModpack, launchInstance, ipcReady } from '$lib/ipc';
	import Icon from '$lib/Icon.svelte';
	import { fade } from 'svelte/transition';

	let { children } = $props();

	let dragOver = $state(false);
	let dropText = $state('Suelta el modpack para importarlo');
	let importMsg = $state('');

	let launchModeId = $state('');
	let launchArmed = false;

	onMount(() => {
		let unlistenDrop: (() => void) | undefined;
		let unlistenOpen: (() => void) | undefined;
		let unlistenLaunch: (() => void) | undefined;
		(async () => {
			await initStore();

			// Modo "acceso directo": la ventana viene oculta; lanzamos y salimos al cerrar el juego.
			try {
				const { invoke } = await import('@tauri-apps/api/core');
				const target = await invoke<string | null>('get_launch_target');
				if (target) {
					setLaunchModeActive(true);
					startLaunchMode(target);
				}
			} catch {
				/* fuera de Tauri */
			}

			try {
				const { getCurrentWebview } = await import('@tauri-apps/api/webview');
				unlistenDrop = await getCurrentWebview().onDragDropEvent(async (e) => {
					const p = e.payload;
					if (p.type === 'enter' || p.type === 'over') {
						dragOver = true;
						dropText = hasContentDrop()
							? 'Suelta los archivos para añadirlos a la instancia'
							: 'Suelta el modpack para importarlo';
					} else if (p.type === 'leave') {
						dragOver = false;
					} else if (p.type === 'drop') {
						dragOver = false;
						const paths = p.paths ?? [];
						if (await importDroppedFiles(paths)) return; // mods → instancia activa
						const pack = paths.find((x) => /\.(f24pack|mrpack|zip)$/i.test(x));
						if (pack) importPack(pack);
					}
				});
			} catch {
				/* fuera de Tauri */
			}
			try {
				const { listen } = await import('@tauri-apps/api/event');
				unlistenOpen = await listen<string>('open-f24pack', (e) => {
					if (e.payload) importPack(e.payload);
				});
				unlistenLaunch = await listen<string>('launch-instance', (e) => {
					if (e.payload) launchForwarded(e.payload);
				});
				const { invoke } = await import('@tauri-apps/api/core');
				const pending = await invoke<string | null>('take_pending_open');
				if (pending) importPack(pending);
			} catch {
				/* fuera de Tauri */
			}
		})();
		return () => {
			unlistenDrop?.();
			unlistenOpen?.();
			unlistenLaunch?.();
		};
	});

	// Modo lanzar: al terminar el juego (sin instancias en ejecución) cierra la app;
	// si falla la cuenta/lanzamiento, abre la ventana en /accounts con un mensaje.
	$effect(() => {
		if (!launchModeId) return;
		const st = ui.status[launchModeId];
		if (st === 'error') {
			launchFallback(ui.errors[launchModeId] || 'No se pudo lanzar la instancia.');
			return;
		}
		const anyRunning = Object.values(ui.status).some((s) => s === 'running');
		if (anyRunning) launchArmed = true;
		else if (launchArmed) exitApp();
	});

	/** Arranque en frío desde un acceso directo: lanza y deja la app para salir al terminar. */
	async function startLaunchMode(id: string) {
		launchModeId = id;
		await ipcReady;
		const active = ui.accounts.find((a) => a.active);
		if (!active) {
			launchFallback('Inicia sesión para jugar esta instancia.');
			return;
		}
		try {
			await launchInstance(id, active.username);
		} catch (e) {
			launchFallback('No se pudo lanzar: ' + (e as Error).message);
		}
	}

	/** Acceso directo con la app ya abierta: solo lanza (no cierra la app al terminar). */
	async function launchForwarded(id: string) {
		const active = ui.accounts.find((a) => a.active);
		if (!active) {
			goto('/accounts');
			return;
		}
		try {
			await launchInstance(id, active.username);
			goto('/');
		} catch {
			/* la tarjeta de la instancia mostrará el error */
		}
	}

	async function launchFallback(msg: string) {
		launchModeId = '';
		setLaunchModeActive(false);
		try {
			const { invoke } = await import('@tauri-apps/api/core');
			await invoke('show_window');
		} catch {
			/* fuera de Tauri */
		}
		importMsg = msg;
		setTimeout(() => (importMsg = ''), 6000);
		goto('/accounts');
	}

	async function exitApp() {
		try {
			const { invoke } = await import('@tauri-apps/api/core');
			await invoke('exit_app');
		} catch {
			/* fuera de Tauri */
		}
	}

	async function importPack(path: string) {
		importMsg = 'Importando modpack…';
		try {
			await ipcReady;
			const inst = await importModpack(path);
			setProgress(inst.id, { phase: 'Importando', done: 0, total: 1 });
			await refreshInstances();
			importMsg = '';
			goto('/');
		} catch (e) {
			importMsg = 'No se pudo importar: ' + (e as Error).message;
			setTimeout(() => (importMsg = ''), 4500);
		}
	}

	const path = $derived($page.url.pathname);

	let menuOpen = $state(false);
	const activeAccount = $derived(ui.accounts.find((a) => a.active));

	function headUrl(uuid: string, type: string, size: number) {
		return type === 'microsoft'
			? `https://mc-heads.net/avatar/${uuid}/${size}`
			: `https://mc-heads.net/avatar/MHF_Steve/${size}`;
	}

	function goProfile() {
		menuOpen = false;
		if (activeAccount) goto(`/accounts/${activeAccount.id}`);
	}

	async function doLogout() {
		menuOpen = false;
		await logout();
		goto('/accounts');
	}
</script>

<svelte:head>
	<link rel="icon" href="/favicon.png" />
	<title>F24Launcher</title>
</svelte:head>

<div class="app">
	<aside>
		<div class="brand">F24<span>Launcher</span></div>
		<nav>
			<a class:active={path === '/'} href="/">Instancias</a>
			<a class:active={path === '/modpacks'} href="/modpacks">Modpacks</a>
			<a class:active={path === '/settings'} href="/settings">Ajustes</a>
		</nav>
		<div class="foot">
			{#if activeAccount}
				<div class="acct-wrap">
					{#if menuOpen}
						<div class="backdrop" role="presentation" onclick={() => (menuOpen = false)}></div>
						<div class="acct-menu" transition:fade={{ duration: 120 }}>
							{#if activeAccount.type === 'microsoft'}
								<button onclick={goProfile}><Icon name="user" size={15} />Perfil</button>
							{/if}
							<button onclick={doLogout}><Icon name="logout" size={15} />Cerrar sesión</button>
						</div>
					{/if}
					<button class="acct" class:open={menuOpen} onclick={() => (menuOpen = !menuOpen)}>
						<img class="head" src={headUrl(activeAccount.uuid, activeAccount.type, 56)} alt="" />
						<span class="acct-info">
							<span class="acct-name">{activeAccount.username}</span>
							<span class="acct-type">
								{activeAccount.type === 'microsoft' ? 'Microsoft' : 'Offline'}
							</span>
						</span>
						<span class="chev"><Icon name="chevron-down" size={15} /></span>
					</button>
				</div>
			{:else}
				<button class="acct login" onclick={() => goto('/accounts')}>
					<Icon name="key" size={16} />Iniciar sesión
				</button>
			{/if}
		</div>
	</aside>
	<main>
		{#key path}
			<div class="route" in:fade={{ duration: 140 }}>{@render children()}</div>
		{/key}
	</main>
</div>

{#if dragOver}
	<div class="dropzone" transition:fade={{ duration: 120 }}>
		<div class="dropmsg"><Icon name="download" size={30} />{dropText}</div>
	</div>
{/if}
{#if importMsg}
	<div class="toast" transition:fade={{ duration: 120 }}>{importMsg}</div>
{/if}

<style>
	.app {
		display: grid;
		grid-template-columns: 220px 1fr;
		height: 100vh;
	}
	aside {
		background: var(--bg-elev);
		border-right: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		padding: 18px 14px;
		gap: 18px;
	}
	.brand {
		font-size: 20px;
		font-weight: 700;
		letter-spacing: 0.5px;
	}
	.brand span {
		color: var(--accent);
	}
	nav {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	nav a {
		display: flex;
		align-items: center;
		gap: 8px;
		color: var(--text-dim);
		text-decoration: none;
		padding: 8px 10px;
		border-radius: var(--radius);
		font-weight: 500;
		transition:
			background 0.15s ease,
			color 0.15s ease,
			box-shadow 0.15s ease;
	}
	nav a:hover {
		background: var(--bg-card);
		color: var(--text);
	}
	nav a.active {
		background: var(--bg-card);
		color: var(--text);
		box-shadow: inset 3px 0 0 var(--accent);
	}
	.foot {
		margin-top: auto;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.acct-wrap {
		position: relative;
	}
	.acct {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 10px;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 8px 10px;
		cursor: pointer;
		color: var(--text);
		transition:
			border-color 0.15s ease,
			background 0.15s ease;
	}
	.acct:hover {
		border-color: var(--accent-dim);
	}
	.acct.open {
		border-color: var(--accent);
	}
	.head {
		width: 28px;
		height: 28px;
		border-radius: 6px;
		image-rendering: pixelated;
		background: var(--bg-elev);
		flex-shrink: 0;
	}
	.acct-info {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		flex: 1;
		min-width: 0;
		line-height: 1.2;
	}
	.acct-name {
		font-weight: 600;
		font-size: 13px;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.acct-type {
		font-size: 11px;
		color: var(--text-dim);
	}
	.chev {
		display: flex;
		color: var(--text-dim);
		transition: transform 0.15s ease;
	}
	.acct.open .chev {
		transform: rotate(180deg);
	}
	.acct.login {
		justify-content: center;
		font-weight: 600;
	}
	.backdrop {
		position: fixed;
		inset: 0;
		z-index: 40;
	}
	.acct-menu {
		position: absolute;
		bottom: 100%;
		left: 0;
		right: 0;
		margin-bottom: 8px;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 6px;
		z-index: 41;
		display: flex;
		flex-direction: column;
		gap: 2px;
		box-shadow: 0 10px 26px rgba(0, 0, 0, 0.4);
	}
	.acct-menu button {
		display: flex;
		align-items: center;
		justify-content: flex-start;
		gap: 9px;
		width: 100%;
		background: transparent;
		border: none;
		color: var(--text);
		padding: 9px 10px;
		border-radius: 7px;
		font-size: 13px;
	}
	.acct-menu button:hover {
		background: var(--bg-card);
		color: var(--accent);
	}
	main {
		overflow: auto;
		padding: 28px 32px;
	}
	.dropzone {
		position: fixed;
		inset: 0;
		z-index: 100;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba(0, 0, 0, 0.55);
		backdrop-filter: blur(2px);
		pointer-events: none;
	}
	.dropmsg {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 22px 30px;
		border: 2px dashed var(--accent);
		border-radius: 16px;
		background: var(--bg-elev);
		color: var(--text);
		font-weight: 600;
		font-size: 15px;
	}
	.toast {
		position: fixed;
		bottom: 22px;
		left: 50%;
		transform: translateX(-50%);
		z-index: 101;
		background: var(--bg-elev);
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 11px 18px;
		font-size: 13px;
		color: var(--text);
		box-shadow: 0 10px 26px rgba(0, 0, 0, 0.4);
	}
</style>
