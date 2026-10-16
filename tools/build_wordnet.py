#!/usr/bin/env python3
"""Compacts a Princeton WordNet 3.1 'dict' directory into Readle's asset format.

Usage: python3 -I tools/build_wordnet.py <wordnet dict dir> <output dir>

Writes  wordnet.bin      (lemma index + sense records, big-endian, read with random access)
        wordnet-exc.txt  (irregular forms: "<pos>\t<inflected>\t<base>")

wordnet.bin layout
  "RLWN" | int version(1) | int lemmaCount | int senseBase
  lemmaCount x int  : absolute offset of each lemma record, sorted by lemma bytes (unsigned)
  lemma record      : u8 keyLen | key (utf-8, lower case, '_' for spaces) | u8 senseCount | senseCount x int sense offset
  sense record      : u8 pos (n v a r) | u16 len | utf-8 text  = synonyms '|'-joined \\t definition [\\t example]*
Senses are shared between lemmas of one synset, so each synset is stored once.
"""
import os, re, struct, sys

POS = {"noun": "n", "verb": "v", "adj": "a", "adv": "r"}
MAX_EXAMPLES, MAX_SYNONYMS = 2, 6

def parse_data(path, pos):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("  "):
                continue
            head, _, gloss = line.partition("|")
            parts = head.split()
            offset, wcnt = parts[0], int(parts[3], 16)
            words = []
            for i in range(wcnt):
                w = re.sub(r"\((a|ip|p)\)$", "", parts[4 + 2 * i])
                words.append(w.replace("_", " "))
            gloss = gloss.strip()
            m = re.search(r';\s*"', gloss)
            definition = (gloss[:m.start()] if m else gloss).strip()
            examples = re.findall(r'"([^"]*)"', gloss[m.start():]) if m else []
            out[offset] = (pos, words, definition, examples)
    return out

def main(src, dst):
    senses = {}  # (pos, offset) -> record
    for name, p in POS.items():
        for off, rec in parse_data(os.path.join(src, "data." + name), p).items():
            senses[(p, off)] = rec

    lemmas = {}  # key -> [(pos, offset)]
    for name, p in POS.items():
        with open(os.path.join(src, "index." + name), encoding="utf-8") as f:
            for line in f:
                if line.startswith("  "):
                    continue
                t = line.split()
                lemma, sc, ptrs = t[0], int(t[2]), int(t[3])
                offs = t[6 + ptrs:6 + ptrs + sc]
                lemmas.setdefault(lemma.lower(), []).extend((p, o) for o in offs)

    body = bytearray()
    sense_pos = {}
    for key, rec in senses.items():
        pos, words, definition, examples = rec
        text = "|".join(words[:MAX_SYNONYMS]) + "\t" + definition
        for e in examples[:MAX_EXAMPLES]:
            text += "\t" + e
        b = text.encode("utf-8")
        assert len(b) < 65536
        sense_pos[key] = len(body)
        body += pos.encode() + struct.pack(">H", len(b)) + b

    keys = sorted(lemmas, key=lambda k: k.encode("utf-8"))
    n = len(keys)
    header = 16 + 4 * n
    lemma_blob, offs = bytearray(), []
    for k in keys:
        kb = k.encode("utf-8")
        assert len(kb) < 256
        refs = lemmas[k][:255]
        offs.append(len(lemma_blob))
        lemma_blob += bytes([len(kb)]) + kb + bytes([len(refs)])
        # sense offsets are relative to senseBase; patched below once its position is known
        for r in refs:
            lemma_blob += struct.pack(">i", sense_pos[r])
    sense_base = header + len(lemma_blob)
    with open(os.path.join(dst, "wordnet.bin"), "wb") as f:
        f.write(b"RLWN" + struct.pack(">iii", 1, n, sense_base))
        for o in offs:
            f.write(struct.pack(">i", header + o))
        f.write(lemma_blob)
        f.write(body)

    with open(os.path.join(dst, "wordnet-exc.txt"), "w", encoding="utf-8") as out:
        for name, p in POS.items():
            with open(os.path.join(src, name + ".exc"), encoding="utf-8") as f:
                for line in f:
                    t = line.split()
                    if len(t) >= 2:
                        out.write(f"{p}\t{t[0]}\t{t[1]}\n")
    print(f"{n} lemmas, {len(senses)} synsets, wordnet.bin {os.path.getsize(os.path.join(dst,'wordnet.bin'))} bytes")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
