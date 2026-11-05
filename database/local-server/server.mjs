#!/usr/bin/env node
/**
 * 校园智能运动平台 · 局域网本地模拟服务
 *
 * 目标：
 *  - 没有 MySQL 时，用本机 JSON + 本地视频文件模拟数据库持久化。
 *  - 同一 Wi-Fi 下的手机可以访问 http://<电脑IP>:8787/。
 *  - 同时托管 ui/ 静态页面和 /api/v1/videos 端点。
 *
 * 运行：
 *   node database/local-server/server.mjs
 *
 * 可用环境变量：
 *   PORT=8787
 *   HOST=0.0.0.0
 *   MAX_UPLOAD_BYTES=209715200
 */
import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import dgram from 'node:dgram';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const UI_ROOT = path.join(PROJECT_ROOT, 'ui');
const DATA_ROOT = process.env.DATA_DIR
  ? path.resolve(process.env.DATA_DIR)
  : path.join(__dirname, 'data');
const VIDEO_ROOT = path.join(DATA_ROOT, 'videos');
const STORE_FILE = path.join(DATA_ROOT, 'store.json');
const API_PREFIX = '/api/v1';

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const MAX_UPLOAD_BYTES = Number(process.env.MAX_UPLOAD_BYTES || 200 * 1024 * 1024);
const MAX_BODY_BYTES = MAX_UPLOAD_BYTES + 1024 * 1024;

const VIDEO_EXTENSIONS = new Set(['.mp4', '.webm', '.mov', '.m4v', '.ogv', '.ogg', '.mkv', '.avi']);
const MIME_BY_EXTENSION = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.task': 'application/octet-stream',
};
const MIME_BY_VIDEO_EXTENSION = {
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.m4v': 'video/mp4',
  '.ogv': 'video/ogg',
  '.ogg': 'video/ogg',
  '.mkv': 'video/x-matroska',
  '.avi': 'video/x-msvideo',
};

