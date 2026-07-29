# Prompt for Antigravity: Fix Trapdoor Flip bugs from first test run

The Trapdoor Flip's scanning pipeline works correctly — all three scans ran
and computed correct numbers, confirmed in the log. But the UI isn't
displaying most of that data, and the stack-size suggestion logic picked a
size that actually exceeds the page-1 ceiling, defeating the whole point of
the feature. Three separate bugs to fix.

## Reproduction data (from the actual log, use this to verify your fix)

```
Oak Log top-3 avg price:        $201.33
64x baseline (20 matches):      $1306.25 per unit
Page 1 ceiling (max total):     $20,000.00
Offset input:                   $100
```

Expected ask price/unit: `ceil(1306.25) + 100 = 1307 + 100 = $1407`

Expected stack size comparison:

| Stack | Total Ask (1407 × size) | Under $20,000 ceiling? |
|---|---|---|
| 4x  | $5,628  | ✓ |
| 8x  | $11,256 | ✓ |
| 12x | $16,884 | ✓ |
| 16x | $22,512 | ✗ exceeds by $2,512 |
| 24x | $33,768 | ✗ |

**Correct suggested stack size is 12x** (the largest that stays under
$20,000). The UI currently shows 16x selected, which is over the ceiling —
confirm whether the auto-suggestion logic never ran at all, or ran with a
comparison bug (e.g. `<=` instead of `<`, or comparing against the wrong
variable). Fix whichever it is and verify against the table above.

## Bug 1: Stack-size auto-suggestion is wrong or not running

Find wherever the suggested-stack-size comparison happens (should be:
"largest size in `[4, 8, 12, 16, 24]` where `askPricePerUnit * size <
page1Ceiling`") and verify it actually executes after both the baseline
scan and the ceiling scan complete, using the real computed values — not a
stale or default value. Add a log line when this runs, e.g.:
`LOGGER.info("[Auto Flip] Suggested stack size: {}x (Total: ${}, Ceiling: ${})", ...)`
so future test runs can confirm it fired correctly without needing a
screenshot.

## Bug 2: UI is missing the Ask Price/Unit, Page-1 Ceiling, and Suggested
Stack Size fields entirely

These three read-only labels were part of the original spec but don't
appear in the built screen at all — only Oak Log Price, Offset, and the
stepper are showing. This is why Cost/Output/Breakeven/Profit all show as
blank or `$0`: the profit calculation has no ask price to read from, because
the baseline value that's computed correctly (`$1306.25`, confirmed in the
log) is never being stored somewhere the UI/calc function can access.

Please:
- Add the three missing labels to the Trapdoor Flip tab in
  `ProfitDetailsScreen.java`, in the same style/position as the other
  computed labels (`calcCostLabel`, `calcProfitLabel`, etc.):
  - `Ask Price/Unit: $X`
  - `Page-1 Ceiling: $X`
  - `Suggested Stack Size: Xx` (with a warning icon/color if it doesn't
    fit under the ceiling — see Bug 1)
- Confirm the baseline price, ceiling price, and suggested size are all
  being written into fields the `updateCalc()` equivalent for this tab
  actually reads from (config-backed or in-memory, matching whatever
  pattern the other flip tabs use for their scanned prices) — not just
  logged and discarded.
- After this fix, Cost, Output, Breakeven, Profit, and Margin should all
  populate correctly using the reproduction numbers above. Sanity check:
  ```
  Selected stack size: 12 (after Bug 1 fix)
  Logs needed: 12 × 0.75 = 9
  Total cost: 9 × $201.33 = $1,811.97
  Total revenue: $1407 × 12 = $16,884
  Profit: $16,884 − $1,811.97 = $15,072.03
  Margin: 15,072.03 / 1,811.97 × 100 ≈ 831.7%
  ```
  Use this to confirm the full chain works end to end once both bugs are
  fixed.

## Bug 3: 64x baseline scan wastes a page turn due to a match-count check
   reading the wrong key

Log evidence:
```
[Auto Flip] Running /ah trapdoor for item key: ah_trapdoor_64
[DONUT PROFIT/SCREEN] Screen Opened: Title = 'Auction (Page 1)'
[Auto Flip] Paging forward for /ah trapdoor (Page 2, Total Matches: 0, Empty Pages After Match: 0)...
[DONUT PROFIT/SCREEN] Screen Opened: Title = 'Auction (Page 2)'
[Auto Flip] Finishing scan for /ah trapdoor after 2 pages. Total Matches: 20, Empty Pages After Match: 0
```

Page 1 alone already produced all 20 of the final 20 matches (confirmed by
the final summary), but the live pagination check reported
`Total Matches: 0` right after scanning page 1 and paged forward anyway.
This means whatever function checks "how many matches do we have so far for
this task" during the pagination loop is reading from the wrong
key/accumulator for `ah_trapdoor_64` — likely a key-name mismatch similar to
the one already handled specially for `kelp` in `getAccumulatedMatchCount()`.
Please add a case for `ah_trapdoor_64` (and `ah_trapdoor_page1`, if it goes
through the same pagination path) to that lookup so live match counts are
read correctly during scanning, not just correct in the final summary after
the fact. This didn't cause visible harm on this particular item since page
2 apparently had no conflicting data, but it wastes a scan and could pull in
wrong listings on an item where page 2 has unrelated trapdoor variants.

---

Please output the full diff for the affected files so I can review before
applying, and re-run the trapdoor flip after applying so we can confirm
against the reproduction numbers above.
