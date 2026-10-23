# -*- coding: utf-8 -*-
"""作答判定（批改）。⚠️【已暂缓 · 未接入】M2 整体搁置，本模块不被任何路由引用。

保留原因：判定策略已论证清楚、单测 31 项全绿，恢复成本几乎为零。
恢复方式：在 server/api.py 末尾把作答端点接回来（见 git 历史）。
现状：识别产出的作业项默认 question_id 为空 → 没有标准答案 → 自动批改覆盖率天然很低。

⚠️ 诚实的前提：**assignment_item 上没有标准答案字段**，答案只存在 question_answer 里。
   而识别出来的作业项默认 question_id 为空 —— 没有题库就没有标准答案，也就无从自动批改。
   所以判定分三级，对应 answer_record.grading_mode：

     1 自动     —— 有标准答案（题库），或题干本身是纯算式可以直接算出来
     2 模型初评  —— 主观题（作文 / 阅读 / 跟读），留待 M4 接模型
     3 家长确认  —— 以上都不满足，一律置 NULL 等家长看一眼

⚠️ 原则：**绝不猜**。宁可返回「待批」也不给一个错的判定 ——
   错判会让孩子把错答案当成对的记住，比不批改糟糕得多。

这个原则直接决定了本模块的形状：能自动判的路径非常窄（客观题 + 有答案），
其余全部老实返回 pending，由家长确认流兜底。
"""
from __future__ import annotations

import ast
import re
from fractions import Fraction

# 全角 → 半角（数字 / 运算符 / 括号 / 标点）
_FULL = str.maketrans(
    "０１２３４５６７８９．＋－×÷＝（）：，％",
    "0123456789.+-*/=():,%")
_SUP = {"²": "**2", "³": "**3"}
# 题干里常见的指令性前缀。判定「整条题干是不是纯算式」前先剥掉。
_INSTRUCTION = ("直接写出得数", "用竖式计算", "脱式计算", "简便计算", "列式计算",
                "口算", "计算", "求值", "化简", "解方程", "比一比")
# 数值比较容差。见 _tol_for：按作答书写精度算，但不放宽于 _MAX_TOL。
_TOL = 1e-6
# 容差上限 0.005 = 两位小数的半个末位。含义是「作答至少要写到两位小数」。
# 没有这个上限，只写一位小数（0.7）会拿到 0.05 的容差，把 2/3 的错答 0.7 判成对。
_MAX_TOL = Fraction(1, 200)

MODE_AUTO, MODE_MODEL, MODE_PARENT = 1, 2, 3


# ---------------------------------------------------------------- 归一化
def norm_text(s) -> str:
    """字符串比较用归一：全角转半角、去空白、去首尾标点、英文小写。"""
    if s is None:
        return ""
    t = str(s).strip().translate(_FULL)
    t = re.sub(r"\s+", "", t)
    t = t.strip("。.，,；;！!？?、\"'“”‘’")
    return t.lower()


def norm_expr(s: str) -> str:
    """算式归一：× ÷ ² 统一成 Python 可解析的形式。"""
    t = str(s or "").translate(_FULL)
    for k, v in _SUP.items():
        t = t.replace(k, v)
    t = t.replace("÷", "/").replace("×", "*").replace("－", "-").replace("−", "-")
    return re.sub(r"\s+", "", t)


def _paren_fractions(s: str) -> str:
    """把 `数字/数字` 当成一个分数原子加括号，避免与除号歧义。

    ⚠️ 这是本模块最容易写错的地方：
       "1/2÷3/4" 若先把 ÷ 换成 / 会得到 "1/2/3/4"，按左结合算成 1/24；
       而正确答案是 (1/2)÷(3/4) = 2/3。
       必须**先在原始串上**把分数括起来 → "(1/2)÷(3/4)" → 再换除号。
    """
    return re.sub(r"(?<![\d.])(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)(?![\d.])",
                  r"(\1/\2)", s)


