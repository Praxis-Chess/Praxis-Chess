/**
 * Date helpers for the practice views.
 *
 * All of them work on plain `YYYY-MM-DD` strings rather than Date objects.
 * `new Date('2026-08-17')` parses as UTC midnight and then renders in local
 * time, which in a negative-offset zone shows the day before — a class of bug
 * that would silently shift every dot by one.
 */

export interface DayCell {
  /** YYYY-MM-DD */
  date: string
  practiced: boolean
  isToday: boolean
  /** Later than today: not missed, just not here yet. */
  future: boolean
}

const MS_DAY = 86_400_000

/** Parse YYYY-MM-DD as a UTC instant, so arithmetic never crosses a zone. */
function utc(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

function iso(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

export function addDays(isoDate: string, n: number): string {
  return iso(utc(isoDate) + n * MS_DAY)
}

/** 1 = Monday … 7 = Sunday (ISO), from a YYYY-MM-DD string. */
export function isoWeekday(isoDate: string): number {
  const js = new Date(utc(isoDate)).getUTCDay() // 0 = Sunday
  return js === 0 ? 7 : js
}

export const WEEKDAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** Monday→Sunday of the week containing `today`. */
export function weekOf(today: string, practiced: Set<string>): DayCell[] {
  const monday = addDays(today, -(isoWeekday(today) - 1))
  return Array.from({ length: 7 }, (_, i) => {
    const date = addDays(monday, i)
    return {
      date,
      practiced: practiced.has(date),
      isToday: date === today,
      future: date > today, // ISO strings sort lexicographically
    }
  })
}

export interface MonthBlock {
  /** YYYY-MM */
  key: string
  label: string
  days: DayCell[]
  practicedCount: number
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function daysInMonth(year: number, month1: number): number {
  return new Date(Date.UTC(year, month1, 0)).getUTCDate()
}

/**
 * Every month from the first practice day to the current one — including months
 * with nothing in them.
 *
 * Empty months are kept on purpose. Skipping them would compress a three-month
 * gap into a neighbouring pair of rows and quietly rewrite the history as more
 * consistent than it was.
 */
export function monthsFrom(practiceDays: string[], today: string): MonthBlock[] {
  if (practiceDays.length === 0) return []

  const practiced = new Set(practiceDays)
  const first = practiceDays[0]
  let y = Number(first.slice(0, 4))
  let m = Number(first.slice(5, 7))
  const endY = Number(today.slice(0, 4))
  const endM = Number(today.slice(5, 7))

  const out: MonthBlock[] = []
  while (y < endY || (y === endY && m <= endM)) {
    const total = daysInMonth(y, m)
    const days: DayCell[] = []
    for (let d = 1; d <= total; d++) {
      const date = `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
      days.push({
        date,
        practiced: practiced.has(date),
        isToday: date === today,
        future: date > today,
      })
    }
    out.push({
      key: `${y}-${String(m).padStart(2, '0')}`,
      label: `${MONTHS[m - 1]} ${y}`,
      days,
      practicedCount: days.filter(d => d.practiced).length,
    })
    m += 1
    if (m > 12) { m = 1; y += 1 }
  }
  return out
}
