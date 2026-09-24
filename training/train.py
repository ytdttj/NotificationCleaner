#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NotificationCleaner 广告通知分类器 —— PC 端预训练脚本（参考 Notice 仓库方案重构）
================================================================================

方案（与 github.com/Night-stars-1/Notice 的 ml/ 管线同源）:
  公共语料(80万中文垃圾短信 + UCI 英文 SMS) 预训练
  + 真实通知 CSV 高权重叠加训练（消去理由自动打标）
  → int8 量化导出 model.bin（NSPM 格式, ~256KB）+ parity.json + metadata.json + report.txt

特征化规范（必须与 APP 端 Kotlin FeatureHasher 严格一致，唯一规范见 Plan.md §5.2）:
  1. 归一化:  转小写 -> 删除所有空白字符、十进制数字(Nd)和字母 'x'
     （去伪特征：公共短信语料的正常样本无空格、垃圾样本把数字/人名掩码为 x，
       保留它们模型会学到伪特征而非词语本身）
  2. 分词:    对 UTF-16 代码单元取字符 n-gram（n = 1..3），跨词连续取
  3. 哈希:    每个 n-gram 的 UTF-16-LE 字节做 FNV-1a 32bit -> index = hash & (2^18 - 1)
  4. 特征值:  n-gram 出现次数，整条文本特征向量做 L2 归一化
  5. 模型:    p(ad | text) = sigmoid(w·x + b)，权重 int8 量化存储

用法:
  python train.py --extra "通知滤盒_通知历史_9月12日_10_58.csv"           # 完整训练
  python train.py --extra real.csv --limit 50000                        # 冒烟测试
  python train.py --extra real.csv --no-sms                             # 只用真实数据
  python train.py --extra real.csv --no-extra-train                     # 公共语料基线对照
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import struct
import sys
import time
import unicodedata
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy.sparse import csr_matrix, vstack
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import normalize as l2_normalize

# ========================= 特征化常量（与 Kotlin 端一致） =========================
BUCKETS = 1 << 18  # 262144
NGRAM_MIN, NGRAM_MAX = 1, 3
MAX_TEXT_LEN = 500  # codepoint 截断
MAX_DUP_WEIGHT = 10.0  # 重复文本的样本权重上限

# 与 Notice SpamFeatures.SPACE_CHARS 相同的空白字符集
SPACE_CHARS = frozenset(
    "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f \x85\xa0\u1680"
    "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a"
    "\u2028\u2029\u202f\u205f\u3000"
)
FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193

# ========================= 模型格式（NSPM v2，大端，int16 权重） =========================
# v2（1.0.7）：权重由 int8 升级为 int16 —— 端上在线学习会增大权重幅值，
# int8 每次落盘需以新 scale 重量化全部权重，粒度粗（~0.02）会逐渐抹平学习效果。
MAGIC = b"NSPM"
FORMAT_VERSION = 2

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
# 中文垃圾短信（hrwhisper/SpamMessage，约 80 万条，TSV: label\ttext）
ZH_DATA_URL = (
    "https://raw.githubusercontent.com/hrwhisper/SpamMessage/master/data/"
    "%E5%B8%A6%E6%A0%87%E7%AD%BE%E7%9F%AD%E4%BF%A1.txt"
)
ZH_DATA_PATH = DATA_DIR / "labeled_sms.txt"
# 英文 SMS Spam Collection（UCI），补充拉丁字母覆盖
EN_DATA_URL = (
    "https://raw.githubusercontent.com/mohitgupta-omg/"
    "Kaggle-SMS-Spam-Collection-Dataset-/master/spam.csv"
)
EN_DATA_PATH = DATA_DIR / "spam_en.csv"

