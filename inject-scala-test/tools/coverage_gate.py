#!/usr/bin/env python3
"""Coverage gate for the Scala parity port.

Given one or more candidate topics, report every existing Scala spec that already
exercises them, so a batch is not ported over coverage that is already there.

Two independent signals, because a feature name alone is not enough:
  * feature-name match  -- the topic words appear in a spec's feature name
  * source-usage match  -- the topic's API or annotation appears in a spec body

Usage: coverage_gate.py <topic> [<topic> ...]
A topic is "label=needle[,needle...]".
"""
import re
import sys
from pathlib import Path

SPEC_DIR = Path.home() / "dev/micronaut/scala/inject-scala-test/src/test/groovy/io/micronaut/scala/processing"
FEATURE = re.compile(r"^\s*void\s+['\"](.+?)['\"]\s*\(\s*\)", re.M)


def specs():
    for path in sorted(SPEC_DIR.glob("*.groovy")):
        yield path, path.read_text()


def main(topics):
    corpus = list(specs())
    total = sum(len(FEATURE.findall(text)) for _, text in corpus)
    print(f"inventory: {len(corpus)} specs, {total} feature methods\n")
    verdict = 0
    for topic in topics:
        label, _, needles = topic.partition("=")
        needles = [n for n in needles.split(",") if n]
        name_hits, usage_hits = [], []
        for path, text in corpus:
            for feature in FEATURE.findall(text):
                if any(n.lower() in feature.lower() for n in needles):
                    name_hits.append(f"{path.name}: {feature}")
            if any(n in text for n in needles):
                usage_hits.append(path.name)
        if name_hits or usage_hits:
            verdict = 1
            print(f"[COVERED] {label}")
            for hit in name_hits[:4]:
                print(f"    name  | {hit}")
            for hit in sorted(set(usage_hits))[:4]:
                print(f"    usage | {hit}")
        else:
            print(f"[NEW]     {label}")
        print()
    return verdict


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
