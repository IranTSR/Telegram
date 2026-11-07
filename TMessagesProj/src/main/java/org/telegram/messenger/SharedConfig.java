/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.WebView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.json.JSONObject;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class SharedConfig {
    /**
     * V2: Ping and check time serialized
     */
    private final static int PROXY_SCHEMA_V2 = 2;
    private final static int PROXY_SCHEMA_V3 = 3;
    private final static int PROXY_SCHEMA_V4 = 4;
    private final static int PROXY_SCHEMA_V5 = 5;
    private final static int PROXY_SCHEMA_V6 = 6;
    private final static int PROXY_SCHEMA_V7 = 7;
    private final static int PROXY_CURRENT_SCHEMA_VERSION = PROXY_SCHEMA_V7;

    public final static int PASSCODE_TYPE_PIN = 0,
            PASSCODE_TYPE_PASSWORD = 1;
    private static int legacyDevicePerformanceClass = -1;

    public static boolean loopStickers() {
        return LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_STICKERS_CHAT);
    }

    public static boolean readOnlyStorageDirAlertShowed;

    public static void checkSdCard(File file) {
        if (file == null || SharedConfig.storageCacheDir == null || readOnlyStorageDirAlertShowed) {
            return;
        }
        if (file.getPath().startsWith(SharedConfig.storageCacheDir)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (readOnlyStorageDirAlertShowed) {
                    return;
                }
                BaseFragment fragment = LaunchActivity.getLastFragment();
                if (fragment != null && fragment.getParentActivity() != null) {
                    SharedConfig.storageCacheDir = null;
                    SharedConfig.saveConfig();
                    ImageLoader.getInstance().checkMediaPaths(() -> {

                    });

                    readOnlyStorageDirAlertShowed = true;
                    AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity());
                    dialog.setTitle(LocaleController.getString(R.string.SdCardError));
                    dialog.setSubtitle(LocaleController.getString(R.string.SdCardErrorDescription));
                    dialog.setPositiveButton(LocaleController.getString(R.string.DoNotUseSDCard), (dialog1, which) -> {

                    });
                    Dialog dialogFinal = dialog.create();
                    dialogFinal.setCanceledOnTouchOutside(false);
                    dialogFinal.show();
                }
            });
        }
    }

    static Boolean allowPreparingHevcPlayers;

    public static boolean allowPreparingHevcPlayers() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return false;
        }
        if (allowPreparingHevcPlayers == null) {
            int codecCount = MediaCodecList.getCodecCount();
            int maxInstances = 0;
            int capabilities = 0;

            for (int i = 0; i < codecCount; i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (codecInfo.isEncoder()) {
                    continue;
                }

                boolean found = false;
                for (int k = 0; k < codecInfo.getSupportedTypes().length; k++) {
                    if (codecInfo.getSupportedTypes()[k].contains("video/hevc")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    continue;
                }
                capabilities = codecInfo.getCapabilitiesForType("video/hevc").getMaxSupportedInstances();
                if (capabilities > maxInstances) {
                    maxInstances = capabilities;
                }
            }
            allowPreparingHevcPlayers = maxInstances >= 8;
        }
        return allowPreparingHevcPlayers;
    }

    public static void togglePaymentByInvoice() {
        payByInvoice = !payByInvoice;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("payByInvoice", payByInvoice)
                .apply();
    }

    public static void toggleSurfaceInStories() {
        useSurfaceInStories = !useSurfaceInStories;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useSurfaceInStories", useSurfaceInStories)
                .apply();
    }

    public static void togglePhotoViewerBlur() {
        photoViewerBlur = !photoViewerBlur;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("photoViewerBlur", photoViewerBlur)
                .apply();
    }

    private static String goodHevcEncoder;
    private static HashSet<String> hevcEncoderWhitelist = new HashSet<>();
    static {
        hevcEncoderWhitelist.add("c2.exynos.hevc.encoder");
        hevcEncoderWhitelist.add("OMX.Exynos.HEVC.Encoder".toLowerCase());
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static String findGoodHevcEncoder() {
        if (goodHevcEncoder == null) {
            int codecCount = MediaCodecList.getCodecCount();
            for (int i = 0; i < codecCount; i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (!codecInfo.isEncoder()) {
                    continue;
                }

                for (int k = 0; k < codecInfo.getSupportedTypes().length; k++) {
                    if (codecInfo.getSupportedTypes()[k].contains("video/hevc") && codecInfo.isHardwareAccelerated() && isWhitelisted(codecInfo)) {
                        return goodHevcEncoder = codecInfo.getName();
                    }
                }
            }
            goodHevcEncoder = "";
        }
        return TextUtils.isEmpty(goodHevcEncoder) ? null : goodHevcEncoder;
    }

    private static boolean isWhitelisted(MediaCodecInfo codecInfo) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            return true;
        }
        return hevcEncoderWhitelist.contains(codecInfo.getName().toLowerCase());
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PASSCODE_TYPE_PIN,
            PASSCODE_TYPE_PASSWORD
    })
    public @interface PasscodeType {}

    public final static int SAVE_TO_GALLERY_FLAG_PEER = 1;
    public final static int SAVE_TO_GALLERY_FLAG_GROUP = 2;
    public final static int SAVE_TO_GALLERY_FLAG_CHANNELS = 4;

    @PushListenerController.PushType
    public static int pushType = PushListenerController.PUSH_TYPE_FIREBASE;
    public static String pushString = "";
    public static String pushStringStatus = "";
    public static long pushStringGetTimeStart;
    public static long pushStringGetTimeEnd;
    public static boolean pushStatSent;
    public static byte[] pushAuthKey;
    public static byte[] pushAuthKeyId;
    public static boolean forceForumTabs;
    public static boolean fastWallpaperDisabled;
    public static boolean frameMetricsEnabled;

    public static String directShareHash;

    @PasscodeType
    public static int passcodeType;
    public static String passcodeHash = "";
    public static long passcodeRetryInMs;
    public static long lastUptimeMillis;
    public static int badPasscodeTries;
    public static byte[] passcodeSalt = new byte[0];
    public static boolean appLocked;
    public static int autoLockIn = 60 * 60;

    public static boolean saveIncomingPhotos;
    public static boolean allowScreenCapture;
    public static int lastPauseTime;
    public static boolean isWaitingForPasscodeEnter;
    public static boolean useFingerprintLock = true;
    public static boolean useFaceLock = true;
    public static int suggestStickers;
    public static boolean suggestAnimatedEmoji;
    public static int keepMedia = CacheByChatsController.KEEP_MEDIA_ONE_MONTH; //deprecated
    public static int lastKeepMediaCheckTime;
    public static int lastLogsCheckTime;
    public static int textSelectionHintShows;
    public static int scheduledOrNoSoundHintShows;
    public static long scheduledOrNoSoundHintSeenAt;
    public static int scheduledHintShows;
    public static long scheduledHintSeenAt;
    public static int lockRecordAudioVideoHint;
    public static boolean forwardingOptionsHintShown, replyingOptionsHintShown;
    public static boolean searchMessagesAsListUsed;
    public static boolean stickersReorderingHintUsed;
    public static int dayNightWallpaperSwitchHint;
    public static boolean storyReactionsLongPressHint;
    public static boolean storiesIntroShown;
    public static boolean disableVoiceAudioEffects;
    public static boolean forceDisableTabletMode;
    public static boolean updateStickersOrderOnSend = true;
    public static boolean bigCameraForRound;
    public static Boolean useCamera2Force;
    public static boolean useNewBlur;
    public static boolean useSurfaceInStories;
    public static boolean photoViewerBlur = true;
    public static boolean payByInvoice;
    public static int stealthModeSendMessageConfirm = 2;
    private static int lastLocalId = -210000;
    public static boolean walletUseFingerprintLock = true;

    public static String storageCacheDir;

    private static String passportConfigJson = "";
    private static HashMap<String, String> passportConfigMap;
    public static int passportConfigHash;

    private static boolean configLoaded;
    private static final Object sync = new Object();
    private static final Object localIdSync = new Object();