class ApiError extends Error {
  constructor(status, code, message, details = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

fs.mkdirSync(VIDEO_ROOT, { recursive: true });

function defaultStore() {
  return { version: 2, videos: [], workoutEvents: [], chatMessages: [] };
}

function loadStore() {
  try {
    const raw = JSON.parse(fs.readFileSync(STORE_FILE, 'utf8'));
    if (raw && Array.isArray(raw.videos)) return raw;
  } catch (error) {
    if (error.code !== 'ENOENT') {
      console.warn('[local-store] 读取 store.json 失败，将使用空数据：', error.message);
    }
  }
  return defaultStore();
}

let store = loadStore();
store.workoutEvents ||= [];
store.chatMessages ||= [];

const realtimeClients = new Set();
function broadcast(payload) {
  const body = JSON.stringify(payload);
  for (const client of realtimeClients) {
    if (client.readyState === 1) client.send(body);
  }
}

function saveStore() {
  const tmp = `${STORE_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(store, null, 2), 'utf8');
  fs.renameSync(tmp, STORE_FILE);
}

function setCommonHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range, X-Owner, X-File-Name');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Range, Accept-Ranges, Content-Length');
  res.setHeader('X-Content-Type-Options', 'nosniff');
}

function sendJson(res, status, payload, extraHeaders = {}) {
  if (res.headersSent) return;
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    ...extraHeaders,
  });
  res.end(body);
}

function sendError(res, status, code, message, details = {}) {
  sendJson(res, status, { error: { code, message, details } });
}

function sendNoContent(res) {
  if (res.headersSent) return;
  res.writeHead(204, { 'Cache-Control': 'no-store' });
  res.end();
}

function readBody(req, limit = MAX_BODY_BYTES) {
  const declared = Number(req.headers['content-length'] || 0);
  if (declared > limit) {
    return Promise.reject(new ApiError(
      413,
      'VIDEO_TOO_LARGE',
      `上传内容超过限制（${Math.round(limit / 1024 / 1024)} MB）`,
    ));
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let exceeded = false;
    req.on('data', (chunk) => {
      if (exceeded) return;
      total += chunk.length;
      if (total > limit) {
        exceeded = true;
        chunks.length = 0;
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (exceeded) {
        reject(new ApiError(
          413,
          'VIDEO_TOO_LARGE',
          `上传内容超过限制（${Math.round(limit / 1024 / 1024)} MB）`,
        ));
        return;
      }
      resolve(Buffer.concat(chunks));
    });
    req.on('error', reject);
  });
}

function parseMultipart(buffer, boundary) {
  const delimiter = Buffer.from(`--${boundary}`, 'utf8');
  const parts = [];
  let cursor = buffer.indexOf(delimiter);

  while (cursor !== -1) {
    cursor += delimiter.length;
    if (buffer[cursor] === 45 && buffer[cursor + 1] === 45) break; // --
    if (buffer[cursor] === 13 && buffer[cursor + 1] === 10) cursor += 2;

    const headerEnd = buffer.indexOf(Buffer.from('\r\n\r\n', 'utf8'), cursor);
    if (headerEnd === -1) break;
    const next = buffer.indexOf(delimiter, headerEnd + 4);
    if (next === -1) break;

    const headersText = buffer.subarray(cursor, headerEnd).toString('utf8');
    let data = buffer.subarray(headerEnd + 4, next);
    if (data.length >= 2 && data[data.length - 2] === 13 && data[data.length - 1] === 10) {
      data = data.subarray(0, data.length - 2);
    }

    const name = /name="([^"]*)"/i.exec(headersText)?.[1] || '';
    const filename = /filename="([^"]*)"/i.exec(headersText)?.[1] || '';
    const contentType = /Content-Type:\s*([^\r\n]+)/i.exec(headersText)?.[1]?.trim() || '';
    parts.push({ name, filename, contentType, data });
    cursor = next;
  }

  return parts;
}

function getBoundary(contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  return match ? (match[1] || match[2]).trim() : '';
}

function safeExtension(filename, contentType) {
  const ext = path.extname(filename || '').toLowerCase();
  if (/^\.[a-z0-9]{1,8}$/.test(ext)) return ext;
  const byMime = {
    'video/mp4': '.mp4',
    'video/webm': '.webm',
    'video/quicktime': '.mov',
    'video/ogg': '.ogv',
    'video/x-matroska': '.mkv',
    'video/x-msvideo': '.avi',
  };
  return byMime[(contentType || '').toLowerCase()] || '.bin';
}

function isVideoFile(filename, contentType) {
  const type = (contentType || '').toLowerCase();
  const ext = path.extname(filename || '').toLowerCase();
  return type.startsWith('video/') || VIDEO_EXTENSIONS.has(ext);
}

function nullableInt(value) {
  if (value === undefined || value === null || value === '') return null;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return null;
  return Math.round(number);
}

function publicAsset(asset) {
  return {
    videoId: asset.videoId,
    owner: asset.owner,
    originalName: asset.originalName,
    contentType: asset.contentType,
    byteSize: asset.byteSize,
    durationMs: asset.durationMs,
    width: asset.width,
    height: asset.height,
    sha256: asset.sha256,
    status: asset.status,
    createdAt: asset.createdAt,
    contentUrl: `${API_PREFIX}/videos/${encodeURIComponent(asset.videoId)}/content`,
  };
}

function findActiveVideo(videoId) {
  return store.videos.find((video) => video.videoId === videoId && video.status === 'active');
}

function pageParams(url) {
  const page = Math.max(1, Number.parseInt(url.searchParams.get('page') || '1', 10) || 1);
  const pageSize = Math.min(
    100,
    Math.max(1, Number.parseInt(url.searchParams.get('pageSize') || '20', 10) || 20),
  );
  return { page, pageSize };
}

async function handleUpload(req, res, url) {
  const contentType = req.headers['content-type'] || '';
  let fields = {};
  let file = null;

  if (contentType.toLowerCase().startsWith('multipart/form-data')) {
    const boundary = getBoundary(contentType);
    if (!boundary) throw new ApiError(400, 'VALIDATION_ERROR', 'multipart 缺少 boundary');
    const body = await readBody(req);
    const parts = parseMultipart(body, boundary);
    for (const part of parts) {
      if (part.name === 'file' && part.filename) {
        file = part;
      } else if (part.name) {
        fields[part.name] = part.data.toString('utf8');
      }
    }
  } else {
    // 兼容 fetch(file) 直接发送二进制流：元数据放 query 或请求头。
    const body = await readBody(req);
    file = {
      filename: url.searchParams.get('fileName') || req.headers['x-file-name'] || 'video.bin',
      contentType,
      data: body,
    };
    fields = {
      owner: url.searchParams.get('owner') || req.headers['x-owner'] || '',
      durationMs: url.searchParams.get('durationMs') || '',
      width: url.searchParams.get('width') || '',
      height: url.searchParams.get('height') || '',
    };
  }

  if (!file || !file.data || file.data.length === 0) {
    throw new ApiError(400, 'VALIDATION_ERROR', '缺少视频文件 file');
  }
  if (file.data.length > MAX_UPLOAD_BYTES) {
    throw new ApiError(
      413,
      'VIDEO_TOO_LARGE',
      `视频超过限制（${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB）`,
    );
  }
  if (!isVideoFile(file.filename, file.contentType)) {
    throw new ApiError(415, 'UNSUPPORTED_VIDEO_TYPE', '只支持视频文件');
  }

  const owner = String(fields.owner || '').trim();
  if (!owner || owner.length > 64) {
    throw new ApiError(400, 'VALIDATION_ERROR', 'owner 必须为 1—64 个字符');
  }

  const videoId = crypto.randomUUID();
  const extension = safeExtension(file.filename, file.contentType);
  const storageKey = `${videoId}${extension}`;
  const targetPath = path.join(VIDEO_ROOT, storageKey);

  try {
    await fsp.writeFile(targetPath, file.data);
  } catch (error) {
    throw new ApiError(503, 'LOCAL_STORAGE_UNAVAILABLE', '本地视频目录不可写', {
      reason: error.message,
    });
  }

  const asset = {
    videoId,
    owner,
    originalName: file.filename || `video${extension}`,
    contentType: file.contentType || MIME_BY_VIDEO_EXTENSION[extension] || 'application/octet-stream',
    byteSize: file.data.length,
    durationMs: nullableInt(fields.durationMs),
    width: nullableInt(fields.width),
    height: nullableInt(fields.height),
    sha256: crypto.createHash('sha256').update(file.data).digest('hex'),
    storageKey,
    storageMode: 'lan',
    status: 'active',
    createdAt: new Date().toISOString(),
    deletedAt: null,
  };

  store.videos.push(asset);
  try {
    saveStore();
  } catch (error) {
    await fsp.rm(targetPath, { force: true });
    store.videos.pop();
    throw new ApiError(503, 'LOCAL_STORAGE_UNAVAILABLE', '本地元数据不可写', {
      reason: error.message,
    });
  }

  sendJson(res, 201, publicAsset(asset));
}

function handleListVideos(res, url) {
  const owner = (url.searchParams.get('owner') || '').trim();
  const { page, pageSize } = pageParams(url);
  const filtered = store.videos
    .filter((video) => video.status === 'active' && (!owner || video.owner === owner))
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  const start = (page - 1) * pageSize;
  sendJson(res, 200, {
    items: filtered.slice(start, start + pageSize).map(publicAsset),
    page,
    pageSize,
    total: filtered.length,
  });
}

function handleVideoContent(req, res, videoId) {
  const asset = findActiveVideo(videoId);
  if (!asset) throw new ApiError(404, 'NOT_FOUND', '视频不存在或已删除');

  const filePath = path.join(VIDEO_ROOT, asset.storageKey);
  if (!fs.existsSync(filePath)) {
    throw new ApiError(404, 'NOT_FOUND', '本地视频文件已丢失');
  }

  const stat = fs.statSync(filePath);
  const range = req.headers.range;
  const headers = {
    'Content-Type': asset.contentType || 'application/octet-stream',
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
    'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(asset.originalName)}`,
  };

  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!match) {
      res.writeHead(416, { ...headers, 'Content-Range': `bytes */${stat.size}` });
      res.end();
      return;
    }
    let start;
    let end;
    if (!match[1] && match[2]) {
      const suffix = Number.parseInt(match[2], 10);
      start = Math.max(0, stat.size - suffix);
      end = stat.size - 1;
    } else {
      start = match[1] ? Number.parseInt(match[1], 10) : 0;
      end = match[2] ? Number.parseInt(match[2], 10) : stat.size - 1;
    }
    if (start > end || start >= stat.size) {
      res.writeHead(416, { ...headers, 'Content-Range': `bytes */${stat.size}` });
      res.end();
      return;
    }
    const boundedEnd = Math.min(end, stat.size - 1);
    res.writeHead(206, {
      ...headers,
      'Content-Range': `bytes ${start}-${boundedEnd}/${stat.size}`,
      'Content-Length': boundedEnd - start + 1,
    });
    fs.createReadStream(filePath, { start, end: boundedEnd }).pipe(res);
    return;
  }

