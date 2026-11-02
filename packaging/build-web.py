"""Stage the web player and shared assets for Cloudflare Pages deployment."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "dist" / "web"

if DEST.exists():
    shutil.rmtree(DEST)
shutil.copytree(ROOT / "web", DEST, ignore=shutil.ignore_patterns("clips", "pioneer.png", "CHANGELOG.md"))
shutil.copy2(ROOT / "CHANGELOG.md", DEST / "CHANGELOG.md")
(DEST / "clips").mkdir()
for clip in (ROOT / "assets" / "clips").glob("*.lkd"):
    shutil.copy2(clip, DEST / "clips" / clip.name)
print(f"Staged {len(list((DEST / 'clips').glob('*.lkd')))} clips in {DEST}")

revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
(DEST / "build-revision.txt").write_text(revision + "\n")
