"""mksat.py <mapsdata> <out israel-sat.map>: the satellite layer of the maps app.

The Sentinel-2 cloudless tiles (Web Mercator, PMTiles, zoom 0..12) are resampled into the app's own projection at
its zoom levels 0..6, with exactly the integer scale factors the app uses (Ui.setView), so a tile's pixels fall on
the same screen pixels as the roads drawn over it. Tiles are 128x128 JPEG.

Layout (big-endian): 'ILST' u16 version, u8 nLevels, i32 lat top, i32 lon left (micro-degrees);
per level: u8 zoom, u16 rows, u16 cols, u32 table offset; each table: rows*cols+1 x u32 offsets (from the file
start, a tile is [off[i], off[i+1]); empty when equal), row major from the top-left; then the JPEG data."""
import sys, os, io, math, struct, zipfile
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pmtiles import PM, tile_xy

UPP = [16000, 8000, 4000, 2000, 1000, 500, 250]   # zoom 0..6 (zoom 7 would only enlarge the 30 m photos)
LAT_TOP, LAT_BOT, LON_L, LON_R = 33350000, 29450000, 34200000, 35950000
T = 128
SEA = (16, 30, 58)

def scales(z):
    sh = 20 if z <= 4 else 16
    ky = (1 << sh) // UPP[z]
    kx = int((1 << sh) * np.float32(0.8526) / UPP[z])
    return sh, kx, ky

class Mosaic:
    """source pixels of one Web Mercator zoom over the area (missing tiles from the zoom below)"""
    def __init__(self, pm, zs):
        self.zs = zs
        x0, y0 = tile_xy(zs, LAT_TOP / 1e6, LON_L / 1e6)
        x1, y1 = tile_xy(zs, LAT_BOT / 1e6, LON_R / 1e6)
        self.x0, self.y0 = x0, y0
        w, h = (x1 - x0 + 1) * 256, (y1 - y0 + 1) * 256
        self.img = np.zeros((h, w, 3), dtype=np.uint8)
        self.img[:] = SEA
        miss = 0
        for ty in range(y0, y1 + 1):
            for tx in range(x0, x1 + 1):
                t = pm.get(zs, tx, ty)
                if t is None and zs > 0:
                    # the parent tile, enlarged
                    p = pm.get(zs - 1, tx // 2, ty // 2)
                    if p is not None:
                        im = Image.open(io.BytesIO(p)).convert('RGB')
                        qx, qy = (tx % 2) * 128, (ty % 2) * 128
                        im = im.crop((qx, qy, qx + 128, qy + 128)).resize((256, 256), Image.BILINEAR)
                    else:
                        miss += 1
                        continue
                elif t is None:
                    miss += 1
                    continue
                else:
                    im = Image.open(io.BytesIO(t)).convert('RGB')
                self.img[(ty - y0) * 256:(ty - y0 + 1) * 256, (tx - x0) * 256:(tx - x0 + 1) * 256] = np.asarray(im)
        print('  source zoom %d: %dx%d px, %d tiles missing' % (zs, w, h, miss))

    def sample(self, lat, lon):
        n = (1 << self.zs) * 256
        gx = (lon + 180.0) / 360.0 * n - self.x0 * 256
        gy = (1 - np.arcsinh(np.tan(np.radians(lat))) / np.pi) / 2 * n - self.y0 * 256
        h, w = self.img.shape[:2]
        gx = np.clip(gx - 0.5, 0, w - 1.001); gy = np.clip(gy - 0.5, 0, h - 1.001)
        x0 = gx.astype(np.int32); y0 = gy.astype(np.int32)
        fx = (gx - x0)[..., None]; fy = (gy - y0)[..., None]
        a = self.img[y0, x0].astype(np.float32); b = self.img[y0, x0 + 1].astype(np.float32)
        c = self.img[y0 + 1, x0].astype(np.float32); d = self.img[y0 + 1, x0 + 1].astype(np.float32)
        return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy).astype(np.uint8)

def main():
    src, out = sys.argv[1:3]
    pm = PM(zipfile.ZipFile(src).open('satellite/israel-s2.pmtiles'))
    levels = []
    mosaics = {}
    for z in range(len(UPP)):
        sh, kx, ky = scales(z)
        lon_pp = (1 << sh) / kx / 1e6          # degrees per pixel, as the app maps them
        lat_pp = (1 << sh) / ky / 1e6
        zs = min(12, max(0, math.ceil(math.log2(360.0 / (256 * lon_pp)))))
        if zs not in mosaics:
            mosaics.clear()
            mosaics[zs] = Mosaic(pm, zs)
        mo = mosaics[zs]
        W = math.ceil((LON_R - LON_L) / 1e6 / lon_pp); H = math.ceil((LAT_TOP - LAT_BOT) / 1e6 / lat_pp)
        rows, cols = (H + T - 1) // T, (W + T - 1) // T
        tiles = []
        total = 0
        for r in range(rows):
            for c in range(cols):
                py = (np.arange(T) + r * T + 0.5)[:, None]
                px = (np.arange(T) + c * T + 0.5)[None, :]
                lat = LAT_TOP / 1e6 - py * lat_pp + 0 * px
                lon = LON_L / 1e6 + px * lon_pp + 0 * py
                rgb = mo.sample(lat, lon)
                b = io.BytesIO()
                Image.fromarray(rgb).save(b, 'JPEG', quality=70, optimize=True)
                tiles.append(b.getvalue()); total += len(tiles[-1])
        print('level %d: %dx%d px, %d x %d tiles, %.1f MB' % (z, W, H, rows, cols, total / 1e6))
        levels.append((z, rows, cols, tiles))
    head = bytearray(b'ILST' + struct.pack('>HBii', 1, len(levels), LAT_TOP, LON_L))
    hsize = len(head) + len(levels) * 9
    tsize = sum((r * c + 1) * 4 for _, r, c, _ in levels)
    data_at = hsize + tsize
    tables = bytearray(); data = bytearray()
    for z, r, c, tiles in levels:
        head.extend(struct.pack('>BHHI', z, r, c, hsize + len(tables)))
        for t in tiles:
            tables.extend(struct.pack('>I', data_at + len(data))); data.extend(t)
        tables.extend(struct.pack('>I', data_at + len(data)))
    with open(out, 'wb') as f:
        f.write(head); f.write(tables); f.write(data)
    print('wrote %s %.1f MB' % (out, os.path.getsize(out) / 1e6))

main()
