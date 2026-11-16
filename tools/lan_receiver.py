"""LAN ALS receiver. Python 3 standard library only. Ctrl+C to stop."""
import hashlib
import io
import zipfile
import argparse
import json
import sqlite3
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

PAGE = '''<!doctype html><meta charset="utf-8"><title>ALS 실시간 수신</title>
<style>body{font:16px sans-serif;max-width:1000px;margin:30px auto}canvas{width:100%;border:1px solid #ccc}pre{white-space:pre-wrap}select{padding:8px;min-width:300px}</style>
<h1>보관한 실험 ZIP</h1><p id="quota">불러오는 중</p><ul id="archives"></ul><h1>ALS 실시간 수신</h1><select id="runs"></select><p id="state">수신 대기</p>
<canvas id="plot" width="1000" height="300"></canvas><pre id="meta"></pre>
<script>
const runs=document.getElementById('runs'), state=document.getElementById('state'), meta=document.getElementById('meta'), canvas=document.getElementById('plot');
const deleting=new Set();
async function deleteArchive(a){
if(deleting.has(a.id)||!confirm(a.name+'을(를) 노트북 보관함에서 삭제할까요?\\n삭제하면 복구할 수 없습니다.'))return;
deleting.add(a.id);
try{const response=await fetch('/api/archive?id='+encodeURIComponent(a.id),{method:'DELETE',headers:{'X-ALS-Confirm':'delete'}});
if(!response.ok && response.status!==404)throw new Error('HTTP '+response.status);
}catch(e){alert('삭제 실패: '+e.message)}finally{deleting.delete(a.id);await refresh()}}
async function refresh(){try{
const saved=await(await fetch('/api/archives')).json();
document.getElementById('quota').textContent=`${saved.length} / 20개 보관`+(saved.length>=20?' · 저장 공간이 가득 차 새 ZIP을 받을 수 없습니다.':'');
document.getElementById('archives').replaceChildren(...saved.map(a=>{const li=document.createElement('li'),link=document.createElement('a');link.href='/api/archive?id='+a.id;link.textContent=a.name+' ('+Math.ceil(a.size/1024)+' KB)';const button=document.createElement('button');button.type='button';button.textContent='삭제';button.style.marginLeft='12px';button.disabled=deleting.has(a.id);button.onclick=()=>deleteArchive(a);li.append(link,button);return li}));
const all=await (await fetch('/api/runs')).json();
const chosen=runs.value;runs.replaceChildren(...all.map(r=>{const o=document.createElement('option');o.value=r.run_id;o.textContent=r.metadata.experiment_name+' / '+r.run_id.slice(0,8);return o}));
if(all.some(r=>r.run_id===chosen))runs.value=chosen;if(!runs.value)return;
const r=await(await fetch('/api/run?id='+encodeURIComponent(runs.value))).json();
state.textContent=`수신 ${r.count}개 | 최신 lux ${r.samples.at(-1)?.lux ?? '-'} | ${r.end?'측정 종료':'측정 중 또는 연결 대기'} | 마지막 수신 ${r.received_at}`;
meta.textContent=JSON.stringify(r.metadata,null,2);const ctx=canvas.getContext('2d');ctx.clearRect(0,0,1000,300);
const a=r.samples;if(!a.length)return;const lo=Math.min(...a.map(s=>s.lux)),hi=Math.max(...a.map(s=>s.lux));const t0=a[0].elapsed_ms,t1=a.at(-1).elapsed_ms;
ctx.fillText(`${lo}–${hi} lux / ${(t0/1000).toFixed(1)}–${(t1/1000).toFixed(1)} 초 (최근 1000개)`,15,20);
ctx.fillStyle='#176ed1';for(const s of a){const x=20+960*(s.elapsed_ms-t0)/Math.max(1,t1-t0),y=280-240*(s.lux-lo)/Math.max(1,hi-lo);ctx.fillRect(x,y,3,3)}
}catch(e){state.textContent='수신 화면 연결 오류: '+e.message}}
setInterval(refresh,500);refresh();
</script>'''


