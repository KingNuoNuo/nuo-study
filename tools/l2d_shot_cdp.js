/*
 * Live2D 页面「精确视口」截图 —— 用 CDP 而不是 chrome --screenshot。
 *
 * 为什么非要用 CDP（2026-10-10 实测踩过）：
 *   `chrome --headless=new --window-size=W,H --screenshot=x.png` 在 Windows 上
 *   **窗口宽度会被系统钳到最小 512**，高度也少 98px。于是：
 *       --window-size=480,450  →  页面真实视口 512x352（不是 480x450！）
 *   更阴的是它照样吐出一张 480x450 的 PNG（把视口缩放/裁剪进去），
 *   肉眼看不出问题，但按这张图量出来的构图常数**全是错的**。
 *   验证办法：给页面加 ?probe=1，用 --dump-dom 读 title 里的 canvas=WxH。
 *
 *   CDP 的 Emulation.setDeviceMetricsOverride 能给出**任意**精确视口，
 *   而且能等到 window.l2d.ready 之后再拍，不用靠「文件大小够大就当成功」猜。
 *
 * Node 22 自带 WebSocket/JSON，零依赖。
 *
 * 用法：
 *   node tools/l2d_shot_cdp.js --url "http://127.0.0.1:8777/index.html?...&fit=panel" \
 *        --out .debug-shots/l2d/x.png --w 300 --h 435
 *   node tools/l2d_shot_cdp.js ... --measure      # 顺带量人物 bbox 占比（需纯净背景）
 */
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : def;
}
const flag = (name) => process.argv.includes('--' + name);

const URL_ = arg('url');
const OUT = path.resolve(arg('out', 'shot.png'));
const W = parseInt(arg('w', '300'), 10);
const H = parseInt(arg('h', '435'), 10);
const PORT = parseInt(arg('port', '9333'), 10);
const WAIT_READY_MS = parseInt(arg('ready-ms', '15000'), 10);
// --eval "JS"：截图前在页面里执行一段 JS。
// 用例：定点播放某个动作（`window.l2d.playMotion('FlickUp',0)`）或切状态，
// 好把「抬手那一帧」拍下来 —— 动作是一次性的触发，不主动调就只能拍到待机姿势。
// 配 --after-eval-ms 决定「播下去之后等多久再拍」，也就是拍动作的第几秒。
//
// --eval-file <文件>：同上，但从文件读 JS。
// 为什么需要它：探针脚本动辄几十行、还带引号，塞进 shell 参数里转义会要命；
// 写成文件既好读又能进版本库（见 tools/l2d_probe_trace.js）。
// ⚠️ 与 --eval 二选一，同时给以 --eval-file 为准。
const EVAL_FILE = arg('eval-file', '');
const EVAL = EVAL_FILE
  ? fs.readFileSync(path.resolve(EVAL_FILE), 'utf-8')
  : arg('eval', '');
const AFTER_EVAL_MS = parseInt(arg('after-eval-ms', '500'), 10);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const r = await fetch(url);
  return r.json();
}

