'use strict';

const http = require('node:http');

const PORT = Number(process.env.PORT || 3000);
const API_KEY = process.env.OPENAI_API_KEY || '';
const MODEL = process.env.OPENAI_MODEL || 'gpt-4.1-mini';
const SHARED_TOKEN = process.env.SCREEN_AI_SHARED_TOKEN || '';
const MAX_BODY_BYTES = 12 * 1024 * 1024;

function send(res, status, data) {
  const payload = JSON.stringify(data);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  res.end(payload);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body is too large.'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(Object.assign(new Error('Request body must be valid JSON.'), { statusCode: 400 })); }
    });
    req.on('error', reject);
  });
}

function getAnswerText(response) {
  if (typeof response.output_text === 'string' && response.output_text.trim()) return response.output_text.trim();
  const text = [];
  for (const item of response.output || []) {
    if (item.type !== 'message') continue;
    for (const part of item.content || []) {
      if (part.type === 'output_text' && typeof part.text === 'string') text.push(part.text);
    }
  }
  return text.join('\n').trim();
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true, service: 'screen-ai-backend' });
  if (req.method !== 'POST' || req.url !== '/solve') return send(res, 404, { error: 'Not found.' });

  if (!API_KEY || API_KEY === 'replace_with_your_openai_api_key') {
    return send(res, 503, { error: 'Server administrator must configure OPENAI_API_KEY.' });
  }
  if (SHARED_TOKEN && req.headers['x-screen-ai-token'] !== SHARED_TOKEN) {
    return send(res, 401, { error: 'Invalid backend token.' });
  }

  let body;
  try { body = await readJson(req); }
  catch (error) { return send(res, error.statusCode || 400, { error: error.message || 'Invalid request.' }); }

  const encoded = typeof body.imageBase64 === 'string' ? body.imageBase64 : '';
  if (!encoded || encoded.length > 10 * 1024 * 1024 || !/^[A-Za-z0-9+/=]+$/.test(encoded)) {
    return send(res, 400, { error: 'imageBase64 must contain a valid, reasonably sized base64 screenshot.' });
  }
  const mimeType = body.mimeType === 'image/png' ? 'image/png' : 'image/jpeg';
  const prompt = typeof body.prompt === 'string' ? body.prompt.slice(0, 2000) : 'Read and answer the most prominent question in this screenshot.';

  try {
    const upstream = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: {
        authorization: `Bearer ${API_KEY}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({
        model: MODEL,
        max_output_tokens: 1400,
        input: [{
          role: 'user',
          content: [
            { type: 'input_text', text: `${prompt}\nUse clear, student-friendly language. State the final answer clearly and do not invent details that are unreadable.` },
            { type: 'input_image', image_url: `data:${mimeType};base64,${encoded}`, detail: 'high' }
          ]
        }]
      }),
      signal: AbortSignal.timeout(90000)
    });
    const raw = await upstream.text();
    let result;
    try { result = JSON.parse(raw); } catch { result = {}; }
    if (!upstream.ok) {
      const safeMessage = result.error && result.error.message ? result.error.message : `OpenAI returned HTTP ${upstream.status}.`;
      console.error('OpenAI request failed:', upstream.status, safeMessage);
      return send(res, 502, { error: `AI provider request failed: ${safeMessage}` });
    }
    const answer = getAnswerText(result);
    if (!answer) return send(res, 502, { error: 'The AI response did not contain readable text.' });
    return send(res, 200, { answer, model: MODEL });
  } catch (error) {
    console.error('AI request error:', error.message);
    return send(res, 502, { error: 'Could not contact the AI provider. Check server network/configuration and try again.' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Screen AI backend listening on port ${PORT}`);
  console.log(`Model: ${MODEL}`);
  if (!SHARED_TOKEN) console.warn('WARNING: SCREEN_AI_SHARED_TOKEN is unset; /solve is not protected by an app token. Set it for public deployments.');
});

process.on('SIGINT', () => server.close(() => process.exit(0)));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
