#!/usr/bin/env python3
"""Builds app/src/main/assets/places/world.bin, the outlines of the countries drawn behind photo markers.

Source: Natural Earth 1:50m admin-0 countries (public domain), e.g.
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/<commit>/geojson/ne_50m_admin_0_countries.geojson

Usage: build_worldmap.py ne_50m_admin_0_countries.geojson ../app/src/main/assets/places/world.bin

File format (all integers big-endian unless varint):
  magic "EKWM" | version u8 = 1 | ring count varint
  per ring: point count varint | first point as two i32 (longitude, latitude in 1/10000 degree)
            | then per further point two zigzag varints: the change in longitude and latitude, same unit
Rings are exterior rings of the country polygons (holes are dropped: none matters at this scale).
Points are thinned with Douglas-Peucker at TOLERANCE degrees, which is invisible until far zoom.
"""
import json
import struct
import sys

TOLERANCE = 0.02
SCALE = 10_000


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(n: int) -> int:
    return (n << 1) ^ (n >> 63)


def simplify(points, tolerance):
    """Iterative Douglas-Peucker on a closed ring given as a list of (x, y)."""
    if len(points) <= 4:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        worst, index = 0.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            d = abs(dy * (px - ax) - dx * (py - ay)) / length if length else ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            if d > worst:
                worst, index = d, i
        if worst > tolerance:
            keep[index] = True
            stack += [(a, index), (index, b)]
    return [p for p, k in zip(points, keep) if k]


def rings(geometry):
    if geometry["type"] == "Polygon":
        yield geometry["coordinates"][0]
    elif geometry["type"] == "MultiPolygon":
        for polygon in geometry["coordinates"]:
            yield polygon[0]


def main(source, target):
    data = json.load(open(source, encoding="utf8"))
    out_rings = []
    for feature in data["features"]:
        for ring in rings(feature["geometry"]):
            simple = simplify([(p[0], p[1]) for p in ring], TOLERANCE)
            if len(simple) >= 4:
                out_rings.append(simple)
    blob = bytearray(b"EKWM" + bytes([1]) + varint(len(out_rings)))
    total = 0
    for ring in out_rings:
        q = [(round(x * SCALE), round(y * SCALE)) for x, y in ring]
        blob += varint(len(q)) + struct.pack(">ii", *q[0])
        for (px, py), (x, y) in zip(q, q[1:]):
            blob += varint(zigzag(x - px)) + varint(zigzag(y - py))
        total += len(q)
    open(target, "wb").write(blob)
    print(f"{len(out_rings)} rings, {total} points, {len(blob)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
