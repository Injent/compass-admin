const dayMillis = 86400000
const months = ['янв.', 'фев.', 'мар.', 'апр.', 'мая', 'июн.', 'июл.', 'авг.', 'сент.', 'окт.', 'нояб.', 'дек.']

function calendarDay(date) {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / dayMillis
}

export function academicWeek(date, termStartDate) {
  const monday = calendarDay(termStartDate) - (termStartDate.getDay() + 6) % 7
  return Math.floor((calendarDay(date) - monday) / 7) + 1
}

export function weekParity(week) { return week % 2 === 0 ? 'чет.' : 'нечет.' }

export function shortAcademicDate(date) {
  return `${String(date.getDate()).padStart(2, '0')} ${months[date.getMonth()]} ${date.getFullYear()}`
}

export function previewWeeks(termStartDate, today, count = 16) {
  const monday = new Date(termStartDate)
  monday.setDate(monday.getDate() - (monday.getDay() + 6) % 7)
  const currentWeek = academicWeek(today, termStartDate)
  return Array.from({ length: count }, (_, index) => {
    const from = new Date(monday)
    from.setDate(from.getDate() + index * 7)
    const to = new Date(from)
    to.setDate(to.getDate() + 6)
    return { number: index + 1, from: index === 0 ? new Date(termStartDate) : from, to, parity: weekParity(index + 1), current: index + 1 === currentWeek }
  })
}
