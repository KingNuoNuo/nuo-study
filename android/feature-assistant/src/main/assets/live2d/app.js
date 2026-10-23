/* Live2D 虚拟形象 PoC（多模型：Haru / 桃濑日和 PRO / 桃濑日和 FREE）
 *
 * 目的：验证三件事，并按 App 里将来要用的形态先定好 JS 接口。
 *   1. 模型（moc3 + 2048 贴图）在目标设备上能不能流畅跑；
 *   2. 「跟着说话动嘴」怎么接 —— 口型参数 ParamMouthOpenY 由外部逐帧推入；
 *   3. 动作能不能按状态机（待机/倾听/思考/说话…）切换。
 *
 * 切模型：?model=hiyori_pro | hiyori_free | haru （默认 hiyori_pro = 桃濑日和 PRO）
 *
 * ⚠️ 口型为什么要用 afterMotionUpdate 钩子：
 *    motion 每帧都会把关键帧值写进 core 的参数数组，如果在 ticker 里直接改参数，
 *    会被同帧或下一帧的 motion 覆盖，出现「嘴刚张开就被抹平」。
 *    afterMotionUpdate 正好在 motion 写完之后、coreModel.update() 计算变形之前，
 *    在这里覆盖才是有效的（这也是 pixi-live2d-display 官方推荐的注入点）。
 */
