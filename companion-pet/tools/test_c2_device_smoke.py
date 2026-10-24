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


if __name__ == "__main__":
    unittest.main()
