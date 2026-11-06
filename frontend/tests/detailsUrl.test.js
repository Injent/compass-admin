import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeDetailsUrl } from '../src/detailsUrl.js'

test('accepts optional links and adds HTTPS when omitted', () => {
  assert.equal(normalizeDetailsUrl(''), '')
  assert.equal(normalizeDetailsUrl('  '), '')
  assert.equal(normalizeDetailsUrl('example.org/help?q=1'), 'https://example.org/help?q=1')
  assert.equal(normalizeDetailsUrl(' https://example.org/help '), 'https://example.org/help')
  assert.equal(normalizeDetailsUrl('http://example.org'), 'http://example.org')
  assert.equal(normalizeDetailsUrl('//example.org/help'), 'https://example.org/help')
})

test('rejects malformed links, credentials and non-HTTP actions', () => {
  for (const value of ['not a link', 'javascript:alert(1)', 'data:text/html,test', 'ftp://example.org', 'https://', 'https://user:password@example.org', 'https://example.org:99999']) {
    assert.equal(normalizeDetailsUrl(value), null, value)
  }
})
