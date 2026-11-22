package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runs AmneziaWG inside a userspace gVisor network stack and exposes a loopback
 * SOCKS5 endpoint to Telegram's native ConnectionsManager. This backend never
 * creates an Android VpnService or a system TUN interface.
 */
public final class AmneziaWgManager {
    private static final Object LOCK = new Object();
    private static final String LISTEN_ADDRESS = "127.0.0.1";
    private static final int SOCKS_PORT = 1081;
    private static final int DIAGNOSTICS_PORT = 1082;
    private static final long START_TIMEOUT_MS = 30_000L;

    private static Process process;
    private static volatile String lastError;
    private static volatile File configFile;

    private AmneziaWgManager() {
    }

    public static boolean isRunning() {
        return isSocksReady();
    }

    public static boolean isSocksReady() {
        synchronized (LOCK) {
            if (process == null || !isProcessAlive(process)) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LISTEN_ADDRESS, SOCKS_PORT), 250);
                return true;
            } catch (Exception ignore) {
                return false;
            }
        }
    }

    public static int getLocalSocksPort() {
        return SOCKS_PORT;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void start(Context context, SharedConfig.ProxyInfo info) throws Exception {
        if (context == null || info == null || !info.isXrayWireguard()) {
            throw new IllegalArgumentException("A WireGuard proxy profile is required");
        }

        synchronized (LOCK) {
            if (process != null && isProcessAlive(process) && isSocksReady()) {
                return;
            }
            stopInternal();

            try {
                File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libawgproxy.so");
                if (!executable.isFile() || !executable.canExecute()) {
                    throw new IllegalStateException("Userspace AmneziaWG engine is missing for this device ABI");
                }

                configFile = new File(context.getFilesDir(), "awgproxy-profile.json");
                byte[] config = buildConfig(info).toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream output = new FileOutputStream(configFile)) {
                    output.write(config);
                    output.getFD().sync();
                }
                configFile.setReadable(false, false);
                configFile.setReadable(true, true);
                configFile.setWritable(false, false);
                configFile.setWritable(true, true);

                ProcessBuilder builder = new ProcessBuilder(
                        executable.getAbsolutePath(),
                        "-config", configFile.getAbsolutePath(),
                        "-listen", LISTEN_ADDRESS + ":" + SOCKS_PORT
                );
                builder.directory(context.getFilesDir());
                builder.redirectErrorStream(true);
                process = builder.start();
                final Process started = process;
                new Thread(() -> readProcessOutput(started), "AmneziaWG-Userspace-Log").start();

                long deadline = android.os.SystemClock.elapsedRealtime() + START_TIMEOUT_MS;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (started == null || !isProcessAlive(started)) {
                        throw new IllegalStateException("AmneziaWG userspace process exited during startup");
                    }
                    if (isSocksReady()) {
                        lastError = null;
                        FileLog.d("AmneziaWG userspace SOCKS5 ready on " + LISTEN_ADDRESS + ":" + SOCKS_PORT);
                        return;
                    }
                    Thread.sleep(200);
                }
                throw new IllegalStateException("Timed out waiting for AmneziaWG userspace SOCKS5");
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                stopInternal();
                throw e;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopInternal();
        }
    }

    private static void stopInternal() {
        Process current = process;
        process = null;
        if (current != null) {
            try {
                current.destroy();
                long deadline = android.os.SystemClock.elapsedRealtime() + 1500L;
                while (isProcessAlive(current) && android.os.SystemClock.elapsedRealtime() < deadline) {
                    android.os.SystemClock.sleep(50);
                }
                if (isProcessAlive(current)) {
                    FileLog.e("AmneziaWG userspace process did not stop after SIGTERM");
                }
            } catch (Exception e) {
                FileLog.e("AmneziaWG userspace process stop failed", e);
            }
        }
        File file = configFile;
        configFile = null;
        if (file != null && file.exists() && !file.delete()) {
            FileLog.d("AmneziaWG: could not delete temporary profile file");
        }
    }

    private static JSONObject buildConfig(SharedConfig.ProxyInfo info) throws Exception {
        String endpoint = !TextUtils.isEmpty(info.wgEndpoint)
                ? info.wgEndpoint.trim()
                : info.settings.getAddress() + ":" + info.settings.getPort();
        require(info.wgPrivateKey, "WireGuard private key");
        require(info.wgPublicKey, "WireGuard peer public key");
        require(info.wgLocalAddress, "WireGuard interface address");
        require(endpoint, "WireGuard endpoint");

        JSONObject root = new JSONObject();
        root.put("privateKey", info.wgPrivateKey.trim());
        root.put("addresses", splitValues(info.wgLocalAddress));
        root.put("dns", splitValues(info.wgDns));
        root.put("mtu", parseInt(info.wgMtu, 1408));

        JSONObject peer = new JSONObject();
        peer.put("publicKey", info.wgPublicKey.trim());
        peer.put("presharedKey", info.wgPresharedKey == null ? "" : info.wgPresharedKey.trim());
        peer.put("endpoint", resolveEndpoint(endpoint));
        peer.put("allowedIps", splitValues(TextUtils.isEmpty(info.wgAllowedIps)
                ? "0.0.0.0/0, ::/0" : info.wgAllowedIps));
        peer.put("keepalive", parseInt(info.wgKeepAlive, 25));
        root.put("peer", peer);

        JSONObject amnezia = new JSONObject();
        if (info.wgAmneziaCompatibility) {
            amnezia.put("jc", "4");
            amnezia.put("jmin", "40");
            amnezia.put("jmax", "70");
            amnezia.put("s1", "0");
            amnezia.put("s2", "0");
            amnezia.put("s3", "0");
            amnezia.put("s4", "0");
            amnezia.put("h1", "1");
            amnezia.put("h2", "2");
            amnezia.put("h3", "3");
            amnezia.put("h4", "4");
        }
        if (!TextUtils.isEmpty(info.wgAmneziaProperties)) {
            String[] lines = info.wgAmneziaProperties.split("\\r?\\n");
            for (String line : lines) {
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = normalizeAmneziaKey(line.substring(0, equals));
                String value = line.substring(equals + 1).trim();
                if (isAllowedAmneziaKey(key) && !TextUtils.isEmpty(value)) {
                    amnezia.put(key, value);
                }
            }
        }
        root.put("amnezia", amnezia);
        return root;
    }

    private static String resolveEndpoint(String endpoint) throws Exception {
        String value = endpoint.trim();
        String host;
        String port;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0 || close + 1 >= value.length() || value.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Invalid AmneziaWG endpoint format");
            }
            host = value.substring(1, close);
            port = value.substring(close + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid AmneziaWG endpoint format");
            }
            host = value.substring(0, separator);
            port = value.substring(separator + 1);
        }
        int portNumber;
        try { portNumber = Integer.parseInt(port); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid AmneziaWG endpoint port"); }
        if (portNumber < 1 || portNumber > 65535) throw new IllegalArgumentException("Invalid AmneziaWG endpoint port");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IllegalStateException("Could not resolve AmneziaWG endpoint");
        InetAddress selected = addresses[0];
        for (InetAddress address : addresses) {
            if (address instanceof java.net.Inet4Address) { selected = address; break; }
        }
        String ip = selected.getHostAddress();
        return selected instanceof java.net.Inet6Address ? "[" + ip + "]:" + portNumber : ip + ":" + portNumber;
    }

    public static String getDiagnostics() {
        synchronized (LOCK) {
            if (process == null || !isProcessAlive(process)) {
                return "Engine: stopped\\n" + (lastError == null ? "No active AmneziaWG process." : "Last error: " + lastError);
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LISTEN_ADDRESS, DIAGNOSTICS_PORT), 700);
            socket.setSoTimeout(1200);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return "Diagnostics: no response";
            JSONObject status = new JSONObject(line);
            StringBuilder result = new StringBuilder();
            result.append("Engine: ").append(status.optBoolean("running") ? "running" : "stopped");
            result.append("\\nSOCKS5: ").append(status.optBoolean("socksReady") ? "ready" : "not ready");
            if (status.optBoolean("handshake")) result.append("\\nHandshake: received ").append(status.optLong("handshakeAgeSeconds")).append("s ago");
            else result.append("\\nHandshake: not received yet");
            result.append("\\nReceived: ").append(status.optLong("rxBytes")).append(" bytes");
            result.append("\\nSent: ").append(status.optLong("txBytes")).append(" bytes");
            result.append("\\nUptime: ").append(status.optLong("uptimeSeconds")).append("s");
            String error = status.optString("lastError", "");
            if (!TextUtils.isEmpty(error)) result.append("\\nLast error: ").append(error);
            return result.toString();
        } catch (Exception e) {
            return "Engine: running\\nDiagnostics unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static String getDiagnosticsSummary() {
        String details = getDiagnostics();
        if (details.contains("Handshake: received")) return "Handshake received";
        if (details.contains("Handshake: not received")) return "Waiting for handshake";
        if (details.contains("Diagnostics unavailable")) return "Diagnostics unavailable";
        if (details.contains("Last error:")) return "Connection error";
        if (details.contains("Engine: stopped")) return "Engine stopped";
        return "Checking connection";
    }

    private static String normalizeAmneziaKey(String value) {
        String key = value.trim().toLowerCase(Locale.US).replace("-", "_");
        if ("headerprotectionkey".equals(key)) return "header_protection_key";
        if ("contentpaddingaddition".equals(key)) return "content_padding_addition";
        if ("rekeyaftertime".equals(key)) return "rekey_after_time";
        if ("rekeytimeout".equals(key)) return "rekey_timeout";
        if ("rejectaftertime".equals(key)) return "reject_after_time";
        if ("keepalivetimeout".equals(key)) return "keepalive_timeout";
        if ("maxhandshakeattempts".equals(key)) return "max_handshake_attempts";
        if ("randomtrailers".equals(key)) return "random_trailers";
        if ("disablecookies".equals(key)) return "disable_cookies";
        return key;
    }

    private static boolean isAllowedAmneziaKey(String key) {
        switch (key) {
            case "jc": case "jmin": case "jmax":
            case "s1": case "s2": case "s3": case "s4":
            case "h1": case "h2": case "h3": case "h4":
            case "i1": case "i2": case "i3": case "i4": case "i5":
            case "header_protection_key": case "content_padding_addition":
            case "rekey_after_time": case "rekey_timeout": case "reject_after_time":
            case "keepalive_timeout": case "max_handshake_attempts":
            case "random_trailers": case "disable_cookies":
                return true;
            default:
                return false;
        }
    }

    private static JSONArray splitValues(String value) {
        JSONArray values = new JSONArray();
        if (!TextUtils.isEmpty(value)) {
            for (String item : value.split("[,;\\s]+")) {
                if (!TextUtils.isEmpty(item.trim())) values.put(item.trim());
            }
        }
        return values;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static void require(String value, String label) {
        if (TextUtils.isEmpty(value) || value.trim().isEmpty()
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Missing or invalid " + label);
        }
    }

    private static boolean isProcessAlive(Process candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            candidate.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    private static void readProcessOutput(Process current) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("READY ")) {
                    FileLog.d("AmneziaWG: userspace engine is listening");
                } else {
                    FileLog.d("AmneziaWG userspace: " + line);
                }
            }
        } catch (Exception e) {
            FileLog.e("AmneziaWG userspace log reader failed", e);
        }
    }
}
