package org.telegram.messenger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact Mimic packet presets ported from WG Tunnel EditableInterface.kt. */
public final class AmneziaWgPresets {
    public static final int QUIC = 1;
    public static final int DNS = 2;
    public static final int SIP = 3;

    private AmneziaWgPresets() {
    }

    public static String apply(String existing, int preset) {
        LinkedHashMap<String, String> values = parse(existing);
        putDefault(values, "jc", "4");
        putDefault(values, "jmin", "40");
        putDefault(values, "jmax", "70");
        putDefault(values, "s1", "0");
        putDefault(values, "s2", "0");
        putDefault(values, "s3", "0");
        putDefault(values, "s4", "0");
        putDefault(values, "h1", "1");
        putDefault(values, "h2", "2");
        putDefault(values, "h3", "3");
        putDefault(values, "h4", "4");
        switch (preset) {
            case QUIC:
                values.put("i1", "<b 0xc1ff000012508394c8f03e51570800449f0dbc195a0000f3a694c75775b4e546172ce9e047cd0b5bee5181648c727adc87f7eae54473ec6cba6bdad4f59823174b769f12358abd292d4f3286934484fb8b239c38732e1f3bbbc6a003056487eb8b5c88b9fd9279ffff3b0f4ecf95c4624db6d65d4113329ee9b0bf8cdd7c8a8d72806d55df25ecb66488bc119d7c9a29abaf99bb33c56b08ad8c26995f838bb3b7a3d5c1858b8ec06b839db2dcf918d5ea9317f1acd6b663cc8925868e2f6a1bda546695f3c3f33175944db4a11a346afb07e78489e509b02add51b7b203eda5c330b03641179a31fbba9b56ce00f3d5b5e3d7d9c5429aebb9576f2f7eacbe27bc1b8082aaf68fb69c921aa5d33ec0c8510410865a178d86d7e54122d55ef2c2bbc040be46d7fece73fe8a1b24495ec160df2da9b20a7ba2f26dfa2a44366dbc63de5cd7d7c94c57172fe6d79c901f025c0010b02c89b395402c009f62dc053b8067a1e0ed0a1e0cf5087d7f78cbd94afe0c3dd55d2d4b1a5cfe2b68b86264e351d1dcd858783a240f893f008ceed743d969b8f735a1677ead960b1fb1ecc5ac83c273b49288d02d7286207e663c45e1a7baf50640c91e762941cf380ce8d79f3e86767fbbcd25b42ef70ec334835a3a6d792e170a432ce0cb7bde9aaa1e75637c1c34ae5fef4338f53db8b13a4d2df594efbfa08784543815c9c0d487bddfa1539bc252cf43ec3686e9802d651cfd2a829a06a9f332a733a4a8aed80efe3478093fbc69c8608146b3f16f1a5c4eac9320da49f1afa5f538ddecbbe7888f435512d0dd74fd9b8c99e3145ba84410d8ca9a36dd884109e76e5fb8222a52e1473da168519ce7a8a3c32e9149671b16724c6c5c51bb5cd64fb591e567fb78b10f9f6fee62c276f282a7df6bcf7c17747bc9a81e6c9c3b032fdd0e1c3ac9eaa5077de3ded18b2ed4faf328f49875af2e36ad5ce5f6cc99ef4b60e57b3b5b9c9fcbcd4cfb3975e70ce4c2506bcd71fef0e53592461504e3d42c885caab21b782e26294c6a9d61118cc40a26f378441ceb48f31a362bf8502a723a36c63502229a462cc2a3796279a5e3a7f81a68c7f81312c381cc16a4ab03513a51ad5b54306ec1d78a5e47e2b15e5b7a1438e5b8b2882dbdad13d6a4a8c3558cae043501b68eb3b040067152>");
                values.put("i2", "<b 0x0000000000010000000000000000000000000000000000000000000000000000>");
                break;
            case DNS:
                values.put("i1", "<b 0x123401000001000000000000076578616d706c6503636f6d0000010001>");
                values.remove("i2");
                break;
            case SIP:
                values.put("i1", "<b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e300d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a4d61782d466f7277617264733a2037300d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e746163743a203c7369703a616c69636540706333332e61746c616e74612e636f6d3e0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f7364700d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>");
                values.put("i2", "<b 0x5349502f322e302031303020547279696e670d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>");
                break;
            default:
                throw new IllegalArgumentException("Unknown AmneziaWG Mimic preset: " + preset);
        }
        return serialize(values);
    }

    private static LinkedHashMap<String, String> parse(String source) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (source == null || source.trim().isEmpty()) return values;
        for (String line : source.split("\\r?\\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim().toLowerCase(java.util.Locale.US);
            String value = line.substring(equals + 1).trim();
            if (isAllowed(key) && !value.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private static void putDefault(Map<String, String> values, String key, String value) {
        if (!values.containsKey(key) || values.get(key).isEmpty()) values.put(key, value);
    }

    private static String serialize(LinkedHashMap<String, String> values) {
        String[] order = {"jc","jmin","jmax","s1","s2","s3","s4","h1","h2","h3","h4",
                "i1","i2","i3","i4","i5","headerprotectionkey","contentpaddingaddition",
                "rekeyaftertime","rekeytimeout","rejectaftertime","keepalivetimeout",
                "maxhandshakeattempts","randomtrailers","disablecookies"};
        StringBuilder out = new StringBuilder();
        for (String key : order) {
            String value = values.get(key);
            if (value != null && !value.isEmpty()) out.append(key).append(" = ").append(value).append('\\n');
        }
        return out.toString();
    }

    private static boolean isAllowed(String key) {
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
}
