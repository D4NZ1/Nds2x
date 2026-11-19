#!/usr/bin/env python3
import struct, sys, zipfile, zlib, os

def read_pool(b):
    assert struct.unpack_from('<H', b, 0)[0] == 3
    total = struct.unpack_from('<I', b, 4)[0]
    assert total == len(b), (total, len(b))
    t, hs, size, sc, stc, flags, ss, sts = struct.unpack_from('<HHIIIIII', b, 8)
    assert t == 1 and stc == 0 and size % 4 == 0
    utf8 = bool(flags & 0x100)
    out = []
    for i in range(sc):
        off = struct.unpack_from('<I', b, 8 + hs + 4 * i)[0]
        p = 8 + ss + off
        if utf8:
            def l8(p):
                v = b[p]; p += 1
                if v & 0x80: v = ((v & 0x7F) << 8) | b[p]; p += 1
                return v, p
            _, p = l8(p); n, p = l8(p)
            out.append(b[p:p + n].decode('utf-8')); assert b[p + n] == 0
        else:
            n = struct.unpack_from('<H', b, p)[0]; p += 2
            out.append(b[p:p + 2 * n].decode('utf-16-le')); assert b[p + 2 * n:p + 2 * n + 2] == b'\0\0'
    rest = b[8 + size:]
    return out, rest, utf8

def data_offset(path, zi):
    with open(path, 'rb') as f:
        f.seek(zi.header_offset)
        h = f.read(30)
        sig, = struct.unpack_from('<I', h, 0)
        assert sig == 0x04034b50
        nl, el = struct.unpack_from('<HH', h, 26)
        return zi.header_offset + 30 + nl + el

d = sys.argv[1]
rom = open(os.path.join(d, 'rom.nds'), 'rb').read()
for enc in ('utf8', 'utf16'):
    path = os.path.join(d, 'out_%s.apk' % enc)
    z = zipfile.ZipFile(path)
    assert z.testzip() is None
    names = z.namelist()
    assert not any(n.startswith('META-INF/') for n in names), names
    strings, rest, utf8 = read_pool(z.read('AndroidManifest.xml'))
    assert utf8 == (enc == 'utf8')
    assert strings[3] == 'com.ndspack.game.gatse', strings
    assert strings[4] == 'Meu Jogo Com Nome Bem Longo Para Testar O Pool', strings
    assert strings[5] == 'com.ndspack.player.MainActivity'
    assert strings[:3] == ['versionCode', 'package', 'label'] and strings[6] == 'android'
    assert rest == struct.pack('<HHI', 0x0180, 8, 16) + struct.pack('<II', 0x0101021b, 0x0101021c)
    assert z.read('assets/game.nds') == rom
    for n in ('resources.arsc', 'assets/game.nds'):
        zi = z.getinfo(n)
        assert zi.compress_type == zipfile.ZIP_STORED, n
        assert data_offset(path, zi) % 4 == 0, (n, data_offset(path, zi))
    assert z.read('res/mipmap-mdpi-v4/ic_launcher.png') == bytes([48, 1, 2, 3])
    assert z.read('res/mipmap-xxxhdpi-v4/ic_launcher.png') == bytes([192, 1, 2, 3])
    orig = zipfile.ZipFile(os.path.join(d, 'template_%s.apk' % enc))
    for n in ('classes.dex', 'lib/arm64-v8a/libplayer.so', 'lib/arm64-v8a/libretro_core.so', 'resources.arsc'):
        assert z.read(n) == orig.read(n), n
    print(enc, 'OK', len(names), 'entradas;', 'arsc@%d' % data_offset(path, z.getinfo('resources.arsc')))