# 双端一致性校验文本（Kotlin 单测逐一比对 score）
PARITY_TEXTS = [
    "",
    "你好",
    "您的验证码是 483920，5分钟内有效，请勿泄露。",
    "【XX商城】双11狂欢！全场1折起，点击 http://t.cn/abc 领取888元红包，回复TD退订",
    "妈妈说周末回家吃饭",
    "恭喜您被抽中为幸运用户，加微信 abc123 领取iPhone 15 Pro Max",
    "Your package has been delivered to the front desk.",
    "低息贷款  无抵押　当天放款 详询 138-0000-0000",
    "会议改到下午三点，地点不变",
    "😀🎉 限时特惠！！！",
    "A",
    "等等党又赢了！925马力+CCD悬挂+浩瀚H7，补贴8.8万带劲不？",
    "【你有即将获得的徽章】电音曲风LV3获取进度已超过90%，快来升级吧",
]

# ========================= 标签归一化（显式标签列时使用） =========================
LABEL_POS = {"1", "ad", "ads", "spam", "promo", "推广", "广告", "广告通知", "垃圾广告", "垃圾短信"}
LABEL_NEG = {"0", "normal", "ok", "ham", "notad", "not ad", "普通", "正常", "正常通知", "非广告"}


# ========================= 特征化（Python 参考实现，与 Notice 一致） =========================


def normalize_text(text: str) -> str:
    """小写 -> 删除空白/数字(Nd)/'x'/标点与符号(P*/S*)。与 Notice ml/features.py 同思路，
    并额外删除标点符号：短信域中'，''【】'等与垃圾强相关，但在通知域正常通知同样密集，
    保留会造成 domain shift 误杀（实测'，'单字特征贡献 +3.5~7.5 分）。"""
    text = text[:MAX_TEXT_LEN]
    return "".join(
        ch
        for ch in text.lower()
        if ch not in SPACE_CHARS
        and ch != "x"
        and unicodedata.category(ch) != "Nd"
        and unicodedata.category(ch)[0] not in ("P", "S")
    )


def fnv1a32(data: bytes) -> int:
    h = FNV_OFFSET
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & 0xFFFFFFFF
    return h


def feature_buckets(text: str, ngram_min=NGRAM_MIN, ngram_max=NGRAM_MAX, buckets=BUCKETS) -> dict:
    """UTF-16-LE 字节流的跨词 n-gram 哈希计数。"""
    units = normalize_text(text).encode("utf-16-le", "surrogatepass")
    n_units = len(units) // 2
    mask = buckets - 1
    counts: dict = {}
    for n in range(ngram_min, ngram_max + 1):
        for start in range(0, n_units - n + 1):
            chunk = units[start * 2 : (start + n) * 2]
            k = fnv1a32(chunk) & mask
            counts[k] = counts.get(k, 0) + 1
    return counts


def vectorise(texts: list) -> csr_matrix:
    indptr = [0]
    indices: list = []
    data: list = []
    t0 = time.time()
    for i, t in enumerate(texts):
        b = feature_buckets(t)
        indices.extend(b.keys())
        data.extend(b.values())
        indptr.append(len(indices))
        if i and i % 100000 == 0:
            print(f"  vectorised {i} ({time.time() - t0:.0f}s)", file=sys.stderr)
    x = csr_matrix(
        (np.asarray(data, dtype=np.float32), np.asarray(indices), np.asarray(indptr)),
        shape=(len(texts), BUCKETS),
    )
    return l2_normalize(x, norm="l2", copy=False)


def sigmoid(z: float) -> float:
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def score_text(text: str, w_deq: np.ndarray, bias: float) -> float:
    """参考打分器（= APP 端 Kotlin 实际计算结果，量化权重反量化后计算）。"""
    counts = feature_buckets(text)
    if not counts:
        return sigmoid(bias)
    norm = math.sqrt(sum(c * c for c in counts.values()))
    z = float(np.float32(bias))
    for k, c in counts.items():
        z += float(w_deq[k]) * (c / norm)
    return sigmoid(z)


# ========================= 公共语料下载与加载 =========================


