package f24launcher.util;

import f24launcher.AppVersion;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectionPool.class);

    // User-Agent identificable (recomendado por Modrinth/CurseForge; reduce rate-limit).
    private static final String USER_AGENT =
            "F24Launcher/" + AppVersion.VERSION + " (+https://github.com/MateoF024/F24Launcher)";

    private static HttpConnectionPool instance;
    private volatile OkHttpClient client;

    private HttpConnectionPool() {
        ConnectionPool pool = new ConnectionPool(20, 5, TimeUnit.MINUTES);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(12);
        dispatcher.setMaxRequestsPerHost(6);

        this.client = new OkHttpClient.Builder()
                .connectionPool(pool)
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new RetryInterceptor())
                .build();
    }

    public static synchronized HttpConnectionPool getInstance() {
        if (instance == null) {
            instance = new HttpConnectionPool();
        }
        return instance;
    }

    /**
     * Configura, al arrancar, el dispatcher (alineado con el límite de descargas) y
     * la caché de disco para metadatos. OkHttp negocia HTTP/2 automáticamente. Las
     * descargas de archivos llevan {@code Cache-Control: no-store} y no se cachean.
     */
    public synchronized void configure(int maxDownloads, File cacheDir, long cacheBytes) {
        int perHost = Math.max(6, maxDownloads);
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(perHost);
        dispatcher.setMaxRequests(perHost * 2 + 8);

        // Libera el executor del dispatcher anterior (el del constructor) para no dejarlo
        // colgando con hilos vivos al reconfigurar al arrancar.
        java.util.concurrent.ExecutorService oldExec = client.dispatcher().executorService();

        OkHttpClient.Builder b = client.newBuilder().dispatcher(dispatcher);
        if (cacheDir != null && cacheBytes > 0) {
            try {
                b.cache(new Cache(cacheDir, cacheBytes));
            } catch (Exception e) {
                log.warn("No se pudo habilitar la caché HTTP: {}", e.getMessage());
            }
        }
        this.client = b.build();
        oldExec.shutdown();
        log.info("HTTP: {} req/host · caché de disco {} MB", perHost, cacheBytes / (1024 * 1024));
    }

    public String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json, */*")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    /** POST de un cuerpo JSON; devuelve la respuesta como texto. */
    public String post(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .addHeader("Cache-Control", "no-store")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for POST " + url);
            }
            ResponseBody rb = response.body();
            return rb != null ? rb.string() : "";
        }
    }

    public byte[] getBytes(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Cache-Control", "no-store") // no cachear archivos grandes
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
            }
            ResponseBody body = response.body();
            return body != null ? body.bytes() : new byte[0];
        }
    }

    // Devuelve Response sin cerrar; el caller la maneja con try-with-resources.
    public Response getRaw(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "*/*")
                .addHeader("Cache-Control", "no-store")
                .build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
        }
        return response;
    }

    public OkHttpClient getClient() {
        return client;
    }

    public void evictConnections() {
        client.connectionPool().evictAll();
    }

    /** Vacía la caché de disco HTTP (lo invoca "Purgar caché"). */
    public void evictCache() {
        try {
            Cache c = client.cache();
            if (c != null) c.evictAll();
        } catch (IOException ignored) {}
    }

    /** Tamaño actual de la caché de disco HTTP en bytes (0 si no hay caché). */
    public long cacheSize() {
        try {
            Cache c = client.cache();
            return c != null ? c.size() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private static class RetryInterceptor implements Interceptor {
        private static final int MAX_RETRIES = 3;

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;

            for (int i = 0; i < MAX_RETRIES; i++) {
                try {
                    response = chain.proceed(request);
                    if (response.isSuccessful()) {
                        return response;
                    }
                    // No reintentar errores de cliente (4xx).
                    if (response.code() >= 400 && response.code() < 500) {
                        return response;
                    }
                    // Cerrar antes de reintentar, evita fuga de recursos.
                    response.close();
                    response = null;
                } catch (IOException e) {
                    lastException = e;
                    if (response != null) {
                        response.close();
                        response = null;
                    }
                    if (i == MAX_RETRIES - 1) throw e;
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }

            if (lastException != null) throw lastException;
            if (response != null) return response;
            throw new IOException("Failed after " + MAX_RETRIES + " retries: " + request.url());
        }
    }
}
