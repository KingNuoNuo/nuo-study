/*
 * 状态切换「过渡是否连续」探针 —— 给 l2d_shot_cdp.js 的 --eval-file 用。
 *
 * 为什么需要它：
 *   FORCE 优先级能让动作**立刻**切过去（不加就切不动，见 app.js 的 PRI_FORCE 注释），
 *   代价是切的那一帧姿势是**硬跳**的：上一边是待机的手臂位置，下一边是抬手的
 *   第一帧，中间没有过渡。截图看不出来（都是「切换后」的样子），
 *   必须把参数按帧采出来，看那一帧的跳变有多大。
 *
 * 做法：先切到 idle 稳定下来 → 1.5s 后切到 speak → 再按帧采 1s，
 *       统计切换前后每一帧的 ParamArmLA / ParamBodyAngleZ 跳变量。
 *
 * 用法：
 *   node tools/l2d_shot_cdp.js --url "...&mock=0" --eval-file tools/l2d_probe_switch.js \
 *        --out .debug-shots/l2d/switch.png --w 420 --h 612 --port 9481
 */
(async function () {
  var SAMPLE_MS = 16;
  var SETTLE_MS = 1500;
  var AFTER_MS = 1000;
  var SWITCH_AT = 1500;

  var samples = [];
  var t0 = performance.now();
  var switched = false;

  window.l2d.setState('idle');

  await new Promise(function (resolve) {
    var sampler = setInterval(function () {
      var now = performance.now() - t0;
      if (!switched && now >= SWITCH_AT) {
        window.l2d.setState('speak');
        switched = true;
      }
      var p = window.l2d.params();
      samples.push({
        t: Math.round(now),
        armL: p.params.ParamArmLA,
        armR: p.params.ParamArmRA,
        handL: p.params.ParamHandL,
        bodyZ: p.params.ParamBodyAngleZ,
        angleZ: p.params.ParamAngleZ,
        open: p.params.ParamMouthOpenY,
        form: p.params.ParamMouthForm,
      });
      if (now >= SWITCH_AT + AFTER_MS) { clearInterval(sampler); resolve(); }
    }, SAMPLE_MS);
  });

  // 每帧跳变量的最大者，以及它出现在哪一帧 —— 「硬跳」会表现为某单帧的巨峰
  function peak(key) {
    var best = { jump: 0, at: 0, from: 0, to: 0 };
    for (var i = 1; i < samples.length; i++) {
      var a = samples[i - 1][key], b = samples[i][key];
      if (a === null || b === null) continue;
      var d = Math.abs(b - a);
      if (d > best.jump) best = { jump: +d.toFixed(4), at: samples[i].t, from: +a.toFixed(3), to: +b.toFixed(3) };
    }
    return best;
  }
  // 切换前后的「平均每帧跳变」——软过渡时两者同量级，硬跳时切换点远大于平时
  function avgJump(key, lo, hi) {
    var s = 0, n = 0;
    for (var i = 1; i < samples.length; i++) {
      if (samples[i].t < lo || samples[i].t > hi) continue;
      var a = samples[i - 1][key], b = samples[i][key];
      if (a === null || b === null) continue;
      s += Math.abs(b - a); n++;
    }
    return n ? +(s / n).toFixed(4) : 0;
  }

  var keys = ['armL', 'armR', 'handL', 'bodyZ', 'angleZ'];
  var out = { switchAt: SWITCH_AT, frames: samples.length };
  keys.forEach(function (k) {
    out[k] = {
      peakJump: peak(k),
      avgBefore: avgJump(k, 500, SWITCH_AT - 50),
      avgAfter: avgJump(k, SWITCH_AT + 150, SWITCH_AT + AFTER_MS),
    };
  });
  // 切换点附近逐帧明细，直接看那一帧发生了什么
  out.aroundSwitch = samples.filter(function (s) {
    return s.t >= SWITCH_AT - 80 && s.t <= SWITCH_AT + 250;
  }).map(function (s) { return [s.t, r(s.armL), r(s.handL), r(s.bodyZ)]; });
  return JSON.stringify(out);

  function r(v) { return (typeof v === 'number' && isFinite(v)) ? +v.toFixed(2) : null; }
})();
