/**
 * Minimal QR encoder: byte mode, error correction level M, versions 1-10.
 *
 * No dependencies, because the page is served straight out of the repository
 * and must also work inside the Android room server's static handler. Byte mode
 * carries URLs and UTF-8 text; level M recovers from ~15% damage, which is what
 * a phone camera pointed at a laptop screen needs.
 *
 * The matrix follows ISO/IEC 18004; the test suite rasterises the output and
 * decodes it with OpenCV, so a mistake here fails loudly instead of producing a
 * pretty but unreadable square.
 */

// Data codewords per block for level M, versions 1-10:
// [ecCodewordsPerBlock, [[blockCount, dataCodewordsPerBlock], ...]]
const LEVEL_M = {
  1: [10, [[1, 16]]],
  2: [16, [[1, 28]]],
  3: [26, [[1, 44]]],
  4: [18, [[2, 32]]],
  5: [24, [[2, 43]]],
  6: [16, [[4, 27]]],
  7: [18, [[4, 31]]],
  8: [22, [[2, 38], [2, 39]]],
  9: [22, [[3, 36], [2, 37]]],
  10: [26, [[4, 43], [1, 44]]],
};

const ALIGNMENT = {
  1: [],
  2: [6, 18],
  3: [6, 22],
  4: [6, 26],
  5: [6, 30],
  6: [6, 34],
  7: [6, 22, 38],
  8: [6, 24, 42],
  9: [6, 26, 46],
  10: [6, 28, 50],
};

export const MAX_VERSION = 10;

// ---- Galois field GF(256) -------------------------------------------------

const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);

(function buildTables() {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();

function mul(a, b) {
  if (a === 0 || b === 0) return 0;
  return EXP[LOG[a] + LOG[b]];
}

/** Generator polynomial for [degree] error-correction codewords. */
function generator(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= mul(poly[j], EXP[i]);
    }
    poly = next;
  }
  return poly;
}

/** Reed-Solomon remainder of [data] divided by the generator. */
function remainder(data, degree) {
  const gen = generator(degree);
  const result = new Uint8Array(degree);
  for (const byte of data) {
    const factor = byte ^ result[0];
    result.copyWithin(0, 1);
    result[degree - 1] = 0;
    for (let i = 0; i < degree; i++) {
      result[i] ^= mul(gen[i + 1], factor);
    }
  }
  return result;
}

// ---- bit stream -----------------------------------------------------------

function utf8Bytes(text) {
  if (typeof TextEncoder !== 'undefined') return Array.from(new TextEncoder().encode(text));
  // Fallback for very old runtimes; the page is served from a laptop, not one.
  const out = [];
  for (const ch of unescape(encodeURIComponent(text))) out.push(ch.charCodeAt(0));
  return out;
}

function dataCapacity(version) {
  return LEVEL_M[version][1].reduce((sum, [blocks, size]) => sum + blocks * size, 0);
}

function pickVersion(byteLength) {
  for (let version = 1; version <= MAX_VERSION; version++) {
    const headerBits = 4 + (version >= 10 ? 16 : 8);
    if (byteLength * 8 + headerBits <= dataCapacity(version) * 8) return version;
  }
  throw new Error(`内容太长：${byteLength} 字节超出二维码容量`);
}

function buildCodewords(bytes, version) {
  const bits = [];
  const push = (value, width) => {
    for (let i = width - 1; i >= 0; i--) bits.push((value >> i) & 1);
  };

  push(0b0100, 4); // byte mode
  push(bytes.length, version >= 10 ? 16 : 8);
  for (const byte of bytes) push(byte, 8);

  const capacityBits = dataCapacity(version) * 8;
  if (bits.length > capacityBits) throw new Error('内容超出所选版本的容量');

  // Terminator, then pad to a whole byte, then the alternating pad codewords.
  const terminator = Math.min(4, capacityBits - bits.length);
  push(0, terminator);
  while (bits.length % 8 !== 0) bits.push(0);

  const codewords = [];
  for (let i = 0; i < bits.length; i += 8) {
    let value = 0;
    for (let j = 0; j < 8; j++) value = (value << 1) | bits[i + j];
    codewords.push(value);
  }
  const PAD = [0xec, 0x11];
  for (let i = 0; codewords.length < dataCapacity(version); i++) {
    codewords.push(PAD[i % 2]);
  }
  return codewords;
}

/** Splits data into blocks, appends EC codewords, interleaves both. */
function interleave(dataCodewords, version) {
  const [ecPerBlock, groups] = LEVEL_M[version];
  const blocks = [];
  let offset = 0;
  for (const [count, size] of groups) {
    for (let i = 0; i < count; i++) {
      const block = dataCodewords.slice(offset, offset + size);
      offset += size;
      blocks.push({ data: block, ec: remainder(block, ecPerBlock) });
    }
  }

  const out = [];
  const maxData = Math.max(...blocks.map((b) => b.data.length));
  for (let i = 0; i < maxData; i++) {
    for (const block of blocks) {
      if (i < block.data.length) out.push(block.data[i]);
    }
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (const block of blocks) out.push(block.ec[i]);
  }
  return out;
}