def download() -> None:
    for url, path in ((ZH_DATA_URL, ZH_DATA_PATH), (EN_DATA_URL, EN_DATA_PATH)):
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"[下载] {url}", file=sys.stderr)
        urllib.request.urlretrieve(url, path)


def load_sms() -> tuple:
    texts, labels = [], []
    with open(ZH_DATA_PATH, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or "\t" not in line:
                continue
            label, text = line.split("\t", 1)
            if label not in ("0", "1"):
                continue
            texts.append(text)
            labels.append(int(label))
    zh = len(texts)
    with open(EN_DATA_PATH, encoding="latin-1", newline="") as f:
        for row in csv.reader(f):
            if len(row) < 2 or row[0] not in ("ham", "spam"):
                continue
            texts.append(row[1])
            labels.append(1 if row[0] == "spam" else 0)
    print(f"[语料] 中文短信 {zh} 条, 英文短信 {len(texts) - zh} 条", file=sys.stderr)
    return texts, np.asarray(labels, dtype=np.int8)


# ========================= 真实通知 CSV（消去理由自动打标） =========================


def normalize_label(raw: str):
    """标签值 -> 1(广告)/0(正常)/None(未知)。"""
    v = (raw or "").strip().lower()
    if v in LABEL_POS:
        return 1
    if v in LABEL_NEG:
        return 0
    try:
        return 1 if float(v) > 0.5 else 0
    except ValueError:
        return None


def resolve_column(header, requested, candidates, fuzzy=True):
    """列解析。fuzzy=False 时只接受精确匹配（含大小写/空白归一后的相等）。

    1.4.0 Dev 13：text_col（全文列）必须走精确匹配——候选词"通知"会模糊命中
    "通知标题"，导致训练只取标题、完全丢弃正文（真实数据的正文特征从未参与训练，
    是"银行动账通知被判广告"的重要诱因之一）。
    """
    if requested:
        if requested not in header:
            raise SystemExit(f"[错误] 指定列 {requested!r} 不在表头 {header} 中")
        return requested
    for cand in candidates:
        for h in header:
            if h.strip().lower() == cand.lower():
                return h
    if not fuzzy:
        return None
    for cand in candidates:
        for h in header:
            if cand.lower() in h.lower():
                return h
    return None


def read_csv_rows(path: Path):
    raw = path.read_bytes()
    text = None
    for enc in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise SystemExit(f"[错误] 无法识别文件编码: {path}")
    delim = ","
    try:
        dialect = csv.Sniffer().sniff(text[:65536], delimiters=",\t;|")
        delim = dialect.delimiter
    except csv.Error:
        if text[:65536].count("\t") > text[:65536].count(","):
            delim = "\t"
    reader = csv.DictReader(text.splitlines(), delimiter=delim)
    if not reader.fieldnames:
        raise SystemExit(f"[错误] CSV 无表头行: {path}")
    return [h.strip() for h in reader.fieldnames], list(reader)


def load_real_csv(path: Path, args) -> tuple:
    """真实通知 CSV -> (texts, labels, dup_weights, stats)。

    标签来源（优先级）：
      1. --label-col 显式标签列
      2. 消去理由列：理由以 --ad-reason（默认 "被其他通知服务取消"，即被通知滤盒 AI
         消除的通知，前缀匹配，忽略大小写）开头 → 广告(1)；其余（含空理由）→ 正常(0)
    重复文本去重（重复次数转样本权重，上限 MAX_DUP_WEIGHT）；同文本标签冲突以广告为准。
    """
    header, rows = read_csv_rows(path)
    title_col = resolve_column(header, args.title_col, ["title", "标题", "通知标题", "name"])
    content_col = resolve_column(header, args.content_col, ["content", "text", "内容", "通知内容", "通知信息", "body"])
    label_col = resolve_column(header, args.label_col, ["label", "标签", "is_ad", "type", "类别", "分类", "判定"])
    # 精确匹配：避免"通知"模糊命中"通知标题"导致只用标题训练（Dev 13 修复）
    text_col = resolve_column(header, args.text_col, ["text", "text_full", "全文"], fuzzy=False)
    reason_col = resolve_column(header, args.reason_col, ["消去理由", "取消理由", "reason"])

    ad_prefix = args.ad_reason.strip().lower()
    drop_prefixes = [p.strip().lower() for p in (args.drop_reason_prefix or "").split(",") if p.strip()]

    if text_col:
        if label_col is None and reason_col is None:
            raise SystemExit(f"[错误] 找不到标签列/消去理由列: {header}")
    elif title_col is None and content_col is None:
        raise SystemExit(f"[错误] 无法自动识别文本列: {header}")
    elif label_col is None and reason_col is None:
        raise SystemExit(f"[错误] 找不到标签列/消去理由列: {header}")

    if label_col is not None:
        print(f"[列映射] 标题={title_col!r} 内容={content_col!r} 标签={label_col!r}", file=sys.stderr)
    else:
        print(
            f"[列映射] 标题={title_col!r} 内容={content_col!r} 消去理由={reason_col!r}\n"
            f"[标签规则] 理由前缀 {ad_prefix!r} → 广告；其余（含空理由）→ 正常",
            file=sys.stderr,
        )

    stats = Counter(total=len(rows))
    texts, labels = [], []
    seen: dict = {}  # 文本 -> [label, dup_count, conflict]
    for row in rows:
        if text_col:
            t = (row.get(text_col) or "").strip()
        else:
            t = "\n".join(
                x for x in ((row.get(title_col) or "").strip(), (row.get(content_col) or "").strip()) if x
            )
        if not t:
            stats["emptyText"] += 1
            continue
        t = t[:MAX_TEXT_LEN]
        if label_col is not None:
            y = normalize_label(row.get(label_col) or "")
        else:
            r = (row.get(reason_col) or "").strip().lower()
            if drop_prefixes and any(r.startswith(p) for p in drop_prefixes):
                stats["droppedByReason"] += 1
                continue
            y = 1 if r.startswith(ad_prefix) else 0
        if y is None:
            stats["badLabel"] += 1
            continue
        if t in seen:
            stats["dupRemoved"] += 1
            seen[t][1] += 1
            if y == 1 and seen[t][0] == 0:
                seen[t][0] = 1
                seen[t][2] = 1
                stats["dupLabelConflict"] += 1
            continue
        seen[t] = [y, 1, 0]
        texts.append(t)
        labels.append(y)
        stats["kept"] += 1

    labels = [seen[t][0] for t in texts]
    if args.drop_ambiguous:
        n_before = len(texts)
        texts = [t for t in texts if seen[t][2] == 0]
        labels = [seen[t][0] for t in texts]
        stats["droppedAmbiguous"] = n_before - len(texts)
        if stats["droppedAmbiguous"]:
            print(f"[清洗] 剔除标签冲突文本 {stats['droppedAmbiguous']} 条", file=sys.stderr)
    weights = [float(min(seen[t][1], MAX_DUP_WEIGHT)) for t in texts]

    stats["labelFromReason"] = 1 if label_col is None else 0
    stats["pos"] = sum(labels)
    stats["neg"] = len(labels) - stats["pos"]
    print(
        f"[清洗] 保留 {stats['kept']} 条（广告 {stats['pos']} / 正常 {stats['neg']}），"
        f"去重 {stats['dupRemoved']}（标签冲突以广告为准 {stats['dupLabelConflict']}），"
        f"空文本 {stats['emptyText']}，坏标签 {stats['badLabel']}"
        + (f"，按理由剔除 {stats['droppedByReason']}" if drop_prefixes else ""),
        file=sys.stderr,
    )
    if stats["pos"] == 0 or stats["neg"] == 0:
        raise SystemExit("[错误] 真实数据只有单一类别，无法训练")
    return texts, np.asarray(labels, dtype=np.int8), weights, stats


# ========================= 评估 =========================


def report_eval(name: str, y_true: np.ndarray, proba: np.ndarray, thresholds=(0.5, 0.8, 0.9, 0.95)) -> dict:
    """打印多阈值评估，返回 0.8 阈值处的指标 dict。"""
    out = {}
    for thr in thresholds:
        pred = (proba >= thr).astype(int)
        tp = int(((pred == 1) & (y_true == 1)).sum())
        fp = int(((pred == 1) & (y_true == 0)).sum())
        tn = int(((pred == 0) & (y_true == 0)).sum())
        fn = int(((pred == 0) & (y_true == 1)).sum())
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        acc = (tp + tn) / len(y_true) if len(y_true) else 0.0
        print(
            f"  [{name}] thr={thr:.2f}  P={precision:.4f} R={recall:.4f} F1={f1:.4f} Acc={acc:.4f}"
            f"  TP={tp} FP={fp} TN={tn} FN={fn}",
            file=sys.stderr,
        )
        if abs(thr - 0.8) < 1e-9:
            out = {
                "threshold": thr,
                "accuracy": round(acc, 4),
                "precision": round(precision, 4),
                "recall": round(recall, 4),
                "f1": round(f1, 4),
                "confusionMatrix": {"tp": tp, "fp": fp, "tn": tn, "fn": fn},
            }
    return out


def threshold_scan(y_true: np.ndarray, proba: np.ndarray) -> list:
    out = []
    for thr in np.arange(0.50, 0.951, 0.05):
        thr = round(float(thr), 2)
        pred = (proba >= thr).astype(int)
        tp = int(((pred == 1) & (y_true == 1)).sum())
        fp = int(((pred == 1) & (y_true == 0)).sum())
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + int(((pred == 0) & (y_true == 1)).sum())) if tp else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        out.append(
            {"threshold": thr, "precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4),
             "predictedPos": int(pred.sum())}
        )
    return out


