/**
 * Contract §1 — events carry semantic ids, never prose. The application
 * registers the text and evidence for an insight here; the Thought layer looks
 * it up by id. This keeps the event bus free of presentation payload.
 */
export interface PraxThoughtContent {
  text: string
  evidence?: { label: string; value: string }[]
  /** Where "Examine" should navigate. Optional — omit for thoughts with no drill-down. */
  examineHref?: string
}

const registry = new Map<string, PraxThoughtContent>()

export const praxThoughts = {
  set(id: string, content: PraxThoughtContent): void {
    registry.set(id, content)
  },
  get(id: string | null): PraxThoughtContent | null {
    return id ? (registry.get(id) ?? null) : null
  },
}
