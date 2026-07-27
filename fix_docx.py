import zipfile
import xml.etree.ElementTree as ET
import sys

sys.stdout.reconfigure(encoding='utf-8')

W_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
XML_SPACE = '{http://www.w3.org/XML/1998/namespace}space'

def W(tag):
    return '{' + W_NS + '}' + tag

def get_text(el):
    return ''.join(t.text for t in el.findall('.//' + W('t')) if t.text)

INPUT  = 'cahier-des-charges-v3 (1).docx'
OUTPUT = 'cahier-des-charges-v3 (1).docx'

with zipfile.ZipFile(INPUT, 'r') as z:
    doc_xml    = z.read('word/document.xml')
    files_data = {n: z.read(n) for n in z.namelist() if n != 'word/document.xml'}

root = ET.fromstring(doc_xml)
body = root.find(W('body'))

def get_children():
    return list(body)

def idx_of_after(keyword, start):
    """Search for keyword starting strictly after 'start'."""
    for i, el in enumerate(get_children()):
        if i <= start:
            continue
        if keyword in get_text(el):
            return i
    return None

# ─────────────────────────────────────────────────────────────────────────────
# 1. Find section 11 heading
# ─────────────────────────────────────────────────────────────────────────────
start_11 = None
for i, el in enumerate(get_children()):
    txt = get_text(el)
    if '11. Evolution from the Initial GitHub Actions Design' in txt:
        # Only pick the REAL heading (not the TOC entry which is short)
        if len(txt) < 80:  # TOC entries are short, body headings are too
            start_11 = i
            print(f'Found section 11 heading at index {i}: {txt[:80]}')
            break

if start_11 is None:
    # fallback: pick last occurrence
    for i, el in enumerate(get_children()):
        if '11. Evolution from the Initial GitHub Actions Design' in get_text(el):
            start_11 = i
    print(f'Fallback: section 11 at {start_11}')

# ─────────────────────────────────────────────────────────────────────────────
# 2. Find section 12 heading AFTER section 11
# ─────────────────────────────────────────────────────────────────────────────
start_12 = idx_of_after('12. Conclusion', start_11) if start_11 else None
print(f'Section 12 (after s11) at index {start_12}')

# ─────────────────────────────────────────────────────────────────────────────
# 3. Remove everything between s11 and s12
# ─────────────────────────────────────────────────────────────────────────────
if start_11 is not None and start_12 is not None and start_12 > start_11:
    to_remove = get_children()[start_11:start_12]
    for el in to_remove:
        body.remove(el)
    print(f'Removed {len(to_remove)} elements (section 11 complete).')
else:
    print(f'ERROR: invalid range s11={start_11} s12={start_12}')

# Also remove "11. Evolution..." from TOC if it still exists
removed_toc = 0
for el in list(body):
    txt = get_text(el)
    if '11. Evolution from the Initial GitHub Actions Design' in txt and len(txt) < 80:
        body.remove(el)
        removed_toc += 1
print(f'Removed {removed_toc} TOC entries for section 11.')

# ─────────────────────────────────────────────────────────────────────────────
# Write
# ─────────────────────────────────────────────────────────────────────────────
for prefix, uri in [
    ('w',   W_NS),
    ('r',   'http://schemas.openxmlformats.org/officeDocument/2006/relationships'),
    ('m',   'http://schemas.openxmlformats.org/officeDocument/2006/math'),
    ('mc',  'http://schemas.openxmlformats.org/markup-compatibility/2006'),
    ('wp',  'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'),
    ('w14', 'http://schemas.microsoft.com/office/word/2010/wordml'),
    ('w15', 'http://schemas.microsoft.com/office/word/2012/wordml'),
    ('wne', 'http://schemas.microsoft.com/office/word/2006/wordml'),
]:
    ET.register_namespace(prefix, uri)

new_doc_xml = ET.tostring(root, encoding='utf-8', xml_declaration=True)

with zipfile.ZipFile(OUTPUT, 'w', zipfile.ZIP_DEFLATED) as z_out:
    for name, content in files_data.items():
        z_out.writestr(name, content)
    z_out.writestr('word/document.xml', new_doc_xml)

# Verify
with zipfile.ZipFile(OUTPUT, 'r') as z:
    tree = ET.fromstring(z.read('word/document.xml'))
txt = ''.join(tree.itertext())
print('\n--- Verification ---')
print('Section 11 heading present?', '11. Evolution from the Initial GitHub Actions Design' in txt)
print('11.1 Why the Change Happened?', '11.1 Why the Change Happened' in txt)
print('11.2 What Changed?', '11.2 What Changed' in txt)
print('12. Conclusion present?', '12. Conclusion' in txt)
print('Total body children:', len(list(tree.find(W('body')))))
print(f'\nDone → {OUTPUT}')
