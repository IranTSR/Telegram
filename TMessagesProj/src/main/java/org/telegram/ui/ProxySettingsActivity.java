/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.XrayProxyManager;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.utils.proxy.WebProxyTransport;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.SectionsScrollView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;

public class ProxySettingsActivity extends BaseFragment {

    private final static int FIELD_IP = 0;
    private final static int FIELD_PORT = 1;
    private final static int FIELD_USER = 2;
    private final static int FIELD_PASSWORD = 3;
    private final static int FIELD_SECRET = 4;
    private final static int FIELD_VLESS_ID = 5;
    private final static int FIELD_VLESS_ENCRYPTION = 6;
    private final static int FIELD_VLESS_FLOW = 7;
    private final static int FIELD_VLESS_SECURITY = 8;
    private final static int FIELD_VLESS_TRANSPORT = 9;
    private final static int FIELD_VLESS_SNI = 10;
    private final static int FIELD_VLESS_HOST = 11;
    private final static int FIELD_VLESS_PATH = 12;
    private final static int FIELD_VLESS_SERVICE = 13;
    private final static int FIELD_VLESS_FP = 14;
    private final static int FIELD_VLESS_ALPN = 15;
    private final static int FIELD_VLESS_PBK = 16;
    private final static int FIELD_VLESS_SID = 17;
    private final static int FIELD_VLESS_SPX = 18;
    private final static int FIELD_VLESS_HEADER_TYPE = 19;
    private final static int FIELD_VLESS_SEED = 20;
    private final static int FIELD_VLESS_QUIC_SECURITY = 21;
    private final static int FIELD_VLESS_QUIC_KEY = 22;
    private final static int FIELD_VLESS_MODE = 23;
    private final static int FIELD_VLESS_ALLOW_INSECURE = 24;
    private final static int FIELD_VLESS_ADVANCED_JSON = 25;

    private EditTextBoldCursor[] inputFields;
    private ScrollView scrollView;
    private LinearLayout linearLayout2;
    private LinearLayout inputFieldsContainer;
    private HeaderCell headerCell;
    private ShadowSectionCell[] sectionCell = new ShadowSectionCell[3];
    private TextInfoPrivacyCell[] bottomCells = new TextInfoPrivacyCell[3];
    private TextSettingsCell shareCell;
    private TextSettingsCell pasteCell;
    private TextSettingsCell redownloadCell;
    private TextSettingsCell xrayStatusCell;
    private ActionBarMenuItem doneItem;
    private boolean xrayStatusUpdates;
    private final Runnable xrayStatusUpdater = new Runnable() {
        @Override
        public void run() {
            if (!xrayStatusUpdates) {
                return;
            }
            updateXrayStatusCell();
            AndroidUtilities.runOnUIThread(this, 500);
        }
    };
    private RadioCell[] typeCell = new RadioCell[4];
    private ProxySettings.Type currentType;

    private ProxySettings pasteProxySettings;
    private SharedConfig.ProxyInfo pasteProxyInfo;
    private String pasteString;

    private float shareDoneProgress = 1f;
    private float[] shareDoneProgressAnimValues = new float[2];
    private boolean shareDoneEnabled = true;
    private ValueAnimator shareDoneAnimator;

    private ClipboardManager clipboardManager;

    private boolean addingNewProxy;

    private SharedConfig.ProxyInfo currentProxyInfo;

    private boolean ignoreOnTextChange;

    private static final int done_button = 1;

    public static class TypeCell extends FrameLayout {

        private TextView textView;
        private ImageView checkImage;
        private boolean needDivider;

        public TypeCell(Context context) {
            super(context);

            setWillNotDraw(false);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 23 + 48 : 21, 0, LocaleController.isRTL ? 21 : 23, 0));

