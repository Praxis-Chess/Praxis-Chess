import type { Page } from '@playwright/test'

/**
 * Reading metrics and data parity (docs/design/READABILITY_PLAN.md).
 *
 * Two questions, kept apart on purpose:
 *   - How much is there to READ? Prose words, cut against the reading budget.
 *   - How much is there to SEE? Data tokens, which should stay flat. A screen
 *     that hits its word target by hiding numbers has not become easier to read.
 *
 * Parity is checked as (scope, value) pairs with counts, not as a bag of tokens:
 * "−34%" three times under one cause is three facts, and a value that leaves the
 * item it belonged to is lost even if the page still contains it somewhere.
 */

/** A run of text as it renders: everything inside one block box. */
export interface Run {
  scope: string[]
  text: string
  /** Inside a table: a cell, never a paragraph. */
  table: boolean
  /** An input's current value or a checkbox's state, not text. */
  input?: 'value' | 'on' | 'off'
}

export interface Collected {
  runs: Run[]
  links: { scope: string[]; href: string }[]
  /** Hover-only help (`title=`): method text a phone cannot reach today. */
  methods: { scope: string[]; text: string }[]
}

// ── In the page ──────────────────────────────────────────────────────────────

/**
 * Runs inside the page (serialised by page.evaluate), so it must not close over
 * anything. `rootSel` limits it to one element (a popover); `base` is the scope of
 * whatever opened that element.
 */
function collectInPage(arg: { rootSel: string | null; base: string[]; baseSel?: string }): Collected {
  const main = document.querySelector('main') ?? document.body
  const root = arg.rootSel ? document.querySelector(arg.rootSel) : main
  const out: Collected = { runs: [], links: [], methods: [] }
  if (!root) return out

  // Reading text only: no board coordinates, no chart axes, not the dev-only Prax pill.
  const EXCLUDE = '[data-boardid], svg, [data-parity-ignore], [style*="z-index: 200"][style*="bottom: 12px"]'
  const SCOPES = 'section, article, li, tr, fieldset, dialog, table, details, .card, [role=region], [role=group], ' +
                 '[role=dialog], [role=tooltip], [role=status], [aria-label], [aria-labelledby], [data-scope]'
  const INTERACTIVE = 'a, button, input, select, textarea, summary'

  const clean = (s: string) => s.replace(/\s+/g, ' ').trim()
  const visible = (el: Element) => {
    const v = (el as Element & { checkVisibility?: (o: object) => boolean }).checkVisibility
    return v ? v.call(el, { checkVisibilityCSS: true, visibilityProperty: true }) : true
  }
  const display = (el: Element) => getComputedStyle(el).display
  const isBlock = (el: Element) => { const d = display(el); return !d.startsWith('inline') && d !== 'contents' }

  const names = new Map<Element, string | null>()
  const nameOf = (el: Element): string | null => {
    if (names.has(el)) return names.get(el)!
    let n: string | null = null
    if (!el.matches(INTERACTIVE)) {
      const label = el.getAttribute('aria-label')
      const by = el.getAttribute('aria-labelledby')
      const h = el.querySelector('h1, h2, h3, h4, h5, [role=heading]')
      if (label) n = label
      else if (by) n = by.split(/\s+/).map(id => document.getElementById(id)?.textContent ?? '').join(' ')
      else if ((el as HTMLElement).dataset?.scope) n = (el as HTMLElement).dataset.scope!
      else if (el.tagName === 'TR') n = el.querySelector('td, th')?.textContent ?? null
      else if (el.tagName === 'DETAILS') n = el.querySelector('summary')?.textContent ?? null
      else if (h?.textContent?.trim()) n = h.textContent
      else if (el.tagName === 'LI') n = ((el as HTMLElement).innerText ?? '').split('\n')[0]
      n = n ? clean(n).slice(0, 60) : null
    }
    names.set(el, n || null)
    return n || null
  }
  const scopeOf = (el: Element): string[] => {
    const chain: string[] = []
    for (let e: Element | null = el; e && e !== main.parentElement; e = e.parentElement) {
      if (e !== el || !e.matches(INTERACTIVE)) {
        if (e.matches(SCOPES)) { const n = nameOf(e); if (n && chain[0] !== n) chain.unshift(n) }
      }
      if (e === main) break
    }
    return [...arg.base, ...chain]
  }
  // A popover's facts belong to the item of the control that opened it.
  if (arg.baseSel) {
    const trigger = document.querySelector(arg.baseSel)
    if (trigger) arg = { ...arg, base: scopeOf(trigger) }
  }
  const blockOf = (el: Element): Element => {
    for (let e: Element | null = el; e; e = e.parentElement) { if (e === root || isBlock(e)) return e }
    return root
  }
  // Inline boxes render without spaces between them ("<b>1</b>/2" reads "1/2"),
  // but an inline-block (a chip, a badge) is a word of its own.
  const chunkOf = (el: Element, block: Element): Element => {
    for (let e: Element | null = el; e && e !== block; e = e.parentElement) {
      if (/^inline-(block|flex|grid|table)$/.test(display(e))) return e
    }
    return block
  }

  const byBlock = new Map<Element, { text: string; last: Element | null }>()
  const order: Element[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const parent = node.parentElement
    if (!parent || !node.textContent) continue
    if (parent.closest(EXCLUDE) || parent.closest('script, style, noscript')) continue
    if (!visible(parent)) continue
    const block = blockOf(parent)
    const chunk = chunkOf(parent, block)
    let entry = byBlock.get(block)
    // A whitespace node is the space between two inline boxes ("of{' '}<b>145</b>").
    if (!node.textContent.trim()) { if (entry) entry.text += ' '; continue }
    if (!entry) { entry = { text: '', last: null }; byBlock.set(block, entry); order.push(block) }
    entry.text += (entry.last && entry.last !== chunk ? ' ' : '') + node.textContent
    entry.last = chunk
  }
  for (const block of order) {
    const text = clean(byBlock.get(block)!.text)
    if (text) out.runs.push({ scope: scopeOf(block), text, table: !!block.closest('table') })
  }

  for (const el of Array.from(root.querySelectorAll('input, select, textarea'))) {
    if (el.closest(EXCLUDE) || !visible(el)) continue
    const f = el as HTMLInputElement
    const label = clean(f.labels?.[0]?.textContent ?? f.getAttribute('aria-label') ?? f.name ?? '')
    if (f.type === 'checkbox' || f.type === 'radio') {
      out.runs.push({ scope: scopeOf(el), text: label, table: false, input: f.checked ? 'on' : 'off' })
    } else {
      const value = el.tagName === 'SELECT'
        ? (el as HTMLSelectElement).selectedOptions[0]?.textContent ?? ''
        : f.value
      if (clean(value)) out.runs.push({ scope: [...scopeOf(el), label], text: clean(value), table: false, input: 'value' })
    }
  }
  for (const a of Array.from(root.querySelectorAll('a[href]'))) {
    if (a.closest(EXCLUDE) || !visible(a)) continue
    const u = new URL((a as HTMLAnchorElement).href, location.href)
    out.links.push({ scope: scopeOf(a), href: u.origin === location.origin ? u.pathname + u.search : u.href })
  }
  for (const el of Array.from(root.querySelectorAll('[title]'))) {
    if (el.closest(EXCLUDE) || !visible(el)) continue
    const t = clean(el.getAttribute('title') ?? '')
    if (t) out.methods.push({ scope: scopeOf(el), text: t })
  }
  return out
}

