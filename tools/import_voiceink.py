#!/usr/bin/env python3
"""Copy VoiceInk's dictionary (word replacements + vocabulary) from this Mac into TalkType on the phone.

Reads a temporary copy of VoiceInk's database, so VoiceInk itself is never modified. Existing TalkType
entries are kept; only new ones are added. Needs the phone connected over USB with a debug build of
TalkType installed. TalkType merges the file the next time its process starts (after a reboot or an
update); pass --apk to reinstall a build, which restarts it immediately.
"""
import argparse
import json
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

STORE = Path.home() / "Library/Application Support/com.prakashjoshipax.VoiceInk"
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"


def read_voiceink():
    with tempfile.TemporaryDirectory() as tmp:
        for f in STORE.glob("dictionary.store*"):
            if f.is_file():
                shutil.copy(f, tmp)
        db = sqlite3.connect(Path(tmp) / "dictionary.store")
        replacements = [
            [heard, meant]
            for heard, meant in db.execute(
                "select ZORIGINALTEXT, ZREPLACEMENTTEXT from ZWORDREPLACEMENT where ZISENABLED = 1 order by ZDATEADDED"
            )
            if heard and meant
        ]
        vocabulary = [w for (w,) in db.execute("select ZWORD from ZVOCABULARYWORD order by ZDATEADDED") if w]
        db.close()
    return {"replacements": replacements, "vocabulary": vocabulary}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", help="reinstall this APK afterwards so TalkType restarts and imports right away")
    args = ap.parse_args()

    data = read_voiceink()
    print(f"VoiceInk: {len(data['replacements'])} replacements, {len(data['vocabulary'])} vocabulary words")
    push = subprocess.run(
        [str(ADB), "shell", "run-as app.talktype sh -c 'cat > files/dictionary-import.json'"],
        input=json.dumps(data).encode(),
    )
    if push.returncode != 0:
        sys.exit("could not write to the phone - is it connected with TalkType installed?")
    if args.apk:
        subprocess.run([str(ADB), "install", "-r", args.apk], check=True)
    print("Sent. TalkType merges it the next time it starts.")


if __name__ == "__main__":
    main()
