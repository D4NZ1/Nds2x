#!/usr/bin/env python3
"""Gera um APK-modelo sintetico (AXML UTF-8 ou UTF-16) e uma ROM NDS falsa para os testes."""
import json, os, struct, sys, zipfile, zlib

def len8(n):
    return bytes([n]) if n < 0x80 else bytes([(n >> 8) | 0x80, n & 0xFF])

def string_pool(strings, utf8):
    data = b''; offs = []
    for s in strings:
        offs.append(len(data))
        if utf8:
            raw = s.encode('utf-8')
            data += len8(len(s)) + len8(len(raw)) + raw + b'\0'
        else:
            data += struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\0\0'
    while len(data) % 4: data += b'\0'
    hdr = 28
    start = hdr + 4 * len(strings)
    body = b''.join(struct.pack('<I', o) for o in offs) + data
    return struct.pack('<HHIIIIII', 1, hdr, start + len(data), len(strings), 0, 0x100 if utf8 else 0, start, 0) + body

def axml(strings, utf8):
    pool = string_pool(strings, utf8)
    # um chunk qualquer depois do pool (mapa de recursos) para testar o deslocamento
    resmap = struct.pack('<HHI', 0x0180, 8, 8 + 8) + struct.pack('<II', 0x0101021b, 0x0101021c)
    body = pool + resmap
    return struct.pack('<HHI', 3, 8, 8 + len(body)) + body

def png(size):
    raw = b''.join(b'\0' + b'\x20\x40\x80\xff' * size for _ in range(size))
    def ch(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw)) + ch(b'IEND', b'')

def make_rom(path):
    size = 0x8000
    rom = bytearray(size)
    rom[0:12] = b'TESTGAME\0\0\0\0'
    rom[0x0C:0x10] = b'ATSE'
    struct.pack_into('<I', rom, 0x68, 0x4000)       # offset do banner
    struct.pack_into('<H', rom, 0x15C, 0xCF56)      # CRC do logo
    b = 0x4000
    struct.pack_into('<H', rom, b, 1)
    # icone: tile 0, linha 0: pixels 0..7 = indices 1,2,0,0,...; resto 0
    rom[b + 0x20 + 0] = (2 << 4) | 1                # px0=1 (baixo), px1=2 (alto)
    # tile 5 (ty=1,tx=1), linha 3, pixels 6,7 = 15,3
    rom[b + 0x20 + 5 * 32 + 3 * 4 + 3] = (3 << 4) | 15
    pal = {1: (31, 0, 0), 2: (0, 31, 0), 15: (0, 0, 31), 3: (31, 31, 31)}  # r,g,b 5 bits
    for i, (r, g, bl) in pal.items():
        struct.pack_into('<H', rom, b + 0x220 + 2 * i, r | (g << 5) | (bl << 10))
    title = 'Jogo de Teste\nEditora'
    rom[b + 0x340:b + 0x340 + len(title) * 2] = title.encode('utf-16-le')
    open(path, 'wb').write(rom)

def main(out):
    os.makedirs(out, exist_ok=True)
    result = {}
    for name, utf8 in (('utf8', True), ('utf16', False)):
        strings = ['versionCode', 'package', 'label', 'com.ndspack.player', 'NDSGAME_LABEL_PLACEHOLDER',
                   'com.ndspack.player.MainActivity', 'android']
        path = os.path.join(out, 'template_%s.apk' % name)
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('AndroidManifest.xml', axml(strings, utf8), zipfile.ZIP_DEFLATED)
            zi = zipfile.ZipInfo('resources.arsc'); zi.compress_type = zipfile.ZIP_STORED
            z.writestr(zi, b'ARSC' + os.urandom(1001))
            z.writestr('res/mipmap-mdpi-v4/ic_launcher.png', png(48), zipfile.ZIP_DEFLATED)
            z.writestr('res/mipmap-xxxhdpi-v4/ic_launcher.png', png(192), zipfile.ZIP_DEFLATED)
            z.writestr('classes.dex', os.urandom(5000), zipfile.ZIP_DEFLATED)
            z.writestr('lib/arm64-v8a/libplayer.so', b'\x7fELF' + os.urandom(3000), zipfile.ZIP_DEFLATED)
            z.writestr('lib/arm64-v8a/libretro_core.so', b'\x7fELF' + os.urandom(3000), zipfile.ZIP_DEFLATED)
            z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n', zipfile.ZIP_DEFLATED)
            z.writestr('META-INF/CERT.RSA', b'old signature', zipfile.ZIP_DEFLATED)
    make_rom(os.path.join(out, 'rom.nds'))

if __name__ == '__main__':
    main(sys.argv[1])