class Store:
    def __init__(self, folder):
        folder.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(folder / 'als_live.sqlite3', check_same_thread=False)
        self.lock = threading.Lock()
        self.db.executescript('''CREATE TABLE IF NOT EXISTS runs
            (id TEXT PRIMARY KEY, metadata TEXT, ending TEXT, received TEXT);
            CREATE TABLE IF NOT EXISTS samples
            (run TEXT, seq INTEGER, data TEXT, PRIMARY KEY(run,seq));
            CREATE TABLE IF NOT EXISTS archives
            (id TEXT PRIMARY KEY, name TEXT NOT NULL, received TEXT NOT NULL, data BLOB NOT NULL);''')

    def save_archive(self, name, data):
        digest = hashlib.sha256(data).hexdigest()
        if not name or len(name) > 200 or '/' in name or '\\' in name or not name.lower().endswith('.zip'):
            raise ValueError('Invalid ZIP name')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if not entries or sum(e.file_size for e in entries) > 256 * 1024 * 1024 or archive.testzip():
                raise ValueError('Invalid ZIP contents')
        with self.lock, self.db:
            # Retrying the same ZIP after a lost response does not consume another slot.
            if self.db.execute('SELECT 1 FROM archives WHERE id=?', (digest,)).fetchone():
                return digest
            if self.db.execute('SELECT COUNT(*) FROM archives').fetchone()[0] >= 20:
                raise OverflowError('Archive capacity reached')
            self.db.execute('INSERT INTO archives VALUES (?,?,?,?)',
                            (digest, name, datetime.now(timezone.utc).isoformat(), data))
        return digest

    def archives(self):
        with self.lock:
            return [dict(id=r[0], name=r[1], received_at=r[2], size=r[3]) for r in
                    self.db.execute('SELECT id,name,received,length(data) FROM archives ORDER BY rowid DESC')]

    def archive(self, digest):
        with self.lock:
            row = self.db.execute('SELECT name,data FROM archives WHERE id=?', (digest,)).fetchone()
            if row is None:
                raise KeyError(digest)
            return row[0], row[1]

    def delete_archive(self, digest):
        with self.lock, self.db:
            result = self.db.execute('DELETE FROM archives WHERE id=?', (digest,))
            return result.rowcount > 0

    def ingest(self, packet):
        run = packet['run_id']
        metadata = packet['metadata']
        samples = packet['samples']
        if not isinstance(run, str) or not 1 <= len(run) <= 128 or metadata['run_id'] != run:
            raise ValueError('Invalid run_id')
        ending = packet.get('end')
        rows = []
        for s in samples:
            if not isinstance(s['seq'], int) or s['seq'] < 0:
                raise ValueError('Invalid sequence')
            float(s['lux']); float(s['elapsed_ms']); int(s['sensor_timestamp_ns'])
            rows.append((run, s['seq'], json.dumps(s, ensure_ascii=False, allow_nan=False)))
        with self.lock, self.db:
            self.db.execute('INSERT INTO runs VALUES (?,?,NULL,?) ON CONFLICT(id) DO UPDATE SET received=excluded.received',
                            (run, json.dumps(metadata, ensure_ascii=False), datetime.now(timezone.utc).isoformat()))
            self.db.executemany('INSERT OR REPLACE INTO samples VALUES (?,?,?)', rows)
            if ending is not None:
                count = int(ending['sample_count'])
                if count < 0:
                    raise ValueError('Invalid sample count')
                # Final capture-window snapshot is authoritative, including the exclusive end boundary.
                self.db.execute('DELETE FROM samples WHERE run=? AND seq>=?', (run, count))
                self.db.execute('UPDATE runs SET ending=? WHERE id=?', (json.dumps(ending), run))

    def runs(self):
        with self.lock:
            return [dict(run_id=r[0], metadata=json.loads(r[1])) for r in
                    self.db.execute('SELECT id,metadata FROM runs ORDER BY rowid DESC')]

    def run(self, run):
        with self.lock:
            r = self.db.execute('SELECT metadata,ending,received FROM runs WHERE id=?', (run,)).fetchone()
            if r is None:
                raise KeyError(run)
            samples = [json.loads(s[0]) for s in self.db.execute(
                'SELECT data FROM samples WHERE run=? ORDER BY seq DESC LIMIT 1000', (run,))][::-1]
            count = self.db.execute('SELECT COUNT(*) FROM samples WHERE run=?', (run,)).fetchone()[0]
            return dict(metadata=json.loads(r[0]), end=json.loads(r[1]) if r[1] else None,
                        received_at=r[2], samples=samples, count=count)


