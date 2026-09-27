package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class XrayProxyManager {
    public static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_SOCKS_PORT = 1081;
    private static final String DEFAULT_XRAY_VERSION = "v26.3.27";
    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_STARTING = 2;
    public static final int STATE_RUNNING = 3;
    public static final int STATE_FAILED = 4;

    private static final Object sync = new Object();
    private static Process xrayProcess;
    private static String lastConfigHash;
    private static boolean starting;
    private static volatile ProgressListener progressListener;
    private static volatile int state = STATE_IDLE;
    private static volatile long downloadTotalBytes = -1;
    private static volatile long downloadBytes = 0;
    private static volatile String lastError;

    public interface ProgressListener {
        void onDownloadStart(long totalBytes);

        void onDownloadProgress(long downloadedBytes, long totalBytes);

        void onDownloadDone(boolean success);
    }

    public static void setProgressListener(ProgressListener listener) {
        progressListener = listener;
    }

    public static int getState() {
        return state;
    }

    public static long getDownloadTotalBytes() {
        return downloadTotalBytes;
    }

    public static long getDownloadBytes() {
        return downloadBytes;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void markRunning() {
        setState(STATE_RUNNING, null);
    }

    public static void markFailed(String error) {
        setState(STATE_FAILED, error);
    }

    private static void markStarting() {
        setState(STATE_STARTING, null);
    }

    private static void resetState() {
        state = STATE_IDLE;
        downloadBytes = 0;
        downloadTotalBytes = -1;
        lastError = null;
    }

    private static void setState(int newState, String error) {
        state = newState;
        if (error != null) {
            lastError = error;
        }
    }

    public static int getLocalSocksPort() {
        return DEFAULT_SOCKS_PORT;
    }

    public static boolean isRunning() {
        synchronized (sync) {
            return xrayProcess != null && xrayProcess.isAlive();
        }
    }

    public static boolean deleteCoreFiles() {
        synchronized (sync) {
            stopProcessInternal();
            lastConfigHash = null;
            starting = false;
        }
        resetState();
        File xrayDir = ApplicationLoader.getFilesDirFixed("xray");
        if (xrayDir == null) {
            return false;
        }
        boolean deleted = deleteDirectory(xrayDir);
        if (!xrayDir.exists()) {
            xrayDir.mkdirs();
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Xray: delete core files=" + deleted);
        }
        return deleted;
    }

    public static boolean isSocksReady() {
        return canConnect(200);
    }

    public static boolean waitForSocksReady(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRunning() && canConnect(300)) {
                return true;
            }
            SystemClock.sleep(250);
        }
        return isRunning() && canConnect(400);
    }

    public static void startService() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, XrayProxyService.class);
        intent.setAction(XrayProxyService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopService() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, XrayProxyService.class);
        intent.setAction(XrayProxyService.ACTION_STOP);
        context.startService(intent);
    }

    public static void maybeStartFromApp() {
        SharedConfig.loadProxyList();
        if (SharedConfig.currentProxy != null
                && SharedConfig.currentProxy.proxyType == SharedConfig.ProxyInfo.PROXY_TYPE_XRAY_VLESS
                && MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)) {
            startService();
        }
    }

    public static void ensureRunning(SharedConfig.ProxyInfo info) {
        if (info == null || info.proxyType != SharedConfig.ProxyInfo.PROXY_TYPE_XRAY_VLESS) {
            return;
        }
        if (TextUtils.isEmpty(info.vlessId)) {
            FileLog.e("Xray: missing VLESS id");
            markFailed("missing VLESS id");
            return;
        }
        synchronized (sync) {
            if (starting) {
                return;
            }
            starting = true;
        }
        try {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Xray: ensureRunning " + info.address + ":" + info.port);
            }
            markStarting();
            File xrayDir = ApplicationLoader.getFilesDirFixed("xray");
            if (xrayDir == null) {
                return;
            }
            File binFile = getBundledBinary();
            boolean bundled = binFile != null && binFile.exists();
            if (!bundled) {
                binFile = new File(xrayDir, "xray");
                if (!binFile.exists() || binFile.length() == 0) {
                    if (!downloadBinary(xrayDir, binFile, true)) {
                        markFailed("download failed");
                        return;
                    }
                }
            }
            if (!ensureGeoFiles(xrayDir)) {
                if (!downloadBinary(xrayDir, bundled ? null : binFile, !bundled)) {
                    markFailed("geo download failed");
                    return;
                }
            }
            if (!ensureExecutable(binFile)) {
                markFailed("binary not executable");
                return;
            }
            String config = buildConfig(info);
            String configHash = sha256(config);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Xray: config hash=" + configHash);
            }
            if (xrayProcess != null && xrayProcess.isAlive() && TextUtils.equals(lastConfigHash, configHash)) {
                return;
            }
            stopProcessInternal();
            File configFile = new File(xrayDir, "config.json");
            writeConfig(configFile, config);
            ProcessBuilder builder = new ProcessBuilder(binFile.getAbsolutePath(), "run", "-c", configFile.getAbsolutePath());
            builder.directory(xrayDir);
            builder.redirectErrorStream(true);
            xrayProcess = builder.start();
            lastConfigHash = configHash;
            startLogReader(xrayProcess.getInputStream());
        } catch (Exception e) {
            markFailed(e.getMessage());
            FileLog.e(e);
        } finally {
            synchronized (sync) {
                starting = false;
            }
        }
    }

    public static void stopProcess() {
        synchronized (sync) {
            stopProcessInternal();
        }
    }

    private static void stopProcessInternal() {
        if (xrayProcess != null) {
            try {
                xrayProcess.destroy();
            } catch (Exception ignored) {
            }
            xrayProcess = null;
        }
        setState(STATE_IDLE, null);
    }

    private static void startLogReader(final InputStream inputStream) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("xray: " + line);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }, "XrayLogReader").start();
    }

    private static File getBundledBinary() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        ApplicationInfo info = context.getApplicationInfo();
        if (info == null || TextUtils.isEmpty(info.nativeLibraryDir)) {
            return null;
        }
        File binFile = new File(info.nativeLibraryDir, "libxray.so");
        return binFile.exists() ? binFile : null;
    }

    private static boolean ensureGeoFiles(File xrayDir) {
        if (xrayDir == null) {
            return false;
        }
        File geoip = new File(xrayDir, "geoip.dat");
        File geosite = new File(xrayDir, "geosite.dat");
        boolean ok = true;
        if (!geoip.exists() || geoip.length() == 0) {
            ok = copyAsset("xray/geoip.dat", geoip);
        }
        if (!geosite.exists() || geosite.length() == 0) {
            ok = ok && copyAsset("xray/geosite.dat", geosite);
        }
        return ok;
    }

    private static boolean copyAsset(String assetName, File outFile) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        try (InputStream in = context.getAssets().open(assetName);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return outFile.exists() && outFile.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean downloadBinary(File xrayDir, File binFile, boolean extractBinary) {
        String assetName = getAssetNameForDevice();
        if (assetName == null) {
            FileLog.e("Xray: unsupported ABI for download");
            return false;
        }
        String url = "https://github.com/XTLS/Xray-core/releases/download/" + DEFAULT_XRAY_VERSION + "/" + assetName;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Xray: download " + url);
        }
        ProgressListener listener = progressListener;
        HttpURLConnection connection = null;
        boolean success = false;
        long totalBytes = -1;
        downloadBytes = 0;
        downloadTotalBytes = -1;
        try {
            URL downloadUrl = new URL(url);
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                FileLog.e("Xray download failed: HTTP " + connection.getResponseCode());
                markFailed("HTTP " + connection.getResponseCode());
                return false;
            }
            totalBytes = connection.getContentLengthLong();
            downloadTotalBytes = totalBytes;
            setState(STATE_DOWNLOADING, null);
            if (listener != null) {
                listener.onDownloadStart(totalBytes);
            }
            try (CountingInputStream raw = new CountingInputStream(new BufferedInputStream(connection.getInputStream()));
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ProgressState state = new ProgressState();
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (extractBinary && binFile != null && ("xray".equals(name) || name.endsWith("/xray"))) {
                        writeEntry(zip, binFile, listener, raw, totalBytes, state);
                        ensureExecutable(binFile);
                    } else if ("geoip.dat".equals(name) || "geosite.dat".equals(name)) {
                        writeEntry(zip, new File(xrayDir, name), listener, raw, totalBytes, state);
                    }
                }
            }
            File geoip = new File(xrayDir, "geoip.dat");
            File geosite = new File(xrayDir, "geosite.dat");
            success = geoip.exists() && geosite.exists();
            if (extractBinary && binFile != null) {
                success = success && binFile.exists() && binFile.length() > 0;
            }
            return success;
        } catch (Exception e) {
            markFailed(e.getMessage());
            FileLog.e(e);
            return false;
        } finally {
            if (listener != null) {
                listener.onDownloadDone(success);
            }
            if (connection != null) {
                connection.disconnect();
            }
            if (success) {
                markStarting();
            }
        }
    }

    private static void writeEntry(ZipInputStream zip, File outFile, ProgressListener listener, CountingInputStream counter, long totalBytes, ProgressState state) throws Exception {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = zip.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                maybeUpdateProgress(listener, counter, totalBytes, state, false);
            }
            out.flush();
            maybeUpdateProgress(listener, counter, totalBytes, state, true);
        }
    }

    private static String getAssetNameForDevice() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            abis = new String[]{Build.CPU_ABI};
        }
        for (String abi : abis) {
            String name = mapAbiToAsset(abi);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private static String mapAbiToAsset(String abi) {
        if (abi == null) {
            return null;
        }
        abi = abi.toLowerCase(Locale.US);
        if (abi.contains("arm64")) {
            return "Xray-android-arm64-v8a.zip";
        }
        if (abi.contains("armeabi") || abi.contains("armv7")) {
            return "Xray-android-arm32-v7a.zip";
        }
        if (abi.contains("x86_64") || abi.contains("amd64")) {
            return "Xray-android-amd64.zip";
        }
        if (abi.contains("x86")) {
            return "Xray-android-386.zip";
        }
        return null;
    }

    private static void writeConfig(File file, String config) throws Exception {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(config.getBytes("UTF-8"));
            out.flush();
        }
    }

    private static String buildConfig(SharedConfig.ProxyInfo info) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");
        root.put("log", log);

        JSONObject inbound = new JSONObject();
        inbound.put("tag", "socks-in");
        inbound.put("listen", LOCAL_ADDRESS);
        inbound.put("port", getLocalSocksPort());
        inbound.put("protocol", "socks");
        JSONObject inboundSettings = new JSONObject();
        inboundSettings.put("auth", "noauth");
        inboundSettings.put("udp", true);
        inboundSettings.put("ip", LOCAL_ADDRESS);
        inbound.put("settings", inboundSettings);
        root.put("inbounds", new JSONArray().put(inbound));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "vless");

        JSONObject user = new JSONObject();
        user.put("id", info.vlessId);
        user.put("encryption", TextUtils.isEmpty(info.vlessEncryption) ? "none" : info.vlessEncryption);
        if (!TextUtils.isEmpty(info.vlessFlow)) {
            user.put("flow", info.vlessFlow);
        }

        JSONObject vnext = new JSONObject();
        vnext.put("address", info.address);
        vnext.put("port", info.port);
        vnext.put("users", new JSONArray().put(user));

        JSONObject settings = new JSONObject();
        settings.put("vnext", new JSONArray().put(vnext));
        outbound.put("settings", settings);

        JSONObject stream = new JSONObject();
        String network = mapNetwork(info.vlessType);
        stream.put("network", network);

        String security = normalizeSecurity(info.vlessSecurity);
        stream.put("security", security);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessSni)) {
                tls.put("serverName", info.vlessSni);
            }
            if (!TextUtils.isEmpty(info.vlessFp)) {
                tls.put("fingerprint", info.vlessFp);
            }
            if (!TextUtils.isEmpty(info.vlessAlpn)) {
                JSONArray alpn = new JSONArray();
                for (String item : info.vlessAlpn.split(",")) {
                    if (!TextUtils.isEmpty(item.trim())) {
                        alpn.put(item.trim());
                    }
                }
                if (alpn.length() > 0) {
                    tls.put("alpn", alpn);
                }
            }
            if (info.vlessAllowInsecure) {
                tls.put("allowInsecure", true);
            }
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            JSONObject reality = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessSni)) {
                reality.put("serverName", info.vlessSni);
            }
            if (!TextUtils.isEmpty(info.vlessFp)) {
                reality.put("fingerprint", info.vlessFp);
            }
            if (!TextUtils.isEmpty(info.vlessPublicKey)) {
                reality.put("publicKey", info.vlessPublicKey);
            }
            if (!TextUtils.isEmpty(info.vlessShortId)) {
                reality.put("shortId", info.vlessShortId);
            }
            if (!TextUtils.isEmpty(info.vlessSpiderX)) {
                reality.put("spiderX", info.vlessSpiderX);
            }
            stream.put("realitySettings", reality);
        }

        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessPath)) {
                ws.put("path", info.vlessPath);
            }
            if (!TextUtils.isEmpty(info.vlessHost)) {
                JSONObject headers = new JSONObject();
                headers.put("Host", info.vlessHost);
                ws.put("headers", headers);
            }
            stream.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessServiceName)) {
                grpc.put("serviceName", info.vlessServiceName);
            }
            if (!TextUtils.isEmpty(info.vlessMode)) {
                grpc.put("multiMode", "multi".equalsIgnoreCase(info.vlessMode) || "true".equalsIgnoreCase(info.vlessMode));
            }
            stream.put("grpcSettings", grpc);
        } else if ("http".equals(network)) {
            JSONObject http = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessPath)) {
                http.put("path", info.vlessPath);
            }
            if (!TextUtils.isEmpty(info.vlessHost)) {
                JSONArray hosts = new JSONArray();
                for (String host : info.vlessHost.split(",")) {
                    if (!TextUtils.isEmpty(host.trim())) {
                        hosts.put(host.trim());
                    }
                }
                if (hosts.length() > 0) {
                    http.put("host", hosts);
                }
            }
            stream.put("httpSettings", http);
        } else if ("kcp".equals(network)) {
            JSONObject kcp = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessSeed)) {
                kcp.put("seed", info.vlessSeed);
            }
            if (!TextUtils.isEmpty(info.vlessHeaderType)) {
                JSONObject header = new JSONObject();
                header.put("type", info.vlessHeaderType);
                kcp.put("header", header);
            }
            stream.put("kcpSettings", kcp);
        } else if ("quic".equals(network)) {
            JSONObject quic = new JSONObject();
            if (!TextUtils.isEmpty(info.vlessQuicSecurity)) {
                quic.put("security", info.vlessQuicSecurity);
            }
            if (!TextUtils.isEmpty(info.vlessQuicKey)) {
                quic.put("key", info.vlessQuicKey);
            }
            if (!TextUtils.isEmpty(info.vlessHeaderType)) {
                JSONObject header = new JSONObject();
                header.put("type", info.vlessHeaderType);
                quic.put("header", header);
            }
            stream.put("quicSettings", quic);
        } else if ("tcp".equals(network) && !TextUtils.isEmpty(info.vlessHeaderType)) {
            JSONObject tcp = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("type", info.vlessHeaderType);
            tcp.put("header", header);
            stream.put("tcpSettings", tcp);
        }

        outbound.put("streamSettings", stream);
        applyAdvancedJson(outbound, stream, info.vlessAdvancedJson);
        root.put("outbounds", new JSONArray().put(outbound));
        return root.toString();
    }

    private static String normalizeSecurity(String security) {
        if (TextUtils.isEmpty(security)) {
            return "none";
        }
        security = security.toLowerCase(Locale.US);
        if ("tls".equals(security) || "reality".equals(security)) {
            return security;
        }
        return "none";
    }

    private static String mapNetwork(String type) {
        if (TextUtils.isEmpty(type)) {
            return "tcp";
        }
        type = type.toLowerCase(Locale.US);
        if ("h2".equals(type)) {
            return "http";
        }
        return type;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static boolean canConnect(int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCAL_ADDRESS, DEFAULT_SOCKS_PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void maybeUpdateProgress(ProgressListener listener, CountingInputStream counter, long totalBytes, ProgressState state, boolean force) {
        if (listener == null || counter == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - state.lastUpdateMs < 250) {
            return;
        }
        state.lastUpdateMs = now;
        downloadBytes = counter.getCount();
        downloadTotalBytes = totalBytes;
        listener.onDownloadProgress(downloadBytes, totalBytes);
    }

    private static final class ProgressState {
        long lastUpdateMs;
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read != -1) {
                count++;
            }
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long getCount() {
            return count;
        }
    }

    private static boolean ensureExecutable(File binFile) {
        if (binFile == null) {
            return false;
        }
        if (binFile.canExecute()) {
            return true;
        }
        boolean ok = binFile.setExecutable(true, false);
        if (!ok || !binFile.canExecute()) {
            try {
                Os.chmod(binFile.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IWUSR | OsConstants.S_IXUSR
                        | OsConstants.S_IRGRP | OsConstants.S_IXGRP
                        | OsConstants.S_IROTH | OsConstants.S_IXOTH);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (!binFile.canExecute()) {
            FileLog.e("Xray: binary not executable: " + binFile.getAbsolutePath());
        }
        return binFile.canExecute();
    }

    private static void applyAdvancedJson(JSONObject outbound, JSONObject stream, String advancedJson) {
        if (TextUtils.isEmpty(advancedJson)) {
            return;
        }
        try {
            JSONObject advanced = new JSONObject(advancedJson);
            if (advanced.has("streamSettings") && advanced.opt("streamSettings") instanceof JSONObject) {
                mergeInto(stream, advanced.getJSONObject("streamSettings"));
            } else {
                mergeInto(stream, advanced);
            }
            if (advanced.has("settings") && advanced.opt("settings") instanceof JSONObject) {
                JSONObject settings = outbound.optJSONObject("settings");
                if (settings == null) {
                    settings = new JSONObject();
                    outbound.put("settings", settings);
                }
                mergeInto(settings, advanced.getJSONObject("settings"));
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Xray: applied advanced JSON");
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void mergeInto(JSONObject target, JSONObject source) throws Exception {
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, source.get(key));
        }
    }

    private static boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }
}
