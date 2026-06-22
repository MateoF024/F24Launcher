package f24launcher.util;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectionPool.class);

    private static HttpConnectionPool instance;
    private final OkHttpClient client;

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

    public String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "F24Launcher/1.0.0")
                .addHeader("Accept", "application/json, */*")
                .addHeader("Cache-Control", "no-cache")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    public byte[] getBytes(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "F24Launcher/1.0.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
            }
            ResponseBody body = response.body();
            return body != null ? body.bytes() : new byte[0];
        }
    }

    // Fase 3 #16 — Devuelve Response sin cerrar, el caller la maneja con try-with-resources
    public Response getRaw(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "F24Launcher/1.0.0")
                .addHeader("Accept", "*/*")
                .addHeader("Cache-Control", "no-cache")
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
                    // Fase 2 #10 — No reintentar errores de cliente
                    if (response.code() >= 400 && response.code() < 500) {
                        return response;
                    }
                    // Fase 2 #10 — Cerrar antes de reintentar, evita NPE
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