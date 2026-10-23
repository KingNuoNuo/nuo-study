# -*- coding: utf-8 -*-
"""grader 单元测试（纯逻辑，不碰网络也不碰数据库）。

跑法：python tools/test_grader.py
重点覆盖「算错但不报错」的情况 —— 这类 bug 比崩溃危险得多。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fractions import Fraction  # noqa: E402

from server import grader  # noqa: E402

FAILED = []


def case(label, got, want):
    ok = got == want
    if not ok:
        FAILED.append(label)
    print("  %s %-52s got=%r%s" % ("✓" if ok else "✗", label, got,
                                   "" if ok else "  want=%r" % (want,)))


def grade(stem, expected, given, item_type=1, answer_mode=1):
    return grader.grade(stem, expected, given, item_type, answer_mode)["is_correct"]


print("【1】分数与除号的歧义 —— 本模块最容易写错的地方")
# "1/2÷3/4" 若先把 ÷ 换成 / 会算成 1/24；正确答案是 2/3。
case("1/2÷3/4 答 2/3 → 对", grade("1/2÷3/4", [], "2/3"), True)
case("1/2÷3/4 答 1/24（左结合错解）→ 错", grade("1/2÷3/4", [], "1/24"), False)
case("1/2÷3/4 答 0.6667 → 对（容差）", grade("1/2÷3/4", [], "0.6667"), True)
case("表达式解析结果正确", grader.frac_str(grader.eval_expr(
    grader.stem_to_expr("1/2÷3/4"))), "0.666667")

print("\n【2】全角 / 单位 / 百分数")
case("全角 １２÷４", grade("１２÷４", [], "3"), True)
case("带单位作答 3米 → 3", grade("1+2", [], "3米"), True)
case("带单位且正确 12.56÷3.14 → 4", grade("12.56÷3.14", [], "4"), True)
case("小数点不当题号剥（12.56 不被啃成 56）",
     grader.stem_to_expr("12.56÷3.14"), "12.56/3.14")
case("题号确实要剥（2、1/2×4）", grader.stem_to_expr("2、1/2×4="), "(1/2)*4")
case("百分数 50% == 0.5", grader.num_of("50%") == Fraction(1, 2), True)
case("七十五 这类中文不做解析", grader.num_of("七十五"), None)

print("\n【2b】容差：写几位小数就容下末位的半个单位")
case("2/3 答 0.6667 → 对", grade("1/2÷3/4", [], "0.6667"), True)
case("2/3 答 0.67 → 对", grade("1/2÷3/4", [], "0.67"), True)
case("2/3 答 0.6 → 错", grade("1/2÷3/4", [], "0.6"), False)
case("2/3 答 0.7 → 错", grade("1/2÷3/4", [], "0.7"), False)
case("整数题不吃容差（4 答 5 → 错）", grade("2+2", [], "5"), False)

print("\n【3】题库标准答案（含多解 / 大小写 / 标点）")
case("题库多解：1/2 或 0.5", grade("1/2", ["1/2", "0.5"], "0.5"), True)
case("英文忽略大小写与句号", grade("What's this?", ["apple"], "Apple."), True)
case("英文答错", grade("What's this?", ["apple"], "banana"), False)
case("填空题多解命中其一",
     grade("填关联词", ["虽然", "尽管"], "尽管"), True)

print("\n【4】必须「拒绝判分」的情况（返回 None = 待批）")
case("应用题绝不抠算式", grade("小明有12个苹果，吃了3个，还剩几个？", [], "9"), None)
case("除零不做判定", grade("1÷0", [], "0"), None)
case("题干没有运算符不算算式", grade("计算", [], "5"), None)
case("主观题即使有答案也不自动判",
     grade("写一段话", ["参考答案"], "我写的一段", item_type=2), None)
case("客观题但无答案且推导不出",
     grade("把句子补充完整", [], "春天来了"), None)
case("空作答 → 待批而非判错", grade("1+1", [], ""), None)

print("\n【5】打卡项（answer_mode=0）不参与判分")
r = grader.grade("每天默读15分钟", [], "", 3, answer_mode=0)
case("打卡项 is_correct=None 且不 pending 于家长",
     (r["is_correct"], r["grading_mode"]), (None, grader.MODE_AUTO))

print("\n【6】算式求值的安全边界（不用 eval）")
for expr, label in [("__import__('os').system('echo hi')", "注入 import"),
                    ("open('x')", "文件访问"),
                    ("2**999", "幂次爆炸"),
                    ("1/0", "除零")]:
    try:
        grader.eval_expr(expr)
        case("拒绝执行 %s" % label, "未拦截", "已拦截")
    except Exception as e:
        case("拒绝执行 %s (%s)" % (label, type(e).__name__), "已拦截", "已拦截")

print("\n【7】判断题干的算式抽取")
for stem, want in [("1/2÷3/4=", "(1/2)/(3/4)"),
                   ("计算：3.14×5²", "3.14*5**2"),
                   ("2、1/2×4=", "(1/2)*4"),
                   ("小明有12个苹果", None),
                   ("1+1", "1+1")]:
    case("stem_to_expr(%r)" % stem, grader.stem_to_expr(stem), want)

print()
if FAILED:
    print("❌ %d 项失败：%s" % (len(FAILED), FAILED))
    sys.exit(1)
print("✅ grader 全部用例通过")