# ---------------------------------------------------------------- 数值
def num_of(s) -> Fraction | None:
    """尽力把一个作答串解析成数值；解析不出来返回 None（交给字符串比对）。"""
    if s is None:
        return None
    t = norm_text(s)
    if not t:
        return None
    m = re.fullmatch(r"(-?[\d.]+)%", t)
    if m:                                       # 百分数：50% → 1/2
        try:
            return Fraction(m.group(1)) / 100
        except (ValueError, ZeroDivisionError):
            return None
    m = re.fullmatch(r"-?\d+(?:\.\d+)?(?:/\d+(?:\.\d+)?)?", t)
    if m:                                       # 纯数 / 分数 / 小数
        try:
            return Fraction(t)
        except (ValueError, ZeroDivisionError):
            pass
    # 带单位：「3米」「12.56平方厘米」「约0.5时」→ 剥掉非数字字符再试
    m = re.fullmatch(r"-?\d+(?:\.\d+)?", re.sub(r"[^\d.\-]", "", t))
    if m:
        try:
            return Fraction(m.group(0))
        except (ValueError, ZeroDivisionError):
            return None
    return None


def _close(a: Fraction, b: Fraction, tol: Fraction | None = None) -> bool:
    """数值比较。tol 由 **作答的书写精度** 决定，见 _tol_for。"""
    d = abs(a - b)
    if d == 0:
        return True
    t = tol if tol is not None else Fraction(_TOL)
    return d <= max(t, t * abs(b))


def _tol_for(s) -> Fraction:
    """按作答的书写精度决定容差。

    ⚠️ 这条不能省：孩子把 2/3 写成 0.6667 是在**做四舍五入**，
       固定 1e-6 的容差会把「答对了但保留小数」判成错 —— 这是最伤信任的一类误判。
       写几位小数，就容下末位那半个单位的误差；但不放宽于 _MAX_TOL，
       否则「只写一位小数」会拿到过大容差，把真正的错答放行。
    """
    t = norm_text(s)
    m = re.fullmatch(r"-?\d+\.(\d+)", t)
    if m:
        return min(Fraction(5, 10 ** (len(m.group(1)) + 1)), _MAX_TOL)
    return Fraction(1, 10 ** 6)


# ---------------------------------------------------------------- 算式求值
def stem_to_expr(stem: str) -> str | None:
    """题干本身就是一个纯算式时返回可求值表达式，否则 None。

    刻意保守：只认「整条题干就是算式」的情况（如 "1/2÷3/4="、"计算：3.14×5²"）。
    应用题（"小明有12个苹果，吃了3个，还剩几个"）**绝不抠算式** ——
    抠错比不抠更糟：孩子答对了却被判错，是最伤信任的 bug。
    """
    s = str(stem or "").strip()
    for p in _INSTRUCTION:
        s = s.replace(p, "")
    # 剥掉题号 "1."  "2、"  "3)"。
    # ⚠️ 小数点后跟数字时**不能**当题号 —— 否则 "12.56÷3.14" 会被啃成 "56÷3.14"，
    #    于是明明答对的题被判错。（这个 bug 单测抓到过。）
    s = re.sub(r"^\s*\d+\s*(?:[、)）]|\.(?!\d))\s*", "", s)
    s = s.strip().strip("：:")
    s = re.sub(r"[\s　]+", "", s)
    s = s.rstrip("=＝?？。. ")
    if not s:
        return None
    s = _paren_fractions(s)
    s = norm_expr(s)
    if not re.fullmatch(r"[0-9.()+\-*/]+", s.replace("**", "")):
        return None
    if not re.search(r"[+\-*/]", s):                 # 必须含运算符，否则不是算式
        return None
    return s