async function main() {
  if (!URL_) throw new Error('必须给 --url');
  fs.mkdirSync(path.dirname(OUT), { recursive: true });

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'l2dshot-'));
  const chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
    '--no-default-browser-check', '--disable-extensions',
    '--user-data-dir=' + profile,
    '--remote-debugging-port=' + PORT,
    'about:blank',
  ], { stdio: 'ignore' });

  const cleanup = () => { try { chrome.kill(); } catch (e) {} };
  process.on('exit', cleanup);

  // 等 CDP 端点起来
  let target = null;
  for (let i = 0; i < 60; i++) {
    await sleep(200);
    try {
      const list = await getJson(`http://127.0.0.1:${PORT}/json/list`);
      target = list.find((t) => t.type === 'page');
      if (target) break;
    } catch (e) { /* 还没起来 */ }
  }
  if (!target) throw new Error('CDP 没起来（端口 ' + PORT + '）');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let nextId = 1;
  const pending = new Map();
  const events = [];
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
    } else if (msg.method) {
      events.push(msg.method);
    }
  };
  const send = (method, params) => new Promise((res, rej) => {
    const id = nextId++;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params: params || {} }));
  });

  // 关键的一步：把视口设成「就是我们要的像素尺寸」。
  // deviceScaleFactor=1 → CSS px 与输出 px 一一对应，量出来的比例才好换算成 dp。
  await send('Emulation.setDeviceMetricsOverride', {
    width: W, height: H, deviceScaleFactor: 1, mobile: false,
  });
  await send('Page.enable');
  await send('Runtime.enable');

  const t0 = Date.now();
  await send('Page.navigate', { url: URL_ });

  // 等模型就绪：轮询 window.l2d.ready。比「按文件大小猜」靠谱得多。
  let ready = false;
  while (Date.now() - t0 < WAIT_READY_MS) {
    await sleep(150);
    const r = await send('Runtime.evaluate', {
      expression: '!!(window.l2d && window.l2d.ready)', returnByValue: true,
    });
    if (r.result && r.result.value === true) { ready = true; break; }
  }
  const probe = await send('Runtime.evaluate', {
    expression: "document.title + ' | canvas=' + (document.getElementById('stage')||{}).width + 'x' + (document.getElementById('stage')||{}).height",
    returnByValue: true,
  });

  // 再等几帧，让待机动作/物理摆动走到一个稳定的姿势（否则两次拍照姿势不同，
  // 量出来的 bbox 会差十几像素，看着像构图错了）
  await sleep(parseInt(arg('settle-ms', '600'), 10));

  // --eval：姿势稳了之后再触发动作，这样拍到的姿势差异只来自这个动作本身
  if (EVAL) {
    const ev = await send('Runtime.evaluate', {
      expression: EVAL, returnByValue: true, awaitPromise: true,
    });
    if (ev.exceptionDetails) {
      console.log('  !! --eval 抛错: ' + JSON.stringify(ev.exceptionDetails));
    } else {
      console.log('  --eval -> ' + JSON.stringify(ev.result && ev.result.value));
    }
    await sleep(AFTER_EVAL_MS);
  }

  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'));

  const size = fs.statSync(OUT).size;
  console.log(`拍 ${URL_}`);
  console.log(`  -> ${OUT}  ${W}x${H}  ${(size / 1024).toFixed(0)} KB  ready=${ready}  ${Date.now() - t0}ms`);
  if (probe.result && probe.result.value) console.log('  页面报告: ' + probe.result.value);

  // 把 probe 里的变换参数落一个 sidecar，量取脚本据此把「画面像素」反推成
  // 「包围盒内的比例」—— 这才是模型常数（PERSON_CX/TOP/H/W）该有的单位。
  const m = /PROBE bw=([\d.]+) bh=([\d.]+) bx=([\d.-]+) by=([\d.-]+) canvas=(\d+)x(\d+) fit=(\w+) s=([\d.]+) pos=([\d.-]+),([\d.-]+)/
    .exec(probe.result && probe.result.value || '');
  if (m) {
    fs.writeFileSync(OUT + '.json', JSON.stringify({
      bw: +m[1], bh: +m[2], bx: +m[3], by: +m[4], w: +m[5], h: +m[6],
      fit: m[7], s: +m[8], posX: +m[9], posY: +m[10],
    }, null, 2));
  }

  if (flag('measure')) measure(OUT);
  if (!ready) console.log('  ⚠️ 模型没就绪就拍了，画面可能是空的');

  ws.close();
  cleanup();
}

/** 量非背景像素的包围盒（背景取四角中位色）。要求页面用**纯色**底（?bg=flat） */
function measure(file, tol) {
  // 用 Python/PIL 量更省事（Node 里没有图像库），这里直接转调
  const { execFileSync } = require('child_process');
  const py = arg('python', 'C:/Users/yuhao/.workbuddy/binaries/python/envs/default/Scripts/python.exe');
  try {
    const out = execFileSync(py, [path.join(__dirname, 'l2d_measure.py'), file,
      '--tol', String(tol || 18)], { encoding: 'utf-8' });
    process.stdout.write(out);
  } catch (e) {
    console.log('  量取失败: ' + (e.stdout || e.message));
  }
}

main().catch((e) => { console.error('失败: ' + e.message); process.exit(1); });