            checkImage = new ImageView(context);
            checkImage.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addedIcon), PorterDuff.Mode.MULTIPLY));
            checkImage.setImageResource(R.drawable.sticker_added);
            addView(checkImage, LayoutHelper.createFrame(19, 14, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        public void setValue(String name, boolean checked, boolean divider) {
            textView.setText(name);
            checkImage.setVisibility(checked ? VISIBLE : INVISIBLE);
            needDivider = divider;
        }

        public void setTypeChecked(boolean value) {
            checkImage.setVisibility(value ? VISIBLE : INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    public ProxySettingsActivity() {
        super();
        currentProxyInfo = new SharedConfig.ProxyInfo(ProxySettings.EMPTY);
        addingNewProxy = true;
    }

    public ProxySettingsActivity(SharedConfig.ProxyInfo proxyInfo) {
        super();
        currentProxyInfo = proxyInfo;
    }

    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updatePasteCell;

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        updatePasteCell();
        xrayStatusUpdates = true;
        AndroidUtilities.runOnUIThread(xrayStatusUpdater);
    }

    @Override
    public void onPause() {
        super.onPause();
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        xrayStatusUpdates = false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.ProxyDetails));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    if (getParentActivity() == null) {
                        return;
                    }

                    currentProxyInfo.settings = ProxySettings.builder()
                        .setType(currentType)
                        .setAddress(inputFields[FIELD_IP].getText().toString())
                        .setPort(currentType == ProxySettings.Type.WEB ? 0 : Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()))
                        .setUser(currentType == ProxySettings.Type.SOCKS5 ? inputFields[FIELD_USER].getText().toString() : "")
                        .setPassword(currentType == ProxySettings.Type.SOCKS5 ? inputFields[FIELD_PASSWORD].getText().toString() : "")
                        .setSecret(currentType != ProxySettings.Type.SOCKS5 && currentType != ProxySettings.Type.XRAY_VLESS ? inputFields[FIELD_SECRET].getText().toString() : "")
                        .build();
                    if (currentType == ProxySettings.Type.XRAY_VLESS) {
                        currentProxyInfo.vlessId = inputFields[FIELD_VLESS_ID].getText().toString();
                        currentProxyInfo.vlessEncryption = inputFields[FIELD_VLESS_ENCRYPTION].getText().toString();
                        currentProxyInfo.vlessFlow = inputFields[FIELD_VLESS_FLOW].getText().toString();
                        currentProxyInfo.vlessSecurity = inputFields[FIELD_VLESS_SECURITY].getText().toString();
                        currentProxyInfo.vlessType = inputFields[FIELD_VLESS_TRANSPORT].getText().toString();
                        currentProxyInfo.vlessSni = inputFields[FIELD_VLESS_SNI].getText().toString();
                        currentProxyInfo.vlessHost = inputFields[FIELD_VLESS_HOST].getText().toString();
                        currentProxyInfo.vlessPath = inputFields[FIELD_VLESS_PATH].getText().toString();
                        currentProxyInfo.vlessServiceName = inputFields[FIELD_VLESS_SERVICE].getText().toString();
                        currentProxyInfo.vlessFp = inputFields[FIELD_VLESS_FP].getText().toString();
                        currentProxyInfo.vlessAlpn = inputFields[FIELD_VLESS_ALPN].getText().toString();
                        currentProxyInfo.vlessPublicKey = inputFields[FIELD_VLESS_PBK].getText().toString();
                        currentProxyInfo.vlessShortId = inputFields[FIELD_VLESS_SID].getText().toString();
                        currentProxyInfo.vlessSpiderX = inputFields[FIELD_VLESS_SPX].getText().toString();
                        currentProxyInfo.vlessHeaderType = inputFields[FIELD_VLESS_HEADER_TYPE].getText().toString();
                        currentProxyInfo.vlessSeed = inputFields[FIELD_VLESS_SEED].getText().toString();
                        currentProxyInfo.vlessQuicSecurity = inputFields[FIELD_VLESS_QUIC_SECURITY].getText().toString();
                        currentProxyInfo.vlessQuicKey = inputFields[FIELD_VLESS_QUIC_KEY].getText().toString();
                        currentProxyInfo.vlessMode = inputFields[FIELD_VLESS_MODE].getText().toString();
                        String allowInsecureValue = inputFields[FIELD_VLESS_ALLOW_INSECURE].getText().toString();
                        currentProxyInfo.vlessAllowInsecure = "1".equals(allowInsecureValue) || "true".equalsIgnoreCase(allowInsecureValue);
                        currentProxyInfo.vlessAdvancedJson = inputFields[FIELD_VLESS_ADVANCED_JSON].getText().toString();
                        if (TextUtils.isEmpty(currentProxyInfo.vlessEncryption)) {
                            currentProxyInfo.vlessEncryption = "none";
                        }
                        currentProxyInfo.normalizeVlessFields();
                    }

                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    SharedPreferences.Editor editor = preferences.edit();
                    boolean enabled;
                    if (addingNewProxy) {
                        SharedConfig.addProxy(currentProxyInfo);
                        SharedConfig.currentProxy = currentProxyInfo;
                        editor.putBoolean("proxy_enabled", true);
                        enabled = true;
                    } else {
                        enabled = preferences.getBoolean("proxy_enabled", false);
                        SharedConfig.saveProxyList();
                    }
                    if (addingNewProxy || SharedConfig.currentProxy == currentProxyInfo) {
                        currentProxyInfo.settings.toSharedPreferences(editor);
                        ConnectionsManager.setProxySettings(enabled, currentProxyInfo.settings);
                    }
                    editor.commit();

                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

                    finishFragment();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        linearLayout2 = new SectionsScrollView.SectionsLinearLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                // todo: update to recyclerview
                super.dispatchDraw(canvas);
                invalidate();
            }
        };
        SectionsScrollView sectionsScrollView;
        scrollView = sectionsScrollView = new SectionsScrollView(context, linearLayout2, resourceProvider) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                invalidate();
            }
        };
        actionBar.setAdaptiveBackground(sectionsScrollView);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout2, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View.OnClickListener typeCellClickListener = view -> setProxyType(ProxySettings.intToType((Integer) view.getTag()), true);

        for (int a = 0; a < 4; a++) {
            ProxySettings.Type t = ProxySettings.intToType(a);

            typeCell[a] = new RadioCell(context);
            typeCell[a].setBackground(Theme.getSelectorDrawable(true));
            typeCell[a].setTag(a);
            if (a == 0) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxySocks5), t == currentType, true);
            } else if (a == 1) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyTelegram), t == currentType, true);
            } else if (a == 2) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyWeb), t == currentType, true);
            } else {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyXrayVless), t == currentType, false);
            }
            linearLayout2.addView(typeCell[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
            typeCell[a].setOnClickListener(typeCellClickListener);
        }

        sectionCell[0] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFieldsContainer = new LinearLayout(context);
        inputFieldsContainer.setOrientation(LinearLayout.VERTICAL);
         // inputFieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));


        // bring to front for transitions
        inputFieldsContainer.setElevation(AndroidUtilities.dp(1f));
        inputFieldsContainer.setOutlineProvider(null);
        linearLayout2.addView(inputFieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[26];
        for (int a = 0; a < 26; a++) {
            FrameLayout container = new FrameLayout(context);
            inputFieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

            inputFields[a] = new EditTextBoldCursor(context);
            inputFields[a].setTag(a);
            inputFields[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            inputFields[a].setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            inputFields[a].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setBackground(null);
            inputFields[a].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setCursorSize(AndroidUtilities.dp(20));
            inputFields[a].setCursorWidth(1.5f);
            inputFields[a].setSingleLine(true);
            inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            inputFields[a].setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            inputFields[a].setTransformHintToHeader(true);
            inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));

            if (a == FIELD_IP) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PORT) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_NUMBER);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (ignoreOnTextChange) {
                            return;
                        }
                        EditText phoneField = inputFields[FIELD_PORT];
                        int start = phoneField.getSelectionStart();
                        String chars = "0123456789";
                        String str = phoneField.getText().toString();
                        StringBuilder builder = new StringBuilder(str.length());
                        for (int a = 0; a < str.length(); a++) {
                            String ch = str.substring(a, a + 1);
                            if (chars.contains(ch)) {
                                builder.append(ch);
                            }
                        }
                        ignoreOnTextChange = true;
                        boolean changed;
                        int port = Utilities.parseInt(builder.toString());
                        if (port < 0 || port > 65535 || !str.equals(builder.toString())) {
                            if (port < 0) {
                                phoneField.setText("0");
                            } else if (port > 65535) {
                                phoneField.setText("65535");
                            } else {
                                phoneField.setText(builder.toString());
                            }
                        } else {
                            if (start >= 0) {
                                phoneField.setSelection(Math.min(start, phoneField.length()));
                            }
                        }
                        ignoreOnTextChange = false;
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PASSWORD) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                inputFields[a].setTypeface(Typeface.DEFAULT);
                inputFields[a].setTransformationMethod(PasswordTransformationMethod.getInstance());
            } else if (a == FIELD_VLESS_ID) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_VLESS_ALLOW_INSECURE) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_NUMBER);
            } else if (a == FIELD_VLESS_ADVANCED_JSON) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                inputFields[a].setMinLines(2);
                inputFields[a].setMaxLines(6);
            } else {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            if (a == FIELD_SECRET) {
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            }
            inputFields[a].setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            switch (a) {
                case FIELD_IP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAddress));
                    inputFields[a].setText(currentProxyInfo.settings.getAddress());
                    break;
                case FIELD_PASSWORD:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                    inputFields[a].setText(currentProxyInfo.settings.getPassword());
                    break;
                case FIELD_PORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPort));
                    inputFields[a].setText(Integer.toString(currentProxyInfo.settings.getPort()));
                    break;
                case FIELD_USER:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyUsername));
                    inputFields[a].setText(currentProxyInfo.settings.getUser());
                    break;
                case FIELD_SECRET:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxySecret));
                    inputFields[a].setText(currentProxyInfo.settings.getSecret());
                    break;
                case FIELD_VLESS_ID:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessId));
                    inputFields[a].setText(currentProxyInfo.vlessId);
                    break;
                case FIELD_VLESS_ENCRYPTION:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessEncryption));
                    inputFields[a].setText(currentProxyInfo.vlessEncryption);
                    break;
                case FIELD_VLESS_FLOW:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessFlow));
                    inputFields[a].setText(currentProxyInfo.vlessFlow);
                    break;
                case FIELD_VLESS_SECURITY:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessSecurity));
                    inputFields[a].setText(currentProxyInfo.vlessSecurity);
                    break;
                case FIELD_VLESS_TRANSPORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessTransport));
                    inputFields[a].setText(currentProxyInfo.vlessType);
                    break;
                case FIELD_VLESS_SNI:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessSni));
                    inputFields[a].setText(currentProxyInfo.vlessSni);
                    break;
                case FIELD_VLESS_HOST:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessHost));
                    inputFields[a].setText(currentProxyInfo.vlessHost);
                    break;
                case FIELD_VLESS_PATH:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessPath));
                    inputFields[a].setText(currentProxyInfo.vlessPath);
                    break;
                case FIELD_VLESS_SERVICE:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessServiceName));
                    inputFields[a].setText(currentProxyInfo.vlessServiceName);
                    break;
                case FIELD_VLESS_FP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessFingerprint));
                    inputFields[a].setText(currentProxyInfo.vlessFp);
                    break;
                case FIELD_VLESS_ALPN:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessAlpn));
                    inputFields[a].setText(currentProxyInfo.vlessAlpn);
                    break;
                case FIELD_VLESS_PBK:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessPublicKey));
                    inputFields[a].setText(currentProxyInfo.vlessPublicKey);
                    break;
                case FIELD_VLESS_SID:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessShortId));
                    inputFields[a].setText(currentProxyInfo.vlessShortId);
                    break;
                case FIELD_VLESS_SPX:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessSpiderX));
                    inputFields[a].setText(currentProxyInfo.vlessSpiderX);
                    break;
                case FIELD_VLESS_HEADER_TYPE:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessHeaderType));
                    inputFields[a].setText(currentProxyInfo.vlessHeaderType);
                    break;
                case FIELD_VLESS_SEED:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessSeed));
                    inputFields[a].setText(currentProxyInfo.vlessSeed);
                    break;
                case FIELD_VLESS_QUIC_SECURITY:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessQuicSecurity));
                    inputFields[a].setText(currentProxyInfo.vlessQuicSecurity);
                    break;
                case FIELD_VLESS_QUIC_KEY:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessQuicKey));
                    inputFields[a].setText(currentProxyInfo.vlessQuicKey);
                    break;
                case FIELD_VLESS_MODE:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessMode));
                    inputFields[a].setText(currentProxyInfo.vlessMode);
                    break;
                case FIELD_VLESS_ALLOW_INSECURE:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessAllowInsecure));
                    inputFields[a].setText(currentProxyInfo.vlessAllowInsecure ? "1" : "");
                    break;
                case FIELD_VLESS_ADVANCED_JSON:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyVlessAdvancedJson));
                    inputFields[a].setText(currentProxyInfo.vlessAdvancedJson);
                    break;
            }
            inputFields[a].setSelection(inputFields[a].length());

            inputFields[a].setPadding(0, 0, 0, 0);
            container.addView(inputFields[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, a == FIELD_IP ? 12 : 0, 17, 0));

            inputFields[a].setOnEditorActionListener((textView, i, keyEvent) -> {
                if (i == EditorInfo.IME_ACTION_NEXT) {
                    int num = (Integer) textView.getTag();
                    if (num + 1 < inputFields.length) {
                        num++;
                        inputFields[num].requestFocus();
                    }
                    return true;
                } else if (i == EditorInfo.IME_ACTION_DONE) {
                    finishFragment();
                    return true;
                }
                return false;
            });
        }

        for (int i = 0; i < 3; i++) {
            bottomCells[i] = new TextInfoPrivacyCell(context);
            bottomCells[i].setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            if (i == 0) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyInfo));
            } else if (i == 1) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                bottomCells[i].setVisibility(View.GONE);
            } else {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyXrayVlessInfo));
                bottomCells[i].setVisibility(View.GONE);
            }
            linearLayout2.addView(bottomCells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        pasteCell = new TextSettingsCell(fragmentView.getContext());
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> {
            if (pasteProxySettings != null) {
                final ProxySettings.Type pasteType = pasteProxySettings.getType();
                final SharedConfig.ProxyInfo pasteInfo = pasteProxyInfo;

                for (int i = 0; i < inputFields.length; i++) {
                    if (pasteType == ProxySettings.Type.SOCKS5 && i == FIELD_SECRET) {
                        continue;
                    }
                    if (pasteType == ProxySettings.Type.MTPROTO && (i == FIELD_USER || i == FIELD_PASSWORD)) {
                        continue;
                    }
                    if (pasteType == ProxySettings.Type.XRAY_VLESS && (i == FIELD_USER || i == FIELD_PASSWORD || i == FIELD_SECRET)) {
                        continue;
                    }

                    String field = null;
                    if (i == FIELD_IP) {
                        field = pasteProxySettings.getAddress();
                    } else if (i == FIELD_PORT) {
                        final int port = pasteProxySettings.getPort();
                        field = port != 0 ? Integer.toString(port) : null;
                    } else if (i == FIELD_USER) {
                        field = pasteProxySettings.getUser();
                    } else if (i == FIELD_PASSWORD) {
                        field = pasteProxySettings.getPassword();
                    } else if (i == FIELD_SECRET) {
                        field = pasteProxySettings.getSecret();
                    } else if (pasteType == ProxySettings.Type.XRAY_VLESS && pasteInfo != null) {
                        if (i == FIELD_VLESS_ID) field = pasteInfo.vlessId;
                        else if (i == FIELD_VLESS_ENCRYPTION) field = pasteInfo.vlessEncryption;
                        else if (i == FIELD_VLESS_FLOW) field = pasteInfo.vlessFlow;
                        else if (i == FIELD_VLESS_SECURITY) field = pasteInfo.vlessSecurity;
                        else if (i == FIELD_VLESS_TRANSPORT) field = pasteInfo.vlessType;
                        else if (i == FIELD_VLESS_SNI) field = pasteInfo.vlessSni;
                        else if (i == FIELD_VLESS_HOST) field = pasteInfo.vlessHost;
                        else if (i == FIELD_VLESS_PATH) field = pasteInfo.vlessPath;
                        else if (i == FIELD_VLESS_SERVICE) field = pasteInfo.vlessServiceName;
                        else if (i == FIELD_VLESS_FP) field = pasteInfo.vlessFp;
                        else if (i == FIELD_VLESS_ALPN) field = pasteInfo.vlessAlpn;
                        else if (i == FIELD_VLESS_PBK) field = pasteInfo.vlessPublicKey;
                        else if (i == FIELD_VLESS_SID) field = pasteInfo.vlessShortId;
                        else if (i == FIELD_VLESS_SPX) field = pasteInfo.vlessSpiderX;
                        else if (i == FIELD_VLESS_HEADER_TYPE) field = pasteInfo.vlessHeaderType;
                        else if (i == FIELD_VLESS_SEED) field = pasteInfo.vlessSeed;
                        else if (i == FIELD_VLESS_QUIC_SECURITY) field = pasteInfo.vlessQuicSecurity;
                        else if (i == FIELD_VLESS_QUIC_KEY) field = pasteInfo.vlessQuicKey;
                        else if (i == FIELD_VLESS_MODE) field = pasteInfo.vlessMode;
                        else if (i == FIELD_VLESS_ALLOW_INSECURE) field = pasteInfo.vlessAllowInsecure ? "1" : null;
                        else if (i == FIELD_VLESS_ADVANCED_JSON) field = pasteInfo.vlessAdvancedJson;
                    }

                    if (!TextUtils.isEmpty(field)) {
                        try {
                            inputFields[i].setText(URLDecoder.decode(field, "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            inputFields[i].setText(field);
                        }
                    } else {
                        inputFields[i].setText(null);
                    }
                }
                inputFields[0].setSelection(inputFields[0].length());
                setProxyType(pasteType, true, () -> {
                    AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
                    for (int i = 0; i < inputFields.length; i++) {
                        if (i == FIELD_IP) {
                            continue;
                        }
                        if (pasteType == ProxySettings.Type.WEB) {
                            if (i == FIELD_SECRET) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.MTPROTO) {
                            if (i == FIELD_SECRET || i == FIELD_PORT) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.SOCKS5) {
                            if (i == FIELD_PORT || i == FIELD_USER || i == FIELD_PASSWORD) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.XRAY_VLESS) {
                            if (i == FIELD_PORT || (i >= FIELD_VLESS_ID && i <= FIELD_VLESS_ADVANCED_JSON)) {
                                continue;
                            }
                        }
                        inputFields[i].setText(null);
                    }
                });
            }
        });
        linearLayout2.addView(pasteCell, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        pasteCell.setVisibility(View.GONE);
        sectionCell[2] = new ShadowSectionCell(fragmentView.getContext());
        linearLayout2.addView(sectionCell[2], 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        sectionCell[2].setVisibility(View.GONE);

        shareCell = new TextSettingsCell(context);
        shareCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        shareCell.setText(LocaleController.getString(R.string.ShareFile), false);
        shareCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        linearLayout2.addView(shareCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shareCell.setOnClickListener(v -> {
            StringBuilder params = new StringBuilder();
            String address = inputFields[FIELD_IP].getText().toString();
            String password = inputFields[FIELD_PASSWORD].getText().toString();
            String user = inputFields[FIELD_USER].getText().toString();
            String port = inputFields[FIELD_PORT].getText().toString();
            String secret = inputFields[FIELD_SECRET].getText().toString();
            String url;
            if (currentType == ProxySettings.Type.XRAY_VLESS) {
                String vlessLink = buildVlessLinkFromFields();
                if (vlessLink == null) {
                    return;
                }
                QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), vlessLink, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
                Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
                alert.setCenterImage(icon);
                showDialog(alert);
                return;
            }
            try {
                if (!TextUtils.isEmpty(address)) {
                    params.append("server=").append(URLEncoder.encode(address, "UTF-8"));
                }
                if (!TextUtils.isEmpty(port)) {
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("port=").append(URLEncoder.encode(port, "UTF-8"));
                }
                if (currentType == ProxySettings.Type.MTPROTO) {
                    url = "https://t.me/proxy?";
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("secret=").append(URLEncoder.encode(secret, "UTF-8"));
                } else {
                    url = "https://t.me/socks?";
                    if (!TextUtils.isEmpty(user)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("user=").append(URLEncoder.encode(user, "UTF-8"));
                    }
                    if (!TextUtils.isEmpty(password)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("pass=").append(URLEncoder.encode(password, "UTF-8"));
                    }
                }
            } catch (Exception ignore) {
                return;
            }
            if (params.length() == 0) {
                return;
            }
            String link = url + params.toString();
            QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), link, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
            Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
            alert.setCenterImage(icon);
            showDialog(alert);
        });

        redownloadCell = new TextSettingsCell(context);
        redownloadCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        redownloadCell.setText(LocaleController.getString(R.string.XrayProxyRedownload), false);
        redownloadCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        redownloadCell.setVisibility(View.GONE);
        linearLayout2.addView(redownloadCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        redownloadCell.setOnClickListener(v -> {
            if (currentType != ProxySettings.Type.XRAY_VLESS) {
                return;
            }
            boolean deleted = XrayProxyManager.deleteCoreFiles();
            if (getParentActivity() != null) {
                if (deleted) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.XrayProxyRedownloaded), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.XrayProxyRedownloadFailed), Toast.LENGTH_SHORT).show();
                }
            }
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean enabled = preferences.getBoolean("proxy_enabled", false);
            if (enabled && SharedConfig.currentProxy != null && SharedConfig.currentProxy.isXrayVless()) {
                XrayProxyManager.startService();
                ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
            }
        });

        xrayStatusCell = new TextSettingsCell(context);
        xrayStatusCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        xrayStatusCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        xrayStatusCell.setVisibility(View.GONE);
        xrayStatusCell.setEnabled(false);
        linearLayout2.addView(xrayStatusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sectionCell[1] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        shareDoneEnabled = true;
        shareDoneProgress = 1f;
        checkShareDone(false);

        currentType = null;
        setProxyType(currentProxyInfo.settings.getType(), false);

        pasteProxySettings = null;
        pasteString = null;
        updatePasteCell();

        return fragmentView;
    }

    private void updatePasteCell() {
        final ClipData clip = clipboardManager.getPrimaryClip();

        String clipText;
        if (clip != null && clip.getItemCount() > 0) {
            try {
                clipText = clip.getItemAt(0).coerceToText(fragmentView.getContext()).toString();
            } catch (Exception e) {
                clipText = null;
            }
        } else {
            clipText = null;
        }

        if (TextUtils.equals(clipText, pasteString)) {
            return;
        }

        pasteProxySettings = null;
        pasteProxyInfo = null;
        pasteString = clipText;
        if (clipText != null) {
            if (clipText.trim().toLowerCase().startsWith("vless://")) {
                try {
                    SharedConfig.ProxyInfo info = SharedConfig.ProxyInfo.fromVlessUrl(clipText.trim());
                    if (info != null) {
                        this.pasteProxyInfo = info;
                        this.pasteProxySettings = info.settings;
                    }
                } catch (Exception ignoreE) {
                }
            } else {
                ProxySettings pasteProxySettings = null;
                try {
                    pasteProxySettings = ProxySettings.fromUri(Uri.parse(clipText));
                } catch (Exception ignoreE) {

                }

                if (pasteProxySettings != null && pasteProxySettings.isValid()) {
                    this.pasteProxySettings = pasteProxySettings;
                }
            }
        }

        if (pasteProxySettings != null) {
            if (pasteCell.getVisibility() != View.VISIBLE) {
                pasteCell.setVisibility(View.VISIBLE);
                sectionCell[2].setVisibility(View.VISIBLE);
            }
        } else {
            if (pasteCell.getVisibility() != View.GONE) {
                pasteCell.setVisibility(View.GONE);
                sectionCell[2].setVisibility(View.GONE);
            }
        }
    }

    private void setShareDoneEnabled(boolean enabled, boolean animated) {
        if (shareDoneEnabled != enabled) {
            if (shareDoneAnimator != null) {
                shareDoneAnimator.cancel();
            } else if (animated) {
                shareDoneAnimator = ValueAnimator.ofFloat(0f, 1f);
                shareDoneAnimator.setDuration(200);
                shareDoneAnimator.addUpdateListener(a -> {
                    shareDoneProgress = AndroidUtilities.lerp(shareDoneProgressAnimValues, a.getAnimatedFraction());
                    shareCell.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), shareDoneProgress));
                    doneItem.setAlpha(shareDoneProgress / 2f + 0.5f);
                });
            }
            if (animated) {
                shareDoneProgressAnimValues[0] = shareDoneProgress;
                shareDoneProgressAnimValues[1] = enabled ? 1f : 0f;
                shareDoneAnimator.start();
            } else {
                shareDoneProgress = enabled ? 1f : 0f;
                shareCell.setTextColor(enabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                doneItem.setAlpha(enabled ? 1f : .5f);
            }
            shareCell.setEnabled(enabled);
            doneItem.setEnabled(enabled);
            shareDoneEnabled = enabled;
        }
    }

    private String buildVlessLinkFromFields() {
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                ProxySettings.builder()
                        .setType(ProxySettings.Type.XRAY_VLESS)
                        .setAddress(inputFields[FIELD_IP].getText().toString())
                        .setPort(Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()))
                        .build()
        );
        info.vlessId = inputFields[FIELD_VLESS_ID].getText().toString();
        info.vlessEncryption = inputFields[FIELD_VLESS_ENCRYPTION].getText().toString();
        info.vlessFlow = inputFields[FIELD_VLESS_FLOW].getText().toString();
        info.vlessSecurity = inputFields[FIELD_VLESS_SECURITY].getText().toString();
        info.vlessType = inputFields[FIELD_VLESS_TRANSPORT].getText().toString();
        info.vlessSni = inputFields[FIELD_VLESS_SNI].getText().toString();
        info.vlessHost = inputFields[FIELD_VLESS_HOST].getText().toString();
        info.vlessPath = inputFields[FIELD_VLESS_PATH].getText().toString();
        info.vlessServiceName = inputFields[FIELD_VLESS_SERVICE].getText().toString();
        info.vlessFp = inputFields[FIELD_VLESS_FP].getText().toString();
        info.vlessAlpn = inputFields[FIELD_VLESS_ALPN].getText().toString();
        info.vlessPublicKey = inputFields[FIELD_VLESS_PBK].getText().toString();
        info.vlessShortId = inputFields[FIELD_VLESS_SID].getText().toString();
        info.vlessSpiderX = inputFields[FIELD_VLESS_SPX].getText().toString();
        info.vlessHeaderType = inputFields[FIELD_VLESS_HEADER_TYPE].getText().toString();
        info.vlessSeed = inputFields[FIELD_VLESS_SEED].getText().toString();
        info.vlessQuicSecurity = inputFields[FIELD_VLESS_QUIC_SECURITY].getText().toString();
        info.vlessQuicKey = inputFields[FIELD_VLESS_QUIC_KEY].getText().toString();
        info.vlessMode = inputFields[FIELD_VLESS_MODE].getText().toString();
        String allowInsecureValue = inputFields[FIELD_VLESS_ALLOW_INSECURE].getText().toString();
        info.vlessAllowInsecure = "1".equals(allowInsecureValue) || "true".equalsIgnoreCase(allowInsecureValue);
        info.vlessAdvancedJson = inputFields[FIELD_VLESS_ADVANCED_JSON].getText().toString();
        info.normalizeVlessFields();
        return info.getVlessLink();
    }

    private void updateXrayStatusCell() {
        if (xrayStatusCell == null || currentType != ProxySettings.Type.XRAY_VLESS) {
            return;
        }
        String title = LocaleController.getString(R.string.XrayProxyCoreStatus);
        String value;
        int state = XrayProxyManager.getState();
        if (state == XrayProxyManager.STATE_RUNNING) {
            value = LocaleController.getString(R.string.XrayProxyStatusReady);
        } else if (state == XrayProxyManager.STATE_STARTING) {
            value = LocaleController.getString(R.string.XrayProxyStatusStarting);
        } else if (state == XrayProxyManager.STATE_DOWNLOADING) {
            long total = XrayProxyManager.getDownloadTotalBytes();
            long current = XrayProxyManager.getDownloadBytes();
            if (total > 0) {
                int percent = (int) Math.min(100, (current * 100) / total);
                value = LocaleController.formatString(R.string.XrayProxyStatusDownloadingPercent, percent);
            } else {
                value = LocaleController.getString(R.string.XrayProxyStatusDownloading);
            }
        } else if (state == XrayProxyManager.STATE_FAILED) {
            value = LocaleController.getString(R.string.XrayProxyStatusFailed);
        } else {
            value = LocaleController.getString(R.string.XrayProxyStatusIdle);
        }
        xrayStatusCell.setTextAndValue(title, value, false);
    }

    private void checkShareDone(boolean animated) {
        if (shareCell == null || doneItem == null || inputFields[FIELD_IP] == null || inputFields[FIELD_PORT] == null) {
            return;
        }
        boolean enabled;
        if (currentType == ProxySettings.Type.WEB) {
            enabled = !TextUtils.isEmpty(WebProxyTransport.normalizeHost(inputFields[FIELD_IP].getText().toString()))
                    && WebProxyTransport.isValidSecret(inputFields[FIELD_SECRET].getText().toString());
        } else if (currentType == ProxySettings.Type.XRAY_VLESS) {
            enabled = inputFields[FIELD_IP].length() != 0
                    && Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()) != 0
                    && inputFields[FIELD_VLESS_ID].length() != 0;
        } else {
            enabled = inputFields[FIELD_IP].length() != 0
                    && Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()) != 0;
        }
        setShareDoneEnabled(enabled, animated);
    }

    private void setProxyType(ProxySettings.Type type, boolean animated) {
        setProxyType(type, animated, null);
    }

    private void setProxyType(ProxySettings.Type type, boolean animated, Runnable onTransitionEnd) {
        if (currentType != type) {
            currentType = type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TransitionManager.endTransitions(linearLayout2);
            }
            if (animated) {
                final TransitionSet transitionSet = new TransitionSet()
                        .addTransition(new Fade(Fade.OUT))
                        .addTransition(new ChangeBounds())
                        .addTransition(new Fade(Fade.IN))
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .setDuration(250);

                if (onTransitionEnd != null) {
                    transitionSet.addListener(new Transition.TransitionListener() {
                        @Override
                        public void onTransitionStart(Transition transition) {
                        }

                        @Override
                        public void onTransitionEnd(Transition transition) {
                            onTransitionEnd.run();
                        }

                        @Override
                        public void onTransitionCancel(Transition transition) {
                        }

                        @Override
                        public void onTransitionPause(Transition transition) {
                        }

                        @Override
                        public void onTransitionResume(Transition transition) {
                        }
                    });
                }

                TransitionManager.beginDelayedTransition(linearLayout2, transitionSet);
            }
            boolean isXray = currentType == ProxySettings.Type.XRAY_VLESS;
            if (currentType == ProxySettings.Type.SOCKS5) {
                bottomCells[0].setVisibility(View.VISIBLE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.GONE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            } else if (currentType == ProxySettings.Type.MTPROTO) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                bottomCells[2].setVisibility(View.GONE);
                bottomCells[1].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            } else if (currentType == ProxySettings.Type.WEB) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                bottomCells[2].setVisibility(View.GONE);
                bottomCells[1].setText(LocaleController.getString(R.string.UseProxyWebInfo));
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.GONE);
                inputFields[FIELD_PORT].setText("443");
            } else if (isXray) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.VISIBLE);
                redownloadCell.setVisibility(View.VISIBLE);
                xrayStatusCell.setVisibility(View.VISIBLE);
                updateXrayStatusCell();
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            }
            for (int f = FIELD_VLESS_ID; f <= FIELD_VLESS_ADVANCED_JSON; f++) {
                ((View) inputFields[f].getParent()).setVisibility(isXray ? View.VISIBLE : View.GONE);
            }
            if (!isXray) {
                redownloadCell.setVisibility(View.GONE);
                xrayStatusCell.setVisibility(View.GONE);
            }
            shareCell.setVisibility(currentType == ProxySettings.Type.WEB ? View.GONE : View.VISIBLE);
            typeCell[0].setChecked(currentType == ProxySettings.Type.SOCKS5, animated);
            typeCell[1].setChecked(currentType == ProxySettings.Type.MTPROTO, animated);
            typeCell[2].setChecked(currentType == ProxySettings.Type.WEB, animated);
            typeCell[3].setChecked(currentType == ProxySettings.Type.XRAY_VLESS, animated);
            checkShareDone(animated);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && addingNewProxy) {
            inputFields[FIELD_IP].requestFocus();
            AndroidUtilities.showKeyboard(inputFields[FIELD_IP]);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (shareCell != null && (shareDoneAnimator == null || !shareDoneAnimator.isRunning())) {
                shareCell.setTextColor(shareDoneEnabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
            if (inputFields != null) {
                for (int i = 0; i < inputFields.length; i++) {
                    inputFields[i].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                            Theme.getColor(Theme.key_text_RedRegular));
                }
            }
        };
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(scrollView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));
        arrayList.add(new ThemeDescription(inputFieldsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(linearLayout2, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlueText4));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText2));

        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(pasteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText4));

        for (int a = 0; a < typeCell.length; a++) {
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
            arrayList.add(new ThemeDescription(typeCell[a], 0, new Class[]{RadioCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        }

        if (inputFields != null) {
            for (int a = 0; a < inputFields.length; a++) {
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR | ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputField));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputFieldActivated));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_text_RedRegular));
            }
        } else {
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        }
        arrayList.add(new ThemeDescription(headerCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(headerCell, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        for (int a = 0; a < sectionCell.length; a++) {
            if (sectionCell[a] != null) {
                arrayList.add(new ThemeDescription(sectionCell[a], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            }
        }
        for (int i = 0; i < bottomCells.length; i++) {
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            arrayList.add(new ThemeDescription(bottomCells[i], 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_LINKCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        }

        return arrayList;
    }
}
