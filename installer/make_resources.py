"""Create a minimal Windows AMD64 COFF resource object (no external compiler).
Embeds the project ICO and asInvoker application manifest. Resource payload RVAs
use IMAGE_REL_AMD64_ADDR32NB relocations resolved by the Go PE linker.
"""
from pathlib import Path
import struct

root = Path(__file__).resolve().parent
ico = (root / 'app.ico').read_bytes()
reserved, kind, count = struct.unpack_from('<HHH', ico)
assert reserved == 0 and kind == 1
resources = {}
group = bytearray(struct.pack('<HHH', 0, 1, count))
for i in range(count):
    width, height, colors, zero, planes, bits, size, offset = struct.unpack_from('<BBBBHHII', ico, 6 + i * 16)
    resources[(3, i + 1, 1033)] = ico[offset:offset + size]
    group.extend(struct.pack('<BBBBHHIH', width, height, colors, zero, planes, bits, size, i + 1))
resources[(14, 1, 1033)] = bytes(group)
resources[(24, 1, 1033)] = (root / 'app.manifest').read_bytes()
tree = {}
for (kind, name, lang), payload in sorted(resources.items()):
    tree.setdefault(kind, {}).setdefault(name, {})[lang] = payload
section = bytearray()
relocs = []

def align():
    while len(section) % 4: section.append(0)

def directory(entries):
    offset = len(section)
    section.extend(struct.pack('<IIHHHH', 0, 0, 0, 0, 0, len(entries)))
    table = len(section)
    section.extend(bytes(8 * len(entries)))
    for i, (key, value) in enumerate(sorted(entries.items())):
        if isinstance(value, dict):
            target = directory(value) | 0x80000000
        else:
            align()
            target = len(section)
            section.extend(bytes(16))
            align()
            data_offset = len(section)
            section.extend(value)
            struct.pack_into('<IIII', section, target, data_offset, len(value), 0, 0)
            relocs.append(target)
            align()
        struct.pack_into('<II', section, table + i * 8, key, target)
    return offset

assert directory(tree) == 0
raw_start = 20 + 40
reloc_start = raw_start + len(section)
symbol_start = reloc_start + len(relocs) * 10
header = struct.pack('<HHIIIHH', 0x8664, 1, 0, symbol_start, 1, 0, 0)
section_header = struct.pack('<8sIIIIIIHHI', b'.rsrc', 0, 0, len(section), raw_start, reloc_start, 0, len(relocs), 0, 0x40000040)
relocation_data = b''.join(struct.pack('<IIH', offset, 0, 3) for offset in relocs)
symbol = struct.pack('<8sIhHBB', b'.rsrc', 0, 1, 0, 3, 0)
(root / 'resource_windows_amd64.syso').write_bytes(header + section_header + section + relocation_data + symbol + struct.pack('<I', 4))
print(f'Embedded {count} icon sizes and manifest')
