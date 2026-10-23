/*
 * 口型/动作参数「时间序列」探针 —— 给 l2d_shot_cdp.js 的 --eval-file 用。
 *
 * 为什么需要它：
 *   静态截图只能看到「某一帧长什么样」，而「连贯不连贯」是个**时间维度**的问题。
 *   一个参数是「每 60ms 跳一格」还是「每帧爬一点」，截图完全分辨不出来，
 *   必须把参数按帧采出来看数字。
 *
 * 它做两件事：
 *   ① 以 16.7Hz（= App 真实推送频率，60ms 一个台阶）怼一串方波进 setMouthShape，
 *      模拟 TTS 播放时的口型输入；
 *   ② 同时每帧（16ms）采一次 window.l2d.params()，最后统计：
 *        · 输入（target）每步跳多少   → 反映「原始输入有多台阶」
 *        · 输出（smooth/参数值）每帧跳多少 → 反映「抹平之后还剩多少台阶」
 *      `--eval` 的返回值会打到 stdout，直接读数字即可。
 *
 * 用法：
 *   node tools/l2d_shot_cdp.js --url "http://127.0.0.1:8777/index.html?model=hiyori_pro&fit=panel&span=0.62&hud=0&bg=studio&mock=0" \
 *        --eval-file tools/l2d_probe_trace.js --out .debug-shots/l2d/trace.png --w 420 --h 612 --port 9471
 *
 * ⚠️ 必须带 `mock=0`：`mock=1` 时口型由页面内置包络驱动，setMouthShape 传进去的值
 *    会被 speakEnv 盖住（applyMouth 里取的是 Math.max），量不到外部输入的平滑效果。
 */
(async function () {
  var STEP_MS = 60;      // App 的推送周期（16.7 次/秒）
  var SAMPLE_MS = 16;    // 采样周期（≈ 60fps 一帧）
  var DURATION = 1200;

  var samples = [];
  var t0 = performance.now();

  // ① 以真实推送频率怼方波：开合在 0.15 / 0.95 之间切换，圆扁在 -0.8 / +0.8 之间
  var step = 0;
  var pusher = setInterval(function () {
    var open = (step % 2 === 0) ? 0.15 : 0.95;
    var form = (Math.floor(step / 2) % 2 === 0) ? -0.8 : 0.8;
    window.l2d.setMouthShape(open, form);
    step++;
  }, STEP_MS);

  // ② 逐帧采样
  await new Promise(function (resolve) {
    var sampler = setInterval(function () {
      var p = window.l2d.params();
      samples.push({
        t: Math.round(p.t - t0),
        target: +p.mouth.target.toFixed(4),
        smooth: +p.mouth.smooth.toFixed(4),
        open: p.params.ParamMouthOpenY === null ? null : +p.params.ParamMouthOpenY.toFixed(4),
        form: p.params.ParamMouthForm === null ? null : +p.params.ParamMouthForm.toFixed(4),
        armL: p.params.ParamArmLA === null ? null : +p.params.ParamArmLA.toFixed(4),
        armR: p.params.ParamArmRA === null ? null : +p.params.ParamArmRA.toFixed(4),
      });
      if (performance.now() - t0 >= DURATION) {
        clearInterval(sampler);
        clearInterval(pusher);
        resolve();
      }
    }, SAMPLE_MS);
  });

  // ③ 统计「每步/每帧」的最大跳变量 —— 这就是「连贯」的量化指标
  function maxJump(key, mode) {
    var m = 0;
    for (var i = 1; i < samples.length; i++) {
      var a = samples[i - 1][key], b = samples[i][key];
      if (a === null || b === null) continue;
      m = Math.max(m, Math.abs(b - a));
    }
    return +m.toFixed(4);
  }

  var fps = window.l2d.params().fps;
  return JSON.stringify({
    // 输入口每步最大跳变（60ms 一步）→ 原始台阶有多大
    targetStep: maxJump('target'),
    // 输出口每帧最大跳变（16ms 一帧）→ 抹平后还剩多少
    smoothFrame: maxJump('smooth'),
    paramFrame: maxJump('open'),
    formFrame: maxJump('form'),
    armFrame: maxJump('armL'),
    frames: samples.length,
    spanMs: samples[samples.length - 1].t,
    fps: +fps.toFixed(1),
    range: window.l2d.params().range,
    head: samples.slice(0, 14),
  });
})();
