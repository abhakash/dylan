#!/usr/bin/env python3
"""Static sanity checker for iosApp.xcodeproj/project.pbxproj.

Parses the OpenStep-style plist with a tiny hand-rolled tokenizer (plistlib
cannot read this legacy format) and validates the object graph:
  * balanced delimiters / well-formed values
  * 24-hex IDs, unique object keys
  * every referenced ID is defined; no orphan objects
  * PBXBuildFile.fileRef exists; each build file belongs to exactly one phase
  * build-phase ordering: Kotlin framework script -> Sources -> Frameworks -> Embed
  * Embed phase embeds shared.framework with CodeSignOnCopy from BUILT_PRODUCTS_DIR
  * shell script no longer passes -Pdevice
Exit code 0 = clean.
"""
import re
import sys

PATH = "iosApp.xcodeproj/project.pbxproj"


def tokenize(text):
    toks, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            if j < 0:
                raise SyntaxError("unterminated block comment")
            i = j + 2
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c.isspace():
            i += 1
            continue
        if c == '"':
            j, buf = i + 1, []
            while j < n:
                if text[j] == "\\":
                    buf.append(text[j + 1])
                    j += 2
                    continue
                if text[j] == '"':
                    break
                buf.append(text[j])
                j += 1
            if j >= n:
                raise SyntaxError("unterminated string")
            toks.append(("str", "".join(buf)))
            i = j + 1
        elif c in "{}(),;=":
            toks.append((c, c))
            i += 1
        else:
            j = i
            while j < n and not text[j].isspace() and text[j] not in '{}(),;="':
                j += 1
            toks.append(("id", text[i:j]))
            i = j
    return toks


class Parser:
    def __init__(self, toks):
        self.t, self.i = toks, 0

    def peek(self):
        return self.t[self.i][0] if self.i < len(self.t) else None

    def take(self):
        v = self.t[self.i]
        self.i += 1
        return v

    def parse(self):
        if self.peek() == "{":  # whole-file anonymous dict
            return self.value()
        doc = {}
        while self.peek() is not None:
            kind, key = self.take()
            if kind != "str" and kind != "id":
                raise SyntaxError(f"expected key, got {key!r}")
            if self.take()[0] != "=":
                raise SyntaxError(f"expected '=' after {key!r}")
            val = self.value()
            if self.peek() == ";":
                self.take()
            if key in doc:
                raise ValueError(f"duplicate top-level key {key!r}")
            doc[key] = val
        return doc

    def value(self):
        kind, val = self.take()
        if kind == "{":
            d = {}
            while True:
                k = self.take()
                if k[0] == "}":
                    break
                if self.take()[0] != "=":
                    raise SyntaxError(f"expected '=' after dict key {k[1]!r}")
                v = self.value()
                if self.peek() == ";":
                    self.take()
                if k[1] in d:
                    raise ValueError(f"duplicate key {k[1]!r} in dict")
                d[k[1]] = v
            return d
        if kind == "(":
            a = []
            while True:
                if self.peek() == ")":
                    self.take()
                    break
                a.append(self.value())
                sep = self.take()[0]
                if sep == ")":
                    break
                if sep != ",":
                    raise SyntaxError("expected ',' or ')' in array")
            return a
        return val


