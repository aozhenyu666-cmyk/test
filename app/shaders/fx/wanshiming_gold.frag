#version 460 core

#include <flutter/runtime_effect.glsl>

precision highp float;

uniform vec2 uSize;
uniform float uTime;
uniform float uPulse;
uniform vec2 uLight;

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
  vec2 lightUv = clamp(uLight, vec2(0.05), vec2(0.95));
  vec2 lightP = (lightUv - 0.5) * vec2(res.x / res.y, 1.0);

  // Flowing obsidian silk + subtle anisotropic brushed metallic gold veins
  float t = uTime * 0.14;
  float q = fbm(p * 2.3 + vec2(t, -t * 0.7));
  float r = fbm(p * 3.1 + vec2(q * 1.4 - t * 0.5, q * 0.9 + t * 0.4));

  float brush = noise(vec2(p.x * 95.0, p.y * 6.0 + t * 0.4));
  float ribbon = smoothstep(0.54, 0.84, r);
  float fineVein = smoothstep(0.024, 0.0, abs(r - 0.54));

  // Simplified Cook-Torrance-inspired specular sweep tied to pointer/tilt light uLight
  vec2 delta = p - lightP;
  float sweep = exp(-dot(delta, delta) * 3.8);
  float anisoBand = smoothstep(0.28, 0.0, abs((p.x - lightP.x) * 0.65 + (p.y - lightP.y) * 0.75 + (r - 0.5) * 0.25));

  vec3 obsidian = vec3(0.025, 0.025, 0.034);
  vec3 warmUmber = vec3(0.115, 0.090, 0.052);
  vec3 antiqueGold = vec3(0.773, 0.627, 0.349);
  vec3 paleLeaf = vec3(0.95, 0.85, 0.58);

  vec3 col = mix(obsidian, warmUmber, ribbon * (0.42 + 0.18 * sweep));
  col += antiqueGold * (fineVein * (0.14 + 0.08 * uPulse + 0.10 * sweep));
  col += antiqueGold * (anisoBand * (0.045 + 0.035 * brush) * (0.65 + 0.35 * uPulse));
  col += paleLeaf * (sweep * 0.055 * (0.5 + 0.5 * brush));

  // Drifting warm gold-leaf dust particles + micro glitter near light spot
  vec2 gridUv = p * 14.0 + vec2(0.0, -uTime * 0.22);
  vec2 cell = floor(gridUv);
  vec2 local = fract(gridUv) - 0.5;
  float rnd = hash21(cell);
  if (rnd > 0.83) {
    float phase = rnd * 6.2831 + uTime * (0.7 + rnd);
    vec2 offset = vec2(sin(phase * 0.6), cos(phase * 0.4)) * 0.22;
    float d = length(local - offset);
    float spark = smoothstep(0.11, 0.0, d) * (0.45 + 0.55 * sin(phase));
    col += paleLeaf * spark * (0.20 + 0.22 * sweep);
  }

  // Deep museum vignette framing vertical editorial stele column
  float vig = smoothstep(1.18, 0.20, length(uv - vec2(0.5, 0.42)));
  col *= mix(0.60, 1.0, vig);

  fragColor = vec4(col, 1.0);
}