/** Everything readable in `main` right now. */
export function collect(page: Page): Promise<Collected> {
  return page.evaluate(collectInPage, { rootSel: null, base: [] })
}

// ── Disclosures and popovers ────────────────────────────────────────────────

/**
 * Opens every disclosure in `main`, then opens each popover in turn and adds
 * what it shows under the scope of the control that opened it.
 *
 * `[data-disclose]` and `[data-popover]` are the contract for new components.
 * The LEGACY openers below exist only until each screen migrates; they are
 * scoped to `main`, so no nav menu or page-level control is ever triggered.
 */
export async function expandAll(page: Page): Promise<Collected> {
  for (let pass = 0; pass < 3; pass++) {
    const clicked = await page.evaluate(() => {
      const main = document.querySelector('main') ?? document.body
      main.querySelectorAll('details:not([open])').forEach(d => { (d as HTMLDetailsElement).open = true })
      const buttons = Array.from(main.querySelectorAll<HTMLButtonElement>(
        '[data-disclose][aria-expanded="false"], button[aria-expanded="false"]:not([data-popover])'))
      // LEGACY: toggles without aria-expanded.
      for (const b of Array.from(main.querySelectorAll<HTMLButtonElement>('button'))) {
        if (/^Show (all|evidence|daily history)/i.test(b.textContent?.trim() ?? '') && !buttons.includes(b)) buttons.push(b)
      }
      const todo = buttons.filter(b => !b.disabled && !b.dataset.parityOpened)
      todo.forEach(b => { b.dataset.parityOpened = '1'; b.click() })
      return todo.length
    })
    if (!clicked) break
    await page.waitForTimeout(250)
  }

  const now = await collect(page)

  // Popovers: [data-popover] buttons (new) and, LEGACY, report move links that
  // show a board on focus.
  const count = await page.evaluate(() => {
    const main = document.querySelector('main') ?? document.body
    const triggers = Array.from(main.querySelectorAll<HTMLElement>('[data-popover], a[aria-label$=": open the game"]'))
    triggers.forEach((t, i) => { t.dataset.parityTrigger = String(i) })
    return triggers.length
  })
  for (let i = 0; i < count; i++) {
    const sel = `[data-parity-trigger="${i}"]`
    const isButton = await page.locator(sel).evaluate(el => el.matches('[data-popover]'))
    if (isButton) await page.locator(sel).click()
    else await page.locator(sel).evaluate(el => (el as HTMLElement).focus())
    const popSel = isButton
      ? await page.locator(sel).evaluate(el => `[id="${el.getAttribute('aria-controls')}"]`)
      : '[role="tooltip"]'
    await page.locator(popSel).first().waitFor({ state: 'visible', timeout: 3000 })
    // LEGACY hover links: the scope they were captured under ("popover <move>").
    const base = isButton ? [] : await page.locator(sel).evaluate(el => {
      const names: string[] = []
      const main = document.querySelector('main') ?? document.body
      for (let e: Element | null = el.parentElement; e && e !== main; e = e.parentElement) {
        const n = e.getAttribute('aria-label') ?? (e.tagName === 'SECTION' ? e.querySelector('h1,h2,h3,h4')?.textContent : null)
        if (n && names[0] !== n) names.unshift(n.replace(/\s+/g, ' ').trim().slice(0, 60))
      }
      return [...names, `popover ${(el.textContent ?? '').trim().split(/\s+/)[0]}`]
    })
    const pop = await page.evaluate(collectInPage, { rootSel: popSel, base, baseSel: isButton ? sel : undefined })
    now.runs.push(...pop.runs)
    now.links.push(...pop.links)
    if (isButton) await page.keyboard.press('Escape')
    else await page.locator(sel).evaluate(el => (el as HTMLElement).blur())
  }
  return now
}

