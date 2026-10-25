# Visual direction and UI guidelines

**Working direction:** a warm, modern cocktail notebook—part useful pantry tool, part well-edited drinks guide. It should feel inviting at home, readable in a dim kitchen, and clear about what the user can make. Avoid a nightclub aesthetic and avoid making every screen a photo gallery.

## Design principles

1. **Lead with the next useful action.** The first screen answers “What can I make here?” and always shows which location is active. Location is never changed invisibly.
2. **Keep the data legible.** Names, ingredient amounts, availability, and source attribution matter more than decoration.
3. **Make quick tasks quick.** Updating a bottle level, favoriting a drink, or recording a short note should take only a few taps.
4. **Show variation without clutter.** Search lists cocktail families once; detail pages compare recipe variants and sources.
5. **Use calm, restrained visuals.** Reserve photography for recipe and bottle detail; never put essential text over a busy image.
6. **Make trust visible.** Label recipes as core, household, imported, or AI-generated. Show source and license details when relevant.
7. **Support graceful failure.** Scanning, lookup, import, and model suggestions can fail; manual entry and core use remain available.

## Color starting point

Use semantic tokens rather than hard-coding colors in screens. These are initial values to test for contrast and adapt separately for light and dark appearance.

| Token | Light value | Use |
| --- | --- | --- |
| `canvas` | `#F5F1E8` | Warm paper background |
| `surface` | `#FFFDFA` | Cards and sheets |
| `ink` | `#202923` | Main text |
| `mutedInk` | `#59645D` | Supporting text |
| `juniper` | `#285E4D` | Primary actions and selected navigation |
| `citrus` | `#D78A32` | Small highlights and “ready” accents |
| `berry` | `#8F4650` | Secondary accent, used sparingly |
| `line` | `#DED9CE` | Dividers and card edges |

Stock levels need both a word and a visual indicator; color alone is insufficient. Use distinct labels/icons for Full, Mostly Full, Half, Low, and Out. Do not use the same red treatment for an ordinary “Out” state and a destructive/error state.

Dark appearance should use a deep green-black canvas, raised charcoal-green surfaces, high-contrast warm text, and adjusted accent colors. Avoid pure black backgrounds and low-contrast muted text.

## Type and layout

- Use each platform’s system sans-serif for controls, ingredients, and long reading. A restrained system serif may be used for large recipe titles, with a sans-serif fallback.
- Keep body copy comfortably readable; support platform font scaling without clipping.
- Use a 4-point spacing grid: common gaps 8, 12, 16, 24, and 32 points/dp.
- Prefer generous margins and a clear hierarchy over dense dashboard tiles.
- Keep primary actions reachable near the bottom of the screen; use native navigation, sheets, and back behavior on each platform.
- On tablet or wide layouts, use a list/detail split without changing the underlying navigation model.

## Components and interaction

- **Recipe family card:** name, short descriptor, variant count, ready/missing status, favorite control. No duplicate card per source.
- **Variant row:** variant name, source label, makeability, and concise distinction such as “less sweet” or “uses rye.”
- **Ingredient row:** quantity, ingredient name, preparation, and compact stock state when a location is selected.
- **Stock control:** tap to cycle through levels or open a labeled chooser; make the current level explicit and undoable.
- **Location control:** show the selected home and whether it was selected automatically or manually. Provide visible “Use another home” and “Use nearby home” controls.
- **At the shop:** show the target home at the top, restock-soon items, and candidate spirits with a clear “unlocks N drinks” count plus examples. Shop mode must not change the active home based on the store's GPS position.
- **Primary action:** one visually dominant action per screen (for example, Scan/add on My Bar or Make on a recipe detail).
- **Empty state:** explain what is empty and provide one direct action (add a bottle, enter a recipe, or import a file).
- **Feedback:** confirm saved/imported changes; distinguish errors from warnings; provide Undo for destructive household edits where feasible.
- Use subtle transitions for opening a family or changing location. Avoid animation that delays scanning, saving, or search.

## Accessibility and comfort

- Meet platform contrast guidance for text and controls; verify the chosen tokens in both themes.
- Do not convey stock, conflicts, or recipe provenance by color alone.
- Provide screen-reader labels for scan, favorite, stock level, and variant-selection controls.
- Respect reduced-motion settings, font scaling, and system theme.
- Make tap targets at least platform-recommended size and avoid tiny chip controls for important filters.
- Keep recipe instructions readable while cooking; support a focused, scrollable “making this drink” view in a later iteration.

## Platform consistency

Share naming, colors, spacing values, icon meaning, and behavior between Android and iOS. Use Material/Compose and Apple/SwiftUI navigation and controls where users expect them; do not force identical system chrome. Keep the same content hierarchy and accessible labels on both platforms.

## First design review

Review the sketches in [WIREFRAMES.md](WIREFRAMES.md) against three tasks: find a makeable drink, add a bottle, and record a tasting note. Change the visual direction before implementation if it feels too formal, too dark, or too much like an inventory app.