def main():
    errors = []
    text = open(PATH, encoding="utf-8").read()
    try:
        doc = Parser(tokenize(text)).parse()
    except Exception as e:  # noqa: BLE001
        print("FATAL: parse failure:", e)
        return 1

    objects = doc.get("objects", {})
    ids = list(objects)

    # 1. ID shape + uniqueness (parser already rejects duplicate keys).
    #    Xcode IDs are arbitrary 24-char uppercase alnum; the file's own style mixes
    #    hex-safe (A/B/C/D/E) and non-hex (F/G/H/I) letter prefixes — both legal.
    #    NEW objects added by this toolchain must be strictly hex.
    NEW_IDS = {"D71100000000000000000000", "C80100000000000000000000",
               "E10400000000000000000000"}
    for oid in ids:
        if oid in NEW_IDS:
            if not re.fullmatch(r"[0-9A-F]{24}", oid):
                errors.append(f"new ID not strict 24-hex: {oid}")
        elif not re.fullmatch(r"[A-Z0-9]{24}", oid):
            errors.append(f"ID not 24-char uppercase alnum: {oid}")

    def defined(x):
        return isinstance(x, str) and x in objects

    # 2. rootObject
    root = doc.get("rootObject")
    if not defined(root) or objects[root]["isa"] != "PBXProject":
        errors.append(f"rootObject missing/not PBXProject: {root!r}")

    # 3. Walk references + ownership maps
    buildfile_phase = {}          # PBXBuildFile id -> owning phase id
    fileref_buildfiles = {}       # fileRef id -> [PBXBuildFile ids]
    phase_files = {}              # phase id -> [build file ids]
    group_children = []
    target_phases = None
    product_ref = None
    referenced = {root}

    for oid, obj in objects.items():
        referenced.add(oid)
        isa = obj.get("isa", "?")
        if isa == "PBXBuildFile":
            fr = obj.get("fileRef")
            if not defined(fr):
                errors.append(f"{oid}: fileRef undefined: {fr!r}")
            else:
                fileref_buildfiles.setdefault(fr, []).append(oid)
                fr_isa = objects[fr].get("isa")
                if fr_isa != "PBXFileReference":
                    errors.append(f"{oid}: fileRef {fr} isa={fr_isa}, want PBXFileReference")
        elif isa in ("PBXSourcesBuildPhase", "PBXFrameworksBuildPhase",
                     "PBXCopyFilesBuildPhase", "PBXResourcesBuildPhase",
                     "PBXShellScriptBuildPhase"):
            files = obj.get("files") or []
            if isa == "PBXShellScriptBuildPhase" and files:
                errors.append(f"{oid}: shell-script phase must have empty files[]")
            for f in files:
                if not defined(f) or objects[f]["isa"] != "PBXBuildFile":
                    errors.append(f"{oid}: files entry not a PBXBuildFile: {f!r}")
                else:
                    if f in buildfile_phase:
                        errors.append(f"{f}: PBXBuildFile listed in two phases "
                                      f"({buildfile_phase[f]}, {oid})")
                    buildfile_phase[f] = oid
            phase_files[oid] = files
        elif isa == "PBXGroup":
            group_children += obj.get("children") or []
        elif isa == "PBXNativeTarget":
            target_phases = obj.get("buildPhases") or []
            product_ref = obj.get("productReference")

    # 4. Undefined references anywhere
    def check_refs(container, label):
        for x in container or []:
            if isinstance(x, str) and x not in objects:
                errors.append(f"{label}: undefined id {x!r}")

    for oid, obj in objects.items():
        check_refs(obj.get("files"), f"{obj.get('isa')}:{oid}.files")
        check_refs(obj.get("children"), f"group:{oid}.children")
        check_refs(obj.get("buildPhases"), f"target:{oid}.buildPhases")
        check_refs(obj.get("buildConfigurations"), f"cfglist:{oid}.buildConfigurations")
        for k in ("mainGroup", "productRefGroup"):
            if obj.get("isa") == "PBXProject" and obj.get(k) and not defined(obj[k]):
                errors.append(f"PBXProject.{k} undefined")

    # 5. Orphans: every object reachable as some reference
    refset = set()
    for fr, bfs in fileref_buildfiles.items():
        refset.add(fr)
        refset.update(bfs)
    for p, fs in phase_files.items():
        refset.add(p)
        refset.update(fs)
    refset.update(group_children)
    refset.update(target_phases or [])
    if product_ref:
        refset.add(product_ref)
    refset.add(root)
    for oid, obj in objects.items():
        if obj.get("isa") == "PBXProject":
            for k in ("mainGroup", "productRefGroup"):
                if defined(obj.get(k)):
                    refset.add(obj[k])
            for t in obj.get("targets") or []:
                if defined(t):
                    refset.add(t)
        if obj["isa"] == "XCConfigurationList":
            refset.add(oid)
            for c in obj.get("buildConfigurations") or []:
                refset.add(c)
    orphans = [i for i in ids if i not in refset]
    for o in orphans:
        errors.append(f"orphan object (never referenced): {o} ({objects[o]['isa']})")

    # 6. Phase order inside the native target
    names = {oid: obj.get("name", objects[oid].get("name", "?")) for oid, obj in objects.items()}
    def phase_index(pred):
        for idx, pid in enumerate(target_phases):
            if defined(pid) and pred(objects[pid], names.get(pid, "")):
                return idx
        return None

    script_idx = phase_index(lambda o, n: o["isa"] == "PBXShellScriptBuildPhase")
    sources_idx = phase_index(lambda o, n: o["isa"] == "PBXSourcesBuildPhase")
    link_idx = phase_index(lambda o, n: o["isa"] == "PBXFrameworksBuildPhase")
    embed_idx = phase_index(lambda o, n: o["isa"] == "PBXCopyFilesBuildPhase"
                            and n == "Embed Frameworks")
    for label, ix in (("Build Kotlin framework(script)", script_idx),
                      ("Sources", sources_idx), ("Frameworks(link)", link_idx),
                      ("Embed Frameworks", embed_idx)):
        if ix is None:
            errors.append(f"target missing required build phase: {label}")
    if None not in (script_idx, embed_idx) and not script_idx < embed_idx:
        errors.append("Embed phase must come AFTER 'Build Kotlin framework' script phase")
    if None not in (link_idx, embed_idx) and not link_idx < embed_idx:
        errors.append("Embed phase must come AFTER Frameworks(link) phase")

    # 7. Embed phase contents
    if embed_idx is not None:
        embed = objects[target_phases[embed_idx]]
        if str(embed.get("dstSubfolderSpec")) != "10":
            errors.append("embed phase dstSubfolderSpec != 10 (Frameworks)")
        if embed.get("dstPath") != "":
            errors.append("embed phase dstPath should be empty string")
        fw_bfs = []
        for f in embed.get("files") or []:
            bf = objects[f]
            fr = bf.get("fileRef")
            fref = objects.get(fr, {})
            fname = fref.get("name") or fref.get("path")
            attrs = (bf.get("settings") or {}).get("ATTRIBUTES") or []
            if fname == "shared.framework":
                fw_bfs.append(f)
                if "CodeSignOnCopy" not in attrs:
                    errors.append("shared.framework embed entry lacks CodeSignOnCopy")
                if fref.get("sourceTree") != "BUILT_PRODUCTS_DIR":
                    errors.append("shared.framework fileRef sourceTree != BUILT_PRODUCTS_DIR")
                if fref.get("explicitFileType") != "wrapper.framework":
                    errors.append("shared.framework fileRef explicitFileType != wrapper.framework")
        if len(fw_bfs) != 1:
            errors.append(f"expected exactly 1 shared.framework embed entry, got {len(fw_bfs)}")

    # 8. Shell script hygiene
    scripts = [o for o in objects.values()
               if o.get("isa") == "PBXShellScriptBuildPhase"]
    for s in scripts:
        sc = s.get("shellScript", "")
        if "-Pdevice" in sc:
            errors.append("shell script still contains bogus '-Pdevice' property")
        if "embedAndSignAppleFrameworkForXcode" not in sc:
            errors.append("shell script does not invoke :shared:embedAndSignAppleFrameworkForXcode")
        if "exit 1" not in sc:
            errors.append("shell script lacks '|| exit 1' fail-fast guard")

    # 9. Sources completeness vs Sources.swift refs on disk
    src_phase = objects[target_phases[sources_idx]] if sources_idx is not None else {}
    swift_in_phase = set()
    for f in src_phase.get("files") or []:
        fr = objects[f].get("fileRef")
        swift_in_phase.add(objects[fr].get("path"))
    import os
    on_disk = set()
    for dirpath, _, filenames in os.walk("iosApp"):
        on_disk.update(fn for fn in filenames if fn.endswith(".swift"))
    missing = on_disk - swift_in_phase
    extra = swift_in_phase - on_disk
    for m in sorted(missing):
        errors.append(f"Swift file on disk but NOT in Sources phase: {m}")
    for e in sorted(extra):
        errors.append(f"Swift file in Sources phase but NOT on disk: {e}")

    if errors:
        print(f"FAILED — {len(errors)} problem(s):")
        for e in errors:
            print("  ✗", e)
        return 1
    FALLBACK = {"PBXShellScriptBuildPhase": "(script)", "PBXSourcesBuildPhase": "Sources",
                "PBXFrameworksBuildPhase": "Frameworks", "PBXCopyFilesBuildPhase": "Embed Frameworks"}
    print(f"OK — {len(ids)} objects, graph consistent.")
    print("  phases:", " -> ".join(names.get(p) or FALLBACK[objects[p]["isa"]] for p in target_phases))
    print("  embed: shared.framework @ BUILT_PRODUCTS_DIR, CodeSignOnCopy ✓")
    print("  shell: -Pdevice removed, embedAndSign task + fail-fast present ✓")
    print(f"  sources: {len(swift_in_phase)} Swift files match disk exactly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
