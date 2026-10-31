#!/usr/bin/env python3
"""从 git 提交历史生成 Release 更新日志。

用法：
    python3 gen_release_notes.py <提交范围> <versionCode> [上一个 tag]

输出写到 stdout，由 workflow 重定向到 release-notes.md。

只保留用户能感知的三类：更新（新增功能）/ 修复 / 优化。
docs、ci、build、chore、test 这些工程内部改动不进日志——
读者不关心我们调了哪条流水线，只想知道这次多了什么、修了什么、快了没有。
"""
import collections
import os
import re
import subprocess
import sys

# 提交类型 → 展示分组。没列进来的类型直接丢弃
TITLES = {
    "feat": "✨ 更新",
    "fix": "🐛 修复",
    "perf": "⚡ 优化",
    "refactor": "⚡ 优化",
}

ORDER = ["feat", "fix", "perf", "refactor"]

# "fix(ui): xxx" / "feat!: xxx" → 只留冒号后面那句
PREFIX = re.compile(r"^[a-z]+(\([^)]*\))?!?:\s*", re.IGNORECASE)


def collect(range_spec: str):
    raw = subprocess.run(
        ["git", "log", range_spec, "--no-merges", "--pretty=format:%s%x01"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout

    buckets = collections.defaultdict(list)
    for chunk in raw.split("\x01"):
        subject = chunk.strip()
        if not subject:
            continue
        kind = subject.split(":")[0].split("(")[0].strip().lower()
        if kind not in TITLES:
            continue
        text = PREFIX.sub("", subject).strip()
        if text:
            buckets[kind].append("- " + text)
    return buckets


def render(buckets, code: str, prev_tag: str) -> str:
    # 首行是给客户端读的版本标记，缺了它整条 release 会被更新检查忽略
    lines = ["<!-- versionCode=%s -->" % code, ""]

    emitted = set()
    for kind in ORDER:
        title = TITLES[kind]
        if title in emitted or not buckets[kind]:
            continue
        emitted.add(title)
        lines.append("### " + title)
        lines.append("")
        lines.extend(dict.fromkeys(buckets[kind]))  # 去重并保持顺序
        lines.append("")

    if not emitted:
        lines.append("本次没有用户可见的变化。")
        lines.append("")

    repo = os.environ.get("GITHUB_REPOSITORY", "")
    tag = os.environ.get("GITHUB_REF_NAME", "")
    if prev_tag and repo and tag:
        lines.append("**完整对比**：https://github.com/%s/compare/%s...%s" % (repo, prev_tag, tag))

    return "\n".join(lines)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)

    range_spec = sys.argv[1]
    code = sys.argv[2]
    prev_tag = sys.argv[3] if len(sys.argv) > 3 else ""

    sys.stdout.write(render(collect(range_spec), code, prev_tag) + "\n")


if __name__ == "__main__":
    main()