def handler(store):
    class Handler(BaseHTTPRequestHandler):
        def respond(self, code, body, content_type='application/json; charset=utf-8', download_name='experiment.zip'):
            data = body if isinstance(body, bytes) else body.encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            if content_type == 'application/zip':
                from urllib.parse import quote
                self.send_header('Content-Disposition', "attachment; filename=experiment.zip; filename*=UTF-8''" + quote(download_name, safe=''))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            from urllib.parse import parse_qs
            url = urlparse(self.path)
            if url.path == '/':
                self.respond(200, PAGE, 'text/html; charset=utf-8')
            elif url.path == '/api/archives':
                self.respond(200, json.dumps(store.archives(), ensure_ascii=False))
            elif url.path == '/api/archive':
                try:
                    name, data = store.archive(parse_qs(url.query)['id'][0])
                    self.respond(200, data, 'application/zip', name)
                except (KeyError, IndexError):
                    self.respond(404, '{}')
            elif url.path == '/api/runs':
                self.respond(200, json.dumps(store.runs(), ensure_ascii=False))
            elif url.path == '/api/run':
                try:
                    self.respond(200, json.dumps(store.run(parse_qs(url.query)['id'][0]), ensure_ascii=False))
                except (KeyError, IndexError):
                    self.respond(404, '{}')
            else:
                self.respond(404, '{}')

        def do_DELETE(self):
            from urllib.parse import parse_qs
            url = urlparse(self.path)
            if url.path != '/api/archive':
                self.respond(404, '{}')
                return
            # Require a non-simple header; unrelated websites cannot issue this request via a form.
            if self.headers.get('X-ALS-Confirm') != 'delete':
                self.respond(403, '{"error":"Deletion confirmation required"}')
                return
            try:
                digest = parse_qs(url.query)['id'][0]
                if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                    raise ValueError('Invalid archive ID')
            except (ValueError, KeyError, IndexError):
                self.respond(400, '{"error":"Invalid archive ID"}')
                return
            if store.delete_archive(digest):
                self.respond(200, '{"ok":true}')
            else:
                self.respond(404, '{"error":"Archive not found"}')

        def do_POST(self):
            url = urlparse(self.path)
            if url.path == '/archives':
                from urllib.parse import parse_qs
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if not 0 < length <= 64 * 1024 * 1024:
                        raise ValueError('Invalid ZIP size')
                    data = self.rfile.read(length)
                    if len(data) != length:
                        raise ValueError('Incomplete upload')
                    name = parse_qs(url.query)['name'][0]
                    digest = store.save_archive(name, data)
                    self.respond(200, json.dumps(dict(ok=True, id=digest)))
                except OverflowError:
                    self.respond(409, '{"error":"ZIP capacity is 20; new upload refused"}')
                except (ValueError, KeyError, IndexError, zipfile.BadZipFile, RuntimeError):
                    self.respond(400, '{"error":"Invalid ZIP"}')
                return
            if self.path != '/ingest':
                self.respond(404, '{}')
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 1024 * 1024:
                    raise ValueError('Invalid payload size')
                store.ingest(json.loads(self.rfile.read(length)))
                self.respond(200, '{"ok":true}')
            except (ValueError, KeyError, TypeError, OverflowError):
                self.respond(400, '{"error":"Invalid packet"}')

        def log_message(self, *_):
            pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--output', type=Path, default=Path('lan_received'))
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), handler(Store(args.output)))
    print(f'노트북 화면: http://localhost:{args.port}', flush=True)
    print(f'태블릿 입력: http://<노트북 IPv4 주소>:{args.port}', flush=True)
    print(f'저장: {args.output.resolve() / "als_live.sqlite3"}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
