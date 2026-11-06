const monthNames = ['JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE', 'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER']

export const months = monthNames.map((key, index) => ({
  key,
  label: new Date(2000, index, 1).toLocaleDateString('ru-RU', { month: 'long' }),
  max: new Date(2000, index + 1, 0).getDate(),
}))

export const weekdays = { MONDAY: 'Понедельник', TUESDAY: 'Вторник', WEDNESDAY: 'Среда', THURSDAY: 'Четверг', FRIDAY: 'Пятница', SATURDAY: 'Суббота', SUNDAY: 'Воскресенье' }
export const weekdayAbbreviations = { MONDAY: 'Пн', TUESDAY: 'Вт', WEDNESDAY: 'Ср', THURSDAY: 'Чт', FRIDAY: 'Пт', SATURDAY: 'Сб', SUNDAY: 'Вс' }
