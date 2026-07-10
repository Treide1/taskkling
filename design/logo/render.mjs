// Render every SVG passed on argv to PNG at the requested widths.
// Usage: node render.mjs out/ 512,64,16 a.svg b.svg ...
import { Resvg } from '@resvg/resvg-js'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { basename, join } from 'node:path'

const [outDir, widthsArg, ...files] = process.argv.slice(2)
const widths = widthsArg.split(',').map(Number)
mkdirSync(outDir, { recursive: true })

for (const file of files) {
  const svg = readFileSync(file, 'utf8')
  const name = basename(file, '.svg')
  for (const w of widths) {
    const png = new Resvg(svg, {
      fitTo: { mode: 'width', value: w },
      font: { loadSystemFonts: false },
    }).render().asPng()
    writeFileSync(join(outDir, `${name}@${w}.png`), png)
    console.log(`${name}@${w}.png`)
  }
}