// ── Tokens and metrics ──────────────────────────────────────────────────────

const SAN = /^(\d+\.(\.\.)?)?(O-O(-O)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?)[+#]?[!?]{0,2}$/
const EDGE = /^[.:;,"'“”‘’[\]{}]+|[.:;,"'“”‘’[\]{}]+$/g

export function tokens(text: string): string[] {
  return text.split(/[\s·|;()]+/)
    .map(t => t.replace(EDGE, ''))
    .filter(t => /[\p{L}\p{N}]/u.test(t))
}
export const isData = (t: string) => /\d/.test(t) || SAN.test(t)

export interface Metrics {
  /** Words without a digit that are not moves: what there is to read. */
  prose: number
  /** Numbers and moves: what there is to see. */
  data: number
  /** Blocks of running text over 15 words, outside tables. */
  paragraphs: string[]
}

export function metrics(c: Collected): Metrics {
  let prose = 0, data = 0
  const paragraphs: string[] = []
  for (const r of c.runs) {
    if (r.input) continue
    const ts = tokens(r.text)
    for (const t of ts) { if (isData(t)) data++; else prose++ }
    if (!r.table && ts.length > 15) paragraphs.push(r.text)
  }
  return { prose, data, paragraphs }
}

/** Visible words in each `[data-reading]` element: takeaway, intro, item. */
export function readingBudget(page: Page) {
  return page.evaluate(() => Array.from(document.querySelectorAll<HTMLElement>('main [data-reading]'))
    .filter(e => e.checkVisibility?.() ?? true)
    .map(e => ({ kind: e.dataset.reading!, text: e.innerText.replace(/\s+/g, ' ').trim() })))
}

// ── Parity pairs ────────────────────────────────────────────────────────────

/** [scope, token, count]. A token is a number or move, `~text data`, or `@link`. */
export type Pair = [string, string, number]

const norm = (s: string) => s.toLowerCase().replace(/[’']/g, "'").replace(/\s+/g, ' ').trim()
const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

export function pairs(c: Collected, vocabulary: string[]): Pair[] {
  const counts = new Map<string, number>()
  const add = (scope: string[], token: string) => {
    const key = `${scope.join(' > ')}\u0001${token}`
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const terms = [...new Set(vocabulary.map(norm))].filter(t => t.length >= 3)
    .map(t => ({ t, re: new RegExp(`(^|[^\\p{L}\\p{N}])${escape(t)}(?=$|[^\\p{L}\\p{N}])`, 'gu') }))

  for (const r of c.runs) {
    if (r.input === 'on' || r.input === 'off') { add(r.scope, `~[${r.input}] ${norm(r.text)}`); continue }
    const ts = tokens(r.text)
    for (const t of ts) if (isData(t)) add(r.scope, t)
    if (r.input) { if (!ts.some(isData)) add(r.scope, `~${norm(r.text)}`); continue }
    // Text data counts only in data-shaped runs: a row with a number in it, or
    // a short label, chip, badge or cell. Long prose is reading, not data.
    if (ts.some(isData) || ts.length <= 6) {
      const text = norm(r.text)
      for (const { t, re } of terms) for (const _ of text.matchAll(re)) add(r.scope, `~${t}`)
    }
  }
  for (const l of c.links) add(l.scope, `@${l.href}`)
  return [...counts.entries()].map(([k, n]) => { const [s, t] = k.split('\u0001'); return [s, t, n] as Pair })
    .sort((a, b) => a[0].localeCompare(b[0]) || a[1].localeCompare(b[1]))
}

// ── The check ───────────────────────────────────────────────────────────────

export interface Exceptions {
  /** Deliberate removals, each with the decision behind it. */
  removed: { screen: string; state?: string; scope?: string; token: string; reason: string; decision?: string }[]
  /** A value shown in a new format: `from` is one token, `to` is tokenised like page text. */
  formats: { screen: string; from: string; to: string; reason: string }[]
  /**
   * A scope renamed or moved: every segment equal to `from` becomes `to`. With
   * `regex`, `from` is a pattern over one segment and `to` may use $1.
   */
  moves: { screen: string; state?: string; from: string; to: string; regex?: boolean; reason: string }[]
  /** Help text reworded when it moved out of a hover tooltip. */
  methods: { screen: string; from: string; to: string; reason: string }[]
}

export interface Golden {
  screen: string
  state: string
  path: string
  metrics: Metrics
  pairs: Pair[]
  methods: [string, string][]
}

const applies = (e: { screen: string; state?: string }, g: Golden) =>
  (e.screen === '*' || e.screen === g.screen) && (!e.state || e.state === g.state)

/** Every golden fact still present, in its own item. Returns what is missing. */
export function checkParity(golden: Golden, now: Collected, vocabulary: string[], ex: Exceptions): string[] {
  const current = pairs(now, vocabulary)
  const segments = (scope: string) => scope.split(' > ').filter(Boolean)
  const failures: string[] = []

  for (const [scope, token, count] of golden.pairs) {
    if (ex.removed.some(r => applies(r, golden) && r.token === token && (!r.scope || scope.includes(r.scope)))) continue
    let wanted = [token]
    const f = ex.formats.find(x => applies(x, golden) && x.from === token)
    // Words in the new format are text data ("16 Oct" is 16 and ~oct), as pairs() records them.
    if (f) wanted = token.startsWith('~') ? f.to.split(' | ').map(s => `~${norm(s)}`)
                                          : tokens(f.to).map(t => (isData(t) ? t : `~${norm(t)}`))
    let segs = segments(scope)
    for (const m of ex.moves.filter(x => applies(x, golden))) {
      segs = segs.map(s => (m.regex ? s.replace(new RegExp(m.from), m.to) : s === m.from ? m.to : s))
    }
    // The innermost two names: an outer re-layout is free, leaving the item is not.
    const key = segs.slice(-2)
    // Both names must be in the chain, in either order: a table nests setting then engine
    // where two cards nested engine then setting, and the fact is still in its own cell.
    const within = (s: string) => { const have = segments(s); return key.every(k => have.includes(k)) }
    // A link that gained query parameters ("/games/x" → "/games/x?ply=45") still goes to the same place.
    const same = (t: string, w: string) => t === w || (w.startsWith('@') && !w.includes('?') && t.startsWith(`${w}?`))
    for (const w of wanted) {
      const n = current.filter(([s, t]) => same(t, w) && within(s)).reduce((a, [, , c]) => a + c, 0)
      if (n < count) failures.push(`[${golden.screen}/${golden.state}] ${scope} :: ${w} — was ${count}, now ${n}`)
    }
  }

  const text = norm([...now.runs.map(r => r.text), ...now.methods.map(m => m.text)].join(' \n '))
  for (const [scope, method] of golden.methods) {
    const m = ex.methods.find(x => applies(x, golden) && norm(x.from) === norm(method))
    if (!text.includes(norm(m ? m.to : method))) failures.push(`[${golden.screen}/${golden.state}] ${scope} :: help text missing: "${method}"`)
  }
  return failures
}
