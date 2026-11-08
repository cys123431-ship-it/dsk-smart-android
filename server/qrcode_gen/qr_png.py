"""Writes the pairing QR that DSK-Remote.bat shows, using only the Python standard library.

    python qr_png.py --text "DSK1|192.168.0.12:3284|lan|DESKTOP-AB|KEY" --out pair.png

Why a hand-rolled PNG writer: the PC must not need pip, PIL or an internet connection for
this. The QR *matrix* comes from python-qrcode (MIT, vendored next to this file); the 1-bit
PNG below is written directly because a QR is exactly a 1-bit bitmap, and that keeps the
file a few hundred bytes even though the image is ~400 px wide.
"""
import argparse
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import qrcode  # noqa: E402  (vendored)
from qrcode.constants import ERROR_CORRECT_M  # noqa: E402


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack('>I', len(data))
        + tag
        + data
        + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(matrix, path: str, scale: int, quiet: int = 2) -> int:
    """1-bit greyscale PNG; matrix[y][x] is True for a dark module."""
    modules = len(matrix)
    size = (modules + quiet * 2) * scale
    rows = []
    for y in range(size):
        my = y // scale - quiet
        row = bytearray()
        for x in range(size):
            mx = x // scale - quiet
            dark = 0 <= my < modules and 0 <= mx < modules and matrix[my][mx]
            row.append(0 if dark else 255)
        rows.append(b'\x00' + bytes(row))  # filter type 0 per scanline
    raw = b''.join(rows)

    ihdr = struct.pack('>IIBBBBB', size, size, 8, 0, 0, 0, 0)  # 8-bit greyscale
    png = (
        b'\x89PNG\r\n\x1a\n'
        + _chunk(b'IHDR', ihdr)
        + _chunk(b'IDAT', zlib.compress(raw, 9))
        + _chunk(b'IEND', b'')
    )
    tmp = path + '.tmp'
    with open(tmp, 'wb') as fh:
        fh.write(png)
    os.replace(tmp, path)
    return size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--text', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--target', type=int, default=430, help='approximate pixel width')
    args = ap.parse_args()

    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, border=0)
    qr.add_data(args.text.encode('utf-8'))
    qr.make(fit=True)
    matrix = qr.get_matrix()
    modules = len(matrix)
    scale = max(3, args.target // (modules + 4))
    size = write_png(matrix, args.out, scale)
    print('QR modules=%d scale=%d size=%dpx out=%s' % (modules, scale, size, args.out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