// ---- matrix ---------------------------------------------------------------

function bch(data, poly, bits) {
  let value = data << bits;
  const msb = (input) => {
    let position = -1;
    for (let i = 31; i >= 0; i--) {
      if ((input >>> i) & 1) {
        position = i;
        break;
      }
    }
    return position;
  };
  let top = msb(value);
  const polyTop = msb(poly);
  while (top >= polyTop) {
    value ^= poly << (top - polyTop);
    top = msb(value);
  }
  return value;
}

function formatBits(eccLevelBits, mask) {
  const data = (eccLevelBits << 3) | mask;
  return ((data << 10) | bch(data, 0x537, 10)) ^ 0x5412;
}

function versionBits(version) {
  return (version << 12) | bch(version, 0x1f25, 12);
}

function emptyMatrix(size) {
  const modules = [];
  const reserved = [];
  for (let i = 0; i < size; i++) {
    modules.push(new Array(size).fill(0));
    reserved.push(new Array(size).fill(false));
  }
  return { size, modules, reserved };
}

function placeFinder(grid, row, col) {
  const { size, modules, reserved } = grid;
  for (let r = -1; r <= 7; r++) {
    for (let c = -1; c <= 7; c++) {
      const y = row + r;
      const x = col + c;
      if (y < 0 || y >= size || x < 0 || x >= size) continue;
      const inRing = r >= 0 && r <= 6 && c >= 0 && c <= 6;
      const dark =
        inRing &&
        ((r === 0 || r === 6 || c === 0 || c === 6) || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
      modules[y][x] = dark ? 1 : 0;
      reserved[y][x] = true;
    }
  }
}

function placeAlignment(grid, version) {
  const { modules, reserved } = grid;
  for (const row of ALIGNMENT[version]) {
    for (const col of ALIGNMENT[version]) {
      // Alignment patterns never overlap the three finder corners.
      if (reserved[row][col]) continue;
      for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) {
          const dark = Math.max(Math.abs(r), Math.abs(c)) !== 1;
          modules[row + r][col + c] = dark ? 1 : 0;
          reserved[row + r][col + c] = true;
        }
      }
    }
  }
}

function reserveFormatAreas(grid, version) {
  const { size, reserved } = grid;
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      reserved[8][i] = true;
      reserved[i][8] = true;
    }
  }
  for (let i = 0; i < 8; i++) {
    reserved[8][size - 1 - i] = true;
    reserved[size - 1 - i][8] = true;
  }
  reserved[8][6] = true;
  reserved[6][8] = true;
  reserved[size - 8][8] = true; // the dark module

  if (version >= 7) {
    for (let i = 0; i < 6; i++) {
      for (let j = 0; j < 3; j++) {
        reserved[i][size - 11 + j] = true;
        reserved[size - 11 + j][i] = true;
      }
    }
  }
}

function drawFunctionPatterns(grid, version) {
  const { size, modules, reserved } = grid;
  placeFinder(grid, 0, 0);
  placeFinder(grid, 0, size - 7);
  placeFinder(grid, size - 7, 0);

  for (let i = 8; i < size - 8; i++) {
    const dark = i % 2 === 0 ? 1 : 0;
    modules[i][6] = dark;
    modules[6][i] = dark;
    reserved[i][6] = true;
    reserved[6][i] = true;
  }

  placeAlignment(grid, version);
  reserveFormatAreas(grid, version);

  modules[size - 8][8] = 1; // fixed dark module
}

function placeData(grid, codewords) {
  const { size, modules, reserved } = grid;
  let bit = 0;
  const total = codewords.length * 8;
  const nextBit = () => {
    if (bit >= total) return 0;
    const value = (codewords[bit >> 3] >> (7 - (bit & 7))) & 1;
    bit += 1;
    return value;
  };

  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5; // skip the vertical timing column
    for (let step = 0; step < size; step++) {
      const row = upward ? size - 1 - step : step;
      for (let column = 0; column < 2; column++) {
        const col = right - column;
        if (reserved[row][col]) continue;
        modules[row][col] = nextBit();
      }
    }
    upward = !upward;
  }
}

