"""Add one exact package query while retaining all existing binary XML node bytes."""
import struct

HELPER = "dev.yossi.morningsesame"
ANDROID = "http://schemas.android.com/apk/res/android"
NONE = 0xffffffff


def chunks(data, start=8):
    offset = start
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if header < 8 or size < header or offset + size > len(data):
            raise ValueError("invalid_xml_chunk")
        yield kind, data[offset:offset + size]
        offset += size
    if offset != len(data):
        raise ValueError("invalid_xml_end")


def strings(pool):
    count, styles, flags, start, style_start = struct.unpack_from("<IIIII", pool, 8)
    if styles or style_start or flags != 0 or struct.unpack_from("<H",pool,2)[0] != 28:
        raise ValueError("unexpected_manifest_string_pool")
    result = []
    for i in range(count):
        offset = start + struct.unpack_from("<I", pool, 28 + i * 4)[0]
        length = struct.unpack_from("<H", pool, offset)[0]
        if length & 0x8000:
            raise ValueError("unexpected_long_manifest_string")
        end = offset + 2 + length * 2
        if pool[end:end + 2] != b"\0\0":
            raise ValueError("invalid_manifest_string")
        result.append(pool[offset + 2:end].decode("utf-16le"))
    return result


def append_strings(pool, values):
    current = strings(pool)
    count, _, _, start, _ = struct.unpack_from("<IIIII", pool, 8)
    if start != 28 + count * 4:
        raise ValueError("unexpected_manifest_string_offsets")
    offsets = list(struct.unpack_from("<" + "I" * count, pool, 28))
    payload = bytearray(pool[start:])
    for value in values:
        if value in current:
            continue
        offsets.append(len(payload)); current.append(value)
        encoded = value.encode("utf-16le")
        payload.extend(struct.pack("<H", len(encoded) // 2) + encoded + b"\0\0")
    new_start = 28 + len(current) * 4
    while (new_start + len(payload)) % 4:
        payload.append(0)
    size = new_start + len(payload)
    result = (struct.pack("<HHIIIIII",1,28,size,len(current),0,0,new_start,0)
              + struct.pack("<" + "I" * len(offsets), *offsets) + payload)
    if strings(result)[:count] != strings(pool):
        raise ValueError("original_manifest_strings_changed")
    return bytes(result), current


def start_element(name, attributes=()):
    return (struct.pack("<HHIII",0x102,16,36 + 20 * len(attributes),7,NONE)
        + struct.pack("<IIHHHHHH",NONE,name,20,20,len(attributes),0,0,0)
        + b"".join(attributes))


def end_element(name):
    return struct.pack("<HHIIIII",0x103,16,24,7,NONE,NONE,name)


def patch_manifest(data):
    if struct.unpack_from("<HHI",data) != (3,8,len(data)):
        raise ValueError("unexpected_binary_manifest")
    original = list(chunks(data))
    if original[0][0] != 1:
        raise ValueError("manifest_pool_not_first")
    old_strings = strings(original[0][1])
    if "queries" in old_strings:
        raise ValueError("unexpected_existing_queries")
    pool, names = append_strings(original[0][1], ["queries","package",HELPER])
    query_name, package_name, helper = (names.index(n) for n in ("queries","package",HELPER))
    attribute = struct.pack("<IIIHBBI",names.index(ANDROID),names.index("name"),helper,8,0,3,helper)
    additions = [start_element(query_name),start_element(package_name,[attribute]),
                 end_element(package_name),end_element(query_name)]
    output = [pool]; inserted = False; retained = []
    for kind, chunk in original[1:]:
        output.append(chunk); retained.append(chunk)
        if kind == 0x102 and old_strings[struct.unpack_from("<I",chunk,20)[0]] == "manifest":
            if inserted:
                raise ValueError("duplicate_manifest_root")
            output.extend(additions); inserted = True
    if not inserted:
        raise ValueError("manifest_root_missing")
    result = struct.pack("<HHI",3,8,8 + sum(map(len,output))) + b"".join(output)
    actual = list(chunks(result))
    remainder = [chunk for _,chunk in actual[1:] if chunk not in additions]
    if remainder != retained or strings(actual[0][1])[:len(old_strings)] != old_strings:
        raise ValueError("unowned_manifest_nodes_changed")
    if HELPER not in strings(actual[0][1]):
        raise ValueError("helper_visibility_missing")
    return result
