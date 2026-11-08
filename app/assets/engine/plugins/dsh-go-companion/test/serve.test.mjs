import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SERVE_TYPES, pickServeType } from '../lib/serve.js'

function gen(serveType, at) {
  return { state: 'generated', serveType, at }
}

test('挑出来的总是六种之一', () => {
  for (let roll = 0; roll < 100; roll += 7) {
    assert.ok(SERVE_TYPES.includes(pickServeType([], roll)), String(roll))
  }
})

test('最近三次用过的类型不再挑', () => {
  const entries = [gen('chat', 1), gen('review', 2), gen('observe', 3)]
  const picked = new Set()
  for (let roll = 0; roll < 100; roll += 1) picked.add(pickServeType(entries, roll))
  assert.deepEqual([...picked].sort(), ['challenge', 'memory', 'provoke'])
})

test('只排最近三次，更早的可以再用', () => {
  const entries = [gen('observe', 1), gen('chat', 2), gen('review', 3), gen('memory', 4)]
  const picked = new Set()
  for (let roll = 0; roll < 100; roll += 1) picked.add(pickServeType(entries, roll))
  // 最近三次是 chat/review/memory，observe 可以再用
  assert.ok(picked.has('observe'))
  assert.ok(!picked.has('memory'))
})

test('没有生成记录时六种都能出', () => {
  const picked = new Set()
  for (let roll = 0; roll < 100; roll += 1) picked.add(pickServeType([gen(null, 1)], roll))
  assert.equal(picked.size, SERVE_TYPES.length)
})

test('roll 决定落在哪一个，同一个 roll 结果稳定', () => {
  assert.equal(pickServeType([], 0), pickServeType([], 0))
  assert.equal(pickServeType([], 0), 'observe')
  assert.equal(pickServeType([], 99), 'review')
})