def _ev(n):
    """在 Fraction 上解释 AST。只放行四则运算与幂 —— 绝不用 eval()。"""
    if isinstance(n, ast.Constant):
        if isinstance(n.value, (int, float)) and not isinstance(n.value, bool):
            return Fraction(n.value)
        raise ValueError("非法常量: %r" % (n.value,))
    if isinstance(n, ast.BinOp):
        a, b = _ev(n.left), _ev(n.right)
        if isinstance(n.op, ast.Add):
            return a + b
        if isinstance(n.op, ast.Sub):
            return a - b
        if isinstance(n.op, ast.Mult):
            return a * b
        if isinstance(n.op, ast.Div):
            if b == 0:
                raise ValueError("除数为零")
            return a / b
        if isinstance(n.op, ast.Pow):
            if b.denominator != 1 or abs(b) > 8:
                raise ValueError("幂次非法")
            return a ** int(b)
        raise ValueError("不支持的运算符")
    if isinstance(n, ast.UnaryOp):
        v = _ev(n.operand)
        return -v if isinstance(n.op, ast.USub) else v
    raise ValueError("不支持的表达式节点")


def eval_expr(expr: str) -> Fraction:
    return _ev(ast.parse(expr, mode="eval").body)


def frac_str(v: Fraction) -> str:
    """Fraction → 人类可读：能整除出小数就给小数，否则给 "a/b"。"""
    if v.denominator == 1:
        return str(v.numerator)
    f = float(v)
    return "%.6g" % f


# ---------------------------------------------------------------- 判定
def _res(correct, mode: int, correct_answer=None, note: str | None = None) -> dict:
    d = {"is_correct": correct, "grading_mode": mode,
         "pending": correct is None, "correct_answer": correct_answer,
         "score": None if correct is None else (1.0 if correct else 0.0)}
    if note:
        d["note"] = note
    return d


def grade(stem: str, expected: list[str], given: str, item_type: int,
          answer_mode: int = 1) -> dict:
    """判定一次作答。

    expected 是题库里的标准答案列表（可能多解，命中任一即对）；
    为空表示这道题没有标准答案 —— 那就只剩「题干可推导」这条路。

    is_correct=None 的语义是**待批**，不是错。
    """
    given = "" if given is None else str(given)

    # ① 无需作答的打卡项（如「每天默读15分钟」）：不判分，只登记完成
    if answer_mode == 0:
        return _res(None, MODE_AUTO, note="打卡项，不判分")

    if not norm_text(given):
        return _res(None, MODE_PARENT, note="空作答，等待家长/补交")

    tol = _tol_for(given)

    # ② 只有客观题才自动判。主观题即使有参考答案也可能是多解，不冒险。
    if item_type == 1:
        if expected:
            g = num_of(given)
            for e in expected:
                en = num_of(e)
                if en is not None and g is not None:
                    if _close(g, en, tol):
                        return _res(True, MODE_AUTO, correct_answer=e)
                elif norm_text(e) == norm_text(given):
                    return _res(True, MODE_AUTO, correct_answer=e)
            # 有标准答案却对不上 → 判错，并把正确答案回给孩子
            return _res(False, MODE_AUTO, correct_answer=expected[0])

        # 没有题库答案，试试从题干本身推导（纯算式题，占比不小）
        expr = stem_to_expr(stem)
        if expr:
            try:
                val = eval_expr(expr)
            except (ValueError, SyntaxError, ZeroDivisionError):
                val = None
            if val is not None:
                g = num_of(given)
                if g is not None:
                    return _res(_close(g, val, tol), MODE_AUTO,
                                correct_answer=frac_str(val))
                return _res(None, MODE_PARENT,
                            note="题干可算出答案，但作答不是数字，无法自动比对")

        # 客观题但没答案也推导不出 → 转家长确认（M4 会先试模型初评）
        return _res(None, MODE_PARENT, note="缺标准答案，需家长确认或补录题库")

    # ③ 主观题 / 打卡 / 实践：一律待批
    return _res(None, MODE_MODEL if item_type == 2 else MODE_PARENT,
                note="主观题，待模型初评或家长确认")
