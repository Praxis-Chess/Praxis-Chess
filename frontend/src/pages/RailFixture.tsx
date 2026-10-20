import { Page } from '../components/Page'

/**
 * Dev only (`/dev/rail`): the Page rail slot with placeholder content, so its
 * grid can be tested before any page uses it (POLISH_PLAN.md U1, item 2).
 */
export function RailFixture() {
  return (
    <Page width="standard" rail={<div className="card" data-testid="rail">Rail</div>}>
      <div className="card" data-testid="rail-main">Main column</div>
    </Page>
  )
}
