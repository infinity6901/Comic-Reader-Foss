# NFC tags

An NTAG213 holds about 130 bytes, which is plenty for a short link. The app writes and reads
these links (plain NDEF URI records):

| Link | What a tap does |
|---|---|
| `comicreader://open?p=Comics/One Piece` | Opens that book, or continues the folder where you left off (the path is relative to shared storage; non-ASCII names are stored as-is to save space) |
| `comicreader://continue` | Continues whatever you read last |
| `comicreader://profile?m=longstrip&d=ltr&f=width&n=1&k=1&b=40` | Applies reader settings (mode, direction, fit, night filter, keep screen on, brightness) and continues reading with them, ignoring any per-folder layout for that session |

## Writing a tag
- A book or folder: long-press it in the library → **Write to NFC tag**, then hold the tag to the back of the phone.
- A "continue reading" or reading-profile tag: **⋮ → Tools → NFC tags…**. A profile tag stores the reader settings you have now.

## Notes
- Tapping a tag works with the app closed. If the app does not have file access yet, it asks, then carries out the tap.
- A tag stores a path, not the file: if you move or rename the comic, rewrite the tag.
- Tags can be copied and rewritten by anyone, so they never open anything outside shared storage and
  unknown settings are ignored. Don't use them to protect anything.
- The path must fit on the tag (about 105 bytes after `comicreader://open?p=`); otherwise the app says so.
- A profile tag changes your reader settings until you change them back (tap another profile tag, or
  use the reader menu), so it works well as a pair, e.g. "night" and "day".
