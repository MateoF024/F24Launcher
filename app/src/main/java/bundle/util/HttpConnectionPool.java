package bundle.util;

import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpConnectionPool {
    private static HttpConnectionPool instance;
    private final OkHttpClient client;

    private HttpConnectionPool() {
        ConnectionPool pool = new ConnectionPool(10, 5, TimeUnit.MINUTES);

        this.client = new OkHttpClient.Builder()
                .connectionPool(pool)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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
                .addHeader("User-Agent", "MateoF24-ModpackInstaller/3.0")
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
                .addHeader("User-Agent", "MateoF24-ModpackInstaller/3.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Error " + response.code() + " for URL: " + url);
            }

            ResponseBody body = response.body();
            return body != null ? body.bytes() : new byte[0];
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
                    if (response.code() >= 400 && response.code() < 500) {
                        return response;
                    }
                    if (response != null) {
                        response.close();
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (i == MAX_RETRIES - 1) {
                        throw e;
                    }
                    try {
                        Thread.sleep(1000 * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }

            if (lastException != null) {
                throw lastException;
            }

            return response;
        }
    }
}