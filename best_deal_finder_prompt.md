# Prompt for Antigravity: Best Deal Finder

Add a new feature called **"Best Deal Finder"**, accessible via a new
button in the header bar next to the existing "Price Dumper" button (reuse
that same header-button pattern). It searches the AH for the single
cheapest-per-unit listing of one item, in a quantity the user wants, and
optionally auto-buys it.

Please implement in the parts below and show me the diff for each before
moving on if anything is ambiguous — this feature spends the user's actual
in-game currency, so don't guess on the purchase-safety parts, ask instead.

**Confirmed checkbox behavior:** "Auto Buy" checked = buy immediately with
no confirmation popup. Unchecked = always show the Yes/No confirmation
popup before buying, every time a deal is found.

---

## Part 1: UI

New screen/modal (`BestDealFinderScreen.java` or similar, styled
consistently with `PriceDumperHandler`'s UI and the existing owo-lib
patterns used elsewhere in the mod):

- **Item input**: a single-item text box, reusing the exact same textbox
  component/styling already used for item entry in the Price Dumper screen.
  This one is single-item only (not the multi-line/tag-expanding input the
  dumper uses for bulk lists) — one item per search.
- **Target Quantity** (optional override): numeric text box. If left
  blank, default to the item's real max stack size via
  `Item#getMaxStackSize()` (this handles everything automatically — 64 for
  normal items, 16 for ender pearls/snowballs/eggs, 1 for buckets/boats —
  no manual per-item list needed).
- **Max Price Per Unit** (optional safety cap): numeric text box, blank =
  no cap. See Part 4 for why this matters.
- **Auto Buy**: checkbox, default unchecked.
- **"Find Best Deal"** button to start the search.
- **Result panel** (populated after search completes): item name, best
  price/unit, listing's actual quantity, total price, and a status line —
  either "✓ Found" or, if no listing met the target quantity, "⚠ Best
  available: Nx (less than requested Nx)".

---

## Part 2: Scanning logic

Reuse the existing tick-based scanning state machine and pagination
patterns already proven elsewhere in the mod (`PriceDumperHandler`,
`AutoFlipCalcHandler`). Specifically:

- Run `/ah <item>`.
- A listing **qualifies** if `stack.getCount() >= targetQty` (use
  `ItemStack.getCount()` directly — confirmed reliable for AH listings,
  unlike the broken order-quantity lore parser).
- Pagination stop condition — same pattern already confirmed working for
  the other flips: **never stop before the first qualifying match is
  found** (page up to the existing `MAX_PAGE_LIMIT`), then once the first
  qualifying match lands, stop once either **3 qualifying matches** are
  collected (enough to compare and pick a genuine best) or **5 consecutive
  empty pages** pass after that first match. Reuse the same named
  constants, don't hardcode new numbers inline.
- Among all qualifying matches found, compute `pricePerUnit =
  listingTotalPrice / stack.getCount()` for each, and pick the **lowest**.
- **Fallback**: if scanning completes (hits the page cap or runs out of
  pages) with zero qualifying matches, don't fail silently — report the
  single cheapest-per-unit listing found regardless of quantity, clearly
  flagged in the UI as below the requested amount (see Part 1's result
  panel status line).
- Record the matched listing's **page number and slot index** at the time
  of scanning — needed in Part 3 to navigate back and actually click it.

---

## Part 3: Buying logic

- **If Auto Buy is checked**: navigate back to the recorded page/slot and
  click it immediately via the same
  `mc.gameMode.handleInventoryMouseClick(...)` pattern already used for
  page-turning elsewhere, using whatever click type actually triggers a
  purchase in this GUI (confirm this against how `/ah` buying already
  works elsewhere in the mod, if any existing code does this — otherwise
  this needs to be tested carefully in-game since a wrong click type could
  fail silently or do something unintended).
- **If Auto Buy is unchecked**: show a confirmation popup (owo-lib modal,
  same visual style as other modals in the mod) displaying the item name,
  price/unit, and total price, with **Yes** / **No** buttons.
  - **Yes** → same click-to-buy logic as the auto-buy path.
  - **No** → close the AH container screen without buying, no purchase
    attempt made.
- **Before clicking to buy** (both paths): re-verify the slot still
  contains the expected item and price. Time has passed since the scan
  (navigating pages back, or waiting on user confirmation), so the
  listing may have already been bought by someone else or changed. If the
  slot is empty or doesn't match what was recorded, **abort the purchase**,
  log it clearly (`"[Best Deal Finder] Listing no longer available, purchase aborted"`),
  and notify the user in chat — never click blindly on stale data.
- **No retry loops.** One purchase attempt per search. If it fails or the
  listing's gone, report that and stop — don't automatically re-search or
  re-attempt.
- Log every purchase attempt (success, failure, or abort) with full
  item/price/quantity details, regardless of which path (auto or
  confirmed) triggered it — this is the audit trail if something ever
  looks wrong after the fact.

---

## Part 4: Max Price safety cap

Since Auto Buy skips human confirmation entirely, a single bad/outlier
scan result could otherwise trigger an expensive unattended purchase. If
the user has set a **Max Price Per Unit** value and the found deal's
`pricePerUnit` exceeds it, **force the confirmation popup regardless of
the Auto Buy checkbox state** — don't silently cap the price or skip the
deal, just require a human look before spending above the user's own
stated limit.

---

## Part 5: Sanity checks before calling this done

- Confirm the default target quantity is correct for at least one normal
  item (e.g. trapdoor → 64) and one special-stack item (e.g. ender pearl →
  16) without any hardcoded per-item logic — purely from
  `getMaxStackSize()`.
- Confirm the fallback path (zero qualifying matches) displays the "less
  than requested" warning correctly rather than crashing or showing blank
  fields.
- Confirm the stale-listing re-verification actually aborts cleanly if you
  manually buy/remove the target listing yourself between the scan
  finishing and confirming the purchase — test this deliberately before
  reporting it as done.
- Confirm the Max Price cap correctly forces the popup even when Auto Buy
  is checked.

Please output the full diff/new files so I can review before applying, and
please flag anywhere the actual AH-buying click mechanic is uncertain
rather than guessing at it — I'd rather test that one part manually before
trusting Auto Buy on it.
