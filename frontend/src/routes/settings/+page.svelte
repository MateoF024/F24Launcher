<script lang="ts">
	import { onMount } from 'svelte';
	import {
		ipcReady,
		getSettings,
		updateSettings,
		purgeCache,
		openLogsFolder,
		exportDiagnostics,
		restartApp,
		type AppSettingsDto
	} from '$lib/ipc';
	import { setSettings, applyTheme } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fade } from 'svelte/transition';

	let s = $state<AppSettingsDto | null>(null);
	let saving = $state(false);
	let version = $state('0.0.5');
	let needsRestart = $state(false);
	let purging = $state(false);
	let purgeMsg = $state('');
	let exporting = $state(false);
	let diagMsg = $state('');

	const SIZES = [
		{ w: 1280, h: 720, label: '1280 × 720' },
		{ w: 1440, h: 810, label: '1440 × 810' },
		{ w: 1600, h: 900, label: '1600 × 900' },
		{ w: 1920, h: 1080, label: '1920 × 1080' }
	];

	const launcherSize = $derived(s ? `${s.launcherWidth}x${s.launcherHeight}` : '');

	onMount(async () => {
		await ipcReady;
		try {
			s = await getSettings();
		} catch {
			/* sin conexión con el servicio */
		}
		try {
			const { getVersion } = await import('@tauri-apps/api/app');
			version = await getVersion();
		} catch {
			/* fuera de Tauri: se mantiene el valor por defecto */
		}
	});

	async function commit(patch: Partial<AppSettingsDto>) {
		if (!s) return;
		s = { ...s, ...patch };
		if ('darkMode' in patch) applyTheme(s.darkMode);
		saving = true;
		try {
			const fresh = await updateSettings(patch);
			s = fresh;
			setSettings(fresh);
		} finally {
			saving = false;
		}
	}

	function setLauncherSize(value: string) {
		const [w, h] = value.split('x').map(Number);
		commit({ launcherWidth: w, launcherHeight: h });
	}

	// Los límites de concurrencia se leen al arrancar el backend → requieren reinicio.
	async function commitConcurrency(patch: Partial<AppSettingsDto>) {
		await commit(patch);
		needsRestart = true;
	}

	async function doPurge() {
		purging = true;
		purgeMsg = '';
		try {
			const { freedBytes } = await purgeCache();
			purgeMsg =
				freedBytes > 0
					? `Caché purgada · ${(freedBytes / (1024 * 1024)).toFixed(1)} MB liberados`
					: 'Caché purgada';
		} catch {
			purgeMsg = 'No se pudo purgar la caché';
		} finally {
			purging = false;
		}
	}

	async function doOpenLogs() {
		try {
			await openLogsFolder();
		} catch {
			diagMsg = 'No se pudo abrir la carpeta de logs';
		}
	}

	async function doExport() {
		exporting = true;
		diagMsg = '';
		try {
			const { path } = await exportDiagnostics();
			diagMsg = `Diagnóstico exportado: ${path}`;
			await openLogsFolder();
		} catch {
			diagMsg = 'No se pudo exportar el diagnóstico';
		} finally {
			exporting = false;
		}
	}

	async function doRestart() {
		try {
			await restartApp();
		} catch {
			/* fuera de Tauri (navegador dev) */
		}
	}

	const effectiveInstancesPath = $derived(s ? s.instancesPath || s.defaultInstancesPath : '');

	async function pickInstancesFolder() {
		try {
			const { open } = await import('@tauri-apps/plugin-dialog');
			const dir = await open({ directory: true, multiple: false, title: 'Carpeta de instancias' });
			if (typeof dir === 'string') await commit({ instancesPath: dir });
		} catch {
			/* fuera de Tauri */
		}
	}

	function resetInstancesFolder() {
		commit({ instancesPath: '' });
	}
</script>