  res.writeHead(200, { ...headers, 'Content-Length': stat.size });
  fs.createReadStream(filePath).pipe(res);
}

function handleDeleteVideo(res, videoId) {
  const asset = store.videos.find((video) => video.videoId === videoId);
  if (!asset || asset.status === 'deleted') {
    sendNoContent(res);
    return;
  }
  const filePath = path.join(VIDEO_ROOT, asset.storageKey);
  try {
    fs.rmSync(filePath, { force: true });
  } catch (error) {
    throw new ApiError(503, 'LOCAL_STORAGE_UNAVAILABLE', '删除本地视频文件失败', {
      reason: error.message,
    });
  }
  asset.status = 'deleted';
  asset.deletedAt = new Date().toISOString();
  saveStore();
  sendNoContent(res);
}

async function handleEvents(req, res) {
  if (req.method === 'GET') { sendJson(res, 200, { events: store.workoutEvents }); return true; }
  if (req.method !== 'POST') return false;
  const body = JSON.parse((await readBody(req, 2 * 1024 * 1024)).toString('utf8') || '{}');
  const events = Array.isArray(body.events) ? body.events : [];
  const known = new Set(store.workoutEvents.map((event) => event.eventId));
  for (const event of events) {
    if (!event?.eventId || known.has(event.eventId)) continue;
    if (Number(event.points) < 0 || Number(event.points) > 10000) continue;
    // Demo runs are shown in the UI but must never enter the shared ranking.
    if (event.demo === true) continue;
    store.workoutEvents.push(event); known.add(event.eventId);
  }
  saveStore(); broadcast({ type: 'events', events: store.workoutEvents });
  sendJson(res, 200, { events: store.workoutEvents }); return true;
}

