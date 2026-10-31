#!/usr/bin/env python3
"""Compare a saved AIBuild model with flushed Java Anvil world blocks (read-only)."""

import argparse
import gzip
import io
import json
import struct
import zlib
from pathlib import Path


class Nbt:
    def __init__(self, raw):
        self.stream = io.BytesIO(raw)

    def number(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.stream.read(size))[0]

    def string(self):
        return self.stream.read(self.number(">H")).decode("utf-8")

    def value(self, tag):
        formats = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
        if tag in formats:
            return self.number(formats[tag])
        if tag == 7:
            return self.stream.read(self.number(">i"))
        if tag == 8:
            return self.string()
        if tag == 9:
            kind, size = self.number(">B"), self.number(">i")
            return [self.value(kind) for _ in range(size)]
        if tag == 10:
            result = {}
            while (kind := self.number(">B")) != 0:
                name = self.string()
                result[name] = self.value(kind)
            return result
        if tag in {11, 12}:
            return [self.number(">i" if tag == 11 else ">q") for _ in range(self.number(">i"))]
        raise ValueError(f"Unsupported NBT tag {tag}")

    def root(self):
        tag = self.number(">B")
        self.string()
        return self.value(tag)


class World:
    def __init__(self, path):
        if not (path / "region").is_dir():
            path = path / "dimensions/minecraft/overworld"
        self.path, self.cache = path, {}

    def chunk(self, cx, cz):
        key = cx, cz
        if key in self.cache:
            return self.cache[key]
        path = self.path / "region" / f"r.{cx // 32}.{cz // 32}.mca"
        with path.open("rb") as stream:
            stream.seek(4 * ((cx % 32) + 32 * (cz % 32)))
            location = int.from_bytes(stream.read(4), "big")
            if not location >> 8:
                raise ValueError(f"Chunk {key} is not saved")
            stream.seek((location >> 8) * 4096)
            size = int.from_bytes(stream.read(4), "big")
            compression = stream.read(1)[0]
            raw = stream.read(size - 1)
        if compression == 1:
            raw = gzip.decompress(raw)
        elif compression == 2:
            raw = zlib.decompress(raw)
        elif compression != 3:
            raise ValueError(f"Unsupported chunk compression {compression}")
        chunk = Nbt(raw).root()
        sections = {section["Y"]: section for section in chunk["sections"]}
        self.cache[key] = sections
        return sections

    def block(self, x, y, z):
        section = self.chunk(x // 16, z // 16).get(y // 16)
        if not section or "block_states" not in section:
            return {"Name": "minecraft:air"}
        states = section["block_states"]
        palette = states["palette"]
        if len(palette) == 1:
            return self.state(palette[0])
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        index = (y % 16) * 256 + (z % 16) * 16 + x % 16
        value = states["data"][index // per_long] & ((1 << 64) - 1)
        return self.state(palette[(value >> ((index % per_long) * bits)) & ((1 << bits) - 1)])

    @staticmethod
    def state(entry):
        if isinstance(entry, str):
            entry = {"Name": entry}
        name = entry.get("Name", entry.get("id", entry.get("")))
        properties = dict(entry.get("Properties", entry.get("properties", {})))
        if name.endswith("_stairs") or name in {"minecraft:campfire", "minecraft:soul_campfire"}:
            properties.setdefault("facing", "north")
        return {"Name": name, "Properties": properties}


def rotate_state(state, turns):
    name, _, rest = state.partition("[")
    properties = dict(item.split("=", 1) for item in rest.removesuffix("]").split(",") if item)
    directions = ["north", "east", "south", "west"]
    rotated = {}
    for key, value in properties.items():
        if key == "facing" and value in directions:
            value = directions[(directions.index(value) + turns) % 4]
        elif key == "axis" and turns % 2 and value in {"x", "z"}:
            value = "z" if value == "x" else "x"
        elif key == "rotation":
            value = str((int(value) + 4 * turns) % 16)
        if key in directions:
            key = directions[(directions.index(key) + turns) % 4]
        rotated[key] = value
    return name.removeprefix("minecraft:"), rotated


def verify(server, build_id):
    with gzip.open(server / "plugins/AIBuild/builds" / f"{build_id}.json.gz", "rt") as stream:
        build = json.load(stream)
    model = build["versions"][build["currentVersion"]]["model"]
    world = World(server / build["worldName"])
    palette = [rotate_state(state, build["rotation"]) for state in model["palette"]]
    cells = model["cells"]
    mismatches, total = [], 0
    for index in range(0, len(cells), 4):
        x, y, z, state_index = cells[index:index + 4]
        for _ in range(build["rotation"]):
            x, z = -z, x
        x, y, z = x + build["originX"], y + build["originY"], z + build["originZ"]
        name, props = palette[state_index]
        actual = world.block(x, y, z)
        same_name = actual["Name"].removeprefix("minecraft:") == name
        same_props = all(actual.get("Properties", {}).get(key) == value for key, value in props.items())
        total += 1
        if not same_name or not same_props:
            mismatches.append({"pos": [x, y, z], "expected": model["palette"][state_index], "actual": actual})
    return {"buildId": build_id, "checked": total, "matched": total - len(mismatches), "mismatches": mismatches}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("build_id")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(args.server, args.build_id)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"{result['buildId']}: {result['matched']}/{result['checked']} saved world blocks match the generated model")
    for mismatch in result["mismatches"][:12]:
        print(json.dumps(mismatch))


if __name__ == "__main__":
    main()
