---
name: Money Buckets
description: A Conscious Spending Plan that keeps itself up to date, drawn in black ink on gray paper.
colors:
  fixed-costs-blue: "#2a78d6"
  investments-orange: "#eb6834"
  savings-green: "#1baf7a"
  guilt-free-gold: "#eda100"
  ink: "#171717"
  ink-secondary: "#737373"
  ink-subdued: "#9e9e9e"
  ink-inverse: "#ffffff"
  canvas: "#eeeeee"
  surface: "#ffffff"
  inset: "#f0f0f0"
  inset-hover: "#e7e7e7"
  not-counted-gray: "#cfcfcf"
  success: "#10a861"
  danger: "#c91313"
  over-target: "#c0444e"
  danger-surface: "#fff1f0"
  danger-border: "#ffdedb"
  divider: "rgb(11 11 11 / 8%)"
  ring: "rgb(11 11 11 / 5%)"
  control-border: "rgb(11 11 11 / 10%)"
typography:
  display:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "2.375rem"
    fontWeight: 550
    lineHeight: 1.1
    letterSpacing: "-0.04em"
    fontFeature: "tnum"
  headline:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 550
    lineHeight: 1.2
    letterSpacing: "-0.04em"
  title:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  body:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 500
    lineHeight: 1.4
  caption:
    fontFamily: "Geist Variable, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  swatch: "2px"
  chip: "0.5rem"
  control: "0.625rem"
  container: "0.75rem"
  pill: "999px"
spacing:
  xs: "0.25rem"
  sm: "0.5rem"
  md: "0.75rem"
  lg: "1rem"
  card: "1.25rem"
  page: "1.5rem"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ink-inverse}"
    rounded: "{rounded.control}"
    padding: "0.5rem 0.75rem"
  button-primary-hover:
    backgroundColor: "#242424"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0.5rem 0.75rem"
  button-secondary-hover:
    backgroundColor: "#f7f7f7"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.container}"
    padding: "1.125rem 1.25rem 1.25rem"
  input-compact:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.chip}"
    padding: "0.375rem 0.625rem"
  tooltip:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ink-inverse}"
    rounded: "{rounded.chip}"
    padding: "0.5rem 0.65rem"
  switch-on:
    backgroundColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    width: "2.25rem"
    height: "1.25rem"
  switch-off:
    backgroundColor: "{colors.inset-hover}"
    rounded: "{rounded.pill}"
  payee-icon:
    backgroundColor: "{colors.inset}"
    textColor: "{colors.ink-secondary}"
    rounded: "{rounded.pill}"
    size: "2rem"
---

# Design System: Money Buckets

## Overview

**Creative North Star: "The Quiet Ledger"**

Money Buckets is black ink on gray paper. Almost everything is a neutral: near-black type, white cards resting on a light gray page, gray secondary text, hairline dividers. Color is kept for one job, which is showing where money went. The four bucket colors (fixed costs, investments, savings, guilt-free spending) are the only hues that appear in ordinary use. Success green, danger red and the softer over-target red show up only when they carry meaning. Because there's so little color, a bucket color tells you something every time it appears.

The feel is calm and crafted. Calm means nothing shouts: going over plan is a muted red, not an alarm. Explanations sit behind an info tooltip instead of filling the screen, and the page doesn't move unless you ask it to. Crafted means the small things line up. Money uses tabular figures. Type sizes come from one scale. Hairlines are alpha black so they read the same on any surface. Heading rows keep their height while a button or select loads in, so nothing jumps. Density is moderate: there's room to breathe, but it's a working tool, not a landing page.

Future screens should not drift toward bank-app blue (corporate blue headers or brand chrome) or a crypto/neon dashboard (dark glow, gradients, flashy charts). The blue in this system belongs to fixed costs and nothing else.

**Key Characteristics:**
- Neutral gray scale with near-black as the primary "accent"; color reserved for buckets and meaning
- One font (Geist Variable) on one type scale, with tight negative tracking on headings and figures
- White cards with a 5% hairline ring and a faint shadow, on a #eeeeee page
- Tabular figures for every amount
- The same layout at every size: a left rail on desktop becomes a bottom tab bar on phones, and big dialogs become full-screen sheets

## Colors

A gray ledger with four bucket inks. Neutrals do almost all the work, and color always stands for something.

### Primary
- **Ink** (`ink`): the primary color. Body text, headings, primary buttons, the on state of switches, the active nav marker, focus outlines and the tooltip background. The app has no brand hue. Near-black is the accent.

