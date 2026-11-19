package com.ndspack.generator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Substitui textos no pool de strings de um AndroidManifest.xml binario (AXML),
 * reconstruindo o pool para aceitar textos de qualquer tamanho.
 * Os indices das strings nao mudam, entao o restante do arquivo continua valido.
 */
public final class AxmlPatcher {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int FLAG_UTF8 = 0x100;

    private AxmlPatcher() {}

    /** Retorna o AXML com cada string igual a uma chave de {@code replacements} trocada pelo valor. */
    public static byte[] replaceStrings(byte[] axml, Map<String, String> replacements) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if (axml.length < 16 || (in.getShort(0) & 0xFFFF) != RES_XML_TYPE) {
            throw new IOException("AndroidManifest.xml binario invalido");
        }
        final int poolStart = in.getShort(2) & 0xFFFF;  // tamanho do cabecalho do arquivo (8)
        if ((in.getShort(poolStart) & 0xFFFF) != RES_STRING_POOL_TYPE) {
            throw new IOException("Pool de strings nao encontrado no manifesto");
        }
        final int poolHeader = in.getShort(poolStart + 2) & 0xFFFF;
        final int poolSize = in.getInt(poolStart + 4);
        final int stringCount = in.getInt(poolStart + 8);
        final int styleCount = in.getInt(poolStart + 12);
        final int flags = in.getInt(poolStart + 16);
        final int stringsStart = in.getInt(poolStart + 20);
        if (styleCount != 0) throw new IOException("Pool de strings com estilos nao suportado");
        final boolean utf8 = (flags & FLAG_UTF8) != 0;

        List<String> strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int off = in.getInt(poolStart + poolHeader + 4 * i);
            strings.add(readString(in, poolStart + stringsStart + off, utf8));
        }

        int changed = 0;
        for (int i = 0; i < strings.size(); i++) {
            String repl = replacements.get(strings.get(i));
            if (repl != null) {
                strings.set(i, repl);
                changed++;
            }
        }
        if (changed == 0) throw new IOException("Nenhum texto do modelo foi encontrado no manifesto");

        // reconstroi o pool
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = data.size();
            writeString(data, strings.get(i), utf8);
        }
        while (data.size() % 4 != 0) data.write(0);
        final int newStringsStart = poolHeader + 4 * stringCount;
        final int newPoolSize = newStringsStart + data.size();

        ByteBuffer pool = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN);
        pool.putShort((short) RES_STRING_POOL_TYPE);
        pool.putShort((short) poolHeader);
        pool.putInt(newPoolSize);
        pool.putInt(stringCount);
        pool.putInt(0);
        pool.putInt(flags);
        pool.putInt(newStringsStart);
        pool.putInt(0);
        while (pool.position() < poolHeader) pool.put((byte) 0);  // campos extras do cabecalho, se houver
        for (int i = 0; i < stringCount; i++) pool.putInt(offsets[i]);
        pool.put(data.toByteArray());

        final int rest = poolStart + poolSize;
        final int total = poolStart + newPoolSize + (axml.length - rest);
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(axml, 0, poolStart);
        out.put(pool.array());
        out.put(axml, rest, axml.length - rest);
        out.putInt(4, total);
        return out.array();
    }

    // ------------------------------------------------------------------ leitura
    private static String readString(ByteBuffer b, int pos, boolean utf8) throws IOException {
        if (utf8) {
            int[] p = {pos};
            readLen8(b, p);  // comprimento em UTF-16 (ignorado)
            int byteLen = readLen8(b, p);
            byte[] raw = new byte[byteLen];
            for (int i = 0; i < byteLen; i++) raw[i] = b.get(p[0] + i);
            return new String(raw, StandardCharsets.UTF_8);
        }
        int len = b.getShort(pos) & 0xFFFF;
        pos += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | (b.getShort(pos) & 0xFFFF);
            pos += 2;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = (char) (b.getShort(pos + 2 * i) & 0xFFFF);
        return new String(chars);
    }

    private static int readLen8(ByteBuffer b, int[] p) {
        int v = b.get(p[0]++) & 0xFF;
        if ((v & 0x80) != 0) v = ((v & 0x7F) << 8) | (b.get(p[0]++) & 0xFF);
        return v;
    }

    // ------------------------------------------------------------------ escrita
    private static void writeString(ByteArrayOutputStream o, String s, boolean utf8) throws IOException {
        if (utf8) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            if (s.length() > 0x7FFF || raw.length > 0x7FFF) throw new IOException("Texto longo demais");
            writeLen8(o, s.length());
            writeLen8(o, raw.length);
            o.write(raw);
            o.write(0);
        } else {
            int len = s.length();
            if (len > 0x7FFF) {
                o.write((len >> 16) & 0xFF);
                o.write(((len >> 24) & 0x7F) | 0x80);
                o.write(len & 0xFF);
                o.write((len >> 8) & 0xFF);
            } else {
                o.write(len & 0xFF);
                o.write((len >> 8) & 0xFF);
            }
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                o.write(c & 0xFF);
                o.write((c >> 8) & 0xFF);
            }
            o.write(0);
            o.write(0);
        }
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7F) {
            o.write((len >> 8) | 0x80);
            o.write(len & 0xFF);
        } else {
            o.write(len);
        }
    }
}
