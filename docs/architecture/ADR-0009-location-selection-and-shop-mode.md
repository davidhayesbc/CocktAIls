# ADR-0009: Opt-in local location selection and shop insights

- **Status:** Accepted direction
- **Date:** 2026-10-25

## Context

The app supports multiple household locations. Selecting the right pantry automatically is convenient, but household addresses are sensitive and visiting a store must not switch the pantry context to the store. Users also want practical shopping guidance from pantry levels and recipe coverage.

## Decision

- Location-based selection is opt-in. Request foreground location access only when the user enables automatic selection and explain its purpose.
- Match a foreground location to user-configured household areas on-device. Do not continuously track or request background location. Prefer approximate location when it can reliably distinguish configured homes.
- Store configured coordinates/radii, automatic/manual mode, and last selection in device-local settings. Do not include them in household records, exports, cloud AI prompts, or logs.
- Automatic mode changes the active household location only for one unambiguous configured-area match. On denial, no match, or ambiguous overlap, keep the current/last explicit selection and show the location selector. The user can override manually until they explicitly return to automatic mode.
- **At the shop** is an explicit context with a selected destination household location. It pauses automatic home switching and uses that location's inventory; it does not create a household location for the retail store or need store GPS.
- Shop insights are computed locally. List Low/Out inventory for restocking and optionally Half as a watch item. Rank candidate generic spirits by marginal distinct cocktail families made fully makeable if that spirit is added to the selected location. Count a family only when at least one variant has no other missing required ingredients; count each family once, not each variant. Show examples and explain exclusions/assumptions.
- Keep restock suggestions distinct from new-spirit unlocks. A low item can be important to restock without being presented as a new unlock if it is still considered available by recipe matching.

## Consequences

- Manual location selection and all pantry features work without location permission.
- Location coordinates remain private to each device and must be configured per device; named household locations and their pantry contents can still be exchanged.
- Foreground-only matching reduces background battery and privacy impact, but may not update while the app is closed.
- Ambiguous location results require a user decision rather than an arbitrary switch.
- Shop recommendations depend on accurate generic ingredient mapping and recipe requirement data; show the counting rule and remaining missing items so counts are understandable.
- The shared core can compute unlock counts; platform shells provide location readings and permission UX.
