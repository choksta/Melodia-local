#!/usr/bin/env python3
"""从 git 提交历史生成 Release 更新日志。

用法：
    python3 gen_release_notes.py <提交范围> <versionCode> [上一个 tag]

输出写到 stdout，由 workflow 重定向到 release-notes.md。

为什么单独成文件：逻辑比 shell 复杂（要解析 commit 正文），
放在 .yml 的 heredoc 里既难读又难在本地验证。
"""
import collections
import os
import subprocess
import sys

TITLES = {
    "feat": "✨ 新功能",
    "fix": "🐛 修复",
    "refactor": "♻️ 重构",
    "perf": "⚡ 性能",
    "docs": "📝 文档",
    "test": "✅ 测试",
    "build": "📦 构建",
    "ci": "🔧 CI",
    "chore": "🧹 杂项",
    "style": "🎨 样式",
}

# 与上游 CI 一致的展示顺序
ORDER = ["feat", "fix", "refactor", "perf", "build", "docs", "test", "ci", "chore", "style"]

DETAIL_MAX_CHARS = 120


def collect(range_spec: str):
    """读提交，返回 (按类型分组的条目, 无类型前缀的条目)。

    除了标题，还把 commit 正文的第一段抓成补充说明：
    日志详不详细，取决于写 commit 时舍不舍得多说一句。
    """
    raw = subprocess.run(
        ["git", "log", range_spec, "--no-merges", "--pretty=format:%s%x00%b%x01"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout

    buckets = collections.defaultdict(list)
    other = []

    for chunk in raw.split("\x01"):
        if not chunk.strip():
            continue
        subject, _, body = chunk.partition("\x00")
        subject = subject.strip()
        if not subject:
            continue

        detail = ""
        for line in body.splitlines():
            text = line.strip()
            if not text:
                # 正文第一段结束了；如果还没抓到任何一句，继续往下找
                if detail:
                    break
                continue
            # 列表行是「改动：- xxx」之类的细节，不适合当一句话说明
            if text.startswith(("-", "*")):
                continue
            detail = text[:DETAIL_MAX_CHARS] + ("…" if len(text) > DETAIL_MAX_CHARS else "")
            break

        item = "- " + subject
        if len(detail) >= 10 and detail != subject:
            item += "\n  " + detail

        kind = subject.split(":")[0].split("(")[0].strip()
        if kind in TITLES:
            buckets[kind].append(item)
        else:
            other.append(item)

    return buckets, other


def render(buckets, other, code: str, prev_tag: str) -> str:
    # 首行是给客户端读的版本标记，缺了它整条 release 会被更新检查忽略
    lines = ["<!-- versionCode=%s -->" % code, ""]

    for kind in ORDER:
        if buckets[kind]:
            lines.append("### " + TITLES[kind])
            lines.append("")
            lines.extend(buckets[kind])
            lines.append("")

    if other:
        lines.append("### 📌 其他")
        lines.append("")
        lines.extend(other)
        lines.append("")

    repo = os.environ.get("GITHUB_REPOSITORY", "")
    tag = os.environ.get("GITHUB_REF_NAME", "")
    if prev_tag and repo and tag:
        lines.append(
            "**完整对比**：https://github.com/%s/compare/%s...%s" % (repo, prev_tag, tag)
        )

    return "\n".join(lines)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)

    range_spec = sys.argv[1]
    code = sys.argv[2]
    prev_tag = sys.argv[3] if len(sys.argv) > 3 else ""

    buckets, other = collect(range_spec)
    sys.stdout.write(render(buckets, other, code, prev_tag) + "\n")


if __name__ == "__main__":
    main()