//    public static int saveToGalleryFlags;
    public static int mapPreviewType = 2;
    public static int searchEngineType = 0;
    public static String searchEngineCustomURLQuery, searchEngineCustomURLAutocomplete;
    public static boolean chatBubbles = Build.VERSION.SDK_INT >= 30;
    public static boolean raiseToSpeak = false;
    public static boolean raiseToListen = true;
    public static boolean nextMediaTap = true;
    public static boolean recordViaSco = false;
    public static boolean adaptableColorInBrowser = true;
    public static boolean onlyLocalInstantView = false;
    public static boolean directShare = true;
    public static boolean inappCamera = true;
    public static boolean roundCamera16to9 = true;
    public static boolean noSoundHintShowed = false;
    public static boolean streamMedia = true;
    public static boolean streamAllVideo = false;
    public static boolean streamMkv = false;
    public static boolean saveStreamMedia = true;
    public static boolean pauseMusicOnRecord = false;
    public static boolean pauseMusicOnMedia = false;
    public static boolean noiseSupression;
    public static boolean debugWebView;
    public static boolean sortContactsByName;
    public static boolean sortFilesByName;
    public static boolean shuffleMusic;
    public static boolean playOrderReversed;
    public static boolean hasCameraCache;
    public static boolean showNotificationsForAllAccounts = true;
    public static boolean debugVideoQualities = false;
    public static int repeatMode;
    public static boolean allowBigEmoji;
    public static boolean useSystemEmoji;
    public static boolean useSystemBoldFont;
    public static int fontSize = 16;
    public static boolean fontSizeIsDefault;
    public static int bubbleRadius = 17;
    public static int ivFontSize = 16;
    public static boolean proxyRotationEnabled;
    public static int proxyRotationTimeout;
    public static int messageSeenHintCount;
    public static int emojiInteractionsHintCount;
    public static int dayNightThemeSwitchHintCount;
    public static int callEncryptionHintDisplayedCount;
    public static boolean shadowsInSections;
    public static boolean debugViewMetrics;
    public static boolean photoHighQualityDefault;
    public static boolean photoLiveDefault;

    public static TLRPC.TL_help_appUpdate pendingAppUpdate;
    public static int pendingAppUpdateBuildVersion;
    public static long lastUpdateCheckTime;

    public static boolean hasEmailLogin;

    @PerformanceClass
    private static int devicePerformanceClass;
    @PerformanceClass
    private static int overrideDevicePerformanceClass;

    public static boolean drawDialogIcons;
    public static boolean useThreeLinesLayout;
    public static boolean archiveHidden;

    private static int chatSwipeAction;

    public static int distanceSystemType;
    public static int mediaColumnsCount = 3;
    public static int storiesColumnsCount = 3;
    public static int fastScrollHintCount = 3;
    public static boolean dontAskManageStorage;
    public static boolean multipleReactionsPromoShowed;

    public static boolean isFloatingDebugActive;
    public static LiteMode liteMode;

    private static final int[] LOW_SOC = {
            -1775228513, // EXYNOS 850
            802464304,  // EXYNOS 7872
            802464333,  // EXYNOS 7880
            802464302,  // EXYNOS 7870
            2067362118, // MSM8953
            2067362060, // MSM8937
            2067362084, // MSM8940
            2067362241, // MSM8992
            2067362117, // MSM8952
            2067361998, // MSM8917
            -1853602818 // SDM439
    };

    static {
        loadConfig();
    }

    public static class ProxyInfo {
        public @NonNull ProxySettings settings;
        public long ping;
        public boolean checking;
        public boolean available;
        public long availableCheckTime;

        // Xray VLESS fields (used when settings.getType() == ProxySettings.Type.XRAY_VLESS)
        public String vlessId;
        public String vlessEncryption;
        public String vlessFlow;
        public String vlessSecurity;
        public String vlessType;
        public String vlessSni;
        public String vlessHost;
        public String vlessPath;
        public String vlessServiceName;
        public String vlessFp;
        public String vlessAlpn;
        public String vlessPublicKey;
        public String vlessShortId;
        public String vlessSpiderX;
        public String vlessHeaderType;
        public String vlessSeed;
        public String vlessQuicSecurity;
        public String vlessQuicKey;
        public String vlessMode;
        public boolean vlessAllowInsecure;
        public String vlessRemark;
        public boolean isSubscription;
        public String subscriptionName = "";
        public String proxyName = "";
        public String vlessRawQuery;
        public String vlessAdvancedJson;

        // Xray Shadowsocks fields (schema V7, settings.getType() == ProxySettings.Type.XRAY_SHADOWSOCKS)
        public String ssPassword;
        public String ssMethod;
        // Xray VMess fields (schema V7). Transport/TLS reuse the vless* fields
        // (vlessType, vlessSecurity, vlessSni, vlessFp, vlessAlpn, vlessHost,
        // vlessPath, vlessServiceName, vlessHeaderType, vlessMode).
        public String vmessId;
        public String vmessAlterId;
        public String vmessSecurity;
        // Xray Trojan fields (schema V7). Fingerprint/ALPN/transport reuse vlessFp,
        // vlessAlpn, vlessType, vlessHost, vlessPath, vlessServiceName.
        public String trojanPassword;
        public String trojanSni;
        public boolean trojanAllowInsecure;
        // Xray WireGuard fields (schema V7). settings.address/port hold the peer
        // endpoint host/port; wgEndpoint mirrors them as "host:port".
        public String wgPrivateKey;
        public String wgPublicKey;
        public String wgEndpoint;
        public String wgAllowedIps;
        public String wgLocalAddress;
        public String wgMtu;
        public String wgKeepAlive;
        public String wgPresharedKey;
        public String wgDns;
        // Xray Hysteria2 fields (schema V7).
        public String hyPassword;
        public String hySni;
        public String hyAlpn;
        public boolean hyAllowInsecure;
        public String hyObfsPassword;
        public String hyUpMbps;
        public String hyDownMbps;

        // Aether fields (used when settings.getType() == ProxySettings.Type.AETHER)
        public String aetherProtocol;
        public String aetherScan;
        public String aetherIp;
        public String aetherTransport;
        public boolean aetherFragment;
        public String aetherNoize;
        public boolean aetherQuickReconnect = true;
        public String aetherPeers;
        // Aether v2.4.0 fields (schema V6, appended after aetherPeers).
        public String aetherApiAddress;
        public String aetherApiHost;
        public String aetherApiSni;
        public String aetherMasqueSni;
        public String aetherTls13Ciphers;
        public String aetherTlsKeyShares;
        public String aetherTlsSigalgs;
        public String aetherTlsCertCompression;
        public String aetherTlsAlps;
        public boolean aetherGreaseSigalgs;

        public ProxyInfo(@NonNull ProxySettings proxySettings) {
            settings = proxySettings;
            normalizeVlessFields();
            normalizeXrayProtocolsFields();
            normalizeAetherFields();
        }

        public void normalizeXrayProtocolsFields() {
            if (this.ssPassword == null) {
                this.ssPassword = "";
            }
            if (this.ssMethod == null || this.ssMethod.isEmpty()) {
                this.ssMethod = "aes-256-gcm";
            }
            if (this.vmessId == null) {
                this.vmessId = "";
            }
            if (this.vmessAlterId == null || this.vmessAlterId.isEmpty()) {
                this.vmessAlterId = "0";
            }
            if (this.vmessSecurity == null || this.vmessSecurity.isEmpty()) {
                this.vmessSecurity = "auto";
            }
            if (this.trojanPassword == null) {
                this.trojanPassword = "";
            }
            if (this.trojanSni == null) {
                this.trojanSni = "";
            }
            if (this.wgPrivateKey == null) {
                this.wgPrivateKey = "";
            }
            if (this.wgPublicKey == null) {
                this.wgPublicKey = "";
            }
            if (this.wgEndpoint == null) {
                this.wgEndpoint = "";
            }
            if (this.wgAllowedIps == null || this.wgAllowedIps.isEmpty()) {
                this.wgAllowedIps = "0.0.0.0/0, ::/0";
            }
            if (this.wgLocalAddress == null || this.wgLocalAddress.isEmpty()) {
                this.wgLocalAddress = "10.0.0.2/32";
            }
            if (this.wgMtu == null || this.wgMtu.isEmpty()) {
                this.wgMtu = "1420";
            }
            if (this.wgKeepAlive == null || this.wgKeepAlive.isEmpty()) {
                this.wgKeepAlive = "25";
            }
            if (this.wgPresharedKey == null) {
                this.wgPresharedKey = "";
            }
            if (this.wgDns == null) {
                this.wgDns = "";
            }
            if (this.hyPassword == null) {
                this.hyPassword = "";
            }
            if (this.hySni == null) {
                this.hySni = "";
            }
            if (this.hyAlpn == null || this.hyAlpn.isEmpty()) {
                this.hyAlpn = "h3";
            }
            if (this.hyObfsPassword == null) {
                this.hyObfsPassword = "";
            }
            if (this.hyUpMbps == null) {
                this.hyUpMbps = "";
            }
            if (this.hyDownMbps == null) {
                this.hyDownMbps = "";
            }
        }

        public void normalizeVlessFields() {
            if (this.vlessId == null) {
                this.vlessId = "";
            }
            if (this.vlessFlow == null) {
                this.vlessFlow = "";
            }
            if (this.vlessSecurity == null) {
                this.vlessSecurity = "";
            }
            if (this.vlessType == null) {
                this.vlessType = "";
            }
            if (this.vlessSni == null) {
                this.vlessSni = "";
            }
            if (this.vlessHost == null) {
                this.vlessHost = "";
            }
            if (this.vlessPath == null) {
                this.vlessPath = "";
            }
            if (this.vlessServiceName == null) {
                this.vlessServiceName = "";
            }
            if (this.vlessFp == null) {
                this.vlessFp = "";
            }
            if (this.vlessAlpn == null) {
                this.vlessAlpn = "";
            }
            if (this.vlessPublicKey == null) {
                this.vlessPublicKey = "";
            }
            if (this.vlessShortId == null) {
                this.vlessShortId = "";
            }
            if (this.vlessSpiderX == null) {
                this.vlessSpiderX = "";
            }
            if (this.vlessHeaderType == null) {
                this.vlessHeaderType = "";
            }
            if (this.vlessSeed == null) {
                this.vlessSeed = "";
            }
            if (this.vlessQuicSecurity == null) {
                this.vlessQuicSecurity = "";
            }
            if (this.vlessQuicKey == null) {
                this.vlessQuicKey = "";
            }
            if (this.vlessMode == null) {
                this.vlessMode = "";
            }
            if (this.vlessRemark == null) {
                this.vlessRemark = "";
            }
            if (this.vlessRawQuery == null) {
                this.vlessRawQuery = "";
            }
            if (this.vlessAdvancedJson == null) {
                this.vlessAdvancedJson = "";
            }
            if (this.vlessEncryption == null) {
                this.vlessEncryption = "none";
            }
        }

        public void normalizeAetherFields() {
            if (this.aetherProtocol == null) {
                this.aetherProtocol = "masque";
            }
            if (this.aetherScan == null) {
                this.aetherScan = "turbo";
            }
            if (this.aetherIp == null) {
                this.aetherIp = "v4";
            }
            if (this.aetherTransport == null) {
                this.aetherTransport = "";
            }
            if (this.aetherNoize == null) {
                this.aetherNoize = "firewall";
            }
            if (this.aetherPeers == null) {
                this.aetherPeers = "";
            }
            if (this.aetherApiAddress == null) {
                this.aetherApiAddress = "";
            }
            if (this.aetherApiHost == null) {
                this.aetherApiHost = "";
            }
            if (this.aetherApiSni == null) {
                this.aetherApiSni = "";
            }
            if (this.aetherMasqueSni == null) {
                this.aetherMasqueSni = "";
            }
            if (this.aetherTls13Ciphers == null) {
                this.aetherTls13Ciphers = "";
            }
            if (this.aetherTlsKeyShares == null) {
                this.aetherTlsKeyShares = "";
            }
            if (this.aetherTlsSigalgs == null) {
                this.aetherTlsSigalgs = "";
            }
            if (this.aetherTlsCertCompression == null) {
                this.aetherTlsCertCompression = "";
            }
            if (this.aetherTlsAlps == null) {
                this.aetherTlsAlps = "";
            }
        }

        public boolean isXrayVless() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_VLESS;
        }

        /** True when the proxy is routed through the local Xray SOCKS inbound. */
        public boolean isXray() {
            return settings != null && ProxySettings.isXrayType(settings.getType());
        }

        public boolean isXrayShadowsocks() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_SHADOWSOCKS;
        }

        public boolean isXrayVmess() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_VMESS;
        }

        public boolean isXrayTrojan() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_TROJAN;
        }

        public boolean isXrayWireguard() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_WIREGUARD;
        }

        public boolean isXrayHysteria2() {
            return settings != null && settings.getType() == ProxySettings.Type.XRAY_HYSTERIA2;
        }

        /** Short label used in the proxy list, e.g. "VLESS", "Shadowsocks". */
        public String getXrayProtocolLabel() {
            if (settings == null) {
                return "";
            }
            switch (settings.getType()) {
                case XRAY_VLESS:
                    return "VLESS";
                case XRAY_SHADOWSOCKS:
                    return "Shadowsocks";
                case XRAY_VMESS:
                    return "VMess";
                case XRAY_TROJAN:
                    return "Trojan";
                case XRAY_WIREGUARD:
                    return "WireGuard";
                case XRAY_HYSTERIA2:
                    return "Hysteria2";
                default:
                    return "";
            }
        }

        public boolean isAether() {
            return settings != null && settings.getType() == ProxySettings.Type.AETHER;
        }

        public String getVlessLink() {
            StringBuilder url = new StringBuilder("vless://");
            try {
                url.append(URLEncoder.encode(vlessId != null ? vlessId : "", "UTF-8"));
                url.append("@").append(URLEncoder.encode(settings.getAddress(), "UTF-8"));
                url.append(":").append(settings.getPort());
                ArrayList<String> params = new ArrayList<>();
                appendParam(params, "type", vlessType);
                appendParam(params, "security", vlessSecurity);
                appendParam(params, "encryption", vlessEncryption);
                appendParam(params, "flow", vlessFlow);
                appendParam(params, "sni", vlessSni);
                appendParam(params, "fp", vlessFp);
                appendParam(params, "alpn", vlessAlpn);
                appendParam(params, "host", vlessHost);
                appendParam(params, "path", vlessPath);
                appendParam(params, "serviceName", vlessServiceName);
                appendParam(params, "mode", vlessMode);
                appendParam(params, "headerType", vlessHeaderType);
                appendParam(params, "seed", vlessSeed);
                appendParam(params, "quicSecurity", vlessQuicSecurity);
                appendParam(params, "key", vlessQuicKey);
                appendParam(params, "pbk", vlessPublicKey);
                appendParam(params, "sid", vlessShortId);
                appendParam(params, "spx", vlessSpiderX);
                if (vlessAllowInsecure) {
                    appendParam(params, "allowInsecure", "1");
                }
                if (!params.isEmpty()) {
                    url.append("?").append(TextUtils.join("&", params));
                }
                if (!TextUtils.isEmpty(vlessRemark)) {
                    url.append("#").append(URLEncoder.encode(vlessRemark, "UTF-8"));
                }
            } catch (UnsupportedEncodingException ignored) {}
            return url.toString();
        }

        private static void appendParam(ArrayList<String> params, String key, String value) throws UnsupportedEncodingException {
            if (TextUtils.isEmpty(value)) {
                return;
            }
            params.add(URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8"));
        }

        public static ProxyInfo fromUrl(String url) {
            if (url == null) {
                throw new IllegalArgumentException("null url");
            }
            String lower = url.toLowerCase(Locale.US);
            if (lower.startsWith("vless://")) {
                return fromVlessUrl(url);
            }
            if (lower.startsWith("ss://")) {
                return fromShadowsocksUrl(url);
            }
            if (lower.startsWith("vmess://")) {
                return fromVmessUrl(url);
            }
            if (lower.startsWith("trojan://")) {
                return fromTrojanUrl(url);
            }
            if (lower.startsWith("hy2://") || lower.startsWith("hysteria2://")) {
                return fromHysteria2Url(url);
            }
            android.net.Uri lnk = android.net.Uri.parse(url);
            if (lnk == null) throw new IllegalArgumentException(url);
            ProxySettings settings = ProxySettings.fromUri(lnk);
            if (settings == null) throw new IllegalArgumentException(url);
            ProxyInfo info = new ProxyInfo(settings);
            String fragment = lnk.getFragment();
            if (!TextUtils.isEmpty(fragment)) {
                info.proxyName = fragment;
            }
            return info;
        }

        public static ProxyInfo fromVlessUrl(String url) {            try {
                java.net.URI uri = new java.net.URI(url);
                int port = uri.getPort() > 0 ? uri.getPort() : 443;
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_VLESS)
                        .setAddress(uri.getHost() != null ? uri.getHost() : "")
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                info.vlessId = uri.getUserInfo();
                String rawQuery = uri.getRawQuery();
                info.vlessRawQuery = rawQuery != null ? rawQuery : "";
                info.vlessRemark = uri.getFragment() != null ? URLDecoder.decode(uri.getFragment(), "UTF-8") : "";
                if (!TextUtils.isEmpty(rawQuery)) {
                    String[] parts = rawQuery.split("&");
                    for (String part : parts) {
                        String[] pair = part.split("=", 2);
                        if (pair.length != 2) {
                            continue;
                        }
                        String key = URLDecoder.decode(pair[0], "UTF-8");
                        String value = URLDecoder.decode(pair[1], "UTF-8");
                        if (key == null) {
                            continue;
                        }
                        switch (key.toLowerCase()) {
                            case "encryption":
                                info.vlessEncryption = value;
                                break;
                            case "flow":
                                info.vlessFlow = value;
                                break;
                            case "security":
                                info.vlessSecurity = value;
                                break;
                            case "type":
                                info.vlessType = value;
                                break;
                            case "sni":
                                info.vlessSni = value;
                                break;
                            case "host":
                                info.vlessHost = value;
                                break;
                            case "path":
                                info.vlessPath = value;
                                break;
                            case "servicename":
                                info.vlessServiceName = value;
                                break;
                            case "fp":
                                info.vlessFp = value;
                                break;
                            case "alpn":
                                info.vlessAlpn = value;
                                break;
                            case "pbk":
                                info.vlessPublicKey = value;
                                break;
                            case "sid":
                                info.vlessShortId = value;
                                break;
                            case "spx":
                                info.vlessSpiderX = value;
                                break;
                            case "headertype":
                                info.vlessHeaderType = value;
                                break;
                            case "seed":
                                info.vlessSeed = value;
                                break;
                            case "quicsecurity":
                                info.vlessQuicSecurity = value;
                                break;
                            case "key":
                                info.vlessQuicKey = value;
                                break;
                            case "mode":
                                info.vlessMode = value;
                                break;
                            case "allowinsecure":
                                info.vlessAllowInsecure = "1".equals(value) || "true".equalsIgnoreCase(value);
                                break;
                        }
                    }
                }
                if (TextUtils.isEmpty(info.vlessEncryption)) {
                    info.vlessEncryption = "none";
                }
                info.normalizeVlessFields();
                return info;
            } catch (Throwable e) {
                throw new IllegalArgumentException(url, e);
            }
        }

        // ---- Xray share-link parsers (Shadowsocks / VMess / Trojan / Hysteria2) ----

        private static String decodeBase64UrlSafe(String value) {
            if (TextUtils.isEmpty(value)) {
                return "";
            }
            String normalized = value.replace('-', '+').replace('_', '/');
            while (normalized.length() % 4 != 0) {
                normalized += "=";
            }
            try {
                return new String(Base64.decode(normalized, Base64.DEFAULT), "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static HashMap<String, String> parseQueryParams(String rawQuery) {
            HashMap<String, String> params = new HashMap<>();
            if (TextUtils.isEmpty(rawQuery)) {
                return params;
            }
            for (String part : rawQuery.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) {
                    continue;
                }
                try {
                    params.put(URLDecoder.decode(pair[0], "UTF-8").toLowerCase(Locale.US),
                            URLDecoder.decode(pair[1], "UTF-8"));
                } catch (Throwable ignored) {
                }
            }
            return params;
        }

        private static String decodeFragment(String fragment) {
            if (TextUtils.isEmpty(fragment)) {
                return "";
            }
            try {
                return URLDecoder.decode(fragment, "UTF-8");
            } catch (Throwable ignored) {
                return fragment;
            }
        }

        public static ProxyInfo fromShadowsocksUrl(String url) {
            try {
                // Strip the scheme and the #tag first.
                String body = url.substring("ss://".length());
                String tag = "";
                int hash = body.indexOf('#');
                if (hash >= 0) {
                    tag = decodeFragment(body.substring(hash + 1));
                    body = body.substring(0, hash);
                }
                // Drop any ?plugin=... query: the core fields are imported, plugins are not.
                int query = body.indexOf('?');
                if (query >= 0) {
                    body = body.substring(0, query);
                }
                String method = "";
                String password = "";
                String host = "";
                int port = 0;
                int at = body.lastIndexOf('@');
                if (at >= 0) {
                    // Form 2: ss://BASE64(method:password)@host:port
                    String userinfo = decodeBase64UrlSafe(body.substring(0, at));
                    int colon = userinfo.indexOf(':');
                    if (colon >= 0) {
                        method = userinfo.substring(0, colon);
                        password = userinfo.substring(colon + 1);
                    }
                    String hostPort = body.substring(at + 1);
                    int colonPort = hostPort.lastIndexOf(':');
                    if (colonPort >= 0) {
                        host = hostPort.substring(0, colonPort);
                        port = Integer.parseInt(hostPort.substring(colonPort + 1));
                    } else {
                        host = hostPort;
                    }
                } else {
                    // Form 1: ss://BASE64(method:password@host:port)
                    String decoded = decodeBase64UrlSafe(body);
                    int atDecoded = decoded.lastIndexOf('@');
                    if (atDecoded < 0) {
                        // Form 3 (plain, not base64): ss://method:password@host:port
                        decoded = body;
                        atDecoded = decoded.lastIndexOf('@');
                    }
                    if (atDecoded < 0) {
                        throw new IllegalArgumentException(url);
                    }
                    String userinfo = decoded.substring(0, atDecoded);
                    int colon = userinfo.indexOf(':');
                    if (colon >= 0) {
                        method = userinfo.substring(0, colon);
                        password = userinfo.substring(colon + 1);
                    }
                    String hostPort = decoded.substring(atDecoded + 1);
                    int colonPort = hostPort.lastIndexOf(':');
                    if (colonPort >= 0) {
                        host = hostPort.substring(0, colonPort);
                        port = Integer.parseInt(hostPort.substring(colonPort + 1));
                    } else {
                        host = hostPort;
                    }
                }
                if (TextUtils.isEmpty(host) || port <= 0 || TextUtils.isEmpty(password)) {
                    throw new IllegalArgumentException(url);
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_SHADOWSOCKS)
                        .setAddress(URLDecoder.decode(host, "UTF-8"))
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                info.ssMethod = TextUtils.isEmpty(method) ? "aes-256-gcm" : method;
                info.ssPassword = password;
                info.proxyName = tag;
                info.normalizeXrayProtocolsFields();
                return info;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalArgumentException(url, e);
            }
        }

        public static ProxyInfo fromVmessUrl(String url) {
            try {
                String body = url.substring("vmess://".length());
                String tag = "";
                int hash = body.indexOf('#');
                if (hash >= 0) {
                    tag = decodeFragment(body.substring(hash + 1));
                    body = body.substring(0, hash);
                }
                String json = decodeBase64UrlSafe(body.trim());
                JSONObject obj = new JSONObject(json);
                String address = obj.optString("add", "");
                int port = obj.optInt("port", 0);
                if (TextUtils.isEmpty(address) || port <= 0) {
                    throw new IllegalArgumentException(url);
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_VMESS)
                        .setAddress(address)
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                info.vmessId = obj.optString("id", "");
                info.vmessAlterId = String.valueOf(obj.optInt("aid", 0));
                info.vmessSecurity = obj.optString("scy", "auto");
                if (TextUtils.isEmpty(info.vmessSecurity)) {
                    info.vmessSecurity = "auto";
                }
                info.vlessType = obj.optString("net", "");
                String tls = obj.optString("tls", "");
                info.vlessSecurity = "tls".equalsIgnoreCase(tls) ? "tls" : "";
                info.vlessSni = obj.optString("sni", "");
                info.vlessHost = obj.optString("host", "");
                info.vlessPath = obj.optString("path", "");
                info.vlessServiceName = obj.optString("serviceName", "");
                info.vlessFp = obj.optString("fp", "");
                info.vlessAlpn = obj.optString("alpn", "");
                info.vlessHeaderType = obj.optString("type", "");
                String ps = obj.optString("ps", "");
                info.proxyName = !TextUtils.isEmpty(ps) ? ps : tag;
                info.normalizeVlessFields();
                info.normalizeXrayProtocolsFields();
                return info;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalArgumentException(url, e);
            }
        }

        public static ProxyInfo fromTrojanUrl(String url) {
            try {
                java.net.URI uri = new java.net.URI(url);
                int port = uri.getPort() > 0 ? uri.getPort() : 443;
                String host = uri.getHost() != null ? uri.getHost() : "";
                String password = uri.getUserInfo() != null ? uri.getUserInfo() : "";
                if (TextUtils.isEmpty(host) || TextUtils.isEmpty(password)) {
                    throw new IllegalArgumentException(url);
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_TROJAN)
                        .setAddress(host)
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                info.trojanPassword = URLDecoder.decode(password, "UTF-8");
                HashMap<String, String> params = parseQueryParams(uri.getRawQuery());
                info.trojanSni = params.containsKey("sni") ? params.get("sni") : "";
                String allowInsecure = params.get("allowinsecure");
                info.trojanAllowInsecure = "1".equals(allowInsecure) || "true".equalsIgnoreCase(allowInsecure);
                info.vlessType = params.containsKey("type") ? params.get("type") : "";
                info.vlessHost = params.containsKey("host") ? params.get("host") : "";
                info.vlessPath = params.containsKey("path") ? params.get("path") : "";
                info.vlessServiceName = params.containsKey("servicename") ? params.get("servicename") : "";
                info.vlessFp = params.containsKey("fp") ? params.get("fp") : "";
                info.vlessAlpn = params.containsKey("alpn") ? params.get("alpn") : "";
                info.proxyName = uri.getFragment() != null ? decodeFragment(uri.getFragment()) : "";
                info.normalizeVlessFields();
                info.normalizeXrayProtocolsFields();
                return info;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalArgumentException(url, e);
            }
        }

        public static ProxyInfo fromHysteria2Url(String url) {
            try {
                java.net.URI uri = new java.net.URI(url);
                int port = uri.getPort() > 0 ? uri.getPort() : 443;
                String host = uri.getHost() != null ? uri.getHost() : "";
                String password = uri.getUserInfo() != null ? uri.getUserInfo() : "";
                if (TextUtils.isEmpty(host) || TextUtils.isEmpty(password)) {
                    throw new IllegalArgumentException(url);
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_HYSTERIA2)
                        .setAddress(host)
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                HashMap<String, String> params = parseQueryParams(uri.getRawQuery());
                String auth = params.containsKey("auth") ? params.get("auth") : password;
                info.hyPassword = URLDecoder.decode(auth, "UTF-8");
                info.hySni = params.containsKey("sni") ? params.get("sni") : "";
                info.hyAlpn = params.containsKey("alpn") ? params.get("alpn") : "h3";
                String insecure = params.get("insecure");
                info.hyAllowInsecure = "1".equals(insecure) || "true".equalsIgnoreCase(insecure);
                info.hyObfsPassword = params.containsKey("obfs-password") ? params.get("obfs-password") : "";
                info.hyUpMbps = params.containsKey("upmbps") ? params.get("upmbps") : "";
                info.hyDownMbps = params.containsKey("downmbps") ? params.get("downmbps") : "";
                info.proxyName = uri.getFragment() != null ? decodeFragment(uri.getFragment()) : "";
                info.normalizeXrayProtocolsFields();
                return info;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalArgumentException(url, e);
            }
        }

        /** True when the pasted text looks like a WireGuard .conf file. */
        public static boolean isWireguardConfText(String text) {
            if (TextUtils.isEmpty(text)) {
                return false;
            }
            return text.contains("[Interface]") && text.contains("PrivateKey");
        }

        /** Parse a WireGuard .conf text block ([Interface] / [Peer]) into a proxy. */
        public static ProxyInfo fromWireguardConf(String text) {
            try {
                HashMap<String, String> iface = new HashMap<>();
                HashMap<String, String> peer = new HashMap<>();
                HashMap<String, String> current = null;
                for (String rawLine : text.split("\n")) {
                    String line = rawLine.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    if ("[Interface]".equalsIgnoreCase(line)) {
                        current = iface;
                        continue;
                    }
                    if ("[Peer]".equalsIgnoreCase(line)) {
                        current = peer;
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (current == null || eq < 0) {
                        continue;
                    }
                    current.put(line.substring(0, eq).trim().toLowerCase(Locale.US), line.substring(eq + 1).trim());
                }
                String privateKey = iface.get("privatekey");
                String publicKey = peer.get("publickey");
                String endpoint = peer.get("endpoint");
                if (TextUtils.isEmpty(privateKey) || TextUtils.isEmpty(publicKey) || TextUtils.isEmpty(endpoint)) {
                    throw new IllegalArgumentException("wireguard conf");
                }
                String host = endpoint;
                int port = 51820;
                int colon = endpoint.lastIndexOf(':');
                if (colon >= 0) {
                    host = endpoint.substring(0, colon).replace("[", "").replace("]", "");
                    try {
                        port = Integer.parseInt(endpoint.substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_WIREGUARD)
                        .setAddress(host)
                        .setPort(port)
                        .build();
                ProxyInfo info = new ProxyInfo(settings);
                info.wgPrivateKey = privateKey;
                info.wgPublicKey = publicKey;
                info.wgEndpoint = endpoint;
                info.wgLocalAddress = iface.containsKey("address") ? iface.get("address") : "10.0.0.2/32";
                info.wgDns = iface.containsKey("dns") ? iface.get("dns") : "";
                info.wgAllowedIps = peer.containsKey("allowedips") ? peer.get("allowedips") : "0.0.0.0/0, ::/0";
                info.wgKeepAlive = peer.containsKey("persistentkeepalive") ? peer.get("persistentkeepalive") : "25";
                info.wgPresharedKey = peer.containsKey("presharedkey") ? peer.get("presharedkey") : "";
                info.normalizeXrayProtocolsFields();
                return info;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalArgumentException("wireguard conf", e);
            }
        }

        /** Share link for this proxy (Xray types); null when it cannot be built. */
        public String getShareLink() {
            if (settings == null) {
                return null;
            }
            try {
                switch (settings.getType()) {
                    case XRAY_VLESS:
                        return getVlessLink();
                    case XRAY_SHADOWSOCKS: {
                        String userinfo = ssMethod + ":" + ssPassword;
                        String encoded = Base64.encodeToString(userinfo.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        StringBuilder url = new StringBuilder("ss://");
                        url.append(encoded).append("@").append(settings.getAddress()).append(":").append(settings.getPort());
                        if (!TextUtils.isEmpty(proxyName)) {
                            url.append("#").append(URLEncoder.encode(proxyName, "UTF-8"));
                        }
                        return url.toString();
                    }
                    case XRAY_VMESS: {
                        JSONObject obj = new JSONObject();
                        obj.put("v", "2");
                        obj.put("ps", !TextUtils.isEmpty(proxyName) ? proxyName : settings.getAddress());
                        obj.put("add", settings.getAddress());
                        obj.put("port", settings.getPort());
                        obj.put("id", vmessId);
                        int aid = 0;
                        try {
                            aid = Integer.parseInt(TextUtils.isEmpty(vmessAlterId) ? "0" : vmessAlterId);
                        } catch (NumberFormatException ignored) {
                        }
                        obj.put("aid", aid);
                        obj.put("scy", vmessSecurity);
                        obj.put("net", vlessType);
                        obj.put("type", vlessHeaderType);
                        obj.put("host", vlessHost);
                        obj.put("path", vlessPath);
                        obj.put("tls", "tls".equalsIgnoreCase(vlessSecurity) ? "tls" : "");
                        obj.put("sni", vlessSni);
                        obj.put("fp", vlessFp);
                        obj.put("alpn", vlessAlpn);
                        return "vmess://" + Base64.encodeToString(obj.toString().getBytes("UTF-8"), Base64.NO_WRAP | Base64.NO_PADDING);
                    }
                    case XRAY_TROJAN: {
                        StringBuilder url = new StringBuilder("trojan://");
                        url.append(URLEncoder.encode(trojanPassword, "UTF-8"));
                        url.append("@").append(settings.getAddress()).append(":").append(settings.getPort());
                        ArrayList<String> params = new ArrayList<>();
                        appendParam(params, "sni", trojanSni);
                        appendParam(params, "alpn", vlessAlpn);
                        appendParam(params, "fp", vlessFp);
                        appendParam(params, "type", vlessType);
                        appendParam(params, "host", vlessHost);
                        appendParam(params, "path", vlessPath);
                        if (trojanAllowInsecure) {
                            appendParam(params, "allowInsecure", "1");
                        }
                        if (!params.isEmpty()) {
                            url.append("?").append(TextUtils.join("&", params));
                        }
                        if (!TextUtils.isEmpty(proxyName)) {
                            url.append("#").append(URLEncoder.encode(proxyName, "UTF-8"));
                        }
                        return url.toString();
                    }
                    case XRAY_WIREGUARD: {
                        // No standard share URL for WireGuard: share the .conf text
                        // itself (the QR sheet shows arbitrary text), which the
                        // paste-import path can read back via fromWireguardConf.
                        StringBuilder conf = new StringBuilder();
                        conf.append("[Interface]\n");
                        conf.append("PrivateKey = ").append(wgPrivateKey).append("\n");
                        conf.append("Address = ").append(wgLocalAddress).append("\n");
                        if (!TextUtils.isEmpty(wgDns)) {
                            conf.append("DNS = ").append(wgDns).append("\n");
                        }
                        conf.append("\n[Peer]\n");
                        conf.append("PublicKey = ").append(wgPublicKey).append("\n");
                        conf.append("Endpoint = ").append(wgEndpoint).append("\n");
                        conf.append("AllowedIPs = ").append(wgAllowedIps).append("\n");
                        if (!TextUtils.isEmpty(wgKeepAlive)) {
                            conf.append("PersistentKeepalive = ").append(wgKeepAlive).append("\n");
                        }
                        if (!TextUtils.isEmpty(wgPresharedKey)) {
                            conf.append("PresharedKey = ").append(wgPresharedKey).append("\n");
                        }
                        return conf.toString();
                    }
                    case XRAY_HYSTERIA2: {
                        StringBuilder url = new StringBuilder("hy2://");
                        url.append(URLEncoder.encode(hyPassword, "UTF-8"));
                        url.append("@").append(settings.getAddress()).append(":").append(settings.getPort());
                        ArrayList<String> params = new ArrayList<>();
                        appendParam(params, "sni", hySni);
                        appendParam(params, "alpn", hyAlpn);
                        if (hyAllowInsecure) {
                            appendParam(params, "insecure", "1");
                        }
                        appendParam(params, "obfs-password", hyObfsPassword);
                        appendParam(params, "upmbps", hyUpMbps);
                        appendParam(params, "downmbps", hyDownMbps);
                        if (!params.isEmpty()) {
                            url.append("?").append(TextUtils.join("&", params));
                        }
                        if (!TextUtils.isEmpty(proxyName)) {
                            url.append("#").append(URLEncoder.encode(proxyName, "UTF-8"));
                        }
                        return url.toString();
                    }
                    default:
                        return null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static ProxyInfo fromSerializedData(int version, InputSerializedData data) {
            ProxySettings.Builder builder = ProxySettings.builder()
                    .setAddress(data.readString(false))
                    .setPort(data.readInt32(false))
                    .setUser(data.readString(false))
                    .setPassword(data.readString(false));

            final String secret = data.readString(false);
            builder.setSecret(secret);

            final long ping, availableCheckTime;
            if (version >= PROXY_SCHEMA_V2) {
                ping = data.readInt64(false);
                availableCheckTime = data.readInt64(false);
            } else {
                ping = availableCheckTime = 0;
            }

            if (version >= PROXY_SCHEMA_V3) {
                builder.setType(ProxySettings.intToType(data.readInt32(false)));
            } else {
                builder.setType(TextUtils.isEmpty(secret) ? ProxySettings.Type.SOCKS5 : ProxySettings.Type.MTPROTO);
            }

            final ProxyInfo info = new ProxyInfo(builder.build());
            info.availableCheckTime = availableCheckTime;
            info.ping = ping;
            info.available = ping > 0;

            if (version >= PROXY_SCHEMA_V4) {
                info.vlessId = data.readString(false);
                info.vlessEncryption = data.readString(false);
                info.vlessFlow = data.readString(false);
                info.vlessSecurity = data.readString(false);
                info.vlessType = data.readString(false);
                info.vlessSni = data.readString(false);
                info.vlessHost = data.readString(false);
                info.vlessPath = data.readString(false);
                info.vlessServiceName = data.readString(false);
                info.vlessFp = data.readString(false);
                info.vlessAlpn = data.readString(false);
                info.vlessPublicKey = data.readString(false);
                info.vlessShortId = data.readString(false);
                info.vlessSpiderX = data.readString(false);
                info.vlessHeaderType = data.readString(false);
                info.vlessSeed = data.readString(false);
                info.vlessQuicSecurity = data.readString(false);
                info.vlessQuicKey = data.readString(false);
                info.vlessMode = data.readString(false);
                info.vlessAllowInsecure = data.readBool(false);
                info.vlessRemark = data.readString(false);
                info.vlessRawQuery = data.readString(false);
                info.vlessAdvancedJson = data.readString(false);
                info.subscriptionName = data.readString(false);
                info.proxyName = data.readString(false);
                info.isSubscription = data.readBool(false);
                info.normalizeVlessFields();
            }

            if (version >= PROXY_SCHEMA_V5) {
                info.aetherProtocol = data.readString(false);
                info.aetherScan = data.readString(false);
                info.aetherIp = data.readString(false);
                info.aetherTransport = data.readString(false);
                info.aetherFragment = data.readBool(false);
                info.aetherNoize = data.readString(false);
                info.aetherQuickReconnect = data.readBool(false);
                info.aetherPeers = data.readString(false);
                info.normalizeAetherFields();
            }

            if (version >= PROXY_SCHEMA_V6) {
                info.aetherApiAddress = data.readString(false);
                info.aetherApiHost = data.readString(false);
                info.aetherApiSni = data.readString(false);
                info.aetherMasqueSni = data.readString(false);
                info.aetherTls13Ciphers = data.readString(false);
                info.aetherTlsKeyShares = data.readString(false);
                info.aetherTlsSigalgs = data.readString(false);
                info.aetherTlsCertCompression = data.readString(false);
                info.aetherTlsAlps = data.readString(false);
                info.aetherGreaseSigalgs = data.readBool(false);
                info.normalizeAetherFields();
            }

            if (version >= PROXY_SCHEMA_V7) {
                info.ssPassword = data.readString(false);
                info.ssMethod = data.readString(false);
                info.vmessId = data.readString(false);
                info.vmessAlterId = data.readString(false);
                info.vmessSecurity = data.readString(false);
                info.trojanPassword = data.readString(false);
                info.trojanSni = data.readString(false);
                info.trojanAllowInsecure = data.readBool(false);
                info.wgPrivateKey = data.readString(false);
                info.wgPublicKey = data.readString(false);
                info.wgEndpoint = data.readString(false);
                info.wgAllowedIps = data.readString(false);
                info.wgLocalAddress = data.readString(false);
                info.wgMtu = data.readString(false);
                info.wgKeepAlive = data.readString(false);
                info.wgPresharedKey = data.readString(false);
                info.wgDns = data.readString(false);
                info.hyPassword = data.readString(false);
                info.hySni = data.readString(false);
                info.hyAlpn = data.readString(false);
                info.hyAllowInsecure = data.readBool(false);
                info.hyObfsPassword = data.readString(false);
                info.hyUpMbps = data.readString(false);
                info.hyDownMbps = data.readString(false);
                info.normalizeXrayProtocolsFields();
            }

            return info;
        }

        private void toSerializedData(OutputSerializedData data) {
            data.writeString(settings.getAddress());
            data.writeInt32(settings.getPort());
            data.writeString(settings.getUser());
            data.writeString(settings.getPassword());
            data.writeString(settings.getSecret());
            data.writeInt64(ping);
            data.writeInt64(availableCheckTime);
            data.writeInt32(ProxySettings.typeToInt(settings.getType()));
            data.writeString(vlessId);
            data.writeString(vlessEncryption);
            data.writeString(vlessFlow);
            data.writeString(vlessSecurity);
            data.writeString(vlessType);
            data.writeString(vlessSni);
            data.writeString(vlessHost);
            data.writeString(vlessPath);
            data.writeString(vlessServiceName);
            data.writeString(vlessFp);
            data.writeString(vlessAlpn);
            data.writeString(vlessPublicKey);
            data.writeString(vlessShortId);
            data.writeString(vlessSpiderX);
            data.writeString(vlessHeaderType);
            data.writeString(vlessSeed);
            data.writeString(vlessQuicSecurity);
            data.writeString(vlessQuicKey);
            data.writeString(vlessMode);
            data.writeBool(vlessAllowInsecure);
            data.writeString(vlessRemark);
            data.writeString(vlessRawQuery);
            data.writeString(vlessAdvancedJson);
            data.writeString(subscriptionName != null ? subscriptionName : "");
            data.writeString(proxyName != null ? proxyName : "");
            data.writeBool(isSubscription);
            data.writeString(aetherProtocol);
            data.writeString(aetherScan);
            data.writeString(aetherIp);
            data.writeString(aetherTransport);
            data.writeBool(aetherFragment);
            data.writeString(aetherNoize);
            data.writeBool(aetherQuickReconnect);
            data.writeString(aetherPeers);
            // Schema V6 fields, in the same order as fromSerializedData reads them.
            data.writeString(aetherApiAddress != null ? aetherApiAddress : "");
            data.writeString(aetherApiHost != null ? aetherApiHost : "");
            data.writeString(aetherApiSni != null ? aetherApiSni : "");
            data.writeString(aetherMasqueSni != null ? aetherMasqueSni : "");
            data.writeString(aetherTls13Ciphers != null ? aetherTls13Ciphers : "");
            data.writeString(aetherTlsKeyShares != null ? aetherTlsKeyShares : "");
            data.writeString(aetherTlsSigalgs != null ? aetherTlsSigalgs : "");
            data.writeString(aetherTlsCertCompression != null ? aetherTlsCertCompression : "");
            data.writeString(aetherTlsAlps != null ? aetherTlsAlps : "");
            data.writeBool(aetherGreaseSigalgs);
            // Schema V7 fields, in the same order as fromSerializedData reads them.
            // NOTE: builds on schema V6 or older reject a V7 proxy list outright
            // (loadProxyList only accepts known versions), so downgrading after
            // saving a V7 list loses the saved proxies; the current proxy falls
            // back to the SharedPreferences copy.
            data.writeString(ssPassword != null ? ssPassword : "");
            data.writeString(ssMethod != null ? ssMethod : "aes-256-gcm");
            data.writeString(vmessId != null ? vmessId : "");
            data.writeString(vmessAlterId != null ? vmessAlterId : "0");
            data.writeString(vmessSecurity != null ? vmessSecurity : "auto");
            data.writeString(trojanPassword != null ? trojanPassword : "");
            data.writeString(trojanSni != null ? trojanSni : "");
            data.writeBool(trojanAllowInsecure);
            data.writeString(wgPrivateKey != null ? wgPrivateKey : "");
            data.writeString(wgPublicKey != null ? wgPublicKey : "");
            data.writeString(wgEndpoint != null ? wgEndpoint : "");
            data.writeString(wgAllowedIps != null ? wgAllowedIps : "0.0.0.0/0, ::/0");
            data.writeString(wgLocalAddress != null ? wgLocalAddress : "10.0.0.2/32");
            data.writeString(wgMtu != null ? wgMtu : "1420");
            data.writeString(wgKeepAlive != null ? wgKeepAlive : "25");
            data.writeString(wgPresharedKey != null ? wgPresharedKey : "");
            data.writeString(wgDns != null ? wgDns : "");
            data.writeString(hyPassword != null ? hyPassword : "");
            data.writeString(hySni != null ? hySni : "");
            data.writeString(hyAlpn != null ? hyAlpn : "h3");
            data.writeBool(hyAllowInsecure);
            data.writeString(hyObfsPassword != null ? hyObfsPassword : "");
            data.writeString(hyUpMbps != null ? hyUpMbps : "");
            data.writeString(hyDownMbps != null ? hyDownMbps : "");
        }
    }

    public static ArrayList<ProxyInfo> proxyList = new ArrayList<>();
    private static boolean proxyListLoaded;
    public static ProxyInfo currentProxy;

    public static ArrayList<ProxyInfo> getProxyList() {
        loadProxyList();
        return proxyList;
    }

    public static void saveConfig() {
        synchronized (sync) {
            try {
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putBoolean("saveIncomingPhotos", saveIncomingPhotos);
                editor.putString("passcodeHash1", passcodeHash);
                editor.putString("passcodeSalt", passcodeSalt.length > 0 ? Base64.encodeToString(passcodeSalt, Base64.DEFAULT) : "");
                editor.putBoolean("appLocked", appLocked);
                editor.putInt("passcodeType", passcodeType);
                editor.putLong("passcodeRetryInMs", passcodeRetryInMs);
                editor.putLong("lastUptimeMillis", lastUptimeMillis);
                editor.putInt("badPasscodeTries", badPasscodeTries);
                editor.putInt("autoLockIn", autoLockIn);
                editor.putInt("lastPauseTime", lastPauseTime);
                editor.putBoolean("useFingerprint", useFingerprintLock);
                editor.putBoolean("walletUseFingerprintLock", walletUseFingerprintLock);
                editor.putBoolean("allowScreenCapture", allowScreenCapture);
                editor.putString("pushString2", pushString);
                editor.putInt("pushType", pushType);
                editor.putBoolean("pushStatSent", pushStatSent);
                editor.putString("pushAuthKey", pushAuthKey != null ? Base64.encodeToString(pushAuthKey, Base64.DEFAULT) : "");
                editor.putInt("lastLocalId", lastLocalId);
                editor.putString("passportConfigJson", passportConfigJson);
                editor.putInt("passportConfigHash", passportConfigHash);
                editor.putBoolean("sortContactsByName", sortContactsByName);
                editor.putBoolean("sortFilesByName", sortFilesByName);
                editor.putInt("textSelectionHintShows", textSelectionHintShows);
                editor.putInt("scheduledOrNoSoundHintShows", scheduledOrNoSoundHintShows);
                editor.putLong("scheduledOrNoSoundHintSeenAt", scheduledOrNoSoundHintSeenAt);
                editor.putInt("scheduledHintShows", scheduledHintShows);
                editor.putLong("scheduledHintSeenAt", scheduledHintSeenAt);
                editor.putBoolean("forwardingOptionsHintShown", forwardingOptionsHintShown);
                editor.putBoolean("replyingOptionsHintShown", replyingOptionsHintShown);
                editor.putInt("lockRecordAudioVideoHint", lockRecordAudioVideoHint);
                editor.putString("storageCacheDir", !TextUtils.isEmpty(storageCacheDir) ? storageCacheDir : "");
                editor.putBoolean("proxyRotationEnabled", proxyRotationEnabled);
                editor.putInt("proxyRotationTimeout", proxyRotationTimeout);

                if (pendingAppUpdate != null) {
                    try {
                        SerializedData data = new SerializedData(pendingAppUpdate.getObjectSize());
                        pendingAppUpdate.serializeToStream(data);
                        String str = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
                        editor.putString("appUpdate", str);
                        editor.putInt("appUpdateBuild", pendingAppUpdateBuildVersion);
                        data.cleanup();
                    } catch (Exception ignore) {

                    }
                } else {
                    editor.remove("appUpdate");
                }
                editor.putLong("appUpdateCheckTime", lastUpdateCheckTime);

                editor.apply();

                editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit();
                editor.putBoolean("hasEmailLogin", hasEmailLogin);
                editor.putBoolean("floatingDebugActive", isFloatingDebugActive);
                editor.putBoolean("record_via_sco", recordViaSco);
                editor.apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static int getLastLocalId() {
        int value;
        synchronized (localIdSync) {
            value = lastLocalId--;
        }
        return value;
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded || ApplicationLoader.applicationContext == null) {
                return;
            }

            BackgroundActivityPrefs.prefs = ApplicationLoader.applicationContext.getSharedPreferences("background_activity", Context.MODE_PRIVATE);

            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
            saveIncomingPhotos = preferences.getBoolean("saveIncomingPhotos", false);
            passcodeHash = preferences.getString("passcodeHash1", "");
            appLocked = preferences.getBoolean("appLocked", false);
            passcodeType = preferences.getInt("passcodeType", 0);
            passcodeRetryInMs = preferences.getLong("passcodeRetryInMs", 0);
            lastUptimeMillis = preferences.getLong("lastUptimeMillis", 0);
            badPasscodeTries = preferences.getInt("badPasscodeTries", 0);
            autoLockIn = preferences.getInt("autoLockIn", 60 * 60);
            lastPauseTime = preferences.getInt("lastPauseTime", 0);
            useFingerprintLock = preferences.getBoolean("useFingerprint", true);
            walletUseFingerprintLock = preferences.getBoolean("walletUseFingerprintLock", true);
            allowScreenCapture = preferences.getBoolean("allowScreenCapture", false);
            lastLocalId = preferences.getInt("lastLocalId", -210000);
            pushString = preferences.getString("pushString2", "");
            pushType = preferences.getInt("pushType", PushListenerController.PUSH_TYPE_FIREBASE);
            pushStatSent = preferences.getBoolean("pushStatSent", false);
            passportConfigJson = preferences.getString("passportConfigJson", "");
            passportConfigHash = preferences.getInt("passportConfigHash", 0);
            storageCacheDir = preferences.getString("storageCacheDir", null);
            proxyRotationEnabled = preferences.getBoolean("proxyRotationEnabled", false);
            proxyRotationTimeout = preferences.getInt("proxyRotationTimeout", ProxyRotationController.DEFAULT_TIMEOUT_INDEX);
            String authKeyString = preferences.getString("pushAuthKey", null);
            if (!TextUtils.isEmpty(authKeyString)) {
                pushAuthKey = Base64.decode(authKeyString, Base64.DEFAULT);
            }

            if (passcodeHash.length() > 0 && lastPauseTime == 0) {
                lastPauseTime = (int) (SystemClock.elapsedRealtime() / 1000 - 60 * 10);
            }

            String passcodeSaltString = preferences.getString("passcodeSalt", "");
            if (passcodeSaltString.length() > 0) {
                passcodeSalt = Base64.decode(passcodeSaltString, Base64.DEFAULT);
            } else {
                passcodeSalt = new byte[0];
            }
            lastUpdateCheckTime = preferences.getLong("appUpdateCheckTime", System.currentTimeMillis());
            try {
                String update = preferences.getString("appUpdate", null);
                if (update != null) {
                    pendingAppUpdateBuildVersion = preferences.getInt("appUpdateBuild", buildVersion());
                    byte[] arr = Base64.decode(update, Base64.DEFAULT);
                    if (arr != null) {
                        SerializedData data = new SerializedData(arr);
                        pendingAppUpdate = (TLRPC.TL_help_appUpdate) TLRPC.help_AppUpdate.TLdeserialize(data, data.readInt32(false), false);
                        data.cleanup();
                    }
                }
                if (pendingAppUpdate != null) {
                    long updateTime = 0;
                    int updateVersion = 0;
                    String updateVersionString = null;
                    try {
                        PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                        updateVersion = packageInfo.versionCode;
                        updateVersionString = packageInfo.versionName;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (updateVersion == 0) {
                        updateVersion = buildVersion();
                    }
                    if (updateVersionString == null) {
                        updateVersionString = BuildVars.BUILD_VERSION_STRING;
                    }
                    if (pendingAppUpdateBuildVersion != updateVersion || pendingAppUpdate.version == null || updateVersionString.compareTo(pendingAppUpdate.version) >= 0 || BuildVars.DEBUG_PRIVATE_VERSION) {
                        pendingAppUpdate = null;
                        AndroidUtilities.runOnUIThread(SharedConfig::saveConfig);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            SaveToGallerySettingsHelper.load(preferences);
            mapPreviewType = preferences.getInt("mapPreviewType", 2);
            searchEngineType = preferences.getInt("searchEngineType", 0);
            raiseToListen = preferences.getBoolean("raise_to_listen", true);
            raiseToSpeak = preferences.getBoolean("raise_to_speak", false);
            nextMediaTap = preferences.getBoolean("next_media_on_tap", true);
            recordViaSco = preferences.getBoolean("record_via_sco", false);
            adaptableColorInBrowser = preferences.getBoolean("adaptableBrowser", false);
            onlyLocalInstantView = preferences.getBoolean("onlyLocalInstantView", BuildVars.DEBUG_PRIVATE_VERSION);
            directShare = preferences.getBoolean("direct_share", true);
            shuffleMusic = preferences.getBoolean("shuffleMusic", false);
            playOrderReversed = !shuffleMusic && preferences.getBoolean("playOrderReversed", false);
            inappCamera = preferences.getBoolean("inappCamera", true);
            hasCameraCache = preferences.contains("cameraCache");
            roundCamera16to9 = true;
            repeatMode = preferences.getInt("repeatMode", 0);
            fontSize = preferences.getInt("fons_size", AndroidUtilities.isTablet() && !AndroidUtilities.isFold() ? 18 : 16);
            fontSizeIsDefault = !preferences.contains("fons_size");
            bubbleRadius = preferences.getInt("bubbleRadius", 17);
            ivFontSize = preferences.getInt("iv_font_size", fontSize);
            allowBigEmoji = preferences.getBoolean("allowBigEmoji", true);
            useSystemEmoji = preferences.getBoolean("useSystemEmoji", false);
            useSystemBoldFont = preferences.getBoolean("useSystemBoldFont", false);
            forceForumTabs = preferences.getBoolean("forceForumTabs", false);
            fastWallpaperDisabled = preferences.getBoolean("fastWallpaperDisabled", false);
            frameMetricsEnabled = preferences.getBoolean("frameMetricsEnabled", false);
            if (useSystemBoldFont) {
                AndroidUtilities.mediumTypeface = null;
            }
            streamMedia = preferences.getBoolean("streamMedia", true);
            saveStreamMedia = preferences.getBoolean("saveStreamMedia", true);
            pauseMusicOnRecord = preferences.getBoolean("pauseMusicOnRecord", true);
            pauseMusicOnMedia = preferences.getBoolean("pauseMusicOnMedia", false);
            forceDisableTabletMode = preferences.getBoolean("forceDisableTabletMode", false);
            streamAllVideo = preferences.getBoolean("streamAllVideo", BuildVars.DEBUG_VERSION);
            streamMkv = preferences.getBoolean("streamMkv", false);
            suggestStickers = preferences.getInt("suggestStickers", 0);
            suggestAnimatedEmoji = preferences.getBoolean("suggestAnimatedEmoji", true);
            overrideDevicePerformanceClass = preferences.getInt("overrideDevicePerformanceClass", -1);
            devicePerformanceClass = preferences.getInt("devicePerformanceClass", -1);
            sortContactsByName = preferences.getBoolean("sortContactsByName", false);
            sortFilesByName = preferences.getBoolean("sortFilesByName", false);
            noSoundHintShowed = preferences.getBoolean("noSoundHintShowed", false);
            directShareHash = preferences.getString("directShareHash2", null);
            useThreeLinesLayout = preferences.getBoolean("useThreeLinesLayout", false);
            archiveHidden = preferences.getBoolean("archiveHidden", false);
            distanceSystemType = preferences.getInt("distanceSystemType", 0);
            keepMedia = preferences.getInt("keep_media", CacheByChatsController.KEEP_MEDIA_ONE_MONTH);
            debugWebView = preferences.getBoolean("debugWebView", false);
            lastKeepMediaCheckTime = preferences.getInt("lastKeepMediaCheckTime", 0);
            lastLogsCheckTime = preferences.getInt("lastLogsCheckTime", 0);
            searchMessagesAsListUsed = preferences.getBoolean("searchMessagesAsListUsed", false);
            stickersReorderingHintUsed = preferences.getBoolean("stickersReorderingHintUsed", false);
            storyReactionsLongPressHint = preferences.getBoolean("storyReactionsLongPressHint", false);
            storiesIntroShown = preferences.getBoolean("storiesIntroShown", false);
            textSelectionHintShows = preferences.getInt("textSelectionHintShows", 0);
            scheduledOrNoSoundHintShows = preferences.getInt("scheduledOrNoSoundHintShows", 0);
            scheduledOrNoSoundHintSeenAt = preferences.getLong("scheduledOrNoSoundHintSeenAt", 0);
            scheduledHintShows = preferences.getInt("scheduledHintShows", 0);
            scheduledHintSeenAt = preferences.getLong("scheduledHintSeenAt", 0);
            forwardingOptionsHintShown = preferences.getBoolean("forwardingOptionsHintShown", false);
            replyingOptionsHintShown = preferences.getBoolean("replyingOptionsHintShown", false);
            lockRecordAudioVideoHint = preferences.getInt("lockRecordAudioVideoHint", 0);
            disableVoiceAudioEffects = preferences.getBoolean("disableVoiceAudioEffects", false);
            noiseSupression = preferences.getBoolean("noiseSupression", false);
            chatSwipeAction = preferences.getInt("ChatSwipeAction", -1);
            messageSeenHintCount = preferences.getInt("messageSeenCount", 3);
            emojiInteractionsHintCount = preferences.getInt("emojiInteractionsHintCount", 3);
            dayNightThemeSwitchHintCount = preferences.getInt("dayNightThemeSwitchHintCount", 3);
            stealthModeSendMessageConfirm = preferences.getInt("stealthModeSendMessageConfirm", 2);
            mediaColumnsCount = preferences.getInt("mediaColumnsCount", 3);
            storiesColumnsCount = preferences.getInt("storiesColumnsCount", 3);
            fastScrollHintCount = preferences.getInt("fastScrollHintCount", 3);
            dontAskManageStorage = preferences.getBoolean("dontAskManageStorage", false);
            hasEmailLogin = preferences.getBoolean("hasEmailLogin", false);
            isFloatingDebugActive = preferences.getBoolean("floatingDebugActive", false);
            updateStickersOrderOnSend = preferences.getBoolean("updateStickersOrderOnSend", true);
            dayNightWallpaperSwitchHint = preferences.getInt("dayNightWallpaperSwitchHint", 0);
            bigCameraForRound = preferences.getBoolean("bigCameraForRound", false);
            useNewBlur = preferences.getBoolean("useNewBlur", true);
            useCamera2Force = !preferences.contains("useCamera2Force_2") ? null : preferences.getBoolean("useCamera2Force_2", false);
            useSurfaceInStories = preferences.getBoolean("useSurfaceInStories", Build.VERSION.SDK_INT >= 30);
            payByInvoice = preferences.getBoolean("payByInvoice", false);
            photoViewerBlur = preferences.getBoolean("photoViewerBlur", true);
            multipleReactionsPromoShowed = preferences.getBoolean("multipleReactionsPromoShowed", false);
            callEncryptionHintDisplayedCount = preferences.getInt("callEncryptionHintDisplayedCount", 0);
            debugVideoQualities = preferences.getBoolean("debugVideoQualities", false);
            shadowsInSections = preferences.getBoolean("shadowsInSections", false);
            debugViewMetrics = preferences.getBoolean("debugViewMetrics", false);
            photoHighQualityDefault = preferences.getBoolean("photoHighQualityDefault", false);
            photoLiveDefault = preferences.getBoolean("photoLiveDefault", false);

            loadDebugConfig(preferences);

            preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Activity.MODE_PRIVATE);
            showNotificationsForAllAccounts = preferences.getBoolean("AllAccounts", true);

            configLoaded = true;
        }
    }

    public static int buildVersion() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    public static void updateTabletConfig() {
        if (fontSizeIsDefault) {
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            fontSize = preferences.getInt("fons_size", AndroidUtilities.isTablet() && !AndroidUtilities.isFold() ? 18 : 16);
            ivFontSize = preferences.getInt("iv_font_size", fontSize);
        }
    }

    public static void increaseBadPasscodeTries() {
        badPasscodeTries++;
        if (badPasscodeTries >= 3) {
            switch (badPasscodeTries) {
                case 3:
                    passcodeRetryInMs = 5000;
                    break;
                case 4:
                    passcodeRetryInMs = 10000;
                    break;
                case 5:
                    passcodeRetryInMs = 15000;
                    break;
                case 6:
                    passcodeRetryInMs = 20000;
                    break;
                case 7:
                    passcodeRetryInMs = 25000;
                    break;
                default:
                    passcodeRetryInMs = 30000;
                    break;
            }
            lastUptimeMillis = SystemClock.elapsedRealtime();
        }
        saveConfig();
    }

    public static boolean isAutoplayVideo() {
        return LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_VIDEOS);
    }

    public static boolean isAutoplayGifs() {
        return LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_GIFS);
    }

    public static boolean isPassportConfigLoaded() {
        return passportConfigMap != null;
    }

    public static void setPassportConfig(String json, int hash) {
        passportConfigMap = null;
        passportConfigJson = json;
        passportConfigHash = hash;
        saveConfig();
        getCountryLangs();
    }

    public static HashMap<String, String> getCountryLangs() {
        if (passportConfigMap == null) {
            passportConfigMap = new HashMap<>();
            try {
                JSONObject object = new JSONObject(passportConfigJson);
                Iterator<String> iter = object.keys();
                while (iter.hasNext()) {
                    String key = iter.next();
                    passportConfigMap.put(key.toUpperCase(), object.getString(key).toUpperCase());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return passportConfigMap;
    }

    public static boolean isAppUpdateAvailable() {
        if (pendingAppUpdate == null || pendingAppUpdate.document == null || !ApplicationLoader.isStandaloneBuild()) {
            return false;
        }
        int currentVersion;
        try {
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            currentVersion = pInfo.versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            currentVersion = buildVersion();
        }
        return pendingAppUpdateBuildVersion == currentVersion;
    }

    public static boolean setNewAppVersionAvailable(TLRPC.TL_help_appUpdate update) {
        String updateVersionString = null;
        int versionCode = 0;
        try {
            PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            versionCode = packageInfo.versionCode;
            updateVersionString = packageInfo.versionName;
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (versionCode == 0) {
            versionCode = buildVersion();
        }
        if (updateVersionString == null) {
            updateVersionString = BuildVars.BUILD_VERSION_STRING;
        }
        if (update.version == null || versionBiggerOrEqual(updateVersionString, update.version)) {
            return false;
        }
        pendingAppUpdate = update;
        pendingAppUpdateBuildVersion = versionCode;
        saveConfig();
        return true;
    }

    // returns a >= b
    public static boolean versionBiggerOrEqual(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        for (int i = 0; i < Math.min(partsA.length, partsB.length); ++i) {
            int numA = Integer.parseInt(partsA[i]);
            int numB = Integer.parseInt(partsB[i]);
            if (numA < numB) {
                return false;
            } else if (numA > numB) {
                return true;
            }
        }
        return true;
    }

    public static boolean checkPasscode(String passcode) {
        if (passcodeSalt.length == 0) {
            boolean result = Utilities.MD5(passcode).equals(passcodeHash);
            if (result) {
                try {
                    passcodeSalt = new byte[16];
                    Utilities.random.nextBytes(passcodeSalt);
                    byte[] passcodeBytes = passcode.getBytes("UTF-8");
                    byte[] bytes = new byte[32 + passcodeBytes.length];
                    System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
                    System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
                    System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
                    passcodeHash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
                    saveConfig();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return result;
        } else {
            try {
                byte[] passcodeBytes = passcode.getBytes("UTF-8");
                byte[] bytes = new byte[32 + passcodeBytes.length];
                System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
                System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
                System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
                String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
                return passcodeHash.equals(hash);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    public static void clearConfig() {
        saveIncomingPhotos = false;
        appLocked = false;
        passcodeType = PASSCODE_TYPE_PIN;
        passcodeRetryInMs = 0;
        lastUptimeMillis = 0;
        badPasscodeTries = 0;
        passcodeHash = "";
        passcodeSalt = new byte[0];
        autoLockIn = 60 * 60;
        lastPauseTime = 0;
        useFingerprintLock = true;
        walletUseFingerprintLock = true;
        isWaitingForPasscodeEnter = false;
        allowScreenCapture = false;
        textSelectionHintShows = 0;
        scheduledOrNoSoundHintShows = 0;
        scheduledOrNoSoundHintSeenAt = 0;
        scheduledHintShows = 0;
        scheduledHintSeenAt = 0;
        lockRecordAudioVideoHint = 0;
        forwardingOptionsHintShown = false;
        replyingOptionsHintShown = false;
        messageSeenHintCount = 3;
        emojiInteractionsHintCount = 3;
        dayNightThemeSwitchHintCount = 3;
        stealthModeSendMessageConfirm = 2;
        dayNightWallpaperSwitchHint = 0;
        saveConfig();
    }

    public static void setMultipleReactionsPromoShowed(boolean val) {
        multipleReactionsPromoShowed = val;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("multipleReactionsPromoShowed", multipleReactionsPromoShowed);
        editor.apply();
    }

    public static void setSuggestStickers(int type) {
        suggestStickers = type;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("suggestStickers", suggestStickers);
        editor.apply();
    }

    public static void setSearchMessagesAsListUsed(boolean value) {
        searchMessagesAsListUsed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("searchMessagesAsListUsed", searchMessagesAsListUsed);
        editor.apply();
    }

    public static void setStickersReorderingHintUsed(boolean value) {
        stickersReorderingHintUsed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("stickersReorderingHintUsed", stickersReorderingHintUsed);
        editor.apply();
    }

    public static void setStoriesReactionsLongPressHintUsed(boolean value) {
        storyReactionsLongPressHint = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("storyReactionsLongPressHint", storyReactionsLongPressHint);
        editor.apply();
    }

    public static void setStoriesIntroShown(boolean isShown) {
        storiesIntroShown = isShown;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("storiesIntroShown", storiesIntroShown);
        editor.apply();
    }

    public static void increaseTextSelectionHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textSelectionHintShows", ++textSelectionHintShows);
        editor.apply();
    }

    public static void increaseDayNightWallpaperSiwtchHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("dayNightWallpaperSwitchHint", ++dayNightWallpaperSwitchHint);
        editor.apply();
    }

    public static void removeTextSelectionHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textSelectionHintShows", 3);
        editor.apply();
    }

    public static void increaseScheduledOrNoSoundHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        scheduledOrNoSoundHintSeenAt = System.currentTimeMillis();
        editor.putInt("scheduledOrNoSoundHintShows", ++scheduledOrNoSoundHintShows);
        editor.putLong("scheduledOrNoSoundHintSeenAt", scheduledOrNoSoundHintSeenAt);
        editor.apply();
    }

    public static void increaseScheduledHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        scheduledHintSeenAt = System.currentTimeMillis();
        editor.putInt("scheduledHintShows", ++scheduledHintShows);
        editor.putLong("scheduledHintSeenAt", scheduledHintSeenAt);
        editor.apply();
    }

    public static void forwardingOptionsHintHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        forwardingOptionsHintShown = true;
        editor.putBoolean("forwardingOptionsHintShown", forwardingOptionsHintShown);
        editor.apply();
    }

    public static void replyingOptionsHintHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        replyingOptionsHintShown = true;
        editor.putBoolean("replyingOptionsHintShown", replyingOptionsHintShown);
        editor.apply();
    }

    public static void removeScheduledOrNoSoundHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("scheduledOrNoSoundHintShows", 3);
        editor.apply();
    }

    public static void removeScheduledHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("scheduledHintShows", 3);
        editor.apply();
    }

    public static void increaseLockRecordAudioVideoHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("lockRecordAudioVideoHint", ++lockRecordAudioVideoHint);
        editor.apply();
    }

    public static void removeLockRecordAudioVideoHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("lockRecordAudioVideoHint", 3);
        editor.apply();
    }

    public static void setKeepMedia(int value) {
        keepMedia = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("keep_media", keepMedia);
        editor.apply();
    }

    public static void toggleUpdateStickersOrderOnSend() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("updateStickersOrderOnSend", updateStickersOrderOnSend = !updateStickersOrderOnSend);
        editor.apply();
    }

    public static void checkLogsToDelete() {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        int time = (int) (System.currentTimeMillis() / 1000);
        if (Math.abs(time - lastLogsCheckTime) < 60 * 60) {
            return;
        }
        lastLogsCheckTime = time;
        Utilities.cacheClearQueue.postRunnable(() -> {
            long currentTime = time - 60 * 60 * 24 * 10;
            try {
                File dir = AndroidUtilities.getLogsDir();
                if (dir == null) {
                    return;
                }
                Utilities.clearDir(dir.getAbsolutePath(), 0, currentTime, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("lastLogsCheckTime", lastLogsCheckTime);
            editor.apply();
        });
    }

    public static void toggleDisableVoiceAudioEffects() {
        disableVoiceAudioEffects = !disableVoiceAudioEffects;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableVoiceAudioEffects", disableVoiceAudioEffects);
        editor.apply();
    }

    public static void toggleNoiseSupression() {
        noiseSupression = !noiseSupression;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("noiseSupression", noiseSupression);
        editor.apply();
    }

    public static void toggleDebugWebView() {
        debugWebView = !debugWebView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(debugWebView);
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("debugWebView", debugWebView);
        editor.apply();
    }

    public static void incrementCallEncryptionHintDisplayed(int count) {
        callEncryptionHintDisplayedCount += count;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("callEncryptionHintDisplayedCount", callEncryptionHintDisplayedCount);
        editor.apply();
    }

    public static void toggleLoopStickers() {
        LiteMode.toggleFlag(LiteMode.FLAG_ANIMATED_STICKERS_CHAT);
    }

    public static void toggleBigEmoji() {
        allowBigEmoji = !allowBigEmoji;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("allowBigEmoji", allowBigEmoji);
        editor.apply();
    }

    public static void toggleUseSystemBoldFont() {
        useSystemBoldFont = !useSystemBoldFont;
        AndroidUtilities.mediumTypeface = null;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useSystemBoldFont", useSystemBoldFont);
        editor.apply();
    }

    public static void toggleForceForumTabs() {
        forceForumTabs = !forceForumTabs;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceForumTabs", forceForumTabs);
        editor.apply();
    }

    public static void toggleFastWallpaperDisabled() {
        fastWallpaperDisabled = !fastWallpaperDisabled;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("fastWallpaperDisabled", fastWallpaperDisabled);
        editor.apply();
    }

    public static void toggleFrameMetricsEnabled() {
        frameMetricsEnabled = !frameMetricsEnabled;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("frameMetricsEnabled", frameMetricsEnabled);
        editor.apply();
    }

    public static void toggleSuggestAnimatedEmoji() {
        suggestAnimatedEmoji = !suggestAnimatedEmoji;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("suggestAnimatedEmoji", suggestAnimatedEmoji);
        editor.apply();
    }

    public static void setPlaybackOrderType(int type) {
        if (type == 2) {
            shuffleMusic = true;
            playOrderReversed = false;
        } else if (type == 1) {
            playOrderReversed = true;
            shuffleMusic = false;
        } else {
            playOrderReversed = false;
            shuffleMusic = false;
        }
        MediaController.getInstance().checkIsNextMediaFileDownloaded();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("shuffleMusic", shuffleMusic);
        editor.putBoolean("playOrderReversed", playOrderReversed);
        editor.apply();
    }

    public static void setRepeatMode(int mode) {
        repeatMode = mode;
        if (repeatMode < 0 || repeatMode > 2) {
            repeatMode = 0;
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("repeatMode", repeatMode);
        editor.apply();
    }

    public static void overrideDevicePerformanceClass(int performanceClass) {
        MessagesController.getGlobalMainSettings().edit().putInt("overrideDevicePerformanceClass", overrideDevicePerformanceClass = performanceClass).remove("lite_mode").apply();
        if (liteMode != null) {
            liteMode.loadPreference();
        }
    }

    public static void toggleAutoplayGifs() {
        LiteMode.toggleFlag(LiteMode.FLAG_AUTOPLAY_GIFS);
    }

    public static void setUseThreeLinesLayout(boolean value) {
        useThreeLinesLayout = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useThreeLinesLayout", useThreeLinesLayout);
        editor.apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
    }

    public static void toggleArchiveHidden() {
        archiveHidden = !archiveHidden;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("archiveHidden", archiveHidden);
        editor.apply();
    }

    public static void toggleAutoplayVideo() {
        LiteMode.toggleFlag(LiteMode.FLAG_AUTOPLAY_VIDEOS);
    }

    public static boolean isSecretMapPreviewSet() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        return preferences.contains("mapPreviewType");
    }

    public static void setSecretMapPreviewType(int value) {
        mapPreviewType = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("mapPreviewType", mapPreviewType);
        editor.apply();
    }

    public static void setSearchEngineType(int value) {
        searchEngineType = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("searchEngineType", searchEngineType);
        editor.apply();
    }

    public static void setNoSoundHintShowed(boolean value) {
        if (noSoundHintShowed == value) {
            return;
        }
        noSoundHintShowed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("noSoundHintShowed", noSoundHintShowed);
        editor.apply();
    }

    public static void toggleRaiseToSpeak() {
        raiseToSpeak = !raiseToSpeak;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("raise_to_speak", raiseToSpeak);
        editor.apply();
    }

    public static void toggleRaiseToListen() {
        raiseToListen = !raiseToListen;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("raise_to_listen", raiseToListen);
        editor.apply();
    }

    public static void toggleNextMediaTap() {
        nextMediaTap = !nextMediaTap;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("next_media_on_tap", nextMediaTap);
        editor.apply();
    }

    public static boolean enabledRaiseTo(boolean speak) {
        return raiseToListen && (!speak || raiseToSpeak);
    }

    public static void toggleBrowserAdaptableColors() {
        adaptableColorInBrowser = !adaptableColorInBrowser;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("adaptableBrowser", adaptableColorInBrowser);
        editor.apply();
    }

    public static void toggleDebugVideoQualities() {
        debugVideoQualities = !debugVideoQualities;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("debugVideoQualities", debugVideoQualities);
        editor.apply();
    }

    public static void toggleLocalInstantView() {
        onlyLocalInstantView = !onlyLocalInstantView;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("onlyLocalInstantView", onlyLocalInstantView);
        editor.apply();
    }

    public static void toggleDirectShare() {
        directShare = !directShare;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("direct_share", directShare);
        editor.apply();
        ShortcutManagerCompat.removeAllDynamicShortcuts(ApplicationLoader.applicationContext);
        MediaDataController.getInstance(UserConfig.selectedAccount).buildShortcuts();
    }

    public static void toggleStreamMedia() {
        streamMedia = !streamMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamMedia", streamMedia);
        editor.apply();
    }

    public static void toggleSortContactsByName() {
        sortContactsByName = !sortContactsByName;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("sortContactsByName", sortContactsByName);
        editor.apply();
    }

    public static void toggleSortFilesByName() {
        sortFilesByName = !sortFilesByName;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("sortFilesByName", sortFilesByName);
        editor.apply();
    }

    public static void toggleStreamAllVideo() {
        streamAllVideo = !streamAllVideo;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamAllVideo", streamAllVideo);
        editor.apply();
    }

    public static void toggleStreamMkv() {
        streamMkv = !streamMkv;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamMkv", streamMkv);
        editor.apply();
    }

    public static void toggleSaveStreamMedia() {
        saveStreamMedia = !saveStreamMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("saveStreamMedia", saveStreamMedia);
        editor.apply();
    }

    public static void togglePauseMusicOnRecord() {
        pauseMusicOnRecord = !pauseMusicOnRecord;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pauseMusicOnRecord", pauseMusicOnRecord);
        editor.apply();
    }

    public static void togglePauseMusicOnMedia() {
        pauseMusicOnMedia = !pauseMusicOnMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pauseMusicOnMedia", pauseMusicOnMedia);
        editor.apply();
    }

    public static void toggleChatBlur() {
        LiteMode.toggleFlag(LiteMode.FLAG_CHAT_BLUR);
    }

    public static void toggleForceDisableTabletMode() {
        forceDisableTabletMode = !forceDisableTabletMode;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceDisableTabletMode", forceDisableTabletMode);
        editor.apply();
    }

    public static void toggleInappCamera() {
        inappCamera = !inappCamera;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("inappCamera", inappCamera);
        editor.apply();
    }

    public static void toggleRoundCamera16to9() {
        roundCamera16to9 = !roundCamera16to9;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("roundCamera16to9", roundCamera16to9);
        editor.apply();
    }

    public static void setDistanceSystemType(int type) {
        distanceSystemType = type;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("distanceSystemType", distanceSystemType);
        editor.apply();
        LocaleController.resetImperialSystemType();
    }

    public static void loadProxyList() {
        if (proxyListLoaded) {
            return;
        }
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        final ProxySettings proxySettings = ProxySettings.fromSharedPreferences(preferences);

        proxyListLoaded = true;
        proxyList.clear();
        currentProxy = null;
        String list = preferences.getString("proxy_list", null);
        if (!TextUtils.isEmpty(list)) {
            byte[] bytes = Base64.decode(list, Base64.DEFAULT);
            SerializedData data = new SerializedData(bytes);
            int count = data.readInt32(false);
            if (count == -1) { // V2 or newer
                int version = data.readByte(false);

                if (version == PROXY_SCHEMA_V2 || version == PROXY_SCHEMA_V3 || version == PROXY_SCHEMA_V4 || version == PROXY_SCHEMA_V5 || version == PROXY_SCHEMA_V6 || version == PROXY_SCHEMA_V7) {
                    count = data.readInt32(false);

                    for (int i = 0; i < count; i++) {
                        final ProxyInfo info = ProxyInfo.fromSerializedData(version, data);
                        proxyList.add(0, info);
                        if (currentProxy == null && proxySettings.isValid()) {
                            if (Objects.equals(proxySettings, info.settings)) {
                                currentProxy = info;
                            }
                        }
                    }
                } else {
                    FileLog.e("Unknown proxy schema version: " + version);
                }
            } else {
                for (int a = 0; a < count; a++) {
                    final ProxyInfo info = ProxyInfo.fromSerializedData(0, data);
                    proxyList.add(0, info);
                    if (currentProxy == null && proxySettings.isValid()) {
                        if (Objects.equals(proxySettings, info.settings)) {
                            currentProxy = info;
                        }
                    }
                }
            }
            data.cleanup();
        }
        if (currentProxy == null && proxySettings.isValid()) {
            ProxyInfo info = currentProxy = new ProxyInfo(proxySettings);
            proxyList.add(0, info);
        }
    }

    public static void saveProxyList() {
        List<ProxyInfo> infoToSerialize = new ArrayList<>(proxyList);
        Collections.sort(infoToSerialize, (o1, o2) -> {
            long bias1 = SharedConfig.currentProxy == o1 ? -200000 : 0;
            if (!o1.available) {
                bias1 += 100000;
            }
            long bias2 = SharedConfig.currentProxy == o2 ? -200000 : 0;
            if (!o2.available) {
                bias2 += 100000;
            }
            return Long.compare(o1.ping + bias1, o2.ping + bias2);
        });
        SerializedData serializedData = new SerializedData();
        serializedData.writeInt32(-1);
        serializedData.writeByte(PROXY_CURRENT_SCHEMA_VERSION);
        int count = infoToSerialize.size();
        serializedData.writeInt32(count);
        for (int a = count - 1; a >= 0; a--) {
            ProxyInfo info = infoToSerialize.get(a);
            info.toSerializedData(serializedData);
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putString("proxy_list", Base64.encodeToString(serializedData.toByteArray(), Base64.NO_WRAP)).apply();
        serializedData.cleanup();
    }

    public static ProxyInfo addProxy(ProxyInfo proxyInfo) {
        return addProxy(proxyInfo, false);
    }

    public static ProxyInfo addProxy(ProxyInfo proxyInfo, boolean fromSubscription) {
        loadProxyList();
        int count = proxyList.size();
        for (int a = 0; a < count; a++) {
            ProxyInfo info = proxyList.get(a);
            if (isSameProxy(proxyInfo, info)) {
                boolean changed = false;
                if (fromSubscription && !info.isSubscription) {
                    info.isSubscription = true;
                    changed = true;
                }
                if (fromSubscription && TextUtils.isEmpty(info.subscriptionName) && !TextUtils.isEmpty(proxyInfo.subscriptionName)) {
                    info.subscriptionName = proxyInfo.subscriptionName;
                    changed = true;
                }
                if (info.isXrayVless()) {
                    if (TextUtils.isEmpty(info.vlessRemark) && !TextUtils.isEmpty(proxyInfo.vlessRemark)) {
                        info.vlessRemark = proxyInfo.vlessRemark;
                        changed = true;
                    }
                } else {
                    if (TextUtils.isEmpty(info.proxyName) && !TextUtils.isEmpty(proxyInfo.proxyName)) {
                        info.proxyName = proxyInfo.proxyName;
                        changed = true;
                    }
                }
                if (changed) {
                    saveProxyList();
                }
                return info;
            }
        }
        if (fromSubscription) {
            proxyInfo.isSubscription = true;
        }
        proxyList.add(0, proxyInfo);
        saveProxyList();
        return proxyInfo;
    }

    private static boolean isSameProxy(ProxyInfo a, ProxyInfo b) {
        if (a == null || b == null) {
            return false;
        }
        if (!Objects.equals(a.settings, b.settings)) {
            return false;
        }
        if (a.isXrayShadowsocks() && b.isXrayShadowsocks()) {
            return TextUtils.equals(a.ssMethod, b.ssMethod)
                    && TextUtils.equals(a.ssPassword, b.ssPassword);
        }
        if (a.isXrayVmess() && b.isXrayVmess()) {
            return TextUtils.equals(a.vmessId, b.vmessId)
                    && TextUtils.equals(a.vmessAlterId, b.vmessAlterId)
                    && TextUtils.equals(a.vmessSecurity, b.vmessSecurity)
                    && TextUtils.equals(a.vlessType, b.vlessType)
                    && TextUtils.equals(a.vlessHost, b.vlessHost)
                    && TextUtils.equals(a.vlessPath, b.vlessPath)
                    && TextUtils.equals(a.vlessServiceName, b.vlessServiceName)
                    && TextUtils.equals(a.vlessSni, b.vlessSni)
                    && TextUtils.equals(a.vlessFp, b.vlessFp)
                    && TextUtils.equals(a.vlessAlpn, b.vlessAlpn)
                    && TextUtils.equals(a.vlessHeaderType, b.vlessHeaderType)
                    && a.vlessAllowInsecure == b.vlessAllowInsecure;
        }
        if (a.isXrayTrojan() && b.isXrayTrojan()) {
            return TextUtils.equals(a.trojanPassword, b.trojanPassword)
                    && TextUtils.equals(a.trojanSni, b.trojanSni)
                    && TextUtils.equals(a.trojanAllowInsecure, b.trojanAllowInsecure)
                    && TextUtils.equals(a.vlessType, b.vlessType)
                    && TextUtils.equals(a.vlessHost, b.vlessHost)
                    && TextUtils.equals(a.vlessPath, b.vlessPath)
                    && TextUtils.equals(a.vlessServiceName, b.vlessServiceName)
                    && TextUtils.equals(a.vlessFp, b.vlessFp)
                    && TextUtils.equals(a.vlessAlpn, b.vlessAlpn)
                    && TextUtils.equals(a.vlessHeaderType, b.vlessHeaderType);
        }
        if (a.isXrayWireguard() && b.isXrayWireguard()) {
            return TextUtils.equals(a.wgPrivateKey, b.wgPrivateKey)
                    && TextUtils.equals(a.wgPublicKey, b.wgPublicKey)
                    && TextUtils.equals(a.wgEndpoint, b.wgEndpoint)
                    && TextUtils.equals(a.wgAllowedIps, b.wgAllowedIps)
                    && TextUtils.equals(a.wgLocalAddress, b.wgLocalAddress)
                    && TextUtils.equals(a.wgMtu, b.wgMtu)
                    && TextUtils.equals(a.wgKeepAlive, b.wgKeepAlive)
                    && TextUtils.equals(a.wgPresharedKey, b.wgPresharedKey)
                    && TextUtils.equals(a.wgDns, b.wgDns);
        }
        if (a.isXrayHysteria2() && b.isXrayHysteria2()) {
            return TextUtils.equals(a.hyPassword, b.hyPassword)
                    && TextUtils.equals(a.hySni, b.hySni)
                    && TextUtils.equals(a.hyAlpn, b.hyAlpn)
                    && TextUtils.equals(a.hyAllowInsecure, b.hyAllowInsecure)
                    && TextUtils.equals(a.hyObfsPassword, b.hyObfsPassword)
                    && TextUtils.equals(a.hyUpMbps, b.hyUpMbps)
                    && TextUtils.equals(a.hyDownMbps, b.hyDownMbps);
        }
        if (a.isXrayVless() && b.isXrayVless()) {
            return TextUtils.equals(a.vlessId, b.vlessId)
                    && TextUtils.equals(a.vlessSecurity, b.vlessSecurity)
                    && TextUtils.equals(a.vlessType, b.vlessType)
                    && TextUtils.equals(a.vlessFlow, b.vlessFlow)
                    && TextUtils.equals(a.vlessHost, b.vlessHost)
                    && TextUtils.equals(a.vlessPath, b.vlessPath)
                    && TextUtils.equals(a.vlessServiceName, b.vlessServiceName)
                    && TextUtils.equals(a.vlessSni, b.vlessSni)
                    && TextUtils.equals(a.vlessFp, b.vlessFp)
                    && TextUtils.equals(a.vlessAlpn, b.vlessAlpn)
                    && TextUtils.equals(a.vlessPublicKey, b.vlessPublicKey)
                    && TextUtils.equals(a.vlessShortId, b.vlessShortId)
                    && TextUtils.equals(a.vlessSpiderX, b.vlessSpiderX)
                    && TextUtils.equals(a.vlessHeaderType, b.vlessHeaderType)
                    && TextUtils.equals(a.vlessSeed, b.vlessSeed)
                    && TextUtils.equals(a.vlessQuicSecurity, b.vlessQuicSecurity)
                    && TextUtils.equals(a.vlessQuicKey, b.vlessQuicKey)
                    && TextUtils.equals(a.vlessMode, b.vlessMode)
                    && a.vlessAllowInsecure == b.vlessAllowInsecure
                    && TextUtils.equals(a.vlessAdvancedJson, b.vlessAdvancedJson);
        }
        if (a.isAether() && b.isAether()) {
            return TextUtils.equals(a.aetherProtocol, b.aetherProtocol)
                    && TextUtils.equals(a.aetherScan, b.aetherScan)
                    && TextUtils.equals(a.aetherIp, b.aetherIp)
                    && TextUtils.equals(a.aetherTransport, b.aetherTransport)
                    && a.aetherFragment == b.aetherFragment
                    && TextUtils.equals(a.aetherNoize, b.aetherNoize)
                    && a.aetherQuickReconnect == b.aetherQuickReconnect
                    && TextUtils.equals(a.aetherPeers, b.aetherPeers)
                    && TextUtils.equals(a.aetherApiAddress, b.aetherApiAddress)
                    && TextUtils.equals(a.aetherApiHost, b.aetherApiHost)
                    && TextUtils.equals(a.aetherApiSni, b.aetherApiSni)
                    && TextUtils.equals(a.aetherMasqueSni, b.aetherMasqueSni)
                    && TextUtils.equals(a.aetherTls13Ciphers, b.aetherTls13Ciphers)
                    && TextUtils.equals(a.aetherTlsKeyShares, b.aetherTlsKeyShares)
                    && TextUtils.equals(a.aetherTlsSigalgs, b.aetherTlsSigalgs)
                    && TextUtils.equals(a.aetherTlsCertCompression, b.aetherTlsCertCompression)
                    && TextUtils.equals(a.aetherTlsAlps, b.aetherTlsAlps)
                    && a.aetherGreaseSigalgs == b.aetherGreaseSigalgs;
        }
        return true;
    }

    public static boolean isProxyEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false) && currentProxy != null;
    }

    public static void deleteProxy(ProxyInfo proxyInfo) {
        if (currentProxy == proxyInfo) {
            currentProxy = null;
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean enabled = preferences.getBoolean("proxy_enabled", false);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("proxy_ip", "");
            editor.putString("proxy_pass", "");
            editor.putString("proxy_user", "");
            editor.putString("proxy_secret", "");
            editor.putInt("proxy_type", 0);
            editor.putInt("proxy_port", 1080);
            editor.putBoolean("proxy_enabled", false);
            editor.apply();
            if (enabled) {
                ConnectionsManager.setProxySettings(false, null);
            }
            if (proxyInfo.isXray()) {
                XrayProxyManager.stopService();
            }
            if (proxyInfo.isAether()) {
                AetherProxyManager.stopService();
            }
        }
        proxyList.remove(proxyInfo);
        saveProxyList();
    }

    public static void checkSaveToGalleryFiles() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File telegramPath = new File(Environment.getExternalStorageDirectory(), "Telegram");
                File imagePath = new File(telegramPath, "Telegram Images");
                imagePath.mkdir();
                File videoPath = new File(telegramPath, "Telegram Video");
                videoPath.mkdir();

                if (!BuildVars.NO_SCOPED_STORAGE) {
                    if (imagePath.isDirectory()) {
                        new File(imagePath, ".nomedia").delete();
                    }
                    if (videoPath.isDirectory()) {
                        new File(videoPath, ".nomedia").delete();
                    }
                } else {
                    if (imagePath.isDirectory()) {
                        AndroidUtilities.createEmptyFile(new File(imagePath, ".nomedia"));
                    }
                    if (videoPath.isDirectory()) {
                        AndroidUtilities.createEmptyFile(new File(videoPath, ".nomedia"));
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static int getChatSwipeAction(int currentAccount) {
        if (chatSwipeAction >= 0) {
            if (chatSwipeAction == SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS && MessagesController.getInstance(currentAccount).dialogFilters.isEmpty()) {
                return SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE;
            }
            return chatSwipeAction;
        } else if (!MessagesController.getInstance(currentAccount).dialogFilters.isEmpty()) {
            return SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS;

        }
        return SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE;
    }

    public static void updateChatListSwipeSetting(int newAction) {
        chatSwipeAction = newAction;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("ChatSwipeAction", chatSwipeAction).apply();
    }

    public static void updateMessageSeenHintCount(int count) {
        messageSeenHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("messageSeenCount", messageSeenHintCount).apply();
    }

    public static void updateEmojiInteractionsHintCount(int count) {
        emojiInteractionsHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("emojiInteractionsHintCount", emojiInteractionsHintCount).apply();
    }

    public static void updateDayNightThemeSwitchHintCount(int count) {
        dayNightThemeSwitchHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("dayNightThemeSwitchHintCount", dayNightThemeSwitchHintCount).apply();
    }

    public static void updateStealthModeSendMessageConfirm(int count) {
        stealthModeSendMessageConfirm = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("stealthModeSendMessageConfirm", stealthModeSendMessageConfirm).apply();
    }

    public final static int PERFORMANCE_CLASS_LOW = 0;
    public final static int PERFORMANCE_CLASS_AVERAGE = 1;
    public final static int PERFORMANCE_CLASS_HIGH = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PERFORMANCE_CLASS_LOW,
            PERFORMANCE_CLASS_AVERAGE,
            PERFORMANCE_CLASS_HIGH
    })
    public @interface PerformanceClass {}

    @PerformanceClass
    public static int getDevicePerformanceClass() {
        if (overrideDevicePerformanceClass != -1) {
            return overrideDevicePerformanceClass;
        }
        if (devicePerformanceClass == -1) {
            devicePerformanceClass = measureDevicePerformanceClass();
        }
        return devicePerformanceClass;
    }

    public static int measureDevicePerformanceClass() {
        int androidVersion = Build.VERSION.SDK_INT;
        int cpuCount = ConnectionsManager.CPU_COUNT;
        int memoryClass = ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null) {
            int hash = Build.SOC_MODEL.toUpperCase().hashCode();
            for (int i = 0; i < LOW_SOC.length; ++i) {
                if (LOW_SOC[i] == hash) {
                    return PERFORMANCE_CLASS_LOW;
                }
            }
        }

        int totalCpuFreq = 0;
        int freqResolved = 0;
        for (int i = 0; i < cpuCount; i++) {
            try {
                RandomAccessFile reader = new RandomAccessFile(String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r");
                String line = reader.readLine();
                if (line != null) {
                    totalCpuFreq += Utilities.parseInt(line) / 1000;
                    freqResolved++;
                }
                reader.close();
            } catch (Throwable ignore) {}
        }
        int maxCpuFreq = freqResolved == 0 ? -1 : (int) Math.ceil(totalCpuFreq / (float) freqResolved);

        long ram = -1;
        try {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memoryInfo);
            ram = memoryInfo.totalMem;
        } catch (Exception ignore) {}

        int performanceClass;
        if (
            androidVersion < 21 ||
            cpuCount <= 2 ||
            memoryClass <= 100 ||
            cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 ||
            cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 ||
            cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24 ||
            ram != -1 && ram < 2L * 1024L * 1024L * 1024L
        ) {
            performanceClass = PERFORMANCE_CLASS_LOW;
        } else if (
            cpuCount < 8 ||
            memoryClass <= 160 ||
            maxCpuFreq != -1 && maxCpuFreq <= 2055 ||
            maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23
        ) {
            performanceClass = PERFORMANCE_CLASS_AVERAGE;
        } else {
            performanceClass = PERFORMANCE_CLASS_HIGH;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("device performance info selected_class = " + performanceClass + " (cpu_count = " + cpuCount + ", freq = " + maxCpuFreq + ", memoryClass = " + memoryClass + ", android version " + androidVersion + ", manufacture " + Build.MANUFACTURER + ", screenRefreshRate=" + AndroidUtilities.screenRefreshRate + ", screenMaxRefreshRate=" + AndroidUtilities.screenMaxRefreshRate + ")");
        }

        return performanceClass;
    }

    public static String performanceClassName(int perfClass) {
        switch (perfClass) {
            case PERFORMANCE_CLASS_HIGH: return "HIGH";
            case PERFORMANCE_CLASS_AVERAGE: return "AVERAGE";
            case PERFORMANCE_CLASS_LOW: return "LOW";
            default: return "UNKNOWN";
        }
    }

    public static void setMediaColumnsCount(int count) {
        if (mediaColumnsCount != count) {
            mediaColumnsCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("mediaColumnsCount", mediaColumnsCount).apply();
        }
    }

    public static void setStoriesColumnsCount(int count) {
        if (storiesColumnsCount != count) {
            storiesColumnsCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("storiesColumnsCount", storiesColumnsCount).apply();
        }
    }

    public static void setFastScrollHintCount(int count) {
        if (fastScrollHintCount != count) {
            fastScrollHintCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("fastScrollHintCount", fastScrollHintCount).apply();
        }
    }

    public static void setDontAskManageStorage(boolean b) {
        dontAskManageStorage = b;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putBoolean("dontAskManageStorage", dontAskManageStorage).apply();
    }

    public static boolean canBlurChat() {
        return getDevicePerformanceClass() >= (Build.VERSION.SDK_INT >= 31 ? PERFORMANCE_CLASS_AVERAGE : PERFORMANCE_CLASS_HIGH) || BuildVars.DEBUG_PRIVATE_VERSION;
    }

    public static boolean chatBlurEnabled() {
        return canBlurChat() && LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR);
    }

    public static class BackgroundActivityPrefs {
        private static SharedPreferences prefs;

        public static long getLastCheckedBackgroundActivity() {
            return prefs.getLong("last_checked", 0);
        }

        public static void setLastCheckedBackgroundActivity(long l) {
            prefs.edit().putLong("last_checked", l).apply();
        }

        public static int getDismissedCount() {
            return prefs.getInt("dismissed_count", 0);
        }

        public static void increaseDismissedCount() {
            prefs.edit().putInt("dismissed_count", getDismissedCount() + 1).apply();
        }
    }

    private static Boolean animationsEnabled;

    public static void setAnimationsEnabled(boolean b) {
        animationsEnabled = b;
    }

    public static boolean animationsEnabled() {
        if (animationsEnabled == null) {
            animationsEnabled = MessagesController.getGlobalMainSettings().getBoolean("view_animations", true);
        }
        return animationsEnabled;
    }

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
    }

    public static boolean deviceIsLow() {
        return getDevicePerformanceClass() == PERFORMANCE_CLASS_LOW;
    }

    public static boolean deviceIsAboveAverage() {
        return getDevicePerformanceClass() >= PERFORMANCE_CLASS_AVERAGE;
    }

    public static boolean deviceIsHigh() {
        return getDevicePerformanceClass() >= PERFORMANCE_CLASS_HIGH;
    }

    public static boolean deviceIsAverage() {
        return getDevicePerformanceClass() <= PERFORMANCE_CLASS_AVERAGE;
    }

    public static void toggleRoundCamera() {
        bigCameraForRound = !bigCameraForRound;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("bigCameraForRound", bigCameraForRound)
                .apply();
    }

    public static void toggleUseNewBlur() {
        useNewBlur = !useNewBlur;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useNewBlur", useNewBlur)
                .apply();
    }

    public static boolean isUsingCamera2(int currentAccount) {
        return useCamera2Force == null ? !MessagesController.getInstance(currentAccount).androidDisableRoundCamera2 : useCamera2Force;
    }

    public static void toggleUseCamera2(int currentAccount) {
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useCamera2Force_2", useCamera2Force = !isUsingCamera2(currentAccount))
                .apply();
    }


    @Deprecated
    public static int getLegacyDevicePerformanceClass() {
        if (legacyDevicePerformanceClass == -1) {
            int androidVersion = Build.VERSION.SDK_INT;
            int cpuCount = ConnectionsManager.CPU_COUNT;
            int memoryClass = ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
            int totalCpuFreq = 0;
            int freqResolved = 0;
            for (int i = 0; i < cpuCount; i++) {
                try {
                    RandomAccessFile reader = new RandomAccessFile(String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r");
                    String line = reader.readLine();
                    if (line != null) {
                        totalCpuFreq += Utilities.parseInt(line) / 1000;
                        freqResolved++;
                    }
                    reader.close();
                } catch (Throwable ignore) {}
            }
            int maxCpuFreq = freqResolved == 0 ? -1 : (int) Math.ceil(totalCpuFreq / (float) freqResolved);

            if (androidVersion < 21 || cpuCount <= 2 || memoryClass <= 100 || cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 || cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 || cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24) {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_LOW;
            } else if (cpuCount < 8 || memoryClass <= 160 || maxCpuFreq != -1 && maxCpuFreq <= 2050 || maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23) {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_AVERAGE;
            } else {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_HIGH;
            }
        }
        return legacyDevicePerformanceClass;
    }


    //DEBUG
    public static boolean drawActionBarShadow = true;

    private static void loadDebugConfig(SharedPreferences preferences) {
        drawActionBarShadow = preferences.getBoolean("drawActionBarShadow", true);
    }

    public static void saveDebugConfig() {
        SharedPreferences pref = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        pref.edit().putBoolean("drawActionBarShadow", drawActionBarShadow);
    }



}
