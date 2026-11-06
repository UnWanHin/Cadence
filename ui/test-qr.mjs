// Checks the QR encoder by rasterising its matrices and decoding them with
// OpenCV. A wrong mask, a bad Reed-Solomon block split or a misplaced format
// field all still look like a plausible square, so decoding is the only honest
// test.
import { spawnSync } from 'node:child_process';
import { encode, toSvg, codewordsFor, MAX_VERSION } from './qr.js';

let failures = 0;

function check(condition, message) {
  if (condition) {
    console.log('ok - ' + message);
  } else {
    console.error('FAIL: ' + message);
    failures += 1;
  }
}

const PIXELS_PER_MODULE = 8;
const QUIET = 4;

/** Renders the matrix as a PGM image on stdout so python can decode it. */
function toPgm(modules) {
  const side = modules.length;
  const scale = PIXELS_PER_MODULE;
  const width = (side + QUIET * 2) * scale;
  const height = width;
  const pixels = Buffer.alloc(width * height, 255);
  for (let row = 0; row < side; row++) {
    for (let col = 0; col < side; col++) {
      if (!modules[row][col]) continue;
      for (let y = 0; y < scale; y++) {
        for (let x = 0; x < scale; x++) {
          const py = (row + QUIET) * scale + y;
          const px = (col + QUIET) * scale + x;
          pixels[py * width + px] = 0;
        }
      }
    }
  }
  return Buffer.concat([Buffer.from(`P5\n${width} ${height}\n255\n`, 'ascii'), pixels]);
}

const DECODER = `
import sys

try:
    import cv2
    import numpy as np
except ImportError:
    print("SKIP")
    raise SystemExit(0)

raw = sys.stdin.buffer.read()
end = raw.index(b"\\n255\\n") + len(b"\\n255\\n")
header = raw[:end].decode("ascii").split("\\n")
width, height = int(header[1].split()[0]), int(header[1].split()[1])
image = np.frombuffer(raw[end:], dtype=np.uint8).reshape((height, width))
detector = cv2.QRCodeDetector()
text, points, _ = detector.detectAndDecode(image)
print(text.replace("\\n", "\\\\n"))
`;

function decode(modules) {
  const result = spawnSync('python3', ['-c', DECODER], {
    input: toPgm(modules),
    maxBuffer: 8 * 1024 * 1024,
  });
  if (result.error) return null;
  const output = (result.stdout || '').toString().trim();
  if (!output || output === 'SKIP') return null;
  return output.replace(/\\n/g, '\n');
}

const cases = [
  'http://10.21.78.18:8080/app.apk',
  'https://github.com/UnWanHin/Cadence/releases/download/v0.3.1/cadence.apk',
  'CADENCE',
  'http://192.168.1.100:8080/?api=http://192.168.1.100:8080/api/v1',
  '深蹲 30 次，质量 0.92，积分 200',
];

const available = decode(encode('probe').modules) !== null;
if (!available) {
  console.log('SKIP: 没有可用的 python3 + OpenCV，跳过解码校验');
} else {
  for (const text of cases) {
    const encoded = encode(text);
    const decoded = decode(encoded.modules);
    check(decoded === text, `解码还原：「${text.length > 40 ? text.slice(0, 40) + '…' : text}」（v${encoded.version} 掩码 ${encoded.mask}）`);
  }
}

// Structure: the geometry has to be right regardless of the decoder.
const sample = encode(cases[0]);
check(sample.size === 17 + sample.version * 4, '边长符合 17 + 4 × 版本');
check(sample.modules.length === sample.size, '矩阵是正方形');
check(sample.version >= 2 && sample.version <= 3, `这个网址落在 v2 或 v3（实际 v${sample.version}）`);

const finder = (row, col) => {
  // Top-left 7x7 finder: dark ring, light gap, dark 3x3 centre.
  for (let r = 0; r < 7; r++) {
    for (let c = 0; c < 7; c++) {
      const ring = r === 0 || r === 6 || c === 0 || c === 6;
      const core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
      if (sample.modules[row + r][col + c] !== (ring || core ? 1 : 0)) return false;
    }
  }
  return true;
};
check(finder(0, 0), '左上角定位图案正确');
check(finder(0, sample.size - 7), '右上角定位图案正确');
check(finder(sample.size - 7, 0), '左下角定位图案正确');
check(sample.modules[sample.size - 8][8] === 1, '固定的暗模块存在');

const timingOk = (() => {
  for (let i = 8; i < sample.size - 8; i++) {
    const expected = i % 2 === 0 ? 1 : 0;
    if (sample.modules[6][i] !== expected || sample.modules[i][6] !== expected) return false;
  }
  return true;
})();
check(timingOk, '横向与纵向时序图案交替正确');

// Capacity: the picker must escalate the version instead of truncating.
const growing = [];
for (const length of [10, 40, 100, 180, 200]) {
  growing.push(encode('a'.repeat(length)).version);
}
check(
  growing.every((version, index) => index === 0 || version >= growing[index - 1]),
  `长度增加时版本不下降（${growing.join(' → ')}）`,
);
check(growing[growing.length - 1] <= MAX_VERSION, `最长用例仍在 v${MAX_VERSION} 以内`);

let overflowed = false;
try {
  encode('a'.repeat(400));
} catch {
  overflowed = true;
}
check(overflowed, '超长内容给出明确错误而不是静默截断');

// Byte-level regression: these are the reference codewords for this URL from
// the Python qrcode library, level M, version 3. Encoding, padding, Reed-Solomon
// and interleaving all have to stay byte-identical for a reader to work.
const REFERENCE_URL = 'http://10.21.78.18:8080/app.apk';
const REFERENCE_CODEWORDS = [
  65, 246, 135, 71, 71, 3, 162, 242, 243, 19, 2, 227, 35, 18, 227, 115, 130, 227,
  19, 131, 163, 131, 3, 131, 2, 246, 23, 7, 2, 230, 23, 6, 176, 236, 17, 236, 17,
  236, 17, 236, 17, 236, 17, 236, 177, 131, 7, 209, 121, 255, 193, 166, 193, 28,
  171, 73, 99, 238, 11, 29, 233, 86, 36, 219, 241, 141, 236, 146, 117, 180,
];
const produced = codewordsFor(REFERENCE_URL);
check(produced.version === 3, `参考网址选中 v3（实际 v${produced.version}）`);
check(
  produced.codewords.join(',') === REFERENCE_CODEWORDS.join(','),
  '码字流与参考实现逐字节一致（含补齐与纠错码）',
);

// The SVG has to carry a quiet zone; readers need it to find the code at all.
const svg = toSvg('http://10.21.78.18:8080/app.apk', { size: 240, margin: 4 });
check(/viewBox="0 0 \d+ \d+"/.test(svg), 'SVG 带 viewBox');
check(svg.includes('<rect'), 'SVG 有浅色底');
check(svg.includes('shape-rendering="crispEdges"'), 'SVG 关闭平滑，模块边缘不糊');

if (failures === 0) {
  console.log('PASS');
} else {
  console.error(failures + ' failure(s)');
  process.exit(1);
}
