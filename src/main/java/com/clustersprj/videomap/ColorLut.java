package com.clustersprj.videomap;

/**
 * RGB(5bit x3) -> マップカラーindex の高速変換テーブル。
 * 非推奨(削除予定)の MapPalette に頼らず、バニラのマップ基本色を直接持つ。
 * index = 基本色ID * 4 + 明度(0:180, 1:220, 2:255, 3:135)
 */
final class ColorLut {

    private static final int[] BASE = {
            0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF,
            0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8,
            0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2,
            0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5, 0x4A80FF,
            0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524,
            0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223,
            0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C,
            0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796
    };
    private static final int[] SHADE = {180, 220, 255, 135};

    static final byte[] LUT = new byte[32768];
    static final byte BLACK;

    static {
        int n = BASE.length * 4;
        int[] pr = new int[n];
        int[] pg = new int[n];
        int[] pb = new int[n];
        int[] idx = new int[n];
        for (int i = 0; i < BASE.length; i++) {
            for (int s = 0; s < 4; s++) {
                int k = i * 4 + s;
                pr[k] = ((BASE[i] >> 16) & 0xFF) * SHADE[s] / 255;
                pg[k] = ((BASE[i] >> 8) & 0xFF) * SHADE[s] / 255;
                pb[k] = (BASE[i] & 0xFF) * SHADE[s] / 255;
                idx[k] = (i + 1) * 4 + s; // 基本色ID 0 は透明なので +1
            }
        }
        for (int key = 0; key < 32768; key++) {
            int r = ((key >> 10) & 31) * 255 / 31;
            int g = ((key >> 5) & 31) * 255 / 31;
            int b = (key & 31) * 255 / 31;
            int best = 0;
            long bestDist = Long.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                int dr = r - pr[k], dg = g - pg[k], db = b - pb[k];
                // 人の目に合わせて緑を重め、青を軽めに
                long d = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
                if (d < bestDist) {
                    bestDist = d;
                    best = k;
                }
            }
            LUT[key] = (byte) idx[best];
        }
        BLACK = LUT[0];
    }

    private ColorLut() {
    }
}
