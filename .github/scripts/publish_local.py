#!/usr/bin/env python3
"""本地构建并发布 Release，绕开 CI 的 8 分钟等待。

用法:
    python .github/scripts/publish_local.py v2.1.0-beta.1-local38
    python .github/scripts/publish_local.py v2.1.0-beta.1-local38 --dry-run
    python .github/scripts/publish_local.py v2.1.0-beta.1-local38 --skip-build

做的事（顺序很重要：构建和签名校验都通过才推 tag，失败时远端不会留下坏版本）：
    1. 检查工作区干净 —— 有未提交改动时构建出来的东西和 push 上去的代码不一致
    2. 本地打 tag
    3. assembleRelease，versionCode = 20512 + N
    4. apksigner 校验证书指纹（复刻 CI 里那道硬门禁）
    5. push tag
    6. 创建 Release（已存在则更新正文）+ 上传 APK

为什么能绕开 CI：本机 ~/.android/debug.keystore 就是发布签名，
本地打出来的包与 GitHub 上的历史版本同签名，可以直接覆盖安装。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OWNER_REPO = "choksta/Melodia-local"
REMOTE = "mine-ssh"

# 衔接已有本地序列：local28 = 20540，正是手机上装着的那个
VERSION_CODE_BASE = 20512

# 发布签名指纹，与 .github/workflows/release.yml 里的 EXPECTED_SHA256 必须一致
EXPECTED_SHA256 = "0D:32:1B:9D:A9:99:04:08:58:8E:3D:A5:B2:1E:E8:F1:DE:B4:3E:11:96:A2:44:3E:F5:D6:1A:30:09:2E:00:7C"


def run(cmd, cwd=REPO, check=True, capture=True, shell=False):
    return subprocess.run(
        cmd, cwd=cwd, check=check, shell=shell,
        capture_output=capture, text=True, encoding="utf-8", errors="replace",
    )


def git(*args, check=True):
    return run(["git", *args], check=check).stdout.strip()


def token() -> str:
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("拿不到 GitHub 凭据")


def api(method, path, auth, data=None, raw=False, content_type="application/json"):
    body = None
    if data is not None:
        body = data if raw else json.dumps(data).encode()
    req = urllib.request.Request(
        path if path.startswith("https://") else f"https://api.github.com{path}",
        data=body,
        headers={
            "Authorization": f"Bearer {auth}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "melodia-publish",
            "Content-Type": content_type,
        },
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text.strip().startswith(("{", "[")) else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode()[:300]}


def find_apksigner():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.expanduser("~/AppData/Local/Android/Sdk")
    build_tools = os.path.join(sdk, "build-tools")
    if not os.path.isdir(build_tools):
        raise SystemExit(f"找不到 build-tools：{build_tools}")
    versions = sorted(os.listdir(build_tools), key=lambda v: [int(x) for x in re.findall(r"\d+", v)])
    for version in reversed(versions):
        for name in ("apksigner.bat", "apksigner"):
            candidate = os.path.join(build_tools, version, name)
            if os.path.isfile(candidate):
                return candidate
    raise SystemExit("找不到 apksigner")


def verify_signature(apk: str):
    signer = find_apksigner()
    cmd = ["cmd", "/c", signer, "verify", "--print-certs", apk] if os.name == "nt" \
        else [signer, "verify", "--print-certs", apk]
    out = run(cmd).stdout
    match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F:]+)", out)
    if not match:
        raise SystemExit("apksigner 没输出证书指纹：\n" + out[:500])
    actual = match.group(1)
    norm = lambda s: re.sub(r"[:\s]", "", s).lower()
    if norm(actual) != norm(EXPECTED_SHA256):
        raise SystemExit(
            f"签名指纹不符！\n  预期 {EXPECTED_SHA256}\n  实际 {actual}\n"
            "换过 keystore 会导致已安装用户无法覆盖升级，已中止。"
        )
    print(f"  签名指纹校验通过：{actual}")


def release_body(tag: str, version_code: int) -> str:
    handwritten = os.path.join(REPO, ".github", "release-notes", f"{tag}.md")
    marker = f"<!-- versionCode={version_code} -->"
    if os.path.isfile(handwritten):
        print(f"  使用手写说明：{tag}.md")
        with open(handwritten, encoding="utf-8") as f:
            return f"{marker}\n\n{f.read().strip()}\n"

    prev = git("describe", "--tags", "--abbrev=0", f"{tag}^", check=False)
    rng = f"{prev}..{tag}" if prev else "HEAD"
    print(f"  自动生成更新日志（范围 {rng}）")
    out = run([
        sys.executable,
        os.path.join(REPO, ".github", "scripts", "gen_release_notes.py"),
        rng, str(version_code), prev or "",
    ], check=False).stdout
    return out if out.strip().startswith("<!--") else f"{marker}\n\n{out}"


def main():
    parser = argparse.ArgumentParser(description="本地构建并发布 Release")
    parser.add_argument("tag", help="形如 v2.1.0-beta.1-local38")
    parser.add_argument("--dry-run", action="store_true", help="只检查并构建，不推 tag 不上传")
    parser.add_argument("--skip-build", action="store_true", help="复用已有产物，跳过构建")
    parser.add_argument("--prerelease", action="store_true", help="发成预发布（默认正式）")
    args = parser.parse_args()

    tag = args.tag
    match = re.search(r"-local(\d+)$", tag)
    if not match:
        raise SystemExit("tag 必须以 -localN 结尾，否则 versionCode 无法计算")
    n = int(match.group(1))
    version_code = VERSION_CODE_BASE + n
    print(f"tag={tag}  versionCode={version_code}")

    # 1. 工作区必须干净：否则构建的是未提交的代码，push 上去的是旧的
    dirty = git("status", "--porcelain")
    if dirty:
        raise SystemExit("工作区有未提交改动，先提交再发版：\n" + dirty)

    already = git("tag", "--list", tag)
    if not already:
        git("tag", tag)
        print(f"  已创建本地 tag {tag}")

    began = time.time()

    # 2. 构建
    apk = os.path.join(
        REPO, "app", "build", "outputs", "apk", "release", f"Melodia-v{tag}-release.apk"
    )
    if args.skip_build:
        print("  跳过构建，复用已有产物")
    else:
        print("  开始构建（本地有 Gradle 缓存，通常 4~5 分钟）…")
        gradlew = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]
        result = run(gradlew + [
            ":app:assembleRelease",
            f"-PreleaseVersionName={tag}",
            f"-PreleaseVersionCode={version_code}",
            "--console=plain",
        ], check=False)
        if result.returncode != 0:
            git("tag", "-d", tag, check=False)
            tail = (result.stdout or "")[-1500:]
            raise SystemExit("构建失败，已删除本地 tag：\n" + tail)
        print(f"  构建完成，用时 {int(time.time() - began)}s")

    if not os.path.isfile(apk):
        raise SystemExit(f"找不到产物：{apk}")

    # 3. 签名门禁
    verify_signature(apk)
    size_mb = os.path.getsize(apk) / 1048576
    print(f"  产物：{os.path.basename(apk)}（{size_mb:.2f} MB）")

    if args.dry_run:
        print("\n--dry-run：到此为止，不推 tag、不上传")
        return

    # 4. 推 tag
    print(f"  推送 tag 到 {REMOTE}…")
    push = run(["git", "push", REMOTE, tag], check=False)
    if push.returncode != 0:
        raise SystemExit("推 tag 失败：\n" + (push.stdout or "") + (push.stderr or ""))

    # 5. Release（已存在则更新正文并替换资产）
    auth = token()
    body = release_body(tag, version_code)
    status, existing = api("GET", f"/repos/{OWNER_REPO}/releases/tags/{tag}", auth)
    if status == 200:
        rel_id = existing["id"]
        api("PATCH", f"/repos/{OWNER_REPO}/releases/{rel_id}", auth, {
            "body": body, "prerelease": args.prerelease, "make_latest": "true",
        })
        for asset in existing.get("assets", []):
            api("DELETE", f"/repos/{OWNER_REPO}/releases/assets/{asset['id']}", auth)
        print("  已更新已有 Release")
    else:
        status, created = api("POST", f"/repos/{OWNER_REPO}/releases", auth, {
            "tag_name": tag,
            "name": tag,
            "body": body,
            "prerelease": args.prerelease,
            "make_latest": "true",
        })
        if status not in (201, 200):
            raise SystemExit(f"创建 Release 失败 {status}: {created}")
        rel_id = created["id"]
        print("  已创建 Release")

    # 6. 上传资产
    with open(apk, "rb") as f:
        payload = f.read()
    upload_url = f"https://uploads.github.com/repos/{OWNER_REPO}/releases/{rel_id}/assets?name={quote(os.path.basename(apk))}"
    status, uploaded = api(
        "POST", upload_url, auth, data=payload, raw=True,
        content_type="application/vnd.android.package-archive",
    )
    if status not in (201, 200):
        raise SystemExit(f"上传 APK 失败 {status}: {uploaded}")
    print(f"  已上传 {uploaded.get('name')}（{uploaded.get('size', 0) / 1048576:.2f} MB）")

    print(f"\n完成，总用时 {int(time.time() - began)}s")
    print(f"https://github.com/{OWNER_REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
