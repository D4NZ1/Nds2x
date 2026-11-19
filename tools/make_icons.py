#!/usr/bin/env python3
"""Gera os icones padrao (PNG) do player e do gerador, sem dependencias externas."""
import os, struct, sys, zlib

def png(w, h, rgba):
    raw = b''.join(b'\x00' + bytes(rgba[y*w*4:(y+1)*w*4]) for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

def draw(size, accent):
    px = bytearray(size * size * 4)
    def put(x, y, c):
        if 0 <= x < size and 0 <= y < size:
            i = (y * size + x) * 4
            px[i:i+4] = bytes(c)
    r = size * 0.22
    for y in range(size):
        for x in range(size):
            # quadrado arredondado
            cx = min(max(x, r), size - 1 - r); cy = min(max(y, r), size - 1 - r)
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                t = y / size
                put(x, y, (int(24 + 20*t), int(28 + 20*t), int(46 + 30*t), 255))
    # duas telas empilhadas (motivo do DS)
    def rect(x0, y0, x1, y1, c):
        for yy in range(int(y0), int(y1)):
            for xx in range(int(x0), int(x1)):
                put(xx, yy, c)
    m = size * 0.24
    w = size - 2 * m
    rect(m, size*0.16, m + w, size*0.16 + size*0.27, (235, 238, 245, 255))
    rect(m + size*0.02, size*0.16 + size*0.02, m + w - size*0.02, size*0.16 + size*0.25, accent + (255,))
    rect(m, size*0.50, m + w, size*0.50 + size*0.27, (235, 238, 245, 255))
    rect(m + size*0.02, size*0.52, m + w - size*0.02, size*0.75, (40, 44, 64, 255))
    # setinha (gerador) ou ponto (player)
    return png(size, size, px)

def main(base):
    sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for module, accent in (('player', (90, 170, 255)), ('generator', (255, 170, 70))):
        for d, s in sizes.items():
            out = os.path.join(base, module, 'src', 'main', 'res', 'mipmap-' + d)
            os.makedirs(out, exist_ok=True)
            with open(os.path.join(out, 'ic_launcher.png'), 'wb') as f:
                f.write(draw(s, accent))

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '.')
