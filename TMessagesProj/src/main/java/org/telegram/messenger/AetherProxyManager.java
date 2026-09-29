package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
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
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public class AetherProxyManager {
    public static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_SOCKS_PORT = 1819;
    private static final String DEFAULT_AETHER_VERSION = "v2.1.0";
    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_STARTING = 2;
    public static final int STATE_RUNNING = 3;
    public static final int STATE_FAILED = 4;

    private static final Object sync = new Object();
    private static Process aetherProcess;
    private static String lastArgsHash;
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
            return aetherProcess != null && aetherProcess.isAlive();
        }
    }

    public static boolean deleteCoreFiles() {
        synchronized (sync) {
            stopProcessInternal();
            lastArgsHash = null;
            starting = false;
        }
        resetState();
        File aetherDir = ApplicationLoader.getFilesDirFixed("aether");
        if (aetherDir == null) {
            return false;
        }
        boolean deleted = deleteDirectory(aetherDir);
        if (!aetherDir.exists()) {
            aetherDir.mkdirs();
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Aether: delete core files=" + deleted);
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
        Intent intent = new Intent(context, AetherProxyService.class);
        intent.setAction(AetherProxyService.ACTION_START);
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
        Intent intent = new Intent(context, AetherProxyService.class);
        intent.setAction(AetherProxyService.ACTION_STOP);
        context.startService(intent);
    }

    public static void maybeStartFromApp() {
        SharedConfig.loadProxyList();
        if (SharedConfig.currentProxy != null
                && SharedConfig.currentProxy.isAether()
                && MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)) {
            startService();
        }
    }

    public static void ensureRunning(SharedConfig.ProxyInfo info) {
        if (info == null || !info.isAether()) {
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
                FileLog.d("Aether: ensureRunning protocol=" + info.aetherProtocol + " scan=" + info.aetherScan);
            }
            markStarting();
            File aetherDir = ApplicationLoader.getFilesDirFixed("aether");
            if (aetherDir == null) {
                markFailed("no private dir");
                return;
            }
            File binFile = getBundledBinary();
            boolean bundled = binFile != null && binFile.exists();
            if (!bundled) {
                binFile = new File(aetherDir, "aether");
                if (!binFile.exists() || binFile.length() == 0) {
                    if (!downloadBinary(aetherDir, binFile)) {
                        markFailed("download failed");
                        return;
                    }
                }
            }
            if (!ensureExecutable(binFile)) {
                markFailed("binary not executable");
                return;
            }
            ArrayList<String> argv = buildArgv(binFile, aetherDir, info);
            String argsHash = sha256(TextUtils.join(" ", argv));
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Aether: argv hash=" + argsHash);
            }
            if (aetherProcess != null && aetherProcess.isAlive() && TextUtils.equals(lastArgsHash, argsHash)) {
                return;
            }
            stopProcessInternal();
            // Never run two engines at once.
            XrayProxyManager.stopProcess();
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(aetherDir);
            builder.redirectErrorStream(true);
            aetherProcess = builder.start();
            lastArgsHash = argsHash;
            startLogReader(aetherProcess.getInputStream());
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
        if (aetherProcess != null) {
            try {
                aetherProcess.destroy();
            } catch (Exception ignored) {
            }
            aetherProcess = null;
        }
        setState(STATE_IDLE, null);
    }

    private static ArrayList<String> buildArgv(File binFile, File aetherDir, SharedConfig.ProxyInfo info) {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(binFile.getAbsolutePath());
        argv.add("--bind");
        argv.add(LOCAL_ADDRESS + ":" + DEFAULT_SOCKS_PORT);
        // Stable config path: the WARP identity is created once and reused.
        argv.add("--config");
        argv.add(new File(aetherDir, "aether.toml").getAbsolutePath());

        String protocol = TextUtils.isEmpty(info.aetherProtocol) ? "masque" : info.aetherProtocol.toLowerCase(Locale.US);
        if ("wg".equals(protocol) || "wireguard".equals(protocol)) {
            argv.add("--wg");
        } else if ("gool".equals(protocol)) {
            argv.add("--gool");
        } else if ("mim".equals(protocol)) {
            argv.add("--mim");
        } else {
            argv.add("--masque");
        }

        String scan = TextUtils.isEmpty(info.aetherScan) ? "turbo" : info.aetherScan.toLowerCase(Locale.US);
        argv.add("--scan");
        argv.add(scan);

        String ip = TextUtils.isEmpty(info.aetherIp) ? "v4" : info.aetherIp.toLowerCase(Locale.US);
        if ("v6".equals(ip)) {
            argv.add("-6");
        } else if ("dual".equals(ip) || "both".equals(ip)) {
            argv.add("--dual");
        } else {
            argv.add("-4");
        }

        String transport = TextUtils.isEmpty(info.aetherTransport) ? "" : info.aetherTransport.toLowerCase(Locale.US);
        if ("h2".equals(transport) || "http2".equals(transport)) {
            argv.add("--h2");
        } else if ("h3".equals(transport) || "quic".equals(transport)) {
            argv.add("--h3");
        }

        if (info.aetherFragment) {
            argv.add("--fragment");
        }

        String noize = TextUtils.isEmpty(info.aetherNoize) ? "firewall" : info.aetherNoize.toLowerCase(Locale.US);
        if (!"off".equals(noize)) {
            argv.add("--noize");
            argv.add(noize);
        }

        if (info.aetherQuickReconnect) {
            argv.add("--quick-reconnect");
        } else {
            argv.add("--no-quick-reconnect");
        }

        if (!TextUtils.isEmpty(info.aetherPeers)) {
            for (String peer : info.aetherPeers.split("[,\\s]+")) {
                peer = peer.trim();
                if (!TextUtils.isEmpty(peer)) {
                    argv.add("--peer");
                    argv.add(peer);
                }
            }
        }
        return argv;
    }

    private static void startLogReader(final InputStream inputStream) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("aether: " + line);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }, "AetherLogReader").start();
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
        File binFile = new File(info.nativeLibraryDir, "libaether.so");
        return binFile.exists() ? binFile : null;
    }

    private static boolean downloadBinary(File aetherDir, File binFile) {
        String assetName = getAssetNameForDevice();
        if (assetName == null) {
            FileLog.e("Aether: unsupported ABI for download");
            markFailed("unsupported device");
            return false;
        }
        String url = "https://github.com/CluvexStudio/Aether/releases/download/" + DEFAULT_AETHER_VERSION + "/" + assetName;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Aether: download " + url);
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
            connection.setReadTimeout(60000);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                FileLog.e("Aether download failed: HTTP " + connection.getResponseCode());
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
                 GZIPInputStream gzip = new GZIPInputStream(raw)) {
                ProgressState state = new ProgressState();
                success = extractTar(gzip, aetherDir, binFile, listener, raw, totalBytes, state);
            }
            if (success) {
                ensureExecutable(binFile);
                success = binFile.exists() && binFile.length() > 0 && binFile.canExecute();
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

    /** Minimal POSIX tar reader: extracts regular files, creates directories. */
    private static boolean extractTar(InputStream in, File aetherDir, File binFile,
                                      ProgressListener listener, CountingInputStream counter,
                                      long totalBytes, ProgressState state) throws Exception {
        byte[] header = new byte[512];
        boolean foundBinary = false;
        while (readFully(in, header)) {
            if (isZeroBlock(header)) {
                break;
            }
            String name = readCString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            char type = (char) header[156];
            File out = new File(aetherDir, name);
            if (type == '5') {
                out.mkdirs();
            } else if (type == '0' || type == '\0') {
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                File target = "aether".equals(name) ? binFile : out;
                try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(target))) {
                    copyN(in, fileOut, size, listener, counter, totalBytes, state);
                }
                if (target == binFile) {
                    foundBinary = true;
                }
            } else {
                skipN(in, size);
            }
            long padding = (512 - (size % 512)) % 512;
            skipN(in, padding);
        }
        return foundBinary;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                return offset > 0;
            }
            offset += read;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readCString(byte[] buffer, int offset, int maxLen) {
        int len = 0;
        while (len < maxLen && buffer[offset + len] != 0) {
            len++;
        }
        try {
            return new String(buffer, offset, len, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseOctal(byte[] buffer, int offset, int len) {
        String s = readCString(buffer, offset, len).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void copyN(InputStream in, java.io.OutputStream out, long n,
                             ProgressListener listener, CountingInputStream counter,
                             long totalBytes, ProgressState state) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
            maybeUpdateProgress(listener, counter, totalBytes, state, false);
        }
        out.flush();
        maybeUpdateProgress(listener, counter, totalBytes, state, true);
    }

    private static void skipN(InputStream in, long n) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                break;
            }
            remaining -= read;
        }
    }

    public static boolean isSupportedDevice() {
        return getAssetNameForDevice() != null;
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
            return "aether-android-arm64.tar.gz";
        }
        if (abi.contains("armeabi") || abi.contains("armv7")) {
            return "aether-android-armv7.tar.gz";
        }
        if (abi.contains("x86_64") || abi.contains("amd64")) {
            return "aether-android-x86_64.tar.gz";
        }
        // No upstream x86 asset.
        return null;
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

    private static final class CountingInputStream extends java.io.FilterInputStream {
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
            FileLog.e("Aether: binary not executable: " + binFile.getAbsolutePath());
        }
        return binFile.canExecute();
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