<header>
	<h1>Ajustes</h1>
	{#if saving}<span class="dim small" transition:fade={{ duration: 120 }}>Guardando…</span>{/if}
</header>

{#if needsRestart}
	<div class="banner" transition:fade={{ duration: 150 }}>
		<Icon name="refresh" size={16} />
		<span>Algunos cambios requieren reiniciar el launcher para aplicarse.</span>
		<button class="restart" onclick={doRestart}>Reiniciar ahora</button>
	</div>
{/if}

{#if !s}
	<p class="dim">Cargando ajustes…</p>
{:else}
	<section class="card">
		<h2>Apariencia</h2>
		<div class="theme">
			<button class="themebtn" class:active={s.darkMode} onclick={() => commit({ darkMode: true })}>
				<Icon name="moon" size={16} />Oscuro
			</button>
			<button class="themebtn" class:active={!s.darkMode} onclick={() => commit({ darkMode: false })}>
				<Icon name="sun" size={16} />Claro
			</button>
		</div>
	</section>

	<section class="card">
		<h2>Carpeta de instancias</h2>
		<p class="dim small">Dónde se guardan las carpetas de juego de las instancias (global, no por instancia).</p>
		<div class="pathrow">
			<input class="path" readonly value={effectiveInstancesPath} title={effectiveInstancesPath} />
			<button class="ghost" onclick={pickInstancesFolder}><Icon name="folder" size={15} />Cambiar</button>
			{#if s.instancesPath}
				<button class="ghost" onclick={resetInstancesFolder}>Restablecer</button>
			{/if}
		</div>
		<p class="dim small">Al cambiarla, las instancias existentes se mueven a la nueva ubicación.</p>
	</section>

	<section class="card">
		<h2>Ventana del launcher</h2>
		<label class="row">
			<span>Tamaño por defecto (16:9)</span>
			<select value={launcherSize} onchange={(e) => setLauncherSize(e.currentTarget.value)}>
				{#each SIZES as sz}
					<option value={`${sz.w}x${sz.h}`}>{sz.label}</option>
				{/each}
			</select>
		</label>
		<p class="dim small">Se aplica al iniciar el launcher.</p>
		<label class="chk">
			<input
				type="checkbox"
				checked={s.closeToBackground}
				onchange={(e) => commit({ closeToBackground: e.currentTarget.checked })}
			/>
			Al cerrar (X), minimizar a la bandeja en vez de salir
		</label>
		<label class="chk">
			<input
				type="checkbox"
				checked={s.minimizeOnLaunch}
				onchange={(e) => commit({ minimizeOnLaunch: e.currentTarget.checked })}
			/>
			Al lanzar una instancia, pasar a segundo plano y volver al cerrarla
		</label>
	</section>

	<section class="card">
		<h2>Nuevas instancias</h2>
		<p class="dim small">Valores por defecto al crear una instancia (cada una puede ajustarse aparte).</p>

		<label class="full">
			<span>Memoria máxima · <strong>{s.defaultMaxMemoryMb} MB</strong></span>
			<input
				type="range"
				min="1024"
				max="16384"
				step="512"
				value={s.defaultMaxMemoryMb}
				onchange={(e) => commit({ defaultMaxMemoryMb: Number(e.currentTarget.value) })}
			/>
		</label>

		<div class="grid">
			<label>
				<span>Memoria mínima (MB)</span>
				<input
					type="number"
					min="256"
					max="16384"
					step="256"
					value={s.defaultMinMemoryMb}
					onchange={(e) => commit({ defaultMinMemoryMb: Number(e.currentTarget.value) })}
				/>
			</label>
			<label>
				<span>Ancho ventana juego</span>
				<input
					type="number"
					min="640"
					step="1"
					value={s.defaultWindowWidth}
					onchange={(e) => commit({ defaultWindowWidth: Number(e.currentTarget.value) })}
				/>
			</label>
			<label>
				<span>Alto ventana juego</span>
				<input
					type="number"
					min="480"
					step="1"
					value={s.defaultWindowHeight}
					onchange={(e) => commit({ defaultWindowHeight: Number(e.currentTarget.value) })}
				/>
			</label>
		</div>

		<label class="full">
			<span>Argumentos JVM por defecto</span>
			<input
				placeholder="-XX:+UseG1GC …"
				value={s.defaultJvmArgs}
				onchange={(e) => commit({ defaultJvmArgs: e.currentTarget.value })}
			/>
		</label>
	</section>

	<section class="card">
		<h2>Versiones</h2>
		<label class="chk">
			<input
				type="checkbox"
				checked={s.showBetaVersions}
				onchange={(e) => commit({ showBetaVersions: e.currentTarget.checked })}
			/>
			Mostrar versiones beta y snapshots
		</label>
	</section>

	<section class="card">
		<h2>Descargas</h2>
		<p class="dim small">
			Cuántos archivos descarga y escribe el launcher a la vez. Baja estos valores si tu conexión o
			tu disco son lentos. Se aplican al reiniciar.
		</p>
		<label class="full">
			<span>Descargas en paralelo · <strong>{s.maxConcurrentDownloads}</strong></span>
			<input
				type="range"
				min="1"
				max="16"
				step="1"
				value={s.maxConcurrentDownloads}
				onchange={(e) => commitConcurrency({ maxConcurrentDownloads: Number(e.currentTarget.value) })}
			/>
		</label>
		<label class="full">
			<span>Escrituras en disco en paralelo · <strong>{s.maxConcurrentWrites}</strong></span>
			<input
				type="range"
				min="1"
				max="32"
				step="1"
				value={s.maxConcurrentWrites}
				onchange={(e) => commitConcurrency({ maxConcurrentWrites: Number(e.currentTarget.value) })}
			/>
		</label>
		<p class="dim small">
			El valor por defecto de escrituras se ajusta a los núcleos de tu procesador.
		</p>
	</section>

	<section class="card">
		<h2>Caché de la app</h2>
		<p class="dim small">
			El launcher guarda una caché de datos para acelerar la carga. Purgarla la fuerza a recargar
			(puede ir más lento un momento). No afecta a tus instancias ni a los archivos del juego.
		</p>
		<div class="cacherow">
			<button class="ghost" onclick={doPurge} disabled={purging}>
				<Icon name="trash" size={15} />{purging ? 'Purgando…' : 'Purgar caché'}
			</button>
			{#if purgeMsg}<span class="dim small" transition:fade={{ duration: 120 }}>{purgeMsg}</span>{/if}
		</div>
	</section>

	<section class="card">
		<h2>Diagnóstico</h2>
		<p class="dim small">
			El launcher guarda registros de cada sesión (los anteriores se comprimen en .zip). Si algo
			falla, exporta un diagnóstico y compártelo: incluye los logs recientes y los ajustes (sin
			datos de tu cuenta).
		</p>
		<div class="cacherow">
			<button class="ghost" onclick={doOpenLogs}>
				<Icon name="folder" size={15} />Abrir carpeta de logs
			</button>
			<button class="ghost" onclick={doExport} disabled={exporting}>
				<Icon name="download" size={15} />{exporting ? 'Exportando…' : 'Exportar diagnóstico'}
			</button>
			{#if diagMsg}<span class="dim small" transition:fade={{ duration: 120 }}>{diagMsg}</span>{/if}
		</div>
	</section>
{/if}

<footer class="about">
	<div class="brand">F24<span>Launcher</span></div>
	<div class="dim small">
		Versión {version} · Edición privada
	</div>
	<div class="dim small">Cliente para Minecraft: Java Edition · © {new Date().getFullYear()} MateoF24</div>
</footer>

<style>
	header {
		display: flex;
		align-items: baseline;
		gap: 12px;
		margin-bottom: 22px;
	}
	h1 {
		font-size: 22px;
	}
	.dim {
		color: var(--text-dim);
	}
	.small {
		font-size: 12px;
	}
	.card {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 20px 22px;
		margin-bottom: 16px;
		max-width: 620px;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.card h2 {
		font-size: 15px;
	}
	.theme {
		display: flex;
		gap: 10px;
	}
	.themebtn {
		flex: 1;
		background: var(--bg-elev);
		color: var(--text-dim);
		border: 1px solid var(--border);
		padding: 12px;
		font-weight: 600;
	}
	.themebtn.active {
		border-color: var(--accent);
		color: var(--accent);
	}
	label {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 13px;
		color: var(--text-dim);
	}
	label.row {
		flex-direction: row;
		align-items: center;
		justify-content: space-between;
	}
	label.chk {
		flex-direction: row;
		align-items: center;
		gap: 9px;
		color: var(--text);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 12px;
	}
	.banner {
		display: flex;
		align-items: center;
		gap: 10px;
		max-width: 620px;
		margin-bottom: 16px;
		padding: 12px 16px;
		background: var(--bg-elev);
		border: 1px solid var(--accent);
		border-radius: var(--radius);
		font-size: 13px;
	}
	.banner span {
		flex: 1;
	}
	.banner .restart {
		background: var(--accent);
		color: #fff;
		border: none;
		padding: 8px 14px;
		font-weight: 600;
		white-space: nowrap;
	}
	.cacherow {
		display: flex;
		gap: 12px;
		align-items: center;
	}
	.pathrow {
		display: flex;
		gap: 8px;
		align-items: center;
	}
	.path {
		flex: 1;
		min-width: 0;
		color: var(--text-dim);
	}
	.pathrow button {
		white-space: nowrap;
	}
	input[type='number'],
	input:not([type]),
	select {
		background: var(--bg-elev);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 8px 10px;
		font-family: inherit;
		font-size: 13px;
	}
	input[type='range'] {
		accent-color: var(--accent);
		padding: 0;
	}
	input[type='checkbox'] {
		accent-color: var(--accent);
		width: 16px;
		height: 16px;
	}
	.about {
		max-width: 620px;
		margin-top: 8px;
		padding: 16px 4px 28px;
		border-top: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.about .brand {
		font-size: 16px;
		font-weight: 700;
		letter-spacing: 0.4px;
	}
	.about .brand span {
		color: var(--accent);
	}
	.about .small {
		font-size: 12px;
	}
</style>