# ========================= 导出（NSPM int16 + parity + metadata + report） =========================


def quantise(weights: np.ndarray) -> tuple:
    max_abs = float(np.max(np.abs(weights))) if weights.size else 0.0
    scale = max_abs / 32767.0 if max_abs > 0 else 1.0
    q = np.clip(np.rint(weights / scale), -32767, 32767).astype(np.int16)
    return q, scale


def export_model(out_dir: Path, weights: np.ndarray, bias: float, model_version: int) -> tuple:
    out_dir.mkdir(parents=True, exist_ok=True)
    bin_path = out_dir / "model.bin"
    q, scale = quantise(weights)
    scale32 = np.float32(scale)
    header = struct.pack(">4siiiiff", MAGIC, FORMAT_VERSION, BUCKETS, NGRAM_MIN, NGRAM_MAX, float(bias), float(scale32))
    with open(bin_path, "wb") as f:
        f.write(header)
        f.write(q.astype(">i2").tobytes())  # int16 大端，与 header 一致
    w_deq = (q.astype(np.float32) * scale32).astype(np.float32)
    return bin_path, w_deq, float(scale32)


def write_parity(path: Path, texts: list, w_deq: np.ndarray, bias: float) -> None:
    rows = [{"text": t, "score": round(score_text(t, w_deq, bias), 6)} for t in texts]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_report(out_dir: Path, args, real_stats, sms_eval, real_eval, scan, quant_drift, scale, model_version, extra_paths):
    lines = []
    ap = lines.append
    ap("=" * 62)
    ap("NotificationCleaner 预训练报告（公共语料 + 真实通知）")
    ap("=" * 62)
    ap(f"时间:         {datetime.now().isoformat(timespec='seconds')}")
    ap(f"真实数据:     {', '.join(extra_paths) or '（无）'}")
    ap(f"模型版本:     {model_version}")
    ap(f"维度:         {BUCKETS}  n-gram: {NGRAM_MIN}~{NGRAM_MAX}(UTF-16 code unit)  int8 scale={scale:.6g}")
    ap(f"量化损失:     max|Δp| = {quant_drift:.5f}")
    ap("")
    ap("-- 真实通知数据统计 --")
    for k in ("total", "kept", "pos", "neg", "dupRemoved", "dupLabelConflict", "emptyText", "badLabel", "droppedByReason"):
        ap(f"  {k:<18}: {real_stats.get(k, 0)}")
    ap("")
    ap("-- 公共语料留出集 (20%) --")
    if sms_eval:
        cm = sms_eval["confusionMatrix"]
        ap(f"  P={sms_eval['precision']}  R={sms_eval['recall']}  F1={sms_eval['f1']}  Acc={sms_eval['accuracy']}")
        ap(f"  混淆矩阵: TP={cm['tp']} FP={cm['fp']} TN={cm['tn']} FN={cm['fn']}")
    else:
        ap("  （未使用公共语料）")
    ap("")
    ap("-- 真实通知留出集 (20%, 评估未参与训练的真实样本) --")
    if real_eval:
        cm = real_eval["confusionMatrix"]
        ap(f"  P={real_eval['precision']}  R={real_eval['recall']}  F1={real_eval['f1']}  Acc={real_eval['accuracy']}")
        ap(f"  混淆矩阵: TP={cm['tp']} FP={cm['fp']} TN={cm['tn']} FN={cm['fn']}")
    ap("")
    ap("-- 真实通知留出集阈值扫描 --")
    ap(f"  {'阈值':<8}{'精确率':<10}{'召回率':<10}{'F1':<10}{'预测为广告数':<10}")
    for row in scan:
        ap(f"  {row['threshold']:<8}{row['precision']:<10}{row['recall']:<10}{row['f1']:<10}{row['predictedPos']:<10}")
    ap("")
    ap("默认阈值 0.8。召回不足时可下调（精确率同步下降）；宁松勿严时上调。")
    (out_dir / "report.txt").write_text("\n".join(lines), encoding="utf-8")


