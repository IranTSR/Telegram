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
            if (backend == null) {
                backend = new GoBackend(context.getApplicationContext());
            }
            Config config = Config.parse(new BufferedReader(new StringReader(buildConfig(context, info))));
            backend.setState(TUNNEL, Tunnel.State.UP, config);
            RUNNING.set(backend.getState(TUNNEL) == Tunnel.State.UP);
            if (!RUNNING.get()) {
                throw new IllegalStateException("AmneziaWG backend did not enter UP state");
            }
            FileLog.d("AmneziaWG: native tunnel started for Telegram package " + context.getPackageName()
                    + ", version " + backend.getVersion());
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
