---
name: memory-task-screen-layout
description: The console screen's colliding text, why constants caused it, and the rule that a screen's arithmetic belongs somewhere a test can reach.
---

# Screen Layout, and Testing It Without Minecraft

## What was wrong

A screenshot from a real client showed two defects on `WebConsoleScreen`:

* the hint read **"It keeps running when you clos"** — clipped mid-word, because it was cut
  to a pixel width with `font.plainSubstrByWidth` rather than wrapped;
* **"Console started."** was drawn underneath the button that produced it. The status sat at
  `height / 2 + 10`; the buttons began at `height / 2 + 16`.

Both had been in `master` through several rounds. Nothing caught them because nothing in this
repository can look at a Minecraft screen.

## The rule that came out of it

**A layout built from constants is one translation away from colliding.** Wrapped text has a
height nobody can predict from the source: it depends on the window, the language, and — here
— on how long a URL's one-time token happens to be. Picking better numbers would have fixed
the screenshot and not the defect.

Rows are stacked now: each one starts where the last ended, so there is no way to express
"put this at y" and therefore no way to express two things at the same y.

**The status goes after the buttons, not before them.** A message the player triggers can be
one line or three, and anything above a control it can grow into is eventually drawn
underneath it.

## The rule worth generalising

**A screen's arithmetic does not need Minecraft to be wrong, so it does not need Minecraft to
be tested.** The sums live in `common/ui/StackedRows`, which knows nothing about fonts; the
screen supplies the two things only it knows — the font's line height, and how many lines its
text wrapped into.

`StackedRowsTest` asserts the property directly rather than checking numbers: no row overlaps
the next, for every shape of content the screen can show, and the status clears the button
block however long the text above it runs. That is the first test in this repository that
covers a screen defect.

`platforms/fabric` still has no test source set. This does not fix that — it routes around it,
and only for the part that is arithmetic. Anything about what a screen *looks* like remains
unproven until somebody opens the game.

## Also in this round

The `/knowledge/` catalog list got its own scroller, matching the library above it. One
recipe shared by both, with the height expressed as a number of card rows and bounded by the
window — a ceiling, not a target, so a short window shrinks the box instead of producing a
second scrollbar fighting the page's. Below 900px both revert to flowing with the page.

## Branches

`fix/console-screen-layout` → `feat/available-scroll-panel` → `docs/console-layout`.