### Secondary
The four bucket colors work as a single categorical set. They come from a validated data-visualization palette. Spending views (Home, transaction lists) order buckets fixed costs, guilt-free, savings, investments, the server's order; the spending plan orders its own buckets fixed costs, investments, savings, guilt-free.
- **Fixed-costs Blue** (`fixed-costs-blue`): fixed costs everywhere they're charted or tagged. On the Investments page it also marks bank accounts.
- **Investments Orange** (`investments-orange`): the investments bucket, and investment accounts on the Investments page.
- **Savings Green** (`savings-green`): the savings bucket.
- **Guilt-free Gold** (`guilt-free-gold`): guilt-free spending, including the fill of the guilt-free progress bar on Home.

### Tertiary
Status colors, used only when they mean something:
- **Success** (`success`): money coming in, such as inflow amounts in transaction lists.
- **Over Target** (`over-target`): a plan line or the guilt-free bar that has gone past its target. It's softer than danger on purpose, because going over is worth a look, not an error.
- **Danger** (`danger`, `danger-surface`, `danger-border`): real errors and destructive actions, such as failed loads and account deletion.

### Neutral
- **Canvas** (`canvas`): the page background behind everything. The phone tab bar uses it at 86% with a blur.
- **Surface** (`surface`): cards, dialogs, popovers, the active nav icon tile, and the switch knob.
- **Inset** (`inset`, `inset-hover`): recessed areas inside a card, such as payee icon fallbacks, progress tracks, the switch's off state and hovered nav icons. Skeletons use inset too.
- **Ink Secondary** (`ink-secondary`): meta lines, captions, secondary labels and inactive nav.
- **Ink Subdued** (`ink-subdued`): decoration only, such as placeholder marks and the "Not sorted yet" bucket color. At 2.7:1 on white it's too faint for anything that has to be read; use Ink Secondary for text and icons.
- **Not-counted Gray** (`not-counted-gray`): excluded transactions and money coming in, which sit outside the plan's buckets.
- **Hairlines** (`divider`, `ring`, `control-border`): alpha black at 8%, 5% and 10%, used for list dividers, card rings and control borders. Because they're alpha, they work on canvas, surface and inset alike.

### Named Rules
**The Ink Is the Accent Rule.** Primary actions, selection and focus are near-black. Don't add a brand hue to make buttons, links or active states "pop".

**The Bucket Color Rule.** The four bucket colors mean their buckets and nothing else. Never use Fixed-costs Blue as a link color, Savings Green as a generic success, or Guilt-free Gold as a warning. Each bucket keeps its color on every chart, legend, tag and bar.

**The Soft Over Rule.** Going over a plan target uses Over Target red, never Danger. Danger is for errors and destructive actions only.

## Typography

**Display Font:** Geist Variable (with system-ui, -apple-system, Segoe UI, sans-serif)
**Body Font:** Geist Variable
**Label/Mono Font:** none distinct. Figures use Geist with tabular numerals.

**Character:** One precise grotesque at every level. Hierarchy comes from size, weight (400 to 600, often the in-between 550) and tracking, not from a second typeface. Headings and big figures tighten to -0.04em so they sit close and solid.

The root size scales with the viewport: `clamp(14px, 0.5vw + 7.5px, 17px)`, which is 14px on phones, about 15px on laptops and 17px on large desktops. Every size below is in rem, so it follows the root. All font sizes come from the `--text-*` scale in `frontend/src/assets/main.css`. The big steps shrink at 900px and below.

### Hierarchy
- **Display**: big money figures. There are three steps: figures inside a card (2rem, 1.75rem on phones), Home's balance (2.375rem, 2rem on phones) and a page's headline figure (2.75rem, 2.25rem on phones). All are weight 550 with tabular numerals.
- **Headline**: page titles (the global `h1`), and empty-state headings at the same size.
- **Title**: card and section titles (the global `.card-title`). Dialog and panel titles go one step up, to 1.125rem.
- **Body**: list rows, paragraphs and controls. Transaction names use weight 500, and amounts use 550 with tabular numerals.
- **Label**: secondary text and compact controls, such as legend titles, the guilt-free bar's heading and compact selects.
- **Caption**: meta lines under a row (date, account, category) and legend meta. Nav labels and tiny tags go one step smaller, to 0.6875rem at weight 500.

