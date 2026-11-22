package org.telegram.messenger;

import android.content.Context;
import android.net.VpnService;
import android.text.TextUtils;

import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native AmneziaWG backend for Telegram's WireGuard proxy profiles.
 *
 * The upstream GoBackend owns the Android TUN descriptor and native userspace
 * engine. The generated config uses IncludedApplications so only this Telegram
 * package is routed into the VPN.
 */
public final class AmneziaWgManager {
    private static final Object LOCK = new Object();
    private static final String TUNNEL_NAME = "telegram-awg";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static GoBackend backend;
    private static volatile String lastError;
    private static final Tunnel TUNNEL = new Tunnel() {
        @Override
        public String getName() {
            return TUNNEL_NAME;
        }

        @Override
        public void onStateChange(State state) {
            RUNNING.set(state == State.UP);
        }
    };

    private AmneziaWgManager() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String getLastError() {
        return lastError;
    }

    /**
     * Returns the Android VPN consent intent, or null when permission is already granted.
     * The caller must launch this intent from a foreground Activity.
     */
    public static android.content.Intent prepareVpnPermission(Context context) {
        return VpnService.prepare(context);
    }

    public static void start(Context context, SharedConfig.ProxyInfo info) throws Exception {
        if (context == null || info == null || !info.isXrayWireguard()) {
            throw new IllegalArgumentException("A WireGuard proxy profile is required");
        }
        if (VpnService.prepare(context) != null) {
            throw new IllegalStateException("Android VPN permission is required for native AmneziaWG");
        }

        synchronized (LOCK) {
            try {
                if (backend == null) {
                    backend = new GoBackend(context.getApplicationContext());
                }
                Config config = Config.parse(new BufferedReader(new StringReader(buildConfig(context, info))));
                backend.setState(TUNNEL, Tunnel.State.UP, config);
                RUNNING.set(backend.getState(TUNNEL) == Tunnel.State.UP);
                if (!RUNNING.get()) {
                    throw new IllegalStateException("AmneziaWG backend did not enter UP state");
                }
                lastError = null;
                FileLog.d("AmneziaWG: native tunnel started for Telegram package " + context.getPackageName()
                        + ", version " + backend.getVersion());
            } catch (Exception e) {
                RUNNING.set(false);
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw e;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (backend == null) {
                RUNNING.set(false);
                return;
            }
            try {
                backend.setState(TUNNEL, Tunnel.State.DOWN, null);
            } catch (Exception e) {
                FileLog.e("AmneziaWG: failed to stop native tunnel", e);
            } finally {
                RUNNING.set(false);
            }
        }
    }

    private static String buildConfig(Context context, SharedConfig.ProxyInfo info) {
        String endpoint = !TextUtils.isEmpty(info.wgEndpoint)
                ? info.wgEndpoint.trim()
                : info.settings.getAddress() + ":" + info.settings.getPort();
        require(info.wgPrivateKey, "WireGuard private key");
        require(info.wgPublicKey, "WireGuard peer public key");
        require(info.wgLocalAddress, "WireGuard interface address");
        require(endpoint, "WireGuard endpoint");

        StringBuilder out = new StringBuilder();
        out.append("[Interface]\n");
        add(out, "PrivateKey", info.wgPrivateKey);
        add(out, "Address", info.wgLocalAddress);
        add(out, "DNS", info.wgDns);
        add(out, "MTU", info.wgMtu);
        // Explicit allow-list: never create a device-wide VPN from a Telegram proxy.
        add(out, "IncludedApplications", context.getPackageName());
        appendAmneziaProperties(out, info);

        out.append("\n[Peer]\n");
        add(out, "PublicKey", info.wgPublicKey);
        add(out, "PresharedKey", info.wgPresharedKey);
        add(out, "Endpoint", endpoint);
        add(out, "AllowedIPs", TextUtils.isEmpty(info.wgAllowedIps)
                ? "0.0.0.0/0, ::/0" : info.wgAllowedIps);
        add(out, "PersistentKeepalive", TextUtils.isEmpty(info.wgKeepAlive)
                ? "25" : info.wgKeepAlive);
        return out.toString();
    }

    private static void appendAmneziaProperties(StringBuilder out, SharedConfig.ProxyInfo info) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        if (info.wgAmneziaCompatibility) {
            values.put("jc", "4");
            values.put("jmin", "40");
            values.put("jmax", "70");
            values.put("s1", "0");
            values.put("s2", "0");
            values.put("s3", "0");
            values.put("s4", "0");
            values.put("h1", "1");
            values.put("h2", "2");
            values.put("h3", "3");
            values.put("h4", "4");
        }
        if (!TextUtils.isEmpty(info.wgAmneziaProperties)) {
            String[] lines = info.wgAmneziaProperties.split("\\r?\\n");
            for (String line : lines) {
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim().toLowerCase(java.util.Locale.US);
                String value = line.substring(equals + 1).trim();
                if (isAllowedAmneziaKey(key) && !TextUtils.isEmpty(value)) {
                    values.put(key, value);
                }
            }
        }
        String[] order = {"jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
                "i1", "i2", "i3", "i4", "i5", "headerprotectionkey", "contentpaddingaddition",
                "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout",
                "maxhandshakeattempts", "randomtrailers", "disablecookies"};
        for (String key : order) {
            String value = values.get(key);
            if (!TextUtils.isEmpty(value)) {
                add(out, key, value);
            }
        }
    }

    private static boolean isAllowedAmneziaKey(String key) {
        switch (key) {
            case "jc": case "jmin": case "jmax":
            case "s1": case "s2": case "s3": case "s4":
            case "h1": case "h2": case "h3": case "h4":
            case "i1": case "i2": case "i3": case "i4": case "i5":
            case "headerprotectionkey": case "contentpaddingaddition":
            case "rekeyaftertime": case "rekeytimeout": case "rejectaftertime":
            case "keepalivetimeout": case "maxhandshakeattempts":
            case "randomtrailers": case "disablecookies":
                return true;
            default:
                return false;
        }
    }

    private static void require(String value, String label) {
        if (TextUtils.isEmpty(value) || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + label);
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static void add(StringBuilder out, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            String normalized = value.trim();
            if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Invalid WireGuard value for " + key);
            }
            out.append(key).append(" = ").append(normalized).append('\n');
        }
    }
}
