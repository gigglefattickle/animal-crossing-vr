#!/usr/bin/env python3
"""Regression checks for RVZ support in the standalone Quest Android build."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class QuestRvzIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pc_cmake = read("pc/CMakeLists.txt")
        cls.quest_cmake = read("quest/CMakeLists.txt")
        cls.disc = read("pc/src/pc_disc.c")
        cls.reader = read("quest/rvz/rvz_reader.cpp")
        cls.setup = read("quest/android/java/com/liquidazir/animalcrossingquest/SetupActivity.java")

    def test_android_game_build_compiles_rvz_reader(self):
        self.assertIn("${DECOMP_ROOT}/quest/rvz/rvz_reader.cpp", self.pc_cmake)
        self.assertIn("${CMAKE_CURRENT_SOURCE_DIR}/src/pc_disc.c", self.pc_cmake)
        self.assertIn('add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../pc" "game")', self.quest_cmake)

    def test_android_build_fetches_and_links_static_zstd(self):
        self.assertIn("if(ANDROID)\n    set(ACVR_FETCH_ZSTD ON)", self.pc_cmake)
        self.assertIn("ZSTD_BUILD_SHARED OFF", self.pc_cmake)
        self.assertIn("target_link_libraries(ac_pc SDL2::SDL2 openxr_loader ${ACVR_ZSTD_TARGET}", self.pc_cmake)

    def test_native_disc_loader_routes_rvz_reads(self):
        self.assertIn('memcmp(hdr, "RVZ\\1", 4)', self.disc)
        self.assertIn("acvr_rvz_open(path)", self.disc)
        self.assertIn("acvr_rvz_read(df->rvz_handle, offset, dest, size)", self.disc)
        self.assertIn("acvr_rvz_close(df->rvz_handle)", self.disc)

    def test_rvz_reader_exports_c_api_and_uses_zstd(self):
        self.assertIn("#include <zstd.h>", self.reader)
        for symbol in ("acvr_rvz_open", "acvr_rvz_read", "acvr_rvz_size", "acvr_rvz_close"):
            self.assertIn(symbol, self.reader)

    def test_android_imports_and_launches_rvz_from_private_storage(self):
        self.assertIn('lower.endsWith(".rvz")', self.setup)
        self.assertIn('extension = ".rvz"', self.setup)
        # SetupActivity validates the four-byte RVZ signature as individual bytes.
        for check in ("containerMagic[0] != 'R'", "containerMagic[1] != 'V'", "containerMagic[2] != 'Z'", "containerMagic[3] != 1"):
            self.assertIn(check, self.setup)
        self.assertIn("GAFE01", self.setup)
        self.assertIn('getFilesDir(), "rom"', self.setup)

    def test_native_loader_searches_android_private_rom_directory(self):
        self.assertIn("SDL_AndroidGetInternalStoragePath()", self.disc)
        self.assertIn('"%s/rom"', self.disc)
        self.assertIn('".rvz"', self.disc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