### Named Rules
**The One Scale Rule.** Every font size is a `--text-*` token. The same kind of text is the same size on every page. If a size you need doesn't exist, the design is wrong, not the scale.

**The Tabular Money Rule.** Every amount uses `font-variant-numeric: tabular-nums` and is formatted by `money.ts`: cents for balances and transactions, whole dollars for summaries, and a + or − sign for money in and out.

## Layout

Desktop is a fixed left rail (5.5rem wide) next to a scrolling page. Page content has 1.25rem of padding at the top and 1.5rem on the right and bottom. Home uses a two-column grid (`minmax(0, 1.2fr) minmax(18rem, 0.8fr)`) with 1rem gaps between cards. At 900px and below, everything stacks into a single column, the rail becomes a 4rem bottom tab bar that respects the safe area, and the page gains bottom padding so the tab bar never covers content. At 640px and below, dialogs that hold a whole list or form (`.app-sheet`) fill the screen.

Spacing works in quarter-rems: 0.25 and 0.5 inside a component, 0.75 to 1 between related items, and 1.25 to 1.5 for card padding and page edges. Card padding is 1.125rem on top and 1.25rem on the sides and bottom. Lists are dense but tappable: rows are at least 3.25rem tall, with hairline dividers between them.

On touch screens (900px and below, or any coarse pointer), everything tappable is at least 44px (`--app-touch-target`). Rows and form fields grow to that height. PrimeVue buttons, paginator buttons and anything marked `.hit-area` keep their look but get an invisible box at least 44px square, centred on them (shared rule in `main.css`). Switches grow with a transparent border instead, since their knob uses `::after`. Clickable rows show an inset-gray highlight while pressed. On touch devices, form fields never use text under 16px, so phones don't zoom into them.

Heading rows reserve their full height from the start (`--app-control-height`, `--app-control-height-sm`), so a row is just as tall before its button or select loads as after. Loading states are skeleton cards in the final layout, not spinners.

**The Same Task Everywhere Rule.** Phone and desktop get the same tasks and the same information. Phones re-flow layout; they never drop features.

## Elevation & Depth

Barely lifted. Cards sit on the gray canvas with a 1px alpha ring and a 1–2px shadow at 6% opacity, which is just enough to separate white from gray without looking like a floating layer. Inside a card, depth goes the other way: recessed inset tones mark tracks, icon tiles and the switch's off state. Stronger shadows are only for things that float above the page, such as popovers and tooltips.

### Shadow Vocabulary
- **Hairline lift** (`box-shadow: 0 1px 2px rgb(11 11 11 / 6%), 0 0 0 1px rgb(11 11 11 / 5%)`): every card (`.panel`), and the active nav tile without the ring.
- **Float** (`box-shadow: 0 1px 6px rgb(11 11 11 / 6%), 0 0 0 1px rgb(11 11 11 / 5%)`): popovers. Tooltips use the shadow without the ring.

### Named Rules
**The Paper, Not Glass Rule.** Depth is a sheet of paper on a desk, not a floating panel. Don't use large, colored or layered shadows. The only blur in the app is the phone tab bar's backdrop.

## Shapes

Gently rounded, on a three-step radius scale that grows with the size of the thing: chips, rows and small tiles use 0.5rem; buttons, inputs, selects, popovers and messages use 0.625rem; cards and containers use 0.75rem. Fully round shapes are kept for payee icons, the info-tip button, switches and progress bars. Legend swatches are 0.5rem dots on Home and 0.625rem squares with 2px corners on the Spending Plan page. Borders are rare: cards use the shadow ring instead of a border, and only form controls have a real 1px border, in control-border alpha. The logo is a dark bucket split into three, on a light rounded square. It's the one illustrated mark.

## Components

Quiet and precise: every control is a neutral shape with one clear state change.

### Buttons
- **Shape:** gently rounded (0.625rem). Padding is 0.5rem by 0.75rem with a 500-weight label. The large size is 0.6875rem by 1rem at 0.9375rem type.
- **Primary:** Ink background with white text. On hover it steps one gray lighter, and when pressed one more.
- **Secondary:** a white surface with a control-border hairline and Ink text, turning #f7f7f7 on hover. This is the most common button in the app.
- **Text:** no background. Used for "View all" and other card-header actions, pulled out to the card edge with a negative margin.
- **Focus:** a 2px Ink outline with a 2px offset, the same for every focusable element.