(function () {
  'use strict';

  var P = new URLSearchParams(location.search);
  if (P.get('bg') === 'transparent') document.body.classList.add('transparent');
  if (P.get('bg') === 'studio') document.body.classList.add('studio');
  var SHOW_HUD = P.get('hud') !== '0';
  if (!SHOW_HUD) document.getElementById('hud').style.display = 'none';

  // ⚠️ 内置「模拟说话」包络（三条正弦叠加）只是 PoC 的验货工具。
  //    接进 App 后口型由 TTS 音频的 RMS 逐帧推入（l2d.setMouth），
  //    此时**必须**把包络关掉，否则 setState('speak') 会同时打开假包络，
  //    两个来源取 max 的结果是「嘴一直在动、跟真人说话对不上」。
  //    App 用 ?mock=0 关它。
  var MOCK = P.get('mock') !== '0';

  var errBox = document.getElementById('err');
  /**
   * 向宿主（Android WebView）报事件。
   * 'ready' = 模型可用了；'error' = 起不来（宿主据此退回到静态头像）。
   * ⚠️ 宿主没注入 L2DBridge 时（纯浏览器打开）这里全程静默 —— 不能因为桥不存在就抛错。
   */
  function notify(name) {
    try { if (window.L2DBridge && L2DBridge.onEvent) L2DBridge.onEvent(name); } catch (e) {}
  }
  function fail(msg) {
    errBox.textContent += msg + '\n';
    console.error(msg);
    notify('error');
  }

  if (!window.PIXI || !PIXI.live2d || !window.Live2DCubismCore) {
    fail('依赖缺失: PIXI=' + !!window.PIXI + ' live2d=' + !!(window.PIXI && PIXI.live2d) +
         ' core=' + !!window.Live2DCubismCore);
    return;
  }

  // ⚠️ Cubism 的渲染器**只支持 WebGL**，没有 WebGL 的 WebView（部分国产 ROM 的
  //    WebView 关掉了硬件加速、或开了「无图模式」）会一路跑到空白画布，
  //    看起来跟「模型没加载出来」一模一样。提前判掉，让宿主早点退到静态头像。
  if (PIXI.utils && PIXI.utils.isWebGLSupported && !PIXI.utils.isWebGLSupported()) {
    fail('WebGL 不可用');
    return;
  }

  var Live2DModel = PIXI.live2d.Live2DModel;
  Live2DModel.registerTicker(PIXI.Ticker);   // 不注册模型不会自动更新

  var canvas = document.getElementById('stage');
  // ---------------------------------------------------------------- 渲染开销
  //
  // 平板（rockchip / Android 11）实测页面帧率：**空闲 ~50fps，说话时只有 30~38fps**。
  // 帧率不够时动作会「一顿一顿」，而这跟动作参数无关 —— 再怎么调动作也顺不了。
  //
  // 两个可调开关（URL 参数，方便真机上做对照，不用改代码）：
  //   ?res=1   像素比（默认 min(devicePixelRatio, 2)）。
  //            ⚠️ 这个影响比想象的大：Live2D 的**裁剪遮罩是按画布尺寸渲染**的，
  //            每个 drawable 的 mask 都要一张画布大小的纹理，所以画布像素数
  //            是**乘在每次绘制上**的，把 res 从 1.5 降到 1 能省掉一半以上的遮蔽绘制。
  //   ?aa=0    关抗锯齿（MSAA）。弱 GPU 上省一点，代价是边缘略毛。
  var RES = P.get('res');
  var AA = P.get('aa') !== '0';
  var RES_NUM = RES ? Math.max(0.5, Math.min(3, parseFloat(RES) || 1))
                    : Math.min(window.devicePixelRatio || 1, 2);

  // ?phys=N  物理每 N 帧算一次（默认 1 = 每帧；0 = 完全关掉）
  //
  // 为什么要这个开关：平板实测说话时页面只有 30~40fps，而把画布像素砍到 1/2.25
  // **一点没变**（?res=1 对照过）→ 瓶颈不在填充率，在 CPU 侧的逐帧计算。
  // Cubism 每帧要算物理（头发/外套的弹簧链）和变形，这是弱机型上最典型的 CPU 大头。
  // 物理本身是**慢变量**（头发摆动周期几百毫秒），降到 1/2 或 1/3 频率几乎看不出来，
  // 但省下的 CPU 是实打实的。降频时会把累积的 dt 补上去，保证模拟速度不变。
  var PHYS_EVERY = parseInt(P.get('phys') || '1', 10);
  if (!(PHYS_EVERY >= 0)) PHYS_EVERY = 1;

  var app = new PIXI.Application({
    view: canvas,
    autoStart: true,
    backgroundAlpha: 0,                      // 透明：面板底色由 App 提供
    antialias: AA,
    resolution: RES_NUM,
    autoDensity: true,
    resizeTo: window,
  });

  var model = null;
  var core = null;
  var LIP_ID = 'ParamMouthOpenY';
  var lipMin = 0, lipMax = 1;
  // 口型「圆扁」——不是所有模型都有（Haru 就没有）。有就用来做语速起伏，
  // 只有开合一个维度的话，说话会像在打哈欠。
  var FORM_ID = 'ParamMouthForm';
  var formMin = -1, formMax = 1, hasForm = false;

  // 口型值（0~1）。两条来源：外部推入（App）/ 本地滑杆 + 模拟说话（验证用）
  var mouthExternal = 0;
  /**
   * 口型「圆扁」的外部值（-1 ~ 1），null = App 没推过。
   * App 用 spectral 分析算出真实元音后连同开口一起推来（`setMouthShape`），
   * 有它就用它；null 时退回内置慢波（见 applyMouth）。
   *
   * ⚠️ 用 null 而不是 0 当「没有」：0 是合法值（「啊」附近），拿 0 当哨兵会在
   *    「嘴张着但元音正好在中位」时错判成「没推过」。
   */
  var mouthFormExternal = null;
  /**
   * 逐帧平滑后的口型（0~1）。**不直接用外部推来的原值** ——
   * App 的推送频率是 16.7 次/秒（跟 TTS 帧率走，60ms 一个台阶），
   * 直接写进参数的话嘴是「每 60ms 跳一格」，观感就是「动作不连贯」。
   * 这里做一阶低通（指数平滑），页面跑多少帧就更新多少次，把台阶抹成连续曲线。
   */
  var mouthOpenSmooth = 0;
  /** 同上，圆扁一路（元音变化比开合慢，时间常数给大一点） */
  var mouthFormSmooth = null;
  /**
   * 平滑时间常数（秒）。**攻快放慢**——真实说话的嘴就是这个不对称节奏：
   * 张嘴 30ms 要跟得上音节起音，闭嘴 75ms 才不会一卡一卡；
   * 两边都给慢会糊成一团，两边都给快等于没平滑。
   */
  var TAU_ATTACK = 0.030, TAU_RELEASE = 0.075, TAU_FORM = 0.110;
  /**
   * 平滑器是否已「接管」口型（一句话的开始/结束）。
   *
   * 为什么需要这个状态：接管的第一帧如果不做特殊处理，平滑器会从 0（或 ft）
   * 起步去追目标，于是**说话那一瞬间嘴会「啪」地跳一下**。实测 `ParamMouthForm`
   * 这样一帧能跳 2.7（该参数全长才 3），肉眼就是明显的抽动。
   * 正解是第一帧**从当前真实参数值起步**，之后再平滑 —— 这样接管是无缝的。
   */
  var mouthEngaged = false;

  /** 诊断计数：App 到底推没推过来。接上后 logcat 里一条都不出 = 问题在 Kotlin 侧 */
  var mouthRx = 0;
  var speakOn = false, speakT = 0, speakEnv = 0;
  /** 音节时钟：给口型「圆扁」用的慢波，跟 MOCK 开关无关，一直走 */
  var speakClock = 0;

  // ---------------------------------------------------------------- 渲染循环

  app.ticker.add(function () {
    speakClock += app.ticker.deltaMS / 1000;
    if (MOCK && speakOn) {
      speakT += app.ticker.deltaMS / 1000;
      // 三条正弦叠加 + 快慢两层，模拟中语句子的开口起伏（比单一正弦像得多）
      var slow = 0.5 + 0.5 * Math.sin(speakT * 5.1);
      var fast = 0.5 + 0.5 * Math.sin(speakT * 27.3);
      var mid = 0.5 + 0.5 * Math.sin(speakT * 11.5);
      speakEnv = Math.max(0, Math.min(1, (0.45 + 0.55 * slow) * (0.62 + 0.38 * mid) * (0.72 + 0.28 * fast)));
    } else {
      speakEnv = 0;
    }
    bumpFps();
  });

  /** 把「参数原值」归一化到 0~1（用于接管时从当前值起步） */
  function normParam(id, lo, hi) {
    try {
      var v = core.getParameterValueById(id);
      var span = (hi - lo) || 1;
      return Math.max(0, Math.min(1, (v - lo) / span));
    } catch (e) { return 0; }
  }

  function applyMouth() {
    if (!core) return;
    // 一阶低通（指数平滑）。系数由 dt 现算而不是写死的百分比 ——
    // 这样设备跑 30fps 还是 60fps，「手感」的时间常数都一样（写死百分比会随帧率漂）。
    var dt = Math.min(app.ticker.deltaMS / 1000, 0.1);
    var target = Math.max(mouthExternal, MOCK ? speakEnv : 0);

    // 接管的第一帧：平滑器从**当前真实参数值**起步（见 mouthEngaged 注释）
    if (target > 0.02 && !mouthEngaged) {
      mouthOpenSmooth = normParam(LIP_ID, lipMin, lipMax);
      if (hasForm) mouthFormSmooth = normParam(FORM_ID, formMin, formMax);
      mouthEngaged = true;
    }

    var tau = target > mouthOpenSmooth ? TAU_ATTACK : TAU_RELEASE;
    mouthOpenSmooth += (target - mouthOpenSmooth) * (1 - Math.exp(-dt / tau));
    // 闭嘴（且已基本收完）时**不写参数**，交给 motion/物理 —— 硬写 0 会把
    // 动作自带的嘴形也一起压掉，反而更假。同时把接管权交还，下一句重新起步。
    if (mouthOpenSmooth < 0.004) { mouthOpenSmooth = 0; mouthEngaged = false; return; }
    try {
      core.setParameterValueById(LIP_ID, lipMin + (lipMax - lipMin) * mouthOpenSmooth);
      // 张口越大，口型越「圆」；中间再叠一层慢波，读音节之间才有变化。
      // 没有这个参数的模型（Haru）hasForm=false，整段跳过。
      if (hasForm) {
        // 两路来源：
        //   · App 推来的（spectral 分析出的真实元音）→ **优先**，这就是「口型对得上」的关键
        //   · 没有外部来源（PoC 手玩 / mock）→ 退回内置慢波，至少不至于像在打哈欠
        var ft;
        if (mouthFormExternal === null) {
          var slur = Math.sin(speakClock * 9.3) * 0.5;      // 慢速换音
          ft = 0.55 * mouthOpenSmooth + 0.45 * (0.5 + 0.5 * slur);
        } else {
          ft = (Math.max(-1, Math.min(1, mouthFormExternal)) + 1) * 0.5;   // -1~1 → 0~1
        }
        // 圆扁也要平滑：F2 换算出来的 form 是**离散元音**，逐帧跳会看到嘴在「闪」
        if (mouthFormSmooth === null) mouthFormSmooth = ft;
        mouthFormSmooth += (ft - mouthFormSmooth) * (1 - Math.exp(-dt / TAU_FORM));
        core.setParameterValueById(FORM_ID, formMin + (formMax - formMin) * mouthFormSmooth);
      }
    } catch (e) { /* 参数不存在时静默 */ }
  }

  // ---------------------------------------------------------------- FPS

  var fpsEl = document.getElementById('fps');
  var frames = 0, fpsT = performance.now();
  /** 5 秒窗口内的帧数（fpsEl 只在 ?hud=1 时才存在，量帧率不能依赖它） */
  var fpsWinFrames = 0, fpsLogT = performance.now();
  /** 最近一次测得的帧率（给 l2d.params() 读，便于脚本里直接取用） */
  var lastFps = 0;
  function bumpFps() {
    frames++;
    fpsWinFrames++;
    var now = performance.now();
    if (now - fpsT >= 1000) {
      if (fpsEl) fpsEl.textContent = frames + ' fps';
      frames = 0; fpsT = now;
    }
    // 每 5 秒把**页面真实渲染帧率**打进控制台 —— 在 App 里就是 logcat 的 chromium CONSOLE 行。
    // 「动画不连贯」是主观描述，得先变成数字：页面要是只有 25fps，那再怎么调动作参数也顺不了，
    // 该动的是渲染开销（分辨率/物理/抗锯齿），而不是动作本身。
    // ⚠️ 别用「数 setMouthShape 的次数」估帧率：那是 App 的 16.7Hz 推送频率，跟渲染帧率无关。
    if (now - fpsLogT >= 5000) {
      lastFps = fpsWinFrames / ((now - fpsLogT) / 1000);
      // 一起报 dpr 和画布实际像素：调 ?res= 时必须知道「省下的到底是多少像素」，
      // 只看 fps 数字没法判断是画质档位起作用了还是设备碰巧空闲。
      console.log('[l2d] fps=' + lastFps.toFixed(1) +
                  ' dpr=' + (window.devicePixelRatio || 1) +
                  ' canvas=' + canvas.width + 'x' + canvas.height +
                  ' res=' + RES_NUM + ' aa=' + (AA ? 1 : 0));
      fpsWinFrames = 0; fpsLogT = now;
    }
  }

  // ---------------------------------------------------------------- 布局
  //
  // 构图模式（用 ?fit= 切换，默认 full）：
  //   full     全身铺满（默认）—— 整具人物缩放到**高度填满容器**，多出的横向留白居中。
  //            这是「全屏显示」：人物上到头顶、下到脚尖都在画面里，尽量占满。
  //   close    半身特写 —— 放大到只留头肩胸，下半身自然出画（塞进 App 那个 320dp 面板时用）。
  //   contain  整体收入 —— 整具人物完整可见并留一圈边距，留白最多（想看清全身轮廓时用）。
  //
  // ⚠️ 关键：**不能用 moc 的包围盒当人的轮廓**。包围盒比人大一圈（左右给手臂/头发摆动
  //    留了空白），直接拿 b.height 去铺满，出来只是根小柱子。
  //    下面每个模型的 person 四常数都是**像素实测**出来的：`?fit=raw&still=1` 把整张
  //    包围盒等比放进画面 → 截图 → PIL 扫描非背景像素量出人物 bbox → 反推比例。
  //    标准流程见 tools/l2d_shot_cdp.js + tools/l2d_measure.py（后者会直接把常数打印出来）。
  //      · h   人物高 / 包围盒高
  //      · top 头顶在包围盒顶边往下多少（占包围盒高）
  //      · cx  人物水平中心（占包围盒宽）
  //      · w   人物宽 / 包围盒宽（仅用于估算占屏比）
  //
  //    ⚠️⚠️ 这组数**在 2026-10-10 重测过一次**，原因值得记：原来是用
  //    `chrome --headless --window-size=W,H --screenshot` 量的，而那个口子在 Windows 上
  //    会把窗口宽度**钳到最小 512**、高度少 98px —— 页面真实视口根本不是你要的尺寸，
  //    但它照样吐一张 W×H 的 PNG 出来，肉眼看不出毛病。于是量出来的 cx 偏了 0.017
  //    （0.5122 vs 真值 0.4950），表现是人物在窄容器里明显偏一边。
  //    现在改成 CDP 的 Emulation.setDeviceMetricsOverride，视口是多少就是多少。
  //    Haru 那组不受影响（重测后几乎相同），因为它当初是用 fit=raw 直接量的、没经过缩放。
  var MODELS = {
    haru: {
      path: 'Haru/Haru.model3.json',
      person: { h: 0.8737, top: 0.0522, cx: 0.5044, w: 0.3561 },
      // 状态 → 动作/表情。expression 名按 Haru 的 model3.json（F01…F08）。
      states: {
        idle:   { group: 'Idle',    index: 0, expr: 'F01', speaking: false },
        listen: { group: 'Idle',    index: 1, expr: 'F02', speaking: false },
        think:  { group: 'TapBody', index: 2, expr: 'F05', speaking: false },
        speak:  { group: 'TapBody', index: 0, expr: 'F01', speaking: true  },
        happy:  { group: 'TapBody', index: 3, expr: 'F03', speaking: false },
        sad:    { group: 'Idle',    index: 0, expr: 'F06', speaking: false },
      },
      // 戳一下随机播的「有反应」动作（[组名, 索引]）
      tapMotions: [['TapBody', 0], ['TapBody', 1], ['TapBody', 2], ['TapBody', 3]],
    },
    hiyori_pro: {
      path: 'HiyoriPro/hiyori_pro_t11.model3.json',
      person: { h: 0.8980, top: 0.0734, cx: 0.4950, w: 0.2833 },
      // ⚠️ 日和**没有表情**（zip 里没有 .exp3.json），所以 expr 全为 null。
      //    能用的动作组：Idle[0..2] / Flick[0] / FlickDown[0] / FlickUp[0] /
      //                  Tap[0..1] / Tap@Body[0] / Flick@Body[0]
      states: {
        idle:   { group: 'Idle',      index: 0, expr: null, speaking: false },
        listen: { group: 'Idle',      index: 1, expr: null, speaking: false },
        think:  { group: 'FlickDown', index: 0, expr: null, speaking: false },
        // speak 用 FlickUp（而不是原来的 Idle[2]）：这是**手上动作最明显**的一组，
        // 双手抬到胸前，说话时有比划感。实测手臂参数幅度（各曲线 max-min 之和）：
        // FlickUp 81 > Idle[2] 53 > Idle[1] 50 > Flick 43 > Tap[1] 59…（见 .debug-shots/l2d/motion_compare2.png）
        speak:  { group: 'FlickUp',   index: 0, expr: null, speaking: true  },
        // happy 从 FlickUp[0] 换成 Tap[1]（2.1s「拍拍」）：跟 speak 撞车了 ——
        // 两者原本都指 FlickUp[0]，状态切换时看不出区别。Tap[1] 幅度 59、独立一只手拍，区分得开。
        happy:  { group: 'Tap',       index: 1, expr: null, speaking: false },
        sad:    { group: 'Flick',     index: 0, expr: null, speaking: false },
      },
      tapMotions: [['Tap', 0], ['Tap', 1], ['Tap@Body', 0],
                   ['Flick', 0], ['Flick@Body', 0], ['FlickUp', 0]],
    },
    hiyori_free: {
      path: 'HiyoriFree/hiyori_free_t08.model3.json',
      person: { h: 0.8980, top: 0.0734, cx: 0.5067, w: 0.3133 },
      // FREE 版少一个 FlickUp 组（happy 改用 Flick）
      states: {
        idle:   { group: 'Idle',      index: 0, expr: null, speaking: false },
        listen: { group: 'Idle',      index: 1, expr: null, speaking: false },
        think:  { group: 'FlickDown', index: 0, expr: null, speaking: false },
        speak:  { group: 'Idle',      index: 2, expr: null, speaking: true  },
        happy:  { group: 'Flick',     index: 0, expr: null, speaking: false },
        sad:    { group: 'FlickDown', index: 0, expr: null, speaking: false },
      },
      // FREE 版没有 FlickUp 组
      tapMotions: [['Tap', 0], ['Tap', 1], ['Tap@Body', 0],
                   ['Flick', 0], ['Flick@Body', 0]],
    },
  };

  var MODEL_KEY = P.get('model') || 'hiyori_pro';
  var MODEL = MODELS[MODEL_KEY] || MODELS.hiyori_pro;
  if (!MODELS[MODEL_KEY]) {
    console.warn('未知模型 "' + MODEL_KEY + '"，回退到 hiyori_pro');
    MODEL_KEY = 'hiyori_pro'; MODEL = MODELS.hiyori_pro;
  }
  var PERSON_H = MODEL.person.h;
  var PERSON_TOP = MODEL.person.top;
  var PERSON_CX = MODEL.person.cx;
  var PERSON_W = MODEL.person.w;

  var FIT = P.get('fit') || 'full';
  // 动作优先级（pixi-live2d-display 的 MotionPriority）：1=IDLE，2=NORMAL(库默认)，3=FORCE。
  // ⚠️⚠️ 切动作**必须**显式给 FORCE。原因：**正在循环播放的 Idle 动作会把同优先级（NORMAL）
  //    的切换请求吃掉** —— `model.motion()` 返回成功、日志没有报错、画面却一帧都不动。
  //    这个坑极难自查：实测「FlickUp vs Idle2」两张截图的像素差只有 **0.11%**（等于同一张图），
  //    改成 FORCE 之后同一对比立刻变成 **14~18%**。全程没有任何异常可看，只能靠截图比对。
  //    受影响的调用点共三处：setState / 点击彩蛋 / window.l2d.playMotion。
  var PRI_FORCE = 3;
  var CLOSE_ZOOM = 2.5;   // close 模式的放大倍数
  // panel 模式的两个旋钮（都用 URL 传，方便拍网格图对比）：
  //   span  可见部分占「人物总高」的比例。0.38 ≈ 头顶到胸口（视频通话那种半身像）
  //   heady 头顶落在容器顶边往下多少（占容器高）
  //   dx    人物整体再往左挪多少（占容器高，见下方 PANEL_DX 的说明）
  // ⚠️ 这三个数的**单位都是比例**，不含像素 —— 所以容器换成什么尺寸/宽高比，
  //    取到的仍是同一截身体，不会像 close 模式那样随高度倍数漂移。
  // ⚠️ 0.38 是**像素实测**选出来的（在 220×320 的画面上量 bbox）：
  //      0.46 → 人物只占画面宽 71.8%，两侧各留 30px，看着像张没裁好的照片
  //      0.42 → 79.5%  |  0.38 → 87.3%  |  0.34 → 97.7%（贴边，头发顶到框，压抑）
  //    靖哥哥说「人物没有全屏显示」，就是 0.46 那版留白太多。
  var PANEL_SPAN = parseFloat(P.get('span') || '0.38');
  var PANEL_HEAD_Y = parseFloat(P.get('heady') || '0.04');
  // 横向微调（占容器高的比例）。默认 0：PERSON_CX 重测之后人物本来就正了。
  // ⚠️ 一开始这里塞了个 0.042 的「残差修正」去补人物偏右 —— 那是**误诊**：
  //    真正的原因是当初那批截图用的是 `chrome --screenshot`，视口被系统钳过，
  //    量出来的 PERSON_CX 本身就偏了 0.017。把常数改对之后这个补丁自然归零。
  //    教训：构图偏了先怀疑**测量工具**，别急着加补偿系数 —— 补偿系数会掩盖工具问题，
  //    换个容器尺寸又崩（当时窄容器里人物直接被推出画面）。
  var PANEL_DX = parseFloat(P.get('dx') || '0');
  var PROBE = P.get('probe') === '1';   // 调试：把量到的包围盒/变换写进 #status，供 --dump-dom 读取
  // 量构图常数时用：不播动作，让姿势彻底静止（见加载完成处的说明）
  var STILL = P.get('still') === '1';

  function layout() {
    if (!model) return;
    var w = app.renderer.width / app.renderer.resolution;
    var h = app.renderer.height / app.renderer.resolution;
    model.scale.set(1);
    model.position.set(0, 0);
    // ⚠️ 不能读 model.width/height：PIXI 的 bounds 是惰性缓存的，
    //    刚 set scale 后立刻读会拿到上一帧的旧值（踩过：算出来的位置整个偏掉）。
    //    getBounds() 才是当场重算的真实渲染包围盒。
    var b = model.getBounds();
    if (!b.width || !b.height) return;

    var s;
    if (FIT === 'close') {
      // 半身特写：按包围盒高铺满再放大，顶边锚在容器顶。
      // ⚠️ 它的放大是「包围盒高的倍数」，换个容器尺寸取到的身体就变了 ——
      //    只适合 480x450 这种调试尺寸。App 里请用 panel。
      s = (h / b.height) * CLOSE_ZOOM;
      model.scale.set(s);
      model.position.set(w / 2 - b.width * s * PERSON_CX, h * PERSON_TOP);
    } else if (FIT === 'panel') {
      // 面板取景（App 用）：只取人物的上面一截（默认头顶到腰）铺满容器高度。
      // 逻辑上是把 close 的「倍数」换成了「要看到人物总高的百分之多少」——
      // 单位是比例、不含像素，所以容器尺寸/宽高比怎么变，取到的都是同一截身体。
      s = h / (b.height * PERSON_H * PANEL_SPAN);
      model.scale.set(s);
      model.position.set(
        w / 2 - b.width * s * PERSON_CX - PANEL_DX * h,
        h * PANEL_HEAD_Y - b.height * s * PERSON_TOP
      );
    } else if (FIT === 'contain') {
      // 整体收入：整具人物完整可见，四周留一圈边距。
      var contain = Math.min(w / b.width, h / (b.height * PERSON_H)) * 0.94;
      model.scale.set(contain);
      model.position.set(
        w / 2 - b.width * contain * PERSON_CX,
        (h - b.height * contain) / 2
      );
    } else if (FIT === 'raw') {
      // 调试专用：把整张 moc 包围盒按比例完整放进容器（人物会偏小、带大量空白）。
      // 用途：截一张图后用像素量出「人物在包围盒里的真实位置」，反推下面的 PERSON_* 常数。
      s = Math.min(w / b.width, h / b.height);
      model.scale.set(s);
      model.position.set((w - b.width * s) / 2, (h - b.height * s) / 2);
    } else {
      // full：整具人物高度 = 容器高（横向超出就居中裁掉两侧，留白居中）。
      s = h / (b.height * PERSON_H);
      model.scale.set(s);
      model.position.set(
        w / 2 - b.width * s * PERSON_CX,
        -b.height * s * PERSON_TOP
      );
    }

    if (PROBE) {
      // 写到 title：layout() 在 #status 赋值之前跑，写 status 会被覆盖
      document.title = 'PROBE bw=' + b.width.toFixed(1) + ' bh=' + b.height.toFixed(1) +
        ' bx=' + b.x.toFixed(1) + ' by=' + b.y.toFixed(1) +
        ' canvas=' + w + 'x' + h + ' fit=' + FIT +
        ' s=' + s.toFixed(4) +
        ' pos=' + model.x.toFixed(1) + ',' + model.y.toFixed(1);
    }
  }
  window.addEventListener('resize', layout);
  window.addEventListener('orientationchange', function () { setTimeout(layout, 200); });

  // ---------------------------------------------------------------- 加载模型

  var nameEl = document.getElementById('modelName');
  if (nameEl) nameEl.textContent = MODEL_KEY;
  document.getElementById('status').textContent = 'loading…';

  Live2DModel.from(MODEL.path, {
    autoInteract: false,
    autoUpdate: true,
  }).then(function (m) {
    model = m;
    core = m.internalModel.coreModel;

    // 关掉内置口型：默认它只在 model.speak() 播音频时驱动，这里我们全程自己推
    try { m.internalModel.motionManager.lipSync = false; } catch (e) {}

    m.internalModel.on('afterMotionUpdate', applyMouth);

    // 物理降频（见 PHYS_EVERY 的注释）。
    // 库里的调用点是 `null==(r=this.physics)||r.evaluate(o,t)` —— **空安全**，
    // 所以「把 this.physics 临时置 null」就是干净的跳过方式，不用改库。
    if (PHYS_EVERY !== 1) {
      var origUpdate = m.internalModel.update.bind(m.internalModel);
      var physAcc = 0, physN = 0;
      m.internalModel.update = function (dt, now) {
        var real = this.physics;
        if (!real || PHYS_EVERY === 0) {
          this.physics = null;
          try { origUpdate(dt, now); } finally { this.physics = real; }
          return;
        }
        physN++;
        if (physN % PHYS_EVERY !== 0) {
          this.physics = null;                       // 这一帧不跑物理
          try { origUpdate(dt, now); } finally { this.physics = real; }
        } else {
          // 跑物理的帧：把攒下来的 dt 一次性喂进去，保证弹簧的时间轴不走样
          physAcc += dt;
          var acc = physAcc;
          physAcc = 0;
          this.physics = { evaluate: function (model) { real.evaluate(model, acc); } };
          try { origUpdate(dt, now); } finally { this.physics = real; }
        }
      };
    }

    // 读出口型参数的合法区间（不同模型 0~1 或 0~30 不等，别硬编码）
    try {
      lipMin = core.getParameterMinimumValue(
        core.getParameterIndex(LIP_ID)) || 0;
      lipMax = core.getParameterMaximumValue(
        core.getParameterIndex(LIP_ID)) || 1;
    } catch (e) { lipMin = 0; lipMax = 1; }

    // 口型圆扁参数：Haru 没有，日和 PRO/FREE 有。有就启用（见 applyMouth）。
    try {
      var fi = core.getParameterIndex(FORM_ID);
      if (fi >= 0) {
        formMin = core.getParameterMinimumValue(fi);
        formMax = core.getParameterMaximumValue(fi);
        hasForm = true;
      }
    } catch (e) { hasForm = false; }

    app.stage.addChild(m);
    layout();

    // 自动眨眼由库自己跑；先播一次待机
    // ?still=1 时**不播任何动作**：量构图常数必须让姿势完全静止，
    // 否则物理摆动每帧都在动，两次拍照量出来的 bbox 差十几像素，
    // 看着像「构图算错了」，其实只是拍在了动作的不同帧上。
    if (!STILL) {
      try { m.motion('Idle', 0); } catch (e) { fail('待机动作播放失败: ' + e.message); }
    }

    document.getElementById('status').textContent =
      'ready · 口型 ' + LIP_ID + ' [' + lipMin + ',' + lipMax + ']' +
      (hasForm ? ' + 圆扁[' + formMin + ',' + formMax + ']' : '（无圆扁参数）');
    buildHud(m);
    // 点一下人物 → 随机播一个「被戳到」的动作。孩子端的小彩蛋：
    // 不说话的时候戳她也有反应，别让画面看起来是张会呼吸的贴纸。
    canvas.addEventListener('click', function () {
      if (!model) return;
      var pool = MODEL.tapMotions || [['Tap', 0]];
      var pick = pool[Math.floor(Math.random() * pool.length)];
      try { model.motion(pick[0], pick[1], PRI_FORCE); } catch (e) {}
    });
    window.l2d.ready = true;
    notify('ready');
    document.dispatchEvent(new Event('l2d-ready'));
  }).catch(function (e) {
    document.getElementById('status').textContent = '加载失败';
    fail('模型加载失败: ' + (e && e.message ? e.message : e));
  });

  // ---------------------------------------------------------------- 调试 HUD

  function buildHud(m) {
    if (!SHOW_HUD) return;

    var slider = document.getElementById('mouth');
    var sliderV = document.getElementById('mouthv');
    slider.addEventListener('input', function () {
      mouthExternal = slider.value / 100;
      sliderV.textContent = ' ' + mouthExternal.toFixed(2);
    });

    var btnSpeak = document.getElementById('btnSpeak');
    btnSpeak.addEventListener('click', function () {
      speakOn = !speakOn;
      btnSpeak.classList.toggle('on', speakOn);
    });
    document.getElementById('btnStop').addEventListener('click', function () {
      speakOn = false; btnSpeak.classList.remove('on');
      mouthExternal = 0; slider.value = 0; sliderV.textContent = ' 0.00';
    });

    var motionDefs = model_motionDefs(m);
    var host = document.getElementById('motions');
    Object.keys(motionDefs).forEach(function (group) {
      motionDefs[group].forEach(function (_, i) {
        addBtn(host, group + '[' + i + ']', function () {
          try { m.motion(group, i); } catch (e) { fail('motion ' + group + '[' + i + ']: ' + e.message); }
        });
      });
    });

    var expHost = document.getElementById('expressions');
    var exps = model_expressionNames(m);
    if (!exps.length) {
      // ⚠️ 日和（PRO/FREE）的 zip 里**没有 .exp3.json**，这个模型根本没有表情。
      //    不能退回到假名列表去点（点了必报错），直接把按钮区隐藏。
      var expWrap = document.getElementById('expWrap');
      if (expWrap) expWrap.style.display = 'none';
    }
    exps.forEach(function (name) {
      addBtn(expHost, name, function () {
        try { m.expression(name); } catch (e) { fail('expression ' + name + ': ' + e.message); }
      });
    });

    var stHost = document.getElementById('states');
    Object.keys(window.l2d.STATES).forEach(function (k) {
      addBtn(stHost, k, function () { window.l2d.setState(k); });
    });
  }

  function addBtn(host, label, fn) {
    var b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', fn);
    host.appendChild(b);
  }

  function model_motionDefs(m) {
    var out = {};
    try {
      var groups = m.internalModel.settings.motions;   // Map<group, Motion[]>
      if (groups && typeof groups.forEach === 'function') {
        groups.forEach(function (list, key) { out[key] = list; });
      } else if (groups) {
        Object.keys(groups).forEach(function (k) { out[k] = groups[k]; });
      }
    } catch (e) { fail('读动作列表失败: ' + e.message); }
    if (!Object.keys(out).length) out = { Idle: [0], TapBody: [0, 1, 2, 3] };
    return out;
  }

  function model_expressionNames(m) {
    var names = [];
    try {
      var list = m.internalModel.settings.expressions || [];
      names = list.map(function (e, i) { return e.Name || e.name || String(i); });
    } catch (e) {}
    // ⚠️ 没有表情就返回空数组 —— 之前这里硬塞 ['F01'..] 假名，点了必然报错
    //    （日和没有 .exp3.json）。调用方负责隐藏按钮区。
    return names;
  }

  // ---------------------------------------------------------------- 对外接口
  //
  // Kotlin 侧将来只需要这两个方向：
  //   · App → 页面：evaluateJavascript("window.l2d.setMouth(0.42)")
  //   · 页面 → App：window.L2DBridge && L2DBridge.onEvent('ready')
  // 每帧推口型的开销很低（一次 JS 调用），比走 addJavascriptInterface 轮询更省。

  // 状态 → 动作（+表情，若该模型有）。按模型注册表里的定义取，别写死成 Haru 的。
  var STATES = MODEL.states;

  window.l2d = {
    ready: false,
    model: MODEL_KEY,
    STATES: STATES,

    /**
     * **App 的口型入口**：开合 + 圆扁一起推。
     *
     * `open` 0~1（TTS 播放电平 RMS），`form` -1~1（元音圆扁，由 spectral 分析出的 F2 换算）。
     * 两个分量必须在**同一次调用**里传 —— 分两次推的话，JS ticker 会在两条之间插进来
     * 渲染一帧，那一帧用的是「新开合 + 旧圆扁」，嘴形是错的（见 Live2DAvatar.kt 同处注释）。
     *
     * ⚠️ 这个函数名是 Kotlin 侧 `Live2DAvatar` 写死的调用点。曾经因为页面里只有
     *    `setMouth` 而没有它，**每帧 push 都抛 `not a function` 被吞掉**，
     *    表现是「在说话但嘴一动不动」——而 logcat 里 Kotlin 侧打印一切正常，
     *    极易误判成渲染问题。改名前先全仓 grep `setMouthShape`。
     */
    setMouthShape: function (open, form) {
      var v = Number(open);
      if (isFinite(v)) mouthExternal = Math.max(0, Math.min(1, v));
      var f = Number(form);
      if (isFinite(f)) mouthFormExternal = Math.max(-1, Math.min(1, f));
      // 诊断：只报前 3 次和每 60 次一条（正常 16.7 次/秒，全报会刷屏）。
      // App 接上后如果 logcat 里一条都不出，问题就在 Kotlin → WebView 那一段
      // （桥没通 / 没就绪），跟渲染侧无关 —— 这条日志就是为了省掉来回猜。
      if (mouthRx < 3 || mouthRx % 60 === 0) {
        console.log('[l2d] setMouthShape #' + mouthRx + ' open=' + mouthExternal.toFixed(3) +
                    ' form=' + (mouthFormExternal === null ? 'n/a' : mouthFormExternal.toFixed(3)));
      }
      mouthRx++;
    },

    /** 只推开合（老接口，PoC 滑杆用）。App 请用 setMouthShape。 */
    setMouth: function (v) {
      v = Number(v);
      if (!isFinite(v)) return;
      mouthExternal = Math.max(0, Math.min(1, v));
      if (mouthRx < 3 || mouthRx % 60 === 0) {
        console.log('[l2d] setMouth #' + mouthRx + ' = ' + mouthExternal.toFixed(3));
      }
      mouthRx++;
    },

    /** 播动作：[组名, 组内索引]。
     *  ⚠️ priority 省略时用库默认（NORMAL=2）。**正在循环的 idle 动作会把同优先级的请求吃掉**
     *  —— 现象是 playMotion 返回 true、但画面一帧都没变（实测跨动作像素差只有 0.11%）。
     *  要强制切换得传 3（FORCE）。 */
    playMotion: function (group, index, priority) {
      if (!model) return false;
      try {
        if (priority) model.motion(group, index || 0, priority);
        else model.motion(group, index || 0);
        return true;
      }
      catch (e) { fail('motion ' + group + ': ' + e.message); return false; }
    },

    /** 切表情 */
    setExpression: function (name) {
      if (!model) return false;
      try { model.expression(name); return true; }
      catch (e) { fail('expression ' + name + ': ' + e.message); return false; }
    },

    /** 高层：按会话状态切换（idle/listen/think/speak/happy/sad） */
    setState: function (name) {
      var s = STATES[name];
      if (!s || !model) return false;
      try {
        if (s.expr) model.expression(s.expr);   // 无表情的模型（日和）expr 为 null，跳过
        model.motion(s.group, s.index, PRI_FORCE);   // FORCE：否则被循环的 idle 吃掉，见 PRI_FORCE 注释
      } catch (e) { fail('setState ' + name + ': ' + e.message); }
      // ?mock=0（App）时**不碰** speakOn —— 口型完全由 App 推的 RMS 决定，
      // 打开假包络会出现「没在说话嘴也在动」。
      if (MOCK) speakOn = !!s.speaking; else speakOn = false;
      mouthExternal = 0;                        // 换状态先闭嘴，避免上一句的张口值卡住
      if (window.L2DBridge && L2DBridge.onEvent) {
        try { L2DBridge.onEvent('state:' + name); } catch (e) {}
      }
      return true;
    },

    /** 验证期用：把内置「模拟说话」开关独立出来 */
    setMockSpeak: function (on) { speakOn = !!on; },

    fps: function () { return fpsEl ? fpsEl.textContent : ''; },

    /**
     * 诊断：读回关键参数的**当前实际值** + 口型内部状态。
     *
     * 用途是查「动作跳变 / 不连贯」这类问题：外面看是一回事，参数怎么变的才是证据。
     * 典型用法（配合 `l2d_shot_cdp.js --eval`，它 awaitPromise）：
     *   每 16ms 采一次，形成时间序列，就能看出参数是「连续爬升」还是「一帧跳到位」。
     *
     * 注意 `mouth.smooth` 是平滑后的真实输出，`mouth.target` 是 App 推来的原值 ——
     * 两者差多少，就是平滑起了多大作用。
     */
    params: function () {
      var ids = ['ParamMouthOpenY', 'ParamMouthForm', 'ParamArmLA', 'ParamArmRA',
                 'ParamBodyAngleZ', 'ParamAngleZ'];
      var p = {};
      ids.forEach(function (id) {
        try {
          // ⚠️ 参数不存在时 core 返回的是 **undefined 而不是抛错**（也可能返回 0），
          //    统一归一成 null，否则下游拿 undefined 去算会炸（踩过）。
          var v = core ? core.getParameterValueById(id) : null;
          p[id] = (typeof v === 'number' && isFinite(v)) ? v : null;
        } catch (e) { p[id] = null; }
      });
      var motion = 'unknown';
      try {
        var mm = model && model.internalModel && model.internalModel.motionManager;
        motion = mm && mm.currentMotion ? 'playing' : 'none';
      } catch (e) { motion = 'err'; }
      return {
        t: performance.now(),
        params: p,
        mouth: { target: mouthExternal, smooth: mouthOpenSmooth,
                 formTarget: mouthFormExternal, formSmooth: mouthFormSmooth },
        // 参数的合法区间也一并报回来：日和 cdi3 里**没写** Min/Max，
        // 真实区间是从 moc3 里读的，光看 json 会猜错（ParamMouthForm 实测不是 -1~1）。
        range: { hasForm: hasForm, lipMin: lipMin, lipMax: lipMax,
                 formMin: formMin, formMax: formMax },
        motion: motion,
        // 当前渲染设置也要报回来：对比两次帧率时必须知道画质档位变了没有，
        // 否则「这次快了」到底是调了画质还是碰巧设备空闲，说不清。
        render: { res: RES_NUM, aa: AA },
        fps: lastFps,
      };
    },
  };
})();
