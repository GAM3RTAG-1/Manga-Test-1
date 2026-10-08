"""Build a personal extension repo (index.pb / index.json + apks + icons) into ./site."""
import gzip
import html
import json
import os
import shutil
import sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2  # noqa: E402
from google.protobuf import json_format  # noqa: E402

ROOT = Path.cwd()
SITE = ROOT / "site"
BASE_URL = os.environ["BASE_URL"].rstrip("/")
SIGNING_KEY = os.environ["SIGNING_KEY_SHA256"].strip().lower()
REPO_NAME = os.environ.get("REPO_DISPLAY_NAME", "My Extensions")
BADGE = os.environ.get("REPO_BADGE", "ME")
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"

shutil.rmtree(SITE, ignore_errors=True)
(SITE / "apk").mkdir(parents=True)
(SITE / "icon").mkdir()


def find_icon(module: str, theme: str | None) -> Path | None:
    candidates = [ROOT / "src" / module.replace(".", "/") / ICON_FILE]
    if theme:
        candidates.append(ROOT / "lib-multisrc" / theme / ICON_FILE)
    candidates.append(ROOT / "core/src/main" / ICON_FILE)
    return next((c for c in candidates if c.exists()), None)


extensions = []
for info_file in sorted(ROOT.glob("src/**/build/keiyoushi-source-info.json")):
    info = json.loads(info_file.read_text(encoding="utf-8"))
    build_dir = info_file.parent
    package = info["packageName"]

    apk = next((build_dir / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise SystemExit(f"{package}: no release apk in {build_dir}")
    jar = next((build_dir / "outputs/jar/release").glob("*.jar"), None)

    shutil.copy(apk, SITE / "apk" / apk.name)
    resources = index_pb2.Resources(apkUrl=f"{BASE_URL}/apk/{quote(apk.name)}")
    if jar is not None:
        shutil.copy(jar, SITE / "apk" / jar.name)
        resources.jarUrl = f"{BASE_URL}/apk/{quote(jar.name)}"

    icon = find_icon(info["module"], info.get("theme"))
    if icon is not None:
        shutil.copy(icon, SITE / "icon" / f"{package}.png")
        resources.iconUrl = f"{BASE_URL}/icon/{package}.png"

    extensions.append(
        index_pb2.Extension(
            name=info["name"],
            packageName=package,
            resources=resources,
            extensionLib=info["extensionLib"],
            versionCode=info["versionCode"],
            versionName=info["versionName"],
            contentWarning=info["contentWarning"],
            sources=[
                index_pb2.Source(
                    id=int(s["id"]),
                    name=s["name"],
                    language=s["lang"],
                    homeUrl=s["baseUrl"],
                    mirrorUrls=s.get("mirrorUrls", []),
                )
                for s in info["sources"]
            ],
        )
    )

if not extensions:
    raise SystemExit("No built extensions found")

extensions.sort(key=lambda e: e.packageName)
index = index_pb2.Index(
    name=REPO_NAME,
    badgeLabel=BADGE,
    signingKey=SIGNING_KEY,
    contact=index_pb2.Contact(website=BASE_URL),
    extensionList=index_pb2.ExtensionList(extensions=extensions),
)

(SITE / "index.json").write_text(
    json_format.MessageToJson(
        index, always_print_fields_with_no_presence=False, preserving_proto_field_name=True
    ),
    encoding="utf-8",
)
(SITE / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))
(SITE / ".nojekyll").write_text("")
links = "\n".join(
    f'<a href="{html.escape(e.resources.apkUrl)}">{html.escape(e.name)} v{html.escape(e.versionName)}</a>'
    for e in extensions
)
(SITE / "index.html").write_text(
    f"<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>{html.escape(REPO_NAME)}</title></head>"
    f"<body><pre>\n{links}\n</pre></body></html>\n",
    encoding="utf-8",
)
print(f"Published {len(extensions)} extension(s) to {SITE}")
