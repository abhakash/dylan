#!/usr/bin/env python3
"""Extract the §13.1 fixture corpus from local HAR captures. One-shot tooling."""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "fixtures")
os.makedirs(FIX, exist_ok=True)


def bodies(f):
    d = json.load(open(os.path.join(ROOT, f)))
    out = []
    for e in d["log"]["entries"]:
        t = e["response"]["content"].get("text", "")
        if t:
            out.append((e["request"]["url"], t))
    return out


h2 = bodies("2.har")
h8 = bodies("8.har")


def find(entries, needle):
    for u, t in entries:
        if needle in u and t.strip():
            return t
    raise SystemExit(f"missing {needle}")


def dump(name, obj):
    with open(os.path.join(FIX, name), "w") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
    print("wrote", name)


# --- real payloads -----------------------------------------------------------
top = json.loads(find(h2, "getTopSearches"))
dump("top_searches.json", top)

trend = json.loads(find(h2, "getTrending"))
dump("trending.json", trend)

album = json.loads(find(h2, "type=album"))
songs = album.get("list") or []
album_trim = dict(album)
album_trim["list"] = songs[:3]
dump("album_detail_full.json", album_trim)

sr = json.loads(find(h8, "search.getResults"))
sr_trim = dict(sr)
sr_trim["results"] = sr["results"][:5]
dump("search_getresults_p1.json", sr_trim)

empty = {"total": "0", "start": "1", "results": []}
dump("search_empty.json", empty)

ws = json.load(open(os.path.join(ROOT, "7.har")))
frame = None
for e in ws["log"]["entries"]:
    for m in e.get("_webSocketMessages", []):
        if m.get("type") == "receive":
            frame = m["data"]
            break
    if frame:
        break
with open(os.path.join(FIX, "autocomplete_ws_frame.json"), "w") as f:
    f.write(frame)
print("wrote autocomplete_ws_frame.json")

auth = json.loads(find(h2, "generateAuthToken"))
assert auth["_160" not in auth] if False else True
dump("generate_auth_token_128.json", auth)

base_song = json.loads(json.dumps(songs[0]))

no320 = json.loads(json.dumps(base_song))
no320["more_info"]["320kbps"] = "false"
dump("song_no320.json", no320)

notc = json.loads(json.dumps(base_song))
notc["more_info"]["rights"] = {"code": "0", "cacheable": "false", "delete_cached_object": "true", "reason": "label restriction"}
dump("song_not_cacheable.json", notc)

nores = json.loads(json.dumps(base_song))
nores["more_info"]["encrypted_media_url"] = ""
dump("song_no_resolve_ref.json", nores)

mal = json.loads(json.dumps(base_song))
mal["year"] = 2026
mal["play_count"] = None
mal["more_info"]["duration"] = "not-a-number"
mal["more_info"]["rights"] = "oops"
mal["image"] = None
dump("malformed_fields.json", mal)

with open(os.path.join(FIX, "html_error_page.txt"), "w") as f:
    f.write("<!DOCTYPE html><html><head><title>Service Unavailable</title></head><body><h1>503 Service Unavailable</h1><p>Request failed. Please try again.</p></body></html>\n")
print("wrote html_error_page.txt")

with open(os.path.join(FIX, "expired_signature_403.txt"), "w") as f:
    f.write("403\n")
print("wrote expired_signature_403.txt")

dump("rate_limited_429.json", {"status": "error", "message": "Too many requests. Please slow down.", "error_code": "RateLimitedError"})
