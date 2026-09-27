#!/usr/bin/env python3
"""
Собирает значок приложения в формате .ico для установщика Windows и ярлыка.

Та же схема, что на вкладке «Социограмма»: синий фон, лидер в центре,
четыре участника по орбите, белые связи. Рисуется здесь, чтобы иконка
не расходилась с палитрой статусов в интерфейсе.

Запуск:  python3 tools/make_icon.py
Результат: src/main/windows/sociometric.ico
"""

import math
import os
import struct
import sys
import zlib

# Палитра совпадает с COLORS в sociogram.js
BACKGROUND = (0x2F, 0x6F, 0xD0)
OUTLINE = (0x1D, 0x24, 0x30)
LINK = (0xFF, 0xFF, 0xFF)
NODES = [
    (0.50, 0.52, 0.130, (0x2E, 0x9E, 0x4F)),  # звезда
    (0.22, 0.25, 0.100, (0x8F, 0xD1, 0x9A)),  # предпочитаемый
    (0.78, 0.25, 0.100, (0xBC, 0xD6, 0xF5)),  # принятый
    (0.22, 0.79, 0.100, (0xF5, 0xC2, 0x6B)),  # изолированный
    (0.78, 0.79, 0.100, (0xE8, 0x8B, 0x8B)),  # отвергаемый
]

# Степени сглаживания: картинка рисуется крупнее и потом усредняется
SUPERSAMPLE = 4
SIZES = [16, 24, 32, 48, 64, 128, 256]


def blend(dst, src, alpha):
    """Подмешивает цвет src к dst с весом alpha."""
    return tuple(int(round(d * (1 - alpha) + s * alpha)) for d, s in zip(dst, src))


def render(size):
    """Рисует квадратный значок и возвращает буфер RGBA."""
    big = size * SUPERSAMPLE
    samples = SUPERSAMPLE * SUPERSAMPLE
    # копим цвет и непрозрачность по каждому пикселю, потом усредняем
    acc = [[[0.0, 0.0, 0.0, 0.0] for _ in range(size)] for _ in range(size)]

    radius = 0.22          # скругление фона
    link_w = 0.022         # толщина связи
    node_outline = 0.018   # толщина обводки узла
    cx, cy = NODES[0][0], NODES[0][1]

    for py in range(big):
        y = (py + 0.5) / big
        row = acc[py // SUPERSAMPLE]
        for px in range(big):
            x = (px + 0.5) / big
            color, alpha = pixel(x, y, radius, link_w, node_outline, cx, cy)
            cell = row[px // SUPERSAMPLE]
            cell[0] += color[0] * alpha
            cell[1] += color[1] * alpha
            cell[2] += color[2] * alpha
            cell[3] += alpha

    out = bytearray()
    for row in acc:
        for cell in row:
            total = cell[3]
            if total > 0:
                r, g, b = cell[0] / total, cell[1] / total, cell[2] / total
                a = total / samples
            else:
                r = g = b = 0.0
                a = 0.0
            out += bytes((int(round(r)), int(round(g)), int(round(b)),
                          int(round(255 * a))))
    return bytes(out)


def pixel(x, y, radius, link_w, node_outline, cx, cy):
    """Цвет и непрозрачность одной точки схемы."""
    # Скруглённый квадрат: углы срезаны дугой радиуса radius, центр которой
    # отстоит от угла на тот же радиус. Расстояние считаем до внутреннего
    # прямоугольника — иначе углы получаются прямыми.
    inset = 0.005
    lo, hi = inset + radius, 1 - inset - radius
    qx = min(max(x, lo), hi)
    qy = min(max(y, lo), hi)
    if math.hypot(x - qx, y - qy) > radius:
        return (0, 0, 0), 0.0
    color, alpha = BACKGROUND, 1.0

    # связи от центра к каждому узлу
    for nx, ny, nr, _ in NODES[1:]:
        dist = point_segment_distance(x, y, cx, cy, nx, ny)
        if dist <= link_w:
            color = blend(color, LINK, 1.0)

    # кружки узлов поверх связей
    for nx, ny, nr, fill in NODES:
        dist = math.hypot(x - nx, y - ny)
        if dist <= nr:
            color = fill
        elif dist <= nr + node_outline:
            color = blend(color, OUTLINE, 1.0)

    return color, alpha


def point_segment_distance(px, py, ax, ay, bx, by):
    """Расстояние от точки до отрезка — для проверки попадания в связь."""
    dx, dy = bx - ax, by - ay
    length_sq = dx * dx + dy * dy
    if length_sq == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length_sq))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def png_bytes(size, rgba):
    """Кодирует буфер в PNG (без внешних библиотек)."""
    raw = bytearray()
    stride = size * 4
    for row in range(size):
        raw.append(0)  # фильтр строки
        raw += rgba[row * stride:(row + 1) * stride]

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    header = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', header)
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
            + chunk(b'IEND', b''))


def ico_bytes(images):
    """Собирает .ico из готовых PNG-картинок."""
    header = struct.pack('<HHH', 0, 1, len(images))
    offset = 6 + 16 * len(images)
    entries, blobs = b'', b''
    for size, data in images:
        entries += struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32,
                               len(data), offset)
        blobs += data
        offset += len(data)
    return header + entries + blobs


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target_dir = os.path.join(root, 'src', 'main', 'windows')
    os.makedirs(target_dir, exist_ok=True)
    target = os.path.join(target_dir, 'sociometric.ico')

    images = []
    for size in SIZES:
        data = png_bytes(size, render(size))
        images.append((size, data))
        print('  %3d x %-3d  %6d байт' % (size, size, len(data)))

    with open(target, 'wb') as f:
        f.write(ico_bytes(images))
    print('Готово: %s (%d байт, размеров: %d)'
          % (target, os.path.getsize(target), len(SIZES)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