### Cards / Containers
- **Corner Style:** 0.75rem.
- **Background:** Surface white on the gray canvas.
- **Shadow Strategy:** Hairline lift (see Elevation & Depth).
- **Border:** none. The ring is part of the shadow.
- **Internal Padding:** 1.125rem on top, 1.25rem on the sides and bottom.
- **Heading:** a card title on the left and an optional compact action on the right, in a row with a reserved height.

### Inputs / Fields
- **Style:** a white surface, a 1px control-border, a 0.5rem radius for compact selects and fields, and a 0.625rem radius for full-size controls.
- **Focus:** the same 2px Ink outline. Compound fields such as the share-% box put the outline around the whole field, not just the inner input.
- **Error:** PrimeVue error messages with a pale red surface, a red hairline and Danger text, without a shadow.

### Navigation
- **Desktop rail:** the logo, then icon-over-label items (18px Lucide icons at stroke 1.75, labels at 0.6875rem/500), with Settings pinned to the bottom. Inactive items are Ink Secondary, and hovered icons get an inset tile. The active item is Ink, its icon sits on a white tile with a faint shadow, and a 4px Ink bar marks the left edge.
- **Phone tab bar:** the same items in a row at the bottom, on a translucent canvas with blur and a hairline top border. The active bar moves to the top edge.

### Switch
A native checkbox drawn as a 2.25rem by 1.25rem pill. It's an inset gray when off and Ink when on, with a white knob that slides over 150ms. Because it stays a real checkbox, keyboard and screen readers work without extra code.

### Info Tip
The only place for explanations. A 14px Lucide info icon in Ink Secondary turns Ink on hover or focus, and opens a small Ink bubble with white 0.8125rem text, up to 16rem wide, that fades in over 120ms. It works from the keyboard and is announced as a tooltip.

### Transaction Row
A 2rem round payee icon (the merchant's logo, or a category icon on an inset circle), then the name (500 weight, cut off with an ellipsis) above a caption meta line, then the amount on the right in tabular figures. Inflows are Success green. The whole row is a button that opens the payee correction dialog.

### Bucket Legend and Progress
On Home, the Spending ring's centre shows the period's total money out ("out this month"); what's left of guilt-free spending lives in the bar at the card's foot. Bucket rows below have a 0.5rem dot in the bucket's color, start collapsed, and open to categories and transactions. The guilt-free bar at the card's foot is a 0.375rem pill track in Inset, filled with Guilt-free Gold, with "$X left" (or "over", in Over Target) and a caption when the number may still change (unsorted spending, a bank needing sign-in). When it can't show, the same slot says why. The Spending Plan page's legend uses 0.625rem square swatches with a 1.125rem/550 figure.

## Do's and Don'ts

### Do:
- **Do** take every color, radius, shadow and font size from the `--app-*` and `--text-*` tokens in `frontend/src/assets/main.css`, or from the gray scale in `frontend/src/palette.ts` where CSS variables can't reach (charts, the PrimeVue preset).
- **Do** keep primary actions, selection and focus in Ink (#171717).
- **Do** show each bucket in its own color, in the order its view uses: fixed costs, guilt-free, savings, investments in spending views; fixed costs, investments, savings, guilt-free in the plan.
- **Do** make every tap target at least 44px on touch screens (`--app-touch-target`): add `.hit-area` to small custom controls rather than resizing them.
- **Do** use tabular numerals and `money.ts` formatting for every amount.
- **Do** put explanations behind the Info Tip, not in paragraphs of hint text.
- **Do** show loading as skeleton cards shaped like the final layout, and reserve control heights so nothing shifts.
- **Do** make every screen work at phone and desktop widths: rail to tab bar, two columns to one, and big dialogs to full-screen sheets.
- **Do** respect `prefers-reduced-motion`. Motion is limited to short (120–150ms) fades and slides on state changes.

### Don't:
- **Don't** add a brand hue, gradients or colored backgrounds to make the app feel livelier. No bank-app blue headers or brand chrome.
- **Don't** drift toward a crypto/neon dashboard: no dark-mode glow, no gradient fills and no flashy chart effects.
- **Don't** reuse a bucket color for anything that isn't that bucket.
- **Don't** use Danger red for "over plan". Use Over Target.
- **Don't** use hard-coded hex values or one-off font sizes in components. Add a token if one is really missing.
- **Don't** put cards inside cards. Group things inside a card with hairline dividers and inset tones.
- **Don't** use shadows heavier than Float, and don't use blur anywhere except the phone tab bar.
