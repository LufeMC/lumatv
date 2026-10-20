package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Keep credentials out of errors and logs. */
final class MediaHttp {
    static boolean https(String value) {
        try {
            URL url = new URL(value);
            return value.length() <= 16384 && "https".equals(url.getProtocol())
                    && url.getHost().length() > 0 && url.getUserInfo() == null && url.getRef() == null;
        } catch (Exception ignored) { return false; }
    }

    static JSONObject json(String address, JSONObject body, int timeout) throws Exception {
        if (!https(address)) throw new Exception("invalid_url");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FreeMyTV/1.0");
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(data); }
            }
            return read(connection);
        } catch (Exception ignored) { throw new Exception("network"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    static JSONObject read(HttpURLConnection connection) throws Exception {
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 3 * 1024 * 1024) throw new Exception();
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline
                            || output.size() + count > 3 * 1024 * 1024) throw new Exception();
                    output.write(block, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { throw new Exception("network"); }
    }

    static JSONObject result(JSONObject response) throws Exception {
        JSONObject result = response.optJSONObject("result");
        if (result == null) throw new Exception("api_error");
        return result;
    }
}
