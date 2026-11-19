package com.ndspack.generator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Le o cabecalho e o banner (titulo + icone 32x32) de uma ROM de Nintendo DS. */
public final class NdsRom {
    public final long size;
    public final String headerTitle;
    public final String gameCode;
    public final String bannerTitle;     // primeira linha do titulo em ingles (pode ser null)
    public final int[] icon;             // 32x32 ARGB (pode ser null)
    public final boolean logoOk;
    public final long headerCrc;

    private NdsRom(long size, String headerTitle, String gameCode, String bannerTitle, int[] icon,
                   boolean logoOk, long headerCrc) {
        this.size = size;
        this.headerTitle = headerTitle;
        this.gameCode = gameCode;
        this.bannerTitle = bannerTitle;
        this.icon = icon;
        this.logoOk = logoOk;
        this.headerCrc = headerCrc;
    }

    public static NdsRom parse(SeekableByteChannel ch) throws IOException {
        final long size = ch.size();
        if (size < 0x4000) throw new IOException("O arquivo e pequeno demais para ser uma ROM de Nintendo DS.");
        byte[] header = readAt(ch, 0, 0x200);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        String title = ascii(header, 0, 12);
        String code = ascii(header, 0x0C, 4);
        long bannerOff = h.getInt(0x68) & 0xFFFFFFFFL;
        boolean logoOk = (h.getShort(0x15C) & 0xFFFF) == 0xCF56;
        CRC32 crc = new CRC32();
        crc.update(header, 0, 0x200);

        String bannerTitle = null;
        int[] icon = null;
        if (bannerOff >= 0x200 && bannerOff + 0x840 <= size) {
            byte[] bn = readAt(ch, bannerOff, 0x840);
            icon = decodeIcon(bn);
            // titulos: 0x240 + idioma*0x100; 1 = ingles, 0 = japones
            bannerTitle = firstLine(utf16(bn, 0x240 + 0x100, 128));
            if (bannerTitle.isEmpty()) bannerTitle = firstLine(utf16(bn, 0x240, 128));
            if (bannerTitle.isEmpty()) bannerTitle = null;
        }
        return new NdsRom(size, title, code, bannerTitle, icon, logoOk, crc.getValue());
    }

    /** Nome sugerido para o app. */
    public String suggestedName() {
        if (bannerTitle != null) return bannerTitle;
        return headerTitle.isEmpty() ? "Jogo NDS" : headerTitle;
    }

    /** Pacote Android unico por jogo: com.ndspack.game.g&lt;codigo&gt; ou h&lt;crc&gt;. */
    public String packageName() {
        String c = gameCode.toLowerCase();
        boolean valid = c.length() == 4 && c.matches("[a-z0-9]{4}") && Character.isLetter(c.charAt(0))
                && !c.equals("####");
        String seg = valid ? "g" + c : "h" + Long.toHexString(headerCrc);
        return "com.ndspack.game." + seg;
    }

    // ------------------------------------------------------------------ icone
    static int[] decodeIcon(byte[] banner) {
        int[] pal = new int[16];
        for (int i = 1; i < 16; i++) {
            int c = (banner[0x220 + 2 * i] & 0xFF) | ((banner[0x220 + 2 * i + 1] & 0xFF) << 8);
            int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
            r = (r << 3) | (r >> 2);
            g = (g << 3) | (g >> 2);
            b = (b << 3) | (b >> 2);
            pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        int[] out = new int[32 * 32];
        for (int ty = 0; ty < 4; ty++) {
            for (int tx = 0; tx < 4; tx++) {
                int tile = ty * 4 + tx;
                for (int py = 0; py < 8; py++) {
                    for (int px = 0; px < 8; px++) {
                        int v = banner[0x20 + tile * 32 + py * 4 + (px >> 1)] & 0xFF;
                        int idx = ((px & 1) == 0) ? (v & 0xF) : (v >> 4);
                        out[(ty * 8 + py) * 32 + tx * 8 + px] = pal[idx];  // indice 0 = transparente
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ utilitarios
    private static byte[] readAt(SeekableByteChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        ch.position(pos);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new IOException("Fim inesperado do arquivo");
        }
        return buf.array();
    }

    private static String ascii(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.ISO_8859_1).trim();
    }

    private static String utf16(byte[] b, int off, int chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars; i++) {
            char c = (char) ((b[off + 2 * i] & 0xFF) | ((b[off + 2 * i + 1] & 0xFF) << 8));
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }
}
