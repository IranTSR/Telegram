package tw.nekomimi.nekogram.utils;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class ProxyUtil {
    private static final Pattern IPV6_PATTERN = Pattern.compile("^((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*::((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*|((?:[0-9A-Fa-f]{1,4}))((?::[0-9A-Fa-f]{1,4})){7}$");
    private static final String PREF_HWID_ENABLED = "proxy_hwid_enabled";
    private static final String PREF_HWID_VALUE = "proxy_hwid";
    private static final String HWID_USER_AGENT = "Happ/3.10.0";

    private static class ParseResult {
        final ArrayList<SharedConfig.ProxyInfo> proxies;
        final boolean error;

        ParseResult(ArrayList<SharedConfig.ProxyInfo> proxies, boolean error) {
            this.proxies = proxies;
            this.error = error;
        }
    }

    private static void showToast(CharSequence text) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show());
    }

    private static void showImportedDialog(Activity ctx, List<SharedConfig.ProxyInfo> proxies) {
        if (ctx == null) {
            return;
        }
        StringBuilder message = new StringBuilder(LocaleController.getString(R.string.ImportedProxies));
        message.append("\n\n");
        for (int i = 0; i < proxies.size(); i++) {
            if (i > 0) {
                message.append("\n");
            }
            message.append(proxies.get(i).address);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }

    public static void importFromClipboard(Activity ctx) {
        if (ctx == null) {
            return;
        }
        ClipboardManager clipboardManager = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = null;
        if (clipboardManager != null && clipboardManager.getPrimaryClip() != null && clipboardManager.getPrimaryClip().getItemCount() > 0) {
            CharSequence clipText = clipboardManager.getPrimaryClip().getItemAt(0).coerceToText(ApplicationLoader.applicationContext);
            if (clipText != null) {
                text = clipText.toString();
            }
        }

        ParseResult result = parseProxyText(text);
        if (result.proxies.isEmpty()) {
            if (!result.error) {
                showToast(LocaleController.getString(R.string.BrokenLink));
            }
            return;
        } else if (!result.error) {
            showImportedDialog(ctx, result.proxies);
        }

        for (SharedConfig.ProxyInfo info : result.proxies) {
            SharedConfig.addProxy(info);
        }

        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
    }

    public static void importFromUrl(Activity ctx, String url, boolean saveSubscription) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        String normalizedUrl = normalizeSubscriptionUrl(url);
        String subscriptionName = getSubscriptionTitle(normalizedUrl);
        if (saveSubscription) {
            addSubscription(normalizedUrl);
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: import url=" + normalizedUrl);
        }
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                applyHwidHeaders(conn);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder buffer = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buffer.append(line).append('\n');
                    }
                    String text = buffer.toString();
                    ParseResult result = parseProxyText(text);
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("proxy_sub: import fetched bytes=" + text.length() + " proxies=" + result.proxies.size() + " error=" + result.error);
                    }
                    if (result.proxies.isEmpty()) {
                        if (!result.error) {
                            AndroidUtilities.runOnUIThread(() -> showToast(LocaleController.getString(R.string.BrokenLink)));
                        }
                        return;
                    }
                    for (SharedConfig.ProxyInfo info : result.proxies) {
                        if (saveSubscription) {
                            info.subscriptionName = subscriptionName;
                        }
                        SharedConfig.addProxy(info, saveSubscription);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                        if (!result.error) {
                            showToast(LocaleController.getString(R.string.ProxySubscriptionAdded));
                        }
                    });
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> showToast(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        });
    }

    public static void refreshSubscriptions(Activity ctx) {
        List<String> urls = getSubscriptions();
        if (urls.isEmpty()) {
            showToast(LocaleController.getString(R.string.ProxySubscriptionEmpty));
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: refresh count=" + urls.size());
        }
        Utilities.globalQueue.postRunnable(() -> {
            final boolean[] any = new boolean[]{false};
            final ArrayList<SharedConfig.ProxyInfo> newProxies = new ArrayList<>();
            for (String url : urls) {
                String normalizedUrl = normalizeSubscriptionUrl(url);
                String subscriptionName = getSubscriptionTitle(normalizedUrl);
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                    applyHwidHeaders(conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder buffer = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buffer.append(line).append('\n');
                        }
                        String text = buffer.toString();
                        ParseResult result = parseProxyText(text);
                        if (!result.proxies.isEmpty()) {
                            for (SharedConfig.ProxyInfo info : result.proxies) {
                                info.subscriptionName = subscriptionName;
                                newProxies.add(info);
                            }
                            any[0] = true;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("proxy_sub: refresh url=" + normalizedUrl + " bytes=" + text.length() + " proxies=" + result.proxies.size());
                        }
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (any[0]) {
                    for (SharedConfig.ProxyInfo info : SharedConfig.getProxyList()) {
                        if (info.isSubscription) {
                            SharedConfig.deleteProxy(info);
                        }
                    }
                    for (SharedConfig.ProxyInfo info : newProxies) {
                        SharedConfig.addProxy(info, true);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    showToast(LocaleController.getString(R.string.ProxySubscriptionUpdated));
                } else {
                    showToast(LocaleController.getString(R.string.BrokenLink));
                }
            });
        });
    }

    public static void refreshSubscriptionsByTitle(Activity ctx, String title) {
        if (TextUtils.isEmpty(title)) {
            return;
        }
        List<String> urls = getSubscriptions();
        if (urls.isEmpty()) {
            showToast(LocaleController.getString(R.string.ProxySubscriptionEmpty));
            return;
        }
        String defaultTitle = LocaleController.getString(R.string.ProxyCategorySubscriptions);
        ArrayList<String> targetUrls = new ArrayList<>();
        for (String url : urls) {
            if (TextUtils.equals(getSubscriptionTitle(url), title)) {
                targetUrls.add(url);
            }
        }
        if (targetUrls.isEmpty()) {
            if (TextUtils.equals(title, defaultTitle)) {
                refreshSubscriptions(ctx);
            } else {
                showToast(LocaleController.getString(R.string.BrokenLink));
            }
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: refresh group=" + title + " count=" + targetUrls.size());
        }
        Utilities.globalQueue.postRunnable(() -> {
            final boolean[] any = new boolean[]{false};
            final ArrayList<SharedConfig.ProxyInfo> newProxies = new ArrayList<>();
            for (String url : targetUrls) {
                String normalizedUrl = normalizeSubscriptionUrl(url);
                String subscriptionName = getSubscriptionTitle(normalizedUrl);
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                    applyHwidHeaders(conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder buffer = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buffer.append(line).append('\n');
                        }
                        String text = buffer.toString();
                        ParseResult result = parseProxyText(text);
                        if (!result.proxies.isEmpty()) {
                            for (SharedConfig.ProxyInfo info : result.proxies) {
                                info.subscriptionName = subscriptionName;
                                newProxies.add(info);
                            }
                            any[0] = true;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("proxy_sub: refresh group url=" + normalizedUrl + " bytes=" + text.length() + " proxies=" + result.proxies.size());
                        }
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (any[0]) {
                    for (SharedConfig.ProxyInfo info : SharedConfig.getProxyList()) {
                        if (!info.isSubscription) {
                            continue;
                        }
                        String groupTitle = TextUtils.isEmpty(info.subscriptionName) ? defaultTitle : info.subscriptionName;
                        if (TextUtils.equals(groupTitle, title)) {
                            SharedConfig.deleteProxy(info);
                        }
                    }
                    for (SharedConfig.ProxyInfo info : newProxies) {
                        SharedConfig.addProxy(info, true);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    showToast(LocaleController.getString(R.string.ProxySubscriptionUpdated));
                } else {
                    showToast(LocaleController.getString(R.string.BrokenLink));
                }
            });
        });
    }

    private static ParseResult parseProxyText(String text) {
        ArrayList<SharedConfig.ProxyInfo> proxies = new ArrayList<>();
        final boolean[] error = new boolean[]{false};

        java.util.function.Consumer<String> handleLine = (line) -> {
            if (TextUtils.isEmpty(line)) {
                return;
            }
            if (line.startsWith("tg://proxy") ||
                    line.startsWith("tg://socks") ||
                    line.startsWith("https://t.me/proxy") ||
                    line.startsWith("https://t.me/socks") ||
                    line.startsWith("vless://")) {
                try {
                    proxies.add(SharedConfig.ProxyInfo.fromUrl(line));
                } catch (Throwable e) {
                    error[0] = true;
                    AndroidUtilities.runOnUIThread(() -> showToast(LocaleController.getString(R.string.BrokenLink) + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                }
            }
        };

        if (text != null) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                String[] lines = trimmed.split("\n");
                for (String line : lines) {
                    String[] parts = line.split(" ");
                    for (String part : parts) {
                        handleLine.accept(part);
                    }
                }
            }
        }

        if (text != null) {
            if (text.trim().startsWith("[") || text.trim().startsWith("{") || text.contains("\"outbounds\"") || text.contains("\"vless\"")) {
                proxies.addAll(parseXrayJson(text));
            }
        }

        if (proxies.isEmpty() && !error[0] && !TextUtils.isEmpty(text)) {
            try {
                String decoded = new String(Base64.decode(text, Base64.NO_PADDING));
                String trimmed = decoded.trim();
                if (!trimmed.isEmpty()) {
                    String[] lines = trimmed.split("\n");
                    for (String line : lines) {
                        String[] parts = line.split(" ");
                        for (String part : parts) {
                            handleLine.accept(part);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: parse result proxies=" + proxies.size() + " error=" + error[0]);
        }
        return new ParseResult(proxies, error[0]);
    }

    private static ArrayList<SharedConfig.ProxyInfo> parseXrayJson(String text) {
        ArrayList<SharedConfig.ProxyInfo> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return result;
        }
        String json = extractJsonPayload(text);
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj != null) {
                        SharedConfig.ProxyInfo info = parseXrayConfig(obj);
                        if (info != null) {
                            result.add(info);
                        }
                    }
                }
            } else if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                SharedConfig.ProxyInfo info = parseXrayConfig(obj);
                if (info != null) {
                    result.add(info);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String extractJsonPayload(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return trimmed;
        }
        int arrayIdx = trimmed.indexOf('[');
        int objIdx = trimmed.indexOf('{');
        int idx = -1;
        if (arrayIdx >= 0 && objIdx >= 0) {
            idx = Math.min(arrayIdx, objIdx);
        } else if (arrayIdx >= 0) {
            idx = arrayIdx;
        } else if (objIdx >= 0) {
            idx = objIdx;
        }
        if (idx >= 0 && idx < trimmed.length()) {
            return trimmed.substring(idx);
        }
        return "";
    }

    private static SharedConfig.ProxyInfo parseXrayConfig(JSONObject config) {
        if (config == null) {
            return null;
        }
        JSONArray outbounds = config.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            return null;
        }
        JSONObject outbound = null;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject candidate = outbounds.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            String protocol = candidate.optString("protocol", "");
            if ("vless".equalsIgnoreCase(protocol)) {
                if ("proxy".equalsIgnoreCase(candidate.optString("tag", ""))) {
                    outbound = candidate;
                    break;
                }
                if (outbound == null) {
                    outbound = candidate;
                }
            }
        }
        if (outbound == null) {
            return null;
        }
        JSONObject settings = outbound.optJSONObject("settings");
        JSONArray vnext = settings != null ? settings.optJSONArray("vnext") : null;
        JSONObject server = vnext != null && vnext.length() > 0 ? vnext.optJSONObject(0) : null;
        if (server == null) {
            return null;
        }
        String address = server.optString("address", "");
        int port = server.optInt("port", 0);
        JSONArray users = server.optJSONArray("users");
        JSONObject user = users != null && users.length() > 0 ? users.optJSONObject(0) : null;
        if (TextUtils.isEmpty(address) || port <= 0 || user == null) {
            return null;
        }
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(address, port, "", "", "");
        info.proxyType = SharedConfig.ProxyInfo.PROXY_TYPE_XRAY_VLESS;
        info.vlessId = user.optString("id", "");
        info.vlessEncryption = user.optString("encryption", "none");
        info.vlessFlow = user.optString("flow", "");
        String remarks = config.optString("remarks", "");
        if (!TextUtils.isEmpty(remarks)) {
            info.vlessRemark = remarks;
        }

        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream != null) {
            info.vlessType = stream.optString("network", "");
            info.vlessSecurity = stream.optString("security", "");

            JSONObject tls = stream.optJSONObject("tlsSettings");
            if (tls != null) {
                info.vlessSni = tls.optString("serverName", info.vlessSni);
                info.vlessFp = tls.optString("fingerprint", info.vlessFp);
                JSONArray alpn = tls.optJSONArray("alpn");
                if (alpn != null && alpn.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < alpn.length(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(alpn.optString(i));
                    }
                    info.vlessAlpn = sb.toString();
                }
                info.vlessAllowInsecure = tls.optBoolean("allowInsecure", info.vlessAllowInsecure);
            }

            JSONObject reality = stream.optJSONObject("realitySettings");
            if (reality != null) {
                info.vlessSni = reality.optString("serverName", info.vlessSni);
                info.vlessFp = reality.optString("fingerprint", info.vlessFp);
                info.vlessPublicKey = reality.optString("publicKey", info.vlessPublicKey);
                info.vlessShortId = reality.optString("shortId", info.vlessShortId);
                info.vlessSpiderX = reality.optString("spiderX", info.vlessSpiderX);
            }

            JSONObject ws = stream.optJSONObject("wsSettings");
            if (ws != null) {
                info.vlessPath = ws.optString("path", info.vlessPath);
                JSONObject headers = ws.optJSONObject("headers");
                if (headers != null) {
                    info.vlessHost = headers.optString("Host", info.vlessHost);
                }
            }

            JSONObject grpc = stream.optJSONObject("grpcSettings");
            if (grpc != null) {
                info.vlessServiceName = grpc.optString("serviceName", info.vlessServiceName);
                if (grpc.optBoolean("multiMode", false)) {
                    info.vlessMode = "multi";
                }
            }

            JSONObject http = stream.optJSONObject("httpSettings");
            if (http != null) {
                info.vlessPath = http.optString("path", info.vlessPath);
                JSONArray host = http.optJSONArray("host");
                if (host != null && host.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < host.length(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(host.optString(i));
                    }
                    info.vlessHost = sb.toString();
                }
            }

            JSONObject kcp = stream.optJSONObject("kcpSettings");
            if (kcp != null) {
                info.vlessSeed = kcp.optString("seed", info.vlessSeed);
                JSONObject header = kcp.optJSONObject("header");
                if (header != null) {
                    info.vlessHeaderType = header.optString("type", info.vlessHeaderType);
                }
            }

            JSONObject quic = stream.optJSONObject("quicSettings");
            if (quic != null) {
                info.vlessQuicSecurity = quic.optString("security", info.vlessQuicSecurity);
                info.vlessQuicKey = quic.optString("key", info.vlessQuicKey);
                JSONObject header = quic.optJSONObject("header");
                if (header != null) {
                    info.vlessHeaderType = header.optString("type", info.vlessHeaderType);
                }
            }
        }
        return info;
    }

    private static List<String> getSubscriptions() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        String value = prefs.getString("proxy_subscriptions", "");
        if (value == null) {
            return new ArrayList<>();
        }
        String[] lines = value.split("\n");
        Set<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(normalizeSubscriptionUrl(trimmed));
            }
        }
        return new ArrayList<>(result);
    }

    private static void addSubscription(String url) {
        String normalizedUrl = normalizeSubscriptionUrl(url);
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        List<String> current = getSubscriptions();
        if (!current.contains(normalizedUrl)) {
            current.add(normalizedUrl);
            prefs.edit().putString("proxy_subscriptions", TextUtils.join("\n", current)).apply();
        }
    }

    private static String normalizeSubscriptionUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String trimmed = url.trim();
        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    private static String getSubscriptionTitle(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String normalized = normalizeSubscriptionUrl(url);
        Uri uri = Uri.parse(normalized);
        String host = uri != null ? uri.getHost() : null;
        if (TextUtils.isEmpty(host)) {
            return normalized;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }

    public static boolean isIpv6Address(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String addr = value;
        if (addr.startsWith("[") && addr.contains("]")) {
            int end = addr.lastIndexOf("]");
            addr = addr.substring(1, end);
        }
        return IPV6_PATTERN.matcher(addr).matches();
    }

    public static boolean removeSubscriptionsByTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return false;
        }
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        List<String> current = getSubscriptions();
        if (current.isEmpty()) {
            return false;
        }
        String defaultTitle = LocaleController.getString(R.string.ProxyCategorySubscriptions);
        if (TextUtils.equals(title, defaultTitle)) {
            prefs.edit().putString("proxy_subscriptions", "").apply();
            return true;
        }
        boolean changed = false;
        ArrayList<String> keep = new ArrayList<>();
        for (String url : current) {
            String name = getSubscriptionTitle(url);
            if (TextUtils.equals(name, title)) {
                changed = true;
                continue;
            }
            keep.add(url);
        }
        if (changed) {
            prefs.edit().putString("proxy_subscriptions", TextUtils.join("\n", keep)).apply();
        }
        return changed;
    }

    public static boolean isHwidModeEnabled() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_HWID_ENABLED, false);
    }

    public static void setHwidModeEnabled(boolean enabled) {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        if (enabled) {
            String hwid = prefs.getString(PREF_HWID_VALUE, "");
            if (TextUtils.isEmpty(hwid)) {
                hwid = generateHwid();
                prefs.edit().putString(PREF_HWID_VALUE, hwid).putBoolean(PREF_HWID_ENABLED, true).apply();
            } else {
                prefs.edit().putBoolean(PREF_HWID_ENABLED, true).apply();
            }
        } else {
            prefs.edit().putBoolean(PREF_HWID_ENABLED, false).apply();
        }
    }

    private static void applyHwidHeaders(HttpURLConnection conn) {
        if (conn == null || !isHwidModeEnabled()) {
            return;
        }
        String locale = getDeviceLocale();
        String hwid = getOrCreateHwid();
        String osVersion = getOsVersion();
        String model = getDeviceModel();
        conn.setRequestProperty("user-agent", HWID_USER_AGENT);
        conn.setRequestProperty("x-device-locale", locale);
        conn.setRequestProperty("x-hwid", hwid);
        conn.setRequestProperty("x-device-os", "Android");
        conn.setRequestProperty("x-ver-os", osVersion);
        conn.setRequestProperty("x-device-model", model);
    }

    private static String getOrCreateHwid() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        String hwid = prefs.getString(PREF_HWID_VALUE, "");
        if (TextUtils.isEmpty(hwid)) {
            hwid = generateHwid();
            prefs.edit().putString(PREF_HWID_VALUE, hwid).apply();
        }
        return hwid;
    }

    private static String generateHwid() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return Utilities.bytesToHex(bytes).toLowerCase(Locale.US);
    }

    private static String getDeviceLocale() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        String language = locale != null ? locale.getLanguage() : null;
        return TextUtils.isEmpty(language) ? "en" : language;
    }

    private static String getOsVersion() {
        String version = Build.VERSION.RELEASE;
        if (TextUtils.isEmpty(version)) {
            version = String.valueOf(Build.VERSION.SDK_INT);
        }
        return version;
    }

    private static String getDeviceModel() {
        String model = Build.MODEL;
        if (TextUtils.isEmpty(model)) {
            model = Build.DEVICE;
        }
        return TextUtils.isEmpty(model) ? "Android" : model;
    }

}
