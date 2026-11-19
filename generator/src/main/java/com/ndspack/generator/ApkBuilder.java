package com.ndspack.generator;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Monta o APK de um jogo a partir do APK-modelo (player): troca nome/pacote no manifesto,
 * troca os icones, remove assinaturas antigas e embute a ROM em assets/game.nds.
 * O resultado ainda precisa ser assinado (ver {@link ApkSign}).
 */
public final class ApkBuilder {
    public static final String TEMPLATE_PACKAGE = "com.ndspack.player";
    public static final String TEMPLATE_LABEL = "NDSGAME_LABEL_PLACEHOLDER";
    public static final String ROM_ENTRY = "assets/game.nds";

    public interface RomSource {
        InputStream open() throws IOException;

        long length();
    }

    public interface IconProvider {
        /** PNG quadrado com o lado indicado, ou null para manter o icone padrao. */
        byte[] png(int sizePx) throws IOException;
    }

    public interface Progress {
        void step(String message);
    }

    private ApkBuilder() {}

    public static void build(File template, File out, RomSource rom, String label, String packageName,
                             IconProvider icons, Progress progress) throws IOException {
        Counting counting = new Counting(new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16));
        try (ZipFile zf = new ZipFile(template); ZipOutputStream zos = new ZipOutputStream(counting)) {
            zos.setLevel(6);
            Enumeration<? extends ZipEntry> en = zf.entries();
            int iconsReplaced = 0;
            boolean manifestDone = false;
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                String name = src.getName();
                if (src.isDirectory() || name.startsWith("META-INF/") || name.equals(ROM_ENTRY)) continue;

                if (name.equals("AndroidManifest.xml")) {
                    progress.step("Ajustando nome e pacote…");
                    byte[] m = readAll(zf.getInputStream(src));
                    Map<String, String> repl = new HashMap<>();
                    repl.put(TEMPLATE_PACKAGE, packageName);
                    repl.put(TEMPLATE_LABEL, label);
                    putBytes(zos, counting, name, AxmlPatcher.replaceStrings(m, repl), false, 4);
                    manifestDone = true;
                } else if (isLauncherIcon(name) && icons != null) {
                    byte[] png = icons.png(iconSize(name));
                    if (png != null) {
                        putBytes(zos, counting, name, png, false, 4);
                        iconsReplaced++;
                    } else {
                        copy(zf, src, zos, counting);
                    }
                } else {
                    copy(zf, src, zos, counting);
                }
            }
            if (!manifestDone) throw new IOException("O modelo de APK nao tem AndroidManifest.xml");

            progress.step("Embutindo a ROM…");
            putRom(zos, counting, rom);
        }
    }

    // ------------------------------------------------------------------ entradas
    static boolean isLauncherIcon(String name) {
        return name.startsWith("res/mipmap") && name.endsWith("/ic_launcher.png");
    }

    static int iconSize(String name) {
        if (name.contains("xxxhdpi")) return 192;
        if (name.contains("xxhdpi")) return 144;
        if (name.contains("xhdpi")) return 96;
        if (name.contains("hdpi")) return 72;
        if (name.contains("mdpi")) return 48;
        if (name.contains("ldpi")) return 36;
        return 96;
    }

    private static void copy(ZipFile zf, ZipEntry src, ZipOutputStream zos, Counting counting) throws IOException {
        String name = src.getName();
        if (src.getMethod() == ZipEntry.STORED) {
            // resources.arsc (e qualquer outro stored) precisa continuar sem compressao e alinhado
            byte[] data = readAll(zf.getInputStream(src));
            putBytes(zos, counting, name, data, true, name.endsWith(".so") ? 16384 : 4);
        } else {
            ZipEntry e = new ZipEntry(name);
            e.setMethod(ZipEntry.DEFLATED);
            e.setTime(FIXED_TIME);
            zos.putNextEntry(e);
            try (InputStream in = zf.getInputStream(src)) {
                pump(in, zos);
            }
            zos.closeEntry();
        }
    }

    private static final long FIXED_TIME = 1704067200000L;  // 2024-01-01

    private static void putBytes(ZipOutputStream zos, Counting counting, String name, byte[] data,
                                 boolean stored, int align) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            storedHeader(e, counting, name, data.length, crc.getValue(), align);
            zos.putNextEntry(e);
            zos.write(data);
        } else {
            e.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(e);
            zos.write(data);
        }
        zos.closeEntry();
    }

    private static void putRom(ZipOutputStream zos, Counting counting, RomSource rom) throws IOException {
        CRC32 crc = new CRC32();
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new BufferedInputStream(rom.open(), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                total += n;
            }
        }
        ZipEntry e = new ZipEntry(ROM_ENTRY);
        e.setTime(FIXED_TIME);
        storedHeader(e, counting, ROM_ENTRY, total, crc.getValue(), 4);
        zos.putNextEntry(e);
        long written = 0;
        try (InputStream in = new BufferedInputStream(rom.open(), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
                written += n;
            }
        }
        if (written != total) throw new IOException("A ROM mudou durante a leitura");
        zos.closeEntry();
    }

    /** Configura a entrada como STORED e acrescenta o preenchimento (extra) para alinhar os dados. */
    private static void storedHeader(ZipEntry e, Counting counting, String name, long size, long crc, int align) {
        e.setMethod(ZipEntry.STORED);
        e.setSize(size);
        e.setCompressedSize(size);
        e.setCrc(crc);
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        long dataOffset = counting.count + 30 + nameLen;
        int pad = (int) ((align - (dataOffset % align)) % align);
        if (pad > 0) {
            if (pad < 4) pad += align;  // o campo extra precisa de pelo menos 4 bytes
            byte[] extra = new byte[pad];
            extra[0] = (byte) 0x35;  // id 0xD935, o mesmo usado pelo zipalign
            extra[1] = (byte) 0xD9;
            extra[2] = (byte) ((pad - 4) & 0xFF);
            extra[3] = (byte) (((pad - 4) >> 8) & 0xFF);
            e.setExtra(extra);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            pump(i, b);
            return b.toByteArray();
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Conta os bytes que passaram, para calcular o alinhamento dos dados no arquivo. */
    private static final class Counting extends FilterOutputStream {
        long count;

        Counting(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
