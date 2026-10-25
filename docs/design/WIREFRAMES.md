# First-pass screen sketches

These text sketches define content hierarchy and behavior, not pixel-perfect layouts. The visual direction is in [UI_DIRECTION.md](UI_DIRECTION.md).

## Make

```text
┌────────────────────────────────────┐
│ Good evening                 [•••]  │
│ [⌂ Home ▾]                          │
│ [Use nearby home]   [At the shop →]│
│                                    │
│ What can you make?                 │
│ [ Search drinks or ingredients ]   │
│                                    │
│ READY NOW · 8                       │
│ ┌────────────────────────────────┐ │
│ │ Negroni                 ♡      │ │
│ │ 2 versions · classic available │ │
│ └────────────────────────────────┘ │
│                                    │
│ CLOSE TO READY                     │
│ Boulevardier · missing sweet verm.│
│                                    │
│ Recently made       See all →      │
│                                    │
│ Make     My Bar    Recipes    More │
└────────────────────────────────────┘
```

Prioritize ready drinks, but always make search visible. A family card appears once even when several sources or variants exist. Near matches state exactly what is missing.

## My Bar

```text
┌────────────────────────────────────┐
│ My Bar                  [＋ Add]    │
│ [⌂ Home ▾]                [Scan]   │
│ [ Search ingredients ]             │
│                                    │
│ SPIRITS                     Edit   │
│ Gin                 Mostly full   │
│ Rye whiskey         Half          │
│ Sweet vermouth      Low            │
│                                    │
│ MIXERS                      Edit   │
│ Lime juice          Out            │
│ Simple syrup        Full           │
│                                    │
│ Missing is not the same as Out.   │
│                                    │
│ Make     My Bar    Recipes    More │
└────────────────────────────────────┘
```

Location switching should be one tap away and visible. Stock level controls use both text and icon/shape; “Out” remains a tracked state while absent items are unknown.

## At the shop

```text
┌────────────────────────────────────┐
│ ‹ Make                    AT SHOP  │
│ Shopping for [Home ▾]              │
│                                    │
│ MOST NEW DRINKS                    │
│                                    │
│ 1  Sweet vermouth        +14 drinks│
│    Negroni · Manhattan · Americano │
│    [See recipes]                   │
│                                    │
│ 2  Rye whiskey            +9 drinks│
│    Sazerac · Boulevardier · ...    │
│    [See recipes]                   │
│                                    │
│ RESTOCK SOON                       │
│ Low · Sweet vermouth               │
│ Out · Lime juice                   │
│ Half · Orange bitters              │
│                                    │
│ Counts are unique cocktail families│
│ Fully makeable · Home's pantry     │
└────────────────────────────────────┘
```

Shop mode requires a selected destination home and uses its existing pantry data. Recommendations count new families that become fully makeable after adding one missing spirit; variants with other missing required ingredients are not counted. Low/Out restocks appear in a separate section so they are not confused with new-spirit unlocks.

## Cocktail family detail

```text
┌────────────────────────────────────┐
│ ‹ Recipes                    ♡     │
│                                    │
│ NEGRONI                            │
│ Bittersweet · aperitif             │
│ 3 versions · 2 sources              │
│                                    │
│ [Classic] [Lighter] [House version]│
│                                    │
│ CLASSIC                            │
│ Gin                  30 ml   ✓     │
│ Sweet vermouth       30 ml   Low   │
│ Bitter aperitif      30 ml   ✓     │
│                                    │
│ Stir with ice. Strain over ice.    │
│                                    │
│ Source: Core collection · Details  │
│ [Make this]       [Edit a copy]    │
└────────────────────────────────────┘
```

Variants are selectable within one family. Show availability for the active location and identify the specific missing/low ingredients. Source attribution is visible and expandable. “Edit a copy” creates a household variant and never edits bundled source content.

## Import review

```text
┌────────────────────────────────────┐
│ ‹ Import review                    │
│ 42 recipes · 18 ingredients         │
│                                    │
│ Source and license                 │
│ Example collection · CC BY-SA 3.0  │
│ Attribution will be included.      │
│                                    │
│ 21 new families                    │
│ 12 variants matched                │
│  6 possible duplicate matches      │
│  3 conflicts                       │
│                                    │
│ Review matches →                   │
│ Review conflicts →                 │
│                                    │
│ [Cancel]           [Apply import]  │
└────────────────────────────────────┘
```

Counts are provisional until review finishes. Selecting “possible duplicate matches” shows why each candidate matched and lets the user link it, keep it separate, or assign it to another family/variant. Cancel leaves the database unchanged.

## Tone and copy

Use short, friendly labels: “Ready now,” “Close to ready,” “Add a bottle,” “Edit a copy,” and “Record how it turned out.” Explain source, cloud, and conflict choices plainly. Avoid treating the app as an expert bartender or presenting generated recipes as established classics.
