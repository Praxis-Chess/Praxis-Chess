import { test, expect } from '../fixtures/test'

// The point cloud is a shader. A shader that fails to compile renders NOTHING
// and throws no exception the app would notice — so assert on the program log
// and the GL error directly, because no visual test would catch it.
test('prax icosphere renders', async ({ page }) => {
  const errors: string[] = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', e => errors.push('PAGEERROR ' + e.message))

  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/')
  await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)

  const info = await page.evaluate(() => {
    const x = (window as unknown as Record<string, any>).__prax
    // Force a draw: the loop is frozen under reduced-motion, so nothing has
    // compiled yet. A shader error surfaces only at first draw.
    let threw: string | null = null
    try { x.renderer.render(x.scene, x.camera) } catch (e) { threw = String(e) }

    const progs = x.renderer.info.programs || []
    const geo = x.handle.points.geometry
    return {
      threw,
      count: x.handle.count,
      positions: geo.getAttribute('position').count,
      programs: progs.length,
      diagnostics: progs.map((p: any) => (p.diagnostics ? p.diagnostics.programLog : 'ok')),
      glError: x.renderer.getContext().getError(),
      uniforms: {
        amp: x.handle.uniforms.uDisplaceAmp.value,
        freq: x.handle.uniforms.uDisplaceFreq.value,
        speed: x.handle.uniforms.uDisplaceSpeed.value,
      },
    }
  })

  console.log('PRAX:', JSON.stringify(info))
  console.log('CONSOLE ERRORS:', JSON.stringify(errors.slice(0, 4)))

  expect(info.threw).toBeNull()
  expect(info.glError).toBe(0)
  expect(info.diagnostics).toEqual(['ok'])
  // 10 * 4^4 + 2 — a real icosphere, not a fibonacci scatter.
  expect(info.count).toBe(2562)
  expect(info.positions).toBe(2562)
})

test('rest positions form an undeformed sphere', async ({ page }) => {
  await page.goto('/')
  await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)

  const radii = await page.evaluate(() => {
    const x = (window as unknown as Record<string, any>).__prax
    const pos = x.handle.points.geometry.getAttribute('position')
    let min = Infinity, max = -Infinity
    for (let i = 0; i < pos.count; i++) {
      const r = Math.hypot(pos.getX(i), pos.getY(i), pos.getZ(i))
      min = Math.min(min, r); max = Math.max(max, r)
    }
    return { min, max }
  })

  console.log('REST RADII:', JSON.stringify(radii))
  // Every vertex the same distance from the centre: the deformation now lives
  // entirely in the shader, sampled per frame, rather than baked at load.
  expect(radii.max - radii.min).toBeLessThan(0.001)
})
