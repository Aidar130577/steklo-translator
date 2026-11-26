#!/usr/bin/env python3
"""Normalize the FreeDict TEI source archives into licensed dictionary shards.
This does not install anything in the app or touch live Supabase.
"""
import hashlib, json, os, re, tarfile, tempfile, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict

VERSION="2025.11.23"
PAIRS={"zho-rus":"zh","eng-rus":"en"}
OUT=Path("dictionary-export")
NS={"t":"http://www.tei-c.org/ns/1.0"}
RU=re.compile(r"[\u0400-\u04ff]")
HAN=re.compile(r"[\u3400-\u9fff]")
LAT=re.compile(r"[A-Za-z]")
def norm(s): return " ".join(s.split())
def txt(el): return norm("".join(el.itertext()))
def download(url):
    with urllib.request.urlopen(url,timeout=90) as response:
        data=response.read(120*1024*1024+1)
    if len(data)>120*1024*1024: raise ValueError("archive too large")
    return data
def parse_tei(file,lang):
    entries=defaultdict(set)
    root=ET.parse(file).getroot()
    availability=root.find(".//t:teiHeader//t:availability",NS)
    if availability is None: raise ValueError("source missing license header")
    license_text=txt(availability)
    license_refs=[x.get("target","") for x in availability.iter() if x.get("target")]
    evidence=" ".join([license_text]+license_refs)
    if not re.search(r"(?i)(creativecommons|creative commons|cc.by.sa|general public license|gpl|gfdl|free documentation license)",evidence):
        raise ValueError("Unrecognized license: "+evidence[:500])
    for entry in root.findall(".//t:body//t:entry",NS):
        headwords={txt(x) for x in entry.findall("./t:form/t:orth",NS)}
        # Only explicit translation equivalents, not descriptions or example sentences.
        trans=set()
        for cit in entry.findall(".//t:cit",NS):
            if cit.get("type") not in {"trans","translation"}: continue
            for quote in cit.findall("./t:quote",NS):
                value=txt(quote)
                if value and len(value)<=1000 and RU.search(value) and not HAN.search(value):
                    trans.add(value)
        for word in headwords:
            if not word or len(word)>160 or not trans: continue
            if lang=="zh" and not HAN.search(word): continue
            if lang=="en" and not LAT.search(word): continue
            entries[word].update(trans)
    return entries,license_text,license_refs
def main():
    OUT.mkdir(exist_ok=True)
    manifest={"version":VERSION,"sources":[]}
    for pair,lang in PAIRS.items():
        name=f"freedict-{pair}-{VERSION}.src.tar.xz"
        url=f"https://download.freedict.org/dictionaries/{pair}/{VERSION}/{name}"
        archive=download(url)
        checksum=download(url+".sha512").decode("ascii").strip()
        expected=re.search(r"\b[0-9a-fA-F]{128}\b",checksum).group(0).lower()
        if hashlib.sha512(archive).hexdigest()!=expected: raise ValueError("Checksum mismatch "+pair)
        with tempfile.TemporaryFile() as f:
            f.write(archive);f.seek(0)
            with tarfile.open(fileobj=f,mode="r:xz") as tar:
                candidates=[m for m in tar.getmembers() if m.isfile() and m.name.endswith(".tei")]
                if len(candidates)!=1: raise ValueError("Expected one TEI file")
                entries,lic,refs=parse_tei(tar.extractfile(candidates[0]),lang)
        if len(entries)<10000: raise ValueError("Unexpectedly few entries: "+str(len(entries)))
        rows=[]
        for word,vals in sorted(entries.items()):
            options=sorted(vals)
            value="; ".join(options)
            if len(value)>4000: continue
            rows.append({"source_text":word,"russian_text":value,"alternatives":len(options)})
        chunks=[]
        for i in range(0,len(rows),2000):
            name=f"{pair}-{i//2000:04}.json"
            data=json.dumps(rows[i:i+2000],ensure_ascii=False,separators=(",",":")).encode()
            (OUT/name).write_bytes(data)
            chunks.append({"file":name,"rows":len(rows[i:i+2000]),"sha256":hashlib.sha256(data).hexdigest()})
        manifest["sources"].append({"pair":pair,"lang":lang,"source_id":"freedict_"+pair+"_"+VERSION,"url":url,"sha512":expected,"license":lic,"license_refs":refs,"rows":len(rows),"chunks":chunks})
        print(pair,len(rows),"license",lic[:500],refs)
    (OUT/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
    (OUT/"README.md").write_text("Derived dictionary data from FreeDict / WikDict / Wiktionary. Original source archives and exact license notices are listed in manifest.json. Normalized translation equivalents; multiple meanings preserved; no guarantee of contextual correctness. Redistribution subject to source license notices.\n",encoding="utf-8")
if __name__=="__main__":main()
