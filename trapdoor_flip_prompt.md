# Prompt for Antigravity: Trapdoor Flip

Add a new flip mode called **"Trapdoor Flip"** as a 7th entry in the flip
dropdown in `ProfitDetailsScreen.java` (tab index 6), alongside Bone, Kelp,
Oak Log, Sticky Piston, Golden Apple, and Bookshelf.

This flip works differently from the others: it doesn't sell into `/order`
listings, it sells by **listing on the AH**, and the target price is derived
from current AH market data rather than a flat top-3 average. Please
implement in the parts below and show me the diff for each before moving on
if anything is ambiguous.

**Stated assumptions — please flag if any of these are wrong rather than
silently guessing around them:**
1. The 64x baseline price is the **average** of the 3–9 matched listings'
   per-unit price (not lowest or highest).
2. The log type is **oak specifically** (`oak_log`), matching the naming
   convention of the existing Oak Log flip.
3. If no stack size fits under the page-1 ceiling, fall back to the
   smallest option (4x) and show a clear warning rather than hiding the
   problem or silently picking something else.

---

## The flow (how it works manually, to replicate in code)

1. Buy oak logs → craft into oak planks → craft into oak trapdoors
   (vanilla ratio: 1 log = 4 planks, 6 planks = 2 trapdoors, so **0.75 logs
   per trapdoor**).
2. Scan `/ah trapdoor` for existing **64x** trapdoor listings (exact stack
   count, not "at least 64") to establish a fair per-unit market price.
3. Round that per-unit price up to the nearest whole dollar, then add a
   user-configurable offset (default `$100`) to get the actual ask
   price/unit.
4. User picks a stack size to list at — one of `4, 8, 12, 16, 24` — via a
   stepper control.
5. Separately, check page 1 of `/ah trapdoor` to find the highest **total**
   listing price currently visible there, and suggest the largest stack
   size (from the same list) whose total ask price stays *below* that
   ceiling — so the listing stays visible on page 1 instead of getting
   buried, while keeping as much volume (and profit) as possible.
6. Compute cost (from log price), revenue (ask price × stack size), and
   profit/margin.

---

## Part 1: Quantity detection — use `stack.getCount()`, not the lore parser

Important: `AutoFlipCalcHandler.parseQuantityFromStack()` is the
**broken** parser (regex against tooltip/lore text, which is why order
listings always came back as `qty: 1`). For this feature, do **not** use
that method. Instead, read `ItemStack.getCount()` directly — the same
approach `PriceDumperHandler.scanCurrentPage()` already uses successfully
for its `DEFAULT_AH_MIN_QTY = 64` threshold filtering on AH listings. AH
listings show real item stacks, so `getCount()` is reliable there, unlike
order-listing icons which are just single-item "ticket" icons regardless of
the requested amount.

---

## Part 2: 64x baseline scan

In `AutoFlipCalcHandler` (or a new dedicated handler if that's cleaner —
your call, but reuse the existing tick-based scanning state machine either
way), add a scan step for `/ah trapdoor` that:

- Filters matches to `stack.getCount() == 64` exactly. Ignore any trapdoor
  listing that isn't exactly a 64-stack.
- Uses the pagination stop condition we just confirmed working correctly
  for the other flips: **never stop early before the first match is found**
  (page all the way to `MAX_PAGE_LIMIT` if needed), then once the first
  match lands, stop when either **9 matches** are collected (raise the cap
  from 3 to 9 for this scan specifically — we want a bigger sample here
  since full 64-stacks are rarer) or **5 consecutive empty pages** pass
  after that first match. Reuse the same named-constant pattern already in
  place, don't hardcode new magic numbers inline.
- Requires a minimum of 3 matches to compute a baseline. If fewer than 3
  are found by the time scanning stops, don't compute a price — show
  "Insufficient data (found N/3 minimum)" in the UI instead of a
  misleading result.
- For each matched listing, compute `pricePerUnit = listingTotalPrice / 64`.
  Average these across all matched listings to get `baselinePerUnit`.
- Final ask price/unit = `ceil(baselinePerUnit) + offsetInput`.

---

## Part 3: Page-1 visibility scan

Add a second, separate scan step for `/ah trapdoor` that:

- Scans **page 1 only** — no pagination, no next-page click. Just read
  whatever's on the first screen.
- Considers **all** trapdoor listings on that page regardless of quantity
  (not just 64x ones) — the goal is finding the highest **total** price
  currently visible, since that's the cutoff for page-1 placement.
- `page1Ceiling = max(listingTotalPrice)` across everything matched on that
  page.
- From `[4, 8, 12, 16, 24]`, find the **largest** stack size where
  `askPricePerUnit * stackSize < page1Ceiling`. This is the suggested stack
  size — display it as a recommendation (e.g. "Suggested: 12x — fits under
  $X page-1 ceiling"), but don't force-override the user's manual stepper
  selection; let them see both and decide.
- If even the smallest option (4x) doesn't fit under the ceiling, show a
  warning: "⚠ Exceeds page-1 ceiling by $X — listing may not appear on page
  1" rather than silently defaulting.

---

## Part 4: Profit calculation

```java
double logsNeeded = selectedStackSize * 0.75; // 3 planks/trapdoor ÷ 4 planks/log
double totalCost = logsNeeded * logPricePerLog;
double totalRevenue = askPricePerUnit * selectedStackSize;
double profit = totalRevenue - totalCost;
double marginPct = (profit / totalCost) * 100.0;
```

`logPricePerLog` should come from the same `/order oak_log` scan pattern
already used by the existing Oak Log flip (reuse that scan task, don't
duplicate the logic).

---

## Part 5: UI

In `ProfitDetailsScreen.java`, add tab index 6 with:

- **Log Price ($/log):** text box, same pattern as other flip's price
  inputs, auto-filled by the scan.
- **Cost Per Trapdoor:** read-only computed label (`logPricePerLog * 0.75`).
- **Offset above baseline ($):** text box, default `"100"`, new config field
  (e.g. `getSavedTrapdoorOffset` / `setSavedTrapdoorOffset` in
  `ProfitConfig`, following the existing saved-price field pattern).
- **Stack Size:** a stepper control — two small arrow buttons (◀ ▶) with a
  label in between showing the current value — cycling through
  `[4, 8, 12, 16, 24]` only (not free text entry). Persist the selected
  index in config the same way other selections are saved.
- **Ask Price/Unit**, **Page-1 Ceiling**, **Suggested Stack Size**, **Total
  Cost**, **Total Revenue**, **Profit**, **Margin %** — all read-only
  computed labels, updated live same as `updateCalc()` does for other tabs.
- **"Auto Check Prices" button:** triggers the full sequence — scan
  `/order oak_log`, then the 64x AH baseline scan, then the page-1 ceiling
  scan — in that order, same queued-task pattern as the other flip modes.

---

## Part 6: Sanity checks before calling this done

- If the 64x baseline scan finds fewer than 3 matches, the whole
  profit/ask calculation should clearly show "insufficient data" rather
  than computing off zero or null values.
- If `logPricePerLog` hasn't been scanned/entered yet, cost and profit
  should show as blank/N/A rather than $0 (which would show a fake
  100%-margin profit).
- Log every stage clearly (matches found, ceiling found, suggested size)
  the same verbose way `AutoFlipCalcHandler` already logs its other scans,
  so a rerun log dump is easy to sanity-check like we did for the earlier
  flips.

Please output the full diff for `ProfitDetailsScreen.java` and
`AutoFlipCalcHandler.java` (or new files, if you go that route) so I can
review before applying.
