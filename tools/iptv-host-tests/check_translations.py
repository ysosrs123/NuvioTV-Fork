import re, sys, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
ENGLISH = RES / 'values'
FILES = sorted(p.name for p in ENGLISH.glob('iptv_*strings.xml'))


def entries(path):
    root = ET.parse(path).getroot()
    out = {}
    for e in root:
        if e.get('translatable') == 'false':
            continue
        if e.tag == 'string':
            out[e.get('name')] = ('s', ''.join(e.itertext()))
        elif e.tag == 'plurals':
            out[e.get('name')] = ('p', {i.get('quantity'): ''.join(i.itertext()) for i in e})
    return out, path.read_text(encoding='utf-8')


def placeholders(text):
    return sorted(re.findall(r'%\d\$[sd]', text))


def main(locales):
    locales = locales or sorted(p.name[len('values-'):] for p in RES.glob('values-*')
                                if (p / 'iptv_strings.xml').exists())
    main_names = set(entries(ENGLISH / 'strings.xml')[0])
    bad = 0
    for loc in locales:
        for name in FILES:
            src, _ = entries(ENGLISH / name)
            path = RES / f'values-{loc}' / name
            if not path.exists():
                print(loc, name, 'MISSING'); bad += 1; continue
            try:
                tr, raw = entries(path)
            except Exception as error:
                print(loc, name, 'XML', error); bad += 1; continue
            diff = (set(src) - set(tr)) | (set(tr) - set(src) - main_names)
            if diff:
                print(loc, name, 'names differ', diff); bad += 1
            if re.search(r"(?<!\\)'", re.sub(r'<[^>]+>', '', raw)):
                print(loc, name, 'unescaped apostrophe'); bad += 1
            if '<!--' in raw:
                print(loc, name, 'comment'); bad += 1
            for key, (kind, value) in src.items():
                if key not in tr:
                    continue
                _, translated = tr[key]
                if kind == 's':
                    if placeholders(value) != placeholders(translated):
                        print(loc, name, key, 'placeholders'); bad += 1
                    if not translated.strip():
                        print(loc, name, key, 'empty'); bad += 1
                else:
                    if 'other' not in translated:
                        print(loc, name, key, 'no other'); bad += 1
                    elif placeholders(translated['other']) != placeholders(value['other']):
                        print(loc, name, key, 'plural placeholders'); bad += 1
    print('problems', bad)
    return bad


if __name__ == '__main__':
    sys.exit(1 if main(sys.argv[1:]) else 0)