async function handleApi(req, res, url) {
  const apiPath = url.pathname.startsWith(API_PREFIX)
    ? url.pathname.slice(API_PREFIX.length)
    : url.pathname;
  const method = req.method || 'GET';

  if (apiPath === '/events') return handleEvents(req, res);
  if (apiPath === '/chat') return handleChat(req, res);
  if (apiPath === '/health' && method === 'GET') {
    sendJson(res, 200, {
      ok: true,
      storage: 'local',
      activeVideos: store.videos.filter((video) => video.status === 'active').length,
    });
    return true;
  }

  if (apiPath === '/videos' && method === 'POST') {
    await handleUpload(req, res, url);
    return true;
  }
  if (apiPath === '/videos' && method === 'GET') {
    handleListVideos(res, url);
    return true;
  }

  const match = /^\/videos\/([^/]+)(\/content)?$/.exec(apiPath);
  if (match) {
    const videoId = decodeURIComponent(match[1]);
    const content = Boolean(match[2]);
    if (content && method === 'GET') {
      handleVideoContent(req, res, videoId);
      return true;
    }
    if (!content && method === 'GET') {
      const asset = findActiveVideo(videoId);
      if (!asset) throw new ApiError(404, 'NOT_FOUND', '视频不存在或已删除');
      sendJson(res, 200, publicAsset(asset));
      return true;
    }
    if (!content && method === 'DELETE') {
      handleDeleteVideo(res, videoId);
      return true;
    }
  }

  return false;
}

async function serveStatic(req, res, url) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    sendError(res, 405, 'METHOD_NOT_ALLOWED', '只支持 GET/HEAD');
    return;
  }

  let pathname = url.pathname;
  try {
    pathname = decodeURIComponent(pathname);
  } catch (_) {
    sendError(res, 400, 'VALIDATION_ERROR', '非法路径');
    return;
  }

  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  let filePath = path.resolve(UI_ROOT, relative);
  if (filePath !== UI_ROOT && !filePath.startsWith(`${UI_ROOT}${path.sep}`)) {
    sendError(res, 403, 'FORBIDDEN', '路径不允许');
    return;
  }

  let stat;
  try {
    stat = await fsp.stat(filePath);
    if (stat.isDirectory()) {
      filePath = path.join(filePath, 'index.html');
      stat = await fsp.stat(filePath);
    }
  } catch (_) {
    sendError(res, 404, 'NOT_FOUND', '文件不存在');
    return;
  }

  const extension = path.extname(filePath).toLowerCase();
  res.writeHead(200, {
    'Content-Type': MIME_BY_EXTENSION[extension] || 'application/octet-stream',
    'Content-Length': stat.size,
    'Cache-Control': extension === '.html' ? 'no-cache' : 'public, max-age=300',
  });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  fs.createReadStream(filePath).pipe(res);
}

