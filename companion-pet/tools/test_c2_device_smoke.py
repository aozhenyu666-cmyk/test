"""Offline checks for c2_device_smoke.find_tap_point (no device needed): python tools/test_c2_device_smoke.py"""
import os
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import c2_device_smoke as smoke  # noqa: E402

COMPOSE_TABS = """<hierarchy rotation="0">
  <node index="0" text="" class="android.view.View" clickable="false" bounds="[0,0][1260,2800]">
    <node index="1" text="" class="android.view.View" clickable="true" bounds="[630,380][945,550]">
      <node index="0" text="记忆" class="android.widget.TextView" clickable="false" bounds="[740,440][835,490]"/>
    </node>
    <node index="2" text="" content-desc="统计" class="android.view.View" clickable="true" bounds="[945,380][1260,550]"/>
  </node>
</hierarchy>"""


class FindTapPointTest(unittest.TestCase):
    def setUp(self):
        self.root = ET.fromstring(COMPOSE_TABS)

    def test_label_inside_clickable_tab_taps_the_tab(self):
        self.assertEqual(smoke.find_tap_point(self.root, "记忆"), (787, 465))

    def test_content_description_matches(self):
        self.assertEqual(smoke.find_tap_point(self.root, "统计"), (1102, 465))

    def test_missing_label(self):
        self.assertIsNone(smoke.find_tap_point(self.root, "设置"))


class LookFlowHelpersTest(unittest.TestCase):
    def test_synthetic_png_is_valid(self):
        import struct
        import zlib
        data = smoke.synthetic_png(40, 60)
        self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))
        w, h = struct.unpack(">II", data[16:24])
        self.assertEqual((w, h), (40, 60))
        idat_len = struct.unpack(">I", data[33:37])[0]
        raw = zlib.decompress(data[41:41 + idat_len])
        self.assertEqual(len(raw), 60 * (1 + 40 * 3))
        # 左上角是红底
        self.assertEqual(raw[1:4], b"\xd2\x1e\x1e")

    def test_classify_separates_look_result_from_chat_bubble(self):
        # 0.5.4 实测：普通搭话气泡被当成看图结果。现在只认“我看了一眼：”开头。
        chat = ["这页用七种语言反复说“我不是服务”，你在找什么？", "打字", "去 Gemini"]
        self.assertEqual(smoke.classify_look(chat), (None, None))
        self.assertEqual(smoke.classify_look(["我看了一眼：红底上有白圆和蓝色方块"])[0], "LOOK_RESULT")
        self.assertEqual(smoke.classify_look(["截到了，但看图还没配好：dashscope 需要它自己的 Key"])[0], "NOT_CONFIGURED")
        self.assertEqual(smoke.classify_look(["截图成功，但视觉模型调用失败：API Key 不对"])[0], "REQUEST_FAILED")

    def test_keywords(self):
        self.assertEqual(smoke.keyword_hits("红色背景，一个白色圆形和蓝色方块"), {"color": True, "shape": True})
        self.assertEqual(smoke.keyword_hits("一个网页"), {"color": False, "shape": False})

    def test_parse_vision_evidence_keeps_only_whitelisted_fields(self):
        xml = ('<?xml version="1.0" encoding="utf-8"?><map><string name="last">'
               '{&quot;at&quot;:1700000000000,&quot;outcome&quot;:&quot;OK&quot;,&quot;imageBlocks&quot;:1,'
               '&quot;imageBytes&quot;:183000,&quot;imageSha256&quot;:&quot;abcdef123456&quot;,&quot;extra&quot;:&quot;x&quot;}'
               '</string></map>')
        e = smoke.parse_vision_evidence(xml)
        self.assertEqual(e["outcome"], "OK")
        self.assertEqual(e["imageBlocks"], 1)
        self.assertNotIn("extra", e)
        self.assertIsNone(smoke.parse_vision_evidence("run-as: package not debuggable"))


if __name__ == "__main__":
    unittest.main()
