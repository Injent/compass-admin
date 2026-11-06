import test from 'node:test'
import assert from 'node:assert/strict'
import { academicWeek, previewWeeks, shortAcademicDate, weekParity } from '../src/academicWeeks.js'

const date = iso => new Date(`${iso}T12:00:00`)

test('academic weeks start on Monday, including dates before the semester', () => {
  const term = date('2026-09-01')
  for (const [day, number] of [['2026-08-23', -1], ['2026-08-24', 0], ['2026-08-30', 0], ['2026-08-31', 1], ['2026-09-01', 1], ['2026-09-06', 1], ['2026-09-07', 2], ['2026-11-06', 10]]) {
    assert.equal(academicWeek(date(day), term), number, day)
  }
  assert.equal(weekParity(0), 'чет.')
  assert.equal(weekParity(-1), 'нечет.')
  assert.equal(weekParity(10), 'чет.')
})

test('preview numbers rows, abbreviates dates and marks the current week', () => {
  const weeks = previewWeeks(date('2026-09-01'), date('2026-11-06'))
  assert.equal(weeks.length, 16)
  assert.equal(shortAcademicDate(weeks[0].from), '01 сент. 2026')
  assert.equal(shortAcademicDate(weeks[0].to), '06 сент. 2026')
  assert.equal(shortAcademicDate(weeks[1].from), '07 сент. 2026')
  assert.equal(shortAcademicDate(weeks[15].to), '20 дек. 2026')
  assert.deepEqual(weeks.filter(week => week.current).map(week => week.number), [10])
  assert.equal(weeks[9].parity, 'чет.')
  assert.equal(previewWeeks(date('2026-09-01'), date('2026-01-01')).some(week => week.current), false)
})

test('calendar-day counting stays correct across year, leap-day and daylight-saving boundaries', () => {
  assert.equal(academicWeek(date('2027-01-04'), date('2026-12-31')), 2)
  assert.equal(academicWeek(date('2028-03-06'), date('2028-02-29')), 2)
  assert.equal(academicWeek(date('2026-03-30'), date('2026-03-23')), 2)
  assert.equal(academicWeek(date('2026-10-26'), date('2026-10-19')), 2)
  assert.equal(shortAcademicDate(date('2026-11-06')), '06 нояб. 2026')
})
