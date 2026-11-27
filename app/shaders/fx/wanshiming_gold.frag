#version 460 core

#include <flutter/runtime_effect.glsl>

precision highp float;

uniform vec2 uSize;
uniform float uTime;
uniform float uPulse;

out vec4 fragColor;

float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash21(i);
  float b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0));
  float d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
  float v = 0.0;
  float a = 0.5;
  mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
  for (int i = 0; i < 4; i++) {
    v += a * noise(p);
    p = rot * p * 2.04 + vec2(1.7, 9.2);
    a *= 0.5;
  }
  return v;
}

void main() {
  vec2 res = max(uSize, vec2(1.0));
  vec2 uv = FlutterFragCoord().xy / res;
  vec2 p = (uv - 0.5) * vec2(res.x / res.y, 1.0);

  // Subtle flowing obsidian ink and antique gold metallic silk waves
  float t = uTime * 0.14;
  float q = fbm(p * 2.3 + vec2(t, -t * 0.7));
  float r = fbm(p * 3.1 + vec2(q * 1.4 - t * 0.5, q * 0.9 + t * 0.4));

  // Brushed metallic gold sheen along fluid contours
  float ribbon = smoothstep(0.56, 0.82, r);
  float fineVein = smoothstep(0.025, 0.0, abs(r - 0.54));

  vec3 obsidian = vec3(0.026, 0.026, 0.035);
  vec3 warmUmber = vec3(0.11, 0.088, 0.052);
  vec3 antiqueGold = vec3(0.773, 0.627, 0.349);
  vec3 paleLeaf = vec3(0.92, 0.82, 0.55);

  vec3 col = mix(obsidian, warmUmber, ribbon * 0.42);
  col += antiqueGold * (fineVein * (0.14 + 0.06 * uPulse));

  // Drifting warm gold-leaf dust particles
  vec2 gridUv = p * 14.0 + vec2(0.0, -uTime * 0.22);
  vec2 cell = floor(gridUv);
  vec2 local = fract(gridUv) - 0.5;
  float rnd = hash21(cell);
  if (rnd > 0.84) {
    float phase = rnd * 6.2831 + uTime * (0.7 + rnd);
    vec2 offset = vec2(sin(phase * 0.6), cos(phase * 0.4)) * 0.22;
    float d = length(local - offset);
    float spark = smoothstep(0.11, 0.0, d) * (0.45 + 0.55 * sin(phase));
    col += paleLeaf * spark * 0.22;
  }

  // Deep museum vignette framing vertical editorial stele column
  float vig = smoothstep(1.15, 0.22, length(uv - vec2(0.5, 0.42)));
  col *= mix(0.62, 1.0, vig);

  fragColor = vec4(col, 1.0);
}