const MASKS = [
  (r, c) => (r + c) % 2 === 0,
  (r) => r % 2 === 0,
  (r, c) => c % 3 === 0,
  (r, c) => (r + c) % 3 === 0,
  (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
  (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
  (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
  (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
];

function applyMask(grid, mask) {
  const { size, modules, reserved } = grid;
  const test = MASKS[mask];
  for (let row = 0; row < size; row++) {
    for (let col = 0; col < size; col++) {
      if (reserved[row][col]) continue;
      if (test(row, col)) modules[row][col] ^= 1;
    }
  }
}

function writeFormat(grid, mask) {
  const { size, modules } = grid;
  const bits = formatBits(0, mask); // level M is 0b00 in the format field
  const bit = (index) => (bits >> index) & 1;

  // The same 15 bits go out twice: once down the column beside the top-left
  // finder, once along the row. The two runs are laid out in opposite
  // directions, which is easy to get backwards — a reader then takes a wrong
  // mask and the whole square stays unreadable.
  for (let i = 0; i < 15; i++) {
    if (i < 6) {
      modules[i][8] = bit(i);
    } else if (i < 8) {
      modules[i + 1][8] = bit(i);
    } else {
      modules[size - 15 + i][8] = bit(i);
    }
  }

  for (let i = 0; i < 15; i++) {
    if (i < 8) {
      modules[8][size - 1 - i] = bit(i);
    } else if (i < 9) {
      modules[8][7] = bit(i);
    } else {
      modules[8][14 - i] = bit(i);
    }
  }

  modules[size - 8][8] = 1; // the dark module sits inside this area
}

function writeVersion(grid, version) {
  if (version < 7) return;
  const { size, modules } = grid;
  const bits = versionBits(version);
  for (let i = 0; i < 18; i++) {
    const bit = (bits >> i) & 1;
    const row = Math.floor(i / 3);
    const col = size - 11 + (i % 3);
    modules[row][col] = bit;
    modules[col][row] = bit;
  }
}

// ---- mask scoring ---------------------------------------------------------

function penalty(matrix) {
  const size = matrix.length;
  let score = 0;

  for (let i = 0; i < size; i++) {
    for (const along of ['row', 'col']) {
      let run = 1;
      for (let j = 1; j < size; j++) {
        const value = along === 'row' ? matrix[i][j] : matrix[j][i];
        const previous = along === 'row' ? matrix[i][j - 1] : matrix[j - 1][i];
        if (value === previous) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) score += 3 + (run - 5);
    }
  }

  for (let row = 0; row < size - 1; row++) {
    for (let col = 0; col < size - 1; col++) {
      const value = matrix[row][col];
      if (
        value === matrix[row][col + 1] &&
        value === matrix[row + 1][col] &&
        value === matrix[row + 1][col + 1]
      ) {
        score += 3;
      }
    }
  }

  const pattern = [1, 0, 1, 1, 1, 0, 1];
  for (let row = 0; row < size; row++) {
    for (let col = 0; col <= size - 7; col++) {
      let horizontal = true;
      let vertical = true;
      for (let k = 0; k < 7; k++) {
        if (matrix[row][col + k] !== pattern[k]) horizontal = false;
        if (matrix[col + k][row] !== pattern[k]) vertical = false;
      }
      if (horizontal) score += 40;
      if (vertical) score += 40;
    }
  }

  let dark = 0;
  for (let row = 0; row < size; row++) {
    for (let col = 0; col < size; col++) dark += matrix[row][col];
  }
  const ratio = (dark * 100) / (size * size);
  score += Math.floor(Math.abs(ratio - 50) / 5) * 10;
  return score;
}

// ---- public API -----------------------------------------------------------

/**
 * The final codeword stream for [text], for tests that compare against a
 * reference implementation byte for byte. Not used by the renderer.
 */
export function codewordsFor(text) {
  const bytes = utf8Bytes(String(text));
  const version = pickVersion(bytes.length);
  return { version, codewords: interleave(buildCodewords(bytes, version), version) };
}

/**
 * Encodes [text] and returns the module matrix (1 = dark), plus the chosen
 * version and mask so tests can assert on them.
 */
export function encode(text, { maxVersion = MAX_VERSION } = {}) {
  const bytes = utf8Bytes(String(text));
  const version = pickVersion(bytes.length);
  if (version > maxVersion) throw new Error(`内容需要更长的二维码版本：v${version}`);

  const codewords = interleave(buildCodewords(bytes, version), version);
  const size = 17 + version * 4;

  let best = null;
  for (let mask = 0; mask < 8; mask++) {
    const matrix = emptyMatrix(size);
    drawFunctionPatterns(matrix, version);
    placeData(matrix, codewords);
    applyMask(matrix, mask);
    writeFormat(matrix, mask);
    writeVersion(matrix, version);

    const score = penalty(matrix.modules);
    if (!best || score < best.score) best = { score, mask, matrix: matrix.modules };
  }

  return { version, mask: best.mask, size, modules: best.matrix };
}

/** Renders [text] as an SVG string with a quiet zone of [margin] modules. */
export function toSvg(text, { size = 256, margin = 4, dark = '#111111', light = '#ffffff' } = {}) {
  const { modules, size: modulesPerSide } = encode(text);
  const total = modulesPerSide + margin * 2;
  const parts = [];
  for (let row = 0; row < modulesPerSide; row++) {
    for (let col = 0; col < modulesPerSide; col++) {
      if (!modules[row][col]) continue;
      parts.push(`M${col + margin} ${row + margin}h1v1h-1z`);
    }
  }
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" ` +
    `viewBox="0 0 ${total} ${total}" shape-rendering="crispEdges" role="img" ` +
    `aria-label="二维码">` +
    `<rect width="${total}" height="${total}" fill="${light}"/>` +
    `<path d="${parts.join('')}" fill="${dark}"/></svg>`
  );
}
