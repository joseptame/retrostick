"""Draws the RetroStick app icons (a white gamepad on the purple gradient) as PNG, stdlib only.

    python -I scripts/make-icons.py
"""
import struct
import zlib

TOP = (0x5B, 0x4B, 0xD6)
BOTTOM = (0x3A, 0x2D, 0x9C)
WHITE = (255, 255, 255)


def inside(x, y):
    """Shape on a 512x512 canvas: 'pad' (white) or 'hole' (accent) or None."""
    # D-pad and buttons are cut out of the body
    if (abs(x - 196) < 30 and abs(y - 262) < 10) or (abs(x - 196) < 10 and abs(y - 262) < 30):
        return 'hole'
    for cx, cy in ((310, 246), (342, 278)):
        if (x - cx) ** 2 + (y - cy) ** 2 < 14 ** 2:
            return 'hole'
    # Body: rounded rectangle plus two grips
    rx = min(max(x, 156), 356)
    ry = min(max(y, 216), 300)
    if (x - rx) ** 2 + (y - ry) ** 2 < 46 ** 2:
        return 'pad'
    for cx in (176, 336):
        if (x - cx) ** 2 + (y - 316) ** 2 < 58 ** 2 and y > 250:
            return 'pad'
    return None


def render(size, rounded):
    s = 512 / size
    rows = []
    for py in range(size):
        row = bytearray([0])
        for px in range(size):
            acc = [0, 0, 0, 0]
            for sy in (0.25, 0.75):
                for sx in (0.25, 0.75):
                    x, y = (px + sx) * s, (py + sy) * s
                    t = y / 512
                    bg = tuple(round(TOP[i] * (1 - t) + BOTTOM[i] * t) for i in range(3))
                    alpha = 255
                    if rounded:  # rounded square for the favicon / apple icon look
                        cx, cy = min(max(x, 112), 400), min(max(y, 112), 400)
                        if (x - cx) ** 2 + (y - cy) ** 2 > 112 ** 2:
                            alpha = 0
                    shape = inside(x, y)
                    color = WHITE if shape == 'pad' else bg
                    for i in range(3):
                        acc[i] += color[i]
                    acc[3] += alpha
            row += bytes(v // 4 for v in acc)
        rows.append(bytes(row))
    raw = b''.join(rows)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


# Full-bleed squares work as "maskable" icons; the drawing stays inside the 80 % safe zone.
for name, size, rounded in (('icon-192.png', 192, False), ('icon-512.png', 512, False), ('apple-touch-icon.png', 180, False)):
    with open(f'public/{name}', 'wb') as f:
        f.write(render(size, rounded))
    print(name)
