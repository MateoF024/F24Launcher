<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import {
		ipcReady,
		beginMicrosoftLogin,
		openExternal,
		getMicrosoftConfig,
		addOfflineAccount
	} from '$lib/ipc';
	import { ui, refreshAccounts, resetAuth } from '$lib/store.svelte';
	import Icon from '$lib/Icon.svelte';
	import { fade, scale } from 'svelte/transition';

	let offlineName = $state('');
	let msError = $state('');
	let msConfigured = $state(true);
	let starting = $state(false);
	let copied = $state(false);

	onMount(async () => {
		await ipcReady;
		resetAuth();
		try {
			msConfigured = (await getMicrosoftConfig()).configured;
		} catch {
			/* backend no listo */
		}
		await refreshAccounts();
	});

	async function loginMicrosoft() {
		msError = '';
		starting = true;
		try {
			const p = await beginMicrosoftLogin();
			ui.auth = {
				stage: 'pending',
				userCode: p.userCode,
				verificationUri: p.verificationUri,
				message: ''
			};
			await openExternal(p.verificationUri); // abre microsoft.com/link en el navegador
		} catch (e) {
			resetAuth();
			msError = e instanceof Error ? e.message : String(e);
		} finally {
			starting = false;
		}
	}

	function copyCode() {
		navigator.clipboard?.writeText(ui.auth.userCode);
		copied = true;
		setTimeout(() => (copied = false), 1500);
	}

	const modalOpen = $derived(ui.auth.stage === 'pending' || ui.auth.stage === 'error');

	async function addOffline() {
		const n = offlineName.trim();
		if (!n) return;
		await addOfflineAccount(n);
		offlineName = '';
		await refreshAccounts();
		goto('/');
	}

	// Al completar el login con éxito, volvemos a Instancias.
	$effect(() => {
		if (ui.auth.stage === 'success') {
			resetAuth();
			goto('/');
		}
	});
</script>

<header>
	<button class="ghost back" onclick={() => goto('/')}><Icon name="arrow-left" size={15} />Instancias</button>
	<h1>Iniciar sesión</h1>
</header>

<div class="cards">
	<!-- Microsoft -->
	<section class="card ms">
		<div class="logo">
			<span class="sq r"></span><span class="sq g"></span>
			<span class="sq b"></span><span class="sq y"></span>
		</div>
		<h2>Cuenta Microsoft</h2>
		<p class="dim">Iniciá sesión con tu cuenta de Minecraft: Java Edition para jugar en línea.</p>
		<button onclick={loginMicrosoft} disabled={starting || ui.auth.stage === 'pending' || !msConfigured}>
			{#if starting}
				Generando código…
			{:else if ui.auth.stage === 'pending'}
				Esperando confirmación…
			{:else}
				Iniciar sesión con Microsoft
			{/if}
		</button>
		{#if !msConfigured}
			<p class="warn">
				El inicio de sesión con Microsoft no está disponible en esta instalación. Por ahora podés
				jugar en modo sin conexión.
			</p>
		{/if}
		{#if msError}
			<p class="err">{msError}</p>
		{/if}
	</section>

	<!-- Offline -->
	<section class="card">
		<div class="logo offline"><Icon name="user" size={26} /></div>
		<h2>Jugar sin cuenta</h2>
		<p class="dim">Modo sin conexión: elegí un nombre de jugador. Necesitás tener el juego para usarlo.</p>
		<div class="row">
			<input
				bind:value={offlineName}
				placeholder="Nombre de jugador"
				spellcheck="false"
				onkeydown={(e) => e.key === 'Enter' && addOffline()}
			/>
			<button class="ghost" onclick={addOffline} disabled={!offlineName.trim()}>Entrar</button>
		</div>
	</section>
</div>

<!-- Modal device-code -->
{#if modalOpen}
	<div class="overlay" role="presentation" onclick={resetAuth} transition:fade={{ duration: 130 }}>
		<div
			class="modal"
			role="dialog"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			transition:scale={{ start: 0.96, duration: 170 }}
		>
			{#if ui.auth.stage === 'error'}
				<h2>No se pudo iniciar sesión</h2>
				<p class="err">{ui.auth.message}</p>
				<div class="modal-actions">
					<button onclick={resetAuth}>Cerrar</button>
				</div>
			{:else}
				<h2>Iniciá sesión con Microsoft</h2>
				<p class="dim">
					Abrimos tu navegador en la página de Microsoft. Ingresá este código para confirmar el acceso:
				</p>
				<div class="code" onclick={copyCode} role="button" tabindex="0">
					{ui.auth.userCode}
					<span class="copy">{copied ? '¡Copiado!' : 'Hacé clic para copiar'}</span>
				</div>
				<div class="modal-actions">
					<button class="btn" onclick={() => openExternal(ui.auth.verificationUri)}>
						Abrir de nuevo
					</button>
					<button class="ghost" onclick={resetAuth}>Cancelar</button>
				</div>
				<p class="dim small">Cuando lo confirmes en el navegador, iniciaremos sesión automáticamente…</p>
			{/if}
		</div>
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 14px;
		margin-bottom: 24px;
	}
	.back {
		font-size: 13px;
		padding: 6px 12px;
	}
	.dim {
		color: var(--text-dim);
	}
	.cards {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
		gap: 16px;
		margin-bottom: 28px;
	}
	.card {
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 22px;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.card h2 {
		font-size: 17px;
	}
	.card.ms {
		border-color: var(--accent-dim);
	}
	.card button:not(.ghost) {
		margin-top: 6px;
	}
	.logo {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 3px;
		width: 34px;
		height: 34px;
		margin-bottom: 4px;
	}
	.sq {
		border-radius: 2px;
	}
	.sq.r {
		background: #f25022;
	}
	.sq.g {
		background: #7fba00;
	}
	.sq.b {
		background: #00a4ef;
	}
	.sq.y {
		background: #ffb900;
	}
	.logo.offline {
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 26px;
	}
	.row {
		display: flex;
		gap: 8px;
		margin-top: 6px;
	}
	input {
		flex: 1;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 9px 11px;
		font-family: inherit;
		font-size: 13px;
	}
	.err {
		color: #ff7b7b;
		font-size: 13px;
		margin: 4px 0 0;
	}
	.warn {
		color: #e8c33b;
		font-size: 12px;
		margin: 4px 0 0;
		line-height: 1.5;
	}
	.small {
		font-size: 12px;
		margin: 4px 0 0;
	}
	/* Modal device-code */
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
		padding: 26px;
		width: 390px;
		display: flex;
		flex-direction: column;
		gap: 14px;
		text-align: center;
	}
	.code {
		font-family: 'Cascadia Code', 'Consolas', monospace;
		font-size: 30px;
		font-weight: 700;
		letter-spacing: 4px;
		background: var(--bg);
		border: 1px dashed var(--accent);
		border-radius: 10px;
		padding: 16px;
		cursor: pointer;
		color: var(--accent);
	}
	.code .copy {
		display: block;
		font-size: 11px;
		letter-spacing: 0;
		color: var(--text-dim);
		font-weight: 400;
		margin-top: 6px;
	}
	.modal-actions {
		display: flex;
		justify-content: center;
		gap: 10px;
	}
	.btn {
		background: var(--accent);
		color: #1c1500;
		font-weight: 600;
	}
	.btn:hover {
		background: var(--accent-dim);
	}
</style>