# ========================= 主流程 =========================


def main():
    ap = argparse.ArgumentParser(description="NotificationCleaner 广告通知分类器预训练脚本")
    ap.add_argument("--extra", action="append", default=[], help="真实通知 CSV（可重复传入）")
    ap.add_argument("--extra-weight", type=float, default=30.0, help="真实通知样本权重（对 80 万公共语料保持话语权）")
    ap.add_argument("--title-col", default=None)
    ap.add_argument("--content-col", default=None)
    ap.add_argument("--text-col", default=None)
    ap.add_argument("--label-col", default=None, help="显式标签列（优先于消去理由推导）")
    ap.add_argument("--reason-col", default=None)
    ap.add_argument("--ad-reason", default="被其他通知服务取消", help="消去理由中代表'广告(AI消除)'的前缀")
    ap.add_argument("--drop-reason-prefix", default="", help="按理由前缀剔除样本（逗号分隔）")
    ap.add_argument("--drop-ambiguous", action="store_true", help="剔除标签冲突文本")
    ap.add_argument("--no-sms", action="store_true", help="不用公共语料，只用真实 CSV 训练")
    ap.add_argument("--sms-ham-only", action="store_true", help="公共语料只保留正常样本（作负样本校准先验）")
    ap.add_argument("--no-extra-train", action="store_true", help="真实 CSV 只做评估不参与训练（对照基线）")
    ap.add_argument("--limit", type=int, default=0, help="公共语料只取前 N 条（冒烟测试）")
    ap.add_argument("--C", type=float, default=1.0, help="逻辑回归正则化倒数")
    ap.add_argument("--out", default=str(ROOT / "model_out"))
    ap.add_argument("--model-version", type=int, default=0, help="模型版本号（0=自动自增）")
    args = ap.parse_args()

    t0 = time.time()
    out_dir = Path(args.out)

    # ---- 公共语料 ----
    sms_x_tr = sms_y_tr = sms_w_tr = sms_x_te = sms_y_te = None
    if not args.no_sms:
        download()
        texts, y = load_sms()
        if args.sms_ham_only:
            keep = [i for i, v in enumerate(y) if v == 0]
            texts, y = [texts[i] for i in keep], y[keep]
        if args.limit:
            texts, y = texts[: args.limit], y[: args.limit]
        print(f"[语料] rows={len(texts)} spam={int((y == 1).sum())} ham={int((y == 0).sum())}", file=sys.stderr)
        print("[语料] 特征化中…", file=sys.stderr)
        x = vectorise(texts)
        strat = y if len(set(y.tolist())) > 1 else None
        x_tr, x_te, y_tr, y_te = train_test_split(x, y, test_size=0.2, random_state=42, stratify=strat)
        sms_x_tr, sms_y_tr = x_tr, y_tr
        sms_w_tr = np.ones(x_tr.shape[0], dtype=np.float32)
        sms_x_te, sms_y_te = x_te, y_te

    # ---- 真实通知 CSV ----
    real_stats = Counter()
    real_eval = None
    scan = []
    all_real_texts: list = []
    if args.extra:
        ex_texts, ex_y, ex_w, real_stats = [], [], [], Counter()
        for p in args.extra:
            t, yy, ww, st = load_real_csv(Path(p), args)
            ex_texts += t
            ex_y.append(yy)
            ex_w += ww
            real_stats.update(st)
        ex_y = np.concatenate(ex_y)
        print(f"[真实] rows={len(ex_texts)} 广告={int((ex_y == 1).sum())} 正常={int((ex_y == 0).sum())}", file=sys.stderr)
        print("[真实] 特征化中…", file=sys.stderr)
        ex_x = vectorise(ex_texts)
        ex_x_tr, ex_x_te, ex_y_tr, ex_y_te = train_test_split(
            ex_x, ex_y, test_size=0.2, random_state=42, stratify=ex_y
        )
        # 按切分同步拆权重
        idx_all = np.arange(len(ex_texts))
        tr_idx, te_idx = train_test_split(idx_all, test_size=0.2, random_state=42, stratify=ex_y)
        ex_w_tr = np.asarray([ex_w[i] for i in tr_idx], dtype=np.float32)
        # 留出集原始文本（报告与 parity 用）
        all_real_texts = [ex_texts[i] for i in te_idx]
        if not args.no_extra_train:
            if sms_x_tr is not None:
                sms_x_tr = vstack([sms_x_tr, ex_x_tr]).tocsr()
                sms_y_tr = np.concatenate([sms_y_tr, ex_y_tr])
                sms_w_tr = np.concatenate([sms_w_tr, ex_w_tr * args.extra_weight])
            else:
                sms_x_tr, sms_y_tr, sms_w_tr = ex_x_tr, ex_y_tr, ex_w_tr

    if sms_x_tr is None:
        raise SystemExit("[错误] 没有任何训练数据（--no-sms 且未提供 --extra）")

    # ---- 训练 ----
    print(f"[训练] rows={sms_x_tr.shape[0]}  spam={int((sms_y_tr == 1).sum())}  ham={int((sms_y_tr == 0).sum())}", file=sys.stderr)
    clf = LogisticRegression(C=args.C, solver="liblinear", max_iter=1000)
    clf.fit(sms_x_tr, sms_y_tr, sample_weight=sms_w_tr)

    sms_eval = {}
    if sms_x_te is not None and len(set(sms_y_te.tolist())) > 1:
        print("[评估] 公共语料留出集:", file=sys.stderr)
        sms_eval = report_eval("sms", sms_y_te, clf.predict_proba(sms_x_te)[:, 1])
    if args.extra:
        print("[评估] 真实通知留出集:", file=sys.stderr)
        real_proba = clf.predict_proba(ex_x_te)[:, 1]
        real_eval = report_eval("real", ex_y_te, real_proba)
        scan = threshold_scan(ex_y_te, real_proba)

    # ---- 导出 ----
    weights = clf.coef_.reshape(-1).astype(np.float32)
    bias = float(clf.intercept_[0])
    model_version = args.model_version
    if model_version <= 0:
        model_version = 1
        meta_old = out_dir / "metadata.json"
        if meta_old.exists():
            try:
                model_version = json.loads(meta_old.read_text(encoding="utf-8")).get("modelVersion", 0) + 1
            except Exception:
                pass
    bin_path, w_deq, scale = export_model(out_dir, weights, bias, model_version)

    # 量化漂移（在真实留出集上衡量）
    quant_drift = 0.0
    if args.extra:
        p_fp = clf.predict_proba(ex_x_te)[:, 1]
        p_q = np.asarray([score_text(all_real_texts[i], w_deq, bias) for i in range(len(all_real_texts))])
        quant_drift = float(np.max(np.abs(p_q - p_fp)))

    # parity：通用校验文本 + 真实留出集样例
    parity_texts = list(PARITY_TEXTS) + [t.replace("\n", " ")[:100] for t in all_real_texts[:8]]
    write_parity(out_dir / "parity.json", parity_texts, w_deq, bias)

    metadata = {
        "formatVersion": FORMAT_VERSION,
        "modelVersion": model_version,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "format": "NSPM big-endian v2: magic/version/buckets/ngram_min/ngram_max/bias f32/scale f32 + int16 weights",
        "featureSpec": {
            "buckets": BUCKETS,
            "ngramRange": [NGRAM_MIN, NGRAM_MAX],
            "hash": "fnv1a-32 over utf-16-le bytes of n-gram, index = hash & (buckets-1)",
            "normalization": "lowercase -> remove whitespace/digits(Nd)/'x'",
            "value": "count, L2 normalized over full text",
            "truncateCodepoints": MAX_TEXT_LEN,
        },
        "trainStats": {
            "corpus": "none" if args.no_sms else ("ham-only" if args.sms_ham_only else "full"),
            "corpusLimit": args.limit,
            "extraWeight": args.extra_weight,
            "C": args.C,
            **{k: int(v) for k, v in real_stats.items()},
        },
        "labelSource": {
            "adReasonPrefix": args.ad_reason,
            "droppedReasonPrefixes": [p for p in (args.drop_reason_prefix or "").split(",") if p],
            "dupCountAsWeight": True,
            "maxDupWeight": MAX_DUP_WEIGHT,
        },
        "metrics": {
            "smsHoldout": sms_eval,
            "realHoldout": real_eval,
            "quantDriftMaxP": round(quant_drift, 6),
        },
        "thresholdScan": scan,
        "parityCount": len(parity_texts),
    }
    (out_dir / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(out_dir, args, real_stats, sms_eval, real_eval, scan, quant_drift, scale, model_version,
                 [Path(p).name for p in args.extra])

    print(f"\n[完成] {bin_path}  ({bin_path.stat().st_size / 1024:.0f} KB)  modelVersion={model_version}", file=sys.stderr)
    print(f"       int8 scale={scale:.6g}  量化漂移 max|Δp|={quant_drift:.5f}", file=sys.stderr)
    print(f"       真实留出集 @0.8: P={real_eval['precision']} R={real_eval['recall']} F1={real_eval['f1']}" if real_eval else "", file=sys.stderr)
    print(f"       parity/metadata/report → {out_dir}", file=sys.stderr)
    print(f"       用时 {time.time() - t0:.1f}s", file=sys.stderr)
    print("[下一步] 将 model.bin 拷入 Android 工程 app/src/main/assets/model/；", file=sys.stderr)
    print("         用 parity.json 编写 Kotlin FeatureHasher/SpamModel 单元测试验证双端打分一致。", file=sys.stderr)


if __name__ == "__main__":
    main()