async function handleChat(req, res) {
  if (req.method === 'GET') { sendJson(res, 200, { messages: store.chatMessages.slice(-100) }); return true; }
  if (req.method !== 'POST') return false;
  const body = JSON.parse((await readBody(req, 64 * 1024)).toString('utf8') || '{}');
  if (!String(body.text || '').trim()) throw new ApiError(400, 'VALIDATION_ERROR', '消息不能为空');
  const chat = { messageId: body.messageId || crypto.randomUUID(), username: String(body.username || '同学').slice(0, 20), profileId: String(body.profileId || ''), text: String(body.text).slice(0, 500), sentAt: Number(body.sentAt) || Date.now() };
  store.chatMessages.push(chat); store.chatMessages = store.chatMessages.slice(-1000); saveStore();
  sendJson(res, 201, chat); return true;
}

function lanAddresses() {
  const addresses = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const entry of list || []) {
      if (entry.family === 'IPv4' && !entry.internal) addresses.push(entry.address);
    }
  }
  return addresses;
}

const server = http.createServer(async (req, res) => {
  setCommonHeaders(res);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const host = req.headers.host || `127.0.0.1:${PORT}`;
  let url;
  try {
    url = new URL(req.url || '/', `http://${host}`);
  } catch (_) {
    sendError(res, 400, 'VALIDATION_ERROR', '非法请求地址');
    return;
  }

  try {
    if (url.pathname.startsWith(`${API_PREFIX}/`)) {
      const handled = await handleApi(req, res, url);
      if (!handled) sendError(res, 404, 'NOT_FOUND', '接口不存在');
      return;
    }
    await serveStatic(req, res, url);
  } catch (error) {
    if (res.headersSent) {
      res.destroy(error);
      return;
    }
    if (error instanceof ApiError) {
      sendError(res, error.status, error.code, error.message, error.details);
      return;
    }
    console.error('[local-server] 未处理错误：', error);
    sendError(res, 500, 'INTERNAL_ERROR', '本地服务内部错误');
  }
});

server.on('clientError', (error, socket) => {
  if (!socket.destroyed) {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
  }
});

// --- room discovery -------------------------------------------------------
// The APK scans for rooms by broadcasting on DISCOVERY_PORT. Announcing here
// means a laptop-hosted room shows up in the phone's "search nearby" list, the
// same way a phone-hosted room announces itself.
const DISCOVERY_PORT = Number(process.env.DISCOVERY_PORT || 8788);
const DISCOVERY_MAGIC = 'CADENCE_ROOM_V1';
const ROOM_NAME = process.env.ROOM_NAME || '电脑的房间';

const discoverySocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
discoverySocket.on('message', (message, remote) => {
  if (message.toString('utf8').trim() !== DISCOVERY_MAGIC) return;
  const reply = Buffer.from(
    JSON.stringify({ app: 'cadence-room', name: ROOM_NAME, port: PORT }),
    'utf8',
  );
  discoverySocket.send(reply, remote.port, remote.address, () => {});
});
discoverySocket.on('error', (error) => {
  console.error(`房间广播不可用: ${error.message}`);
});
discoverySocket.bind(DISCOVERY_PORT, () => {
  discoverySocket.setBroadcast(true);
});

server.listen(PORT, HOST, () => {
  console.log('校园智能运动平台 · 局域网本地服务');
  console.log(`  本机页面: http://127.0.0.1:${PORT}/`);
  for (const address of lanAddresses()) {
    console.log(`  局域网页面: http://${address}:${PORT}/`);
  }
  console.log(`  接口前缀: ${API_PREFIX}`);
  console.log(`  数据目录: ${DATA_ROOT}`);
  console.log(`  上传上限: ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB`);
  console.log(`  房间广播: ${ROOM_NAME}（UDP ${DISCOVERY_PORT}）`);
});
