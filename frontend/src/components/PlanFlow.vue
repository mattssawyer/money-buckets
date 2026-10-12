<script setup lang="ts">
import { PLAN_COLORS, PLAN_TITLES } from '../spendingPlan/plan'

/**
 * How a spending plan splits take-home pay, drawn as a flow: the first three buckets come out
 * first, and what's left flows on to guilt-free spending. The shares are the plan's usual
 * guidelines, to show the idea rather than anyone's numbers.
 */

type Part = keyof typeof PLAN_TITLES

interface Branch {
  id: Part
  /** Share of take-home pay the band is drawn at. */
  share: number
  /** The guideline range shown beside it. */
  range: string
}

const BRANCHES: Branch[] = [
  { id: 'fixedCosts', share: 0.5, range: '50–60%' },
  { id: 'investments', share: 0.1, range: '10%' },
  { id: 'savings', share: 0.1, range: '5–10%' },
  { id: 'guiltFree', share: 0.3, range: '20–35%' },
]

// Drawn in the card's own pixels; the labels are HTML placed over it, in the page's type.
const WIDTH = 408
/** The paycheck bar the bands leave from, and each bucket's bar where they land. */
const BAR_WIDTH = 4
/** Where the paycheck bar sits, leaving room on its left for its label. */
const SOURCE_X = 68
/** Where the bands leave the paycheck bar. */
const START_X = SOURCE_X + BAR_WIDTH
/** The paycheck label's right edge, a little left of its bar. */
const SOURCE_LABEL_RIGHT = WIDTH - SOURCE_X + 10
/** Where the bands land on their bucket's bar; the labels sit to the right of it. */
const TARGET_X = 246
const LABEL_X = TARGET_X + BAR_WIDTH + 12
/** A little room above, so the top band isn't cut off. */
const TOP = 6
/** How tall all the bands are where they leave the paycheck. */
const FLOW = 176
/** The 2px surface gap between bands where they leave. */
const SOURCE_GAP = 2
/**
 * The bands fan apart as they reach their buckets, far enough that each label's two lines fit
 * beside even a thin band, with the widest break before what's left.
 */
const TARGET_GAP = 22
const REST_GAP = 28

const flow = FLOW - SOURCE_GAP * (BRANCHES.length - 1)
const targetHeight = flow + TARGET_GAP * (BRANCHES.length - 2) + REST_GAP
const sourceTop = TOP + (targetHeight - FLOW) / 2
const HEIGHT = TOP + targetHeight + 4

const bands = (() => {
  let source = sourceTop
  let target = TOP
  return BRANCHES.map((branch, index) => {
    const size = branch.share * flow
    const s0 = source
    const t0 = target
    const s1 = s0 + size
    const t1 = t0 + size
    const rest = index === BRANCHES.length - 1
    source = s1 + SOURCE_GAP
    target = t1 + (index === BRANCHES.length - 2 ? REST_GAP : TARGET_GAP)
    const mid = (START_X + TARGET_X) / 2
    return {
      ...branch,
      rest,
      title: PLAN_TITLES[branch.id],
      color: PLAN_COLORS[branch.id],
      path:
        `M ${START_X} ${s0} C ${mid} ${s0}, ${mid} ${t0}, ${TARGET_X} ${t0} ` +
        `L ${TARGET_X} ${t1} C ${mid} ${t1}, ${mid} ${s1}, ${START_X} ${s1} Z`,
      top: t0,
      size,
      /** The label's middle, as a share of the height, so it stays put if the drawing shrinks. */
      labelTop: `${((t0 + size / 2) / HEIGHT) * 100}%`,
    }
  })
})()

const labelLeft = `${(LABEL_X / WIDTH) * 100}%`
// Beside the middle of the paycheck bar.
const sourceLabelStyle = {
  top: `${((sourceTop + FLOW / 2) / HEIGHT) * 100}%`,
  right: `${(SOURCE_LABEL_RIGHT / WIDTH) * 100}%`,
}
</script>

<template>
  <figure
    class="plan-flow"
    role="img"
    aria-label="Your paycheck flows first into fixed costs (50–60%), investments (10%) and savings (5–10%). What's left, 20–35%, is guilt-free spending."
  >
    <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" aria-hidden="true">
      <defs>
        <!-- Each band starts faint and deepens into its bucket's color as it lands. -->
        <linearGradient
          v-for="band in bands"
          :id="`flow-${band.id}`"
          :key="band.id"
          gradientUnits="userSpaceOnUse"
          :x1="START_X"
          :x2="TARGET_X"
          y1="0"
          y2="0"
        >
          <stop offset="0" :stop-color="band.color" stop-opacity="0.12" />
          <stop offset="1" :stop-color="band.color" :stop-opacity="band.rest ? 0.75 : 0.5" />
        </linearGradient>
      </defs>

      <rect
        class="flow-source"
        :x="SOURCE_X"
        :y="sourceTop"
        :width="BAR_WIDTH"
        :height="FLOW"
        rx="2"
      />
      <g v-for="(band, index) in bands" :key="band.id" :style="{ '--order': index }">
        <path class="flow-band" :d="band.path" :fill="`url(#flow-${band.id})`" />
        <!-- Just a shade deeper than where its band lands, so the bar doesn't pop out. -->
        <rect
          class="flow-bucket"
          :x="TARGET_X"
          :y="band.top"
          :width="BAR_WIDTH"
          :height="band.size"
          rx="2"
          :fill="band.color"
          :fill-opacity="band.rest ? 0.85 : 0.65"
        />
      </g>
    </svg>

    <span class="flow-source-label" :style="sourceLabelStyle" aria-hidden="true"> Paycheck </span>
    <span
      v-for="(band, index) in bands"
      :key="band.id"
      class="flow-label"
      :class="{ 'flow-label-rest': band.rest }"
      :style="{ top: band.labelTop, left: labelLeft, '--order': index }"
      aria-hidden="true"
    >
      <span class="flow-label-name">{{ band.title }}</span>
      <span class="flow-label-range">{{ band.range }}</span>
    </span>
  </figure>
</template>

<style scoped>
.plan-flow {
  position: relative;
  width: 100%;
  max-width: 408px;
  margin: 0;
}

.plan-flow svg {
  display: block;
  width: 100%;
  height: auto;
}

/* Soft enough to sit with the faint start of the bands rather than stand out from them. */
.flow-source {
  fill: var(--app-text);
  fill-opacity: 0.32;
}

.flow-source-label {
  position: absolute;
  transform: translateY(-50%);
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: 1;
}

.flow-label {
  position: absolute;
  display: grid;
  gap: 0.0625rem;
  transform: translateY(-50%);
  line-height: 1.25;
  white-space: nowrap;
}

.flow-label-name {
  color: var(--app-text);
  font-size: var(--text-md);
  font-weight: 500;
  letter-spacing: -0.01em;
}

.flow-label-range {
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
}

/* What's left is the point of the plan. */
.flow-label-rest .flow-label-name {
  font-weight: 600;
}

/*
 * The money pours in from the left: each band sweeps out in turn, its bucket and label just
 * behind it, and what's left arrives last.
 */
.flow-band {
  animation: pour 900ms calc(250ms + var(--order) * 140ms) cubic-bezier(0.22, 1, 0.36, 1) both;
}

.flow-bucket {
  animation: appear 500ms calc(650ms + var(--order) * 140ms) cubic-bezier(0.22, 1, 0.36, 1) both;
}

.flow-label {
  animation: label-in 500ms calc(650ms + var(--order) * 140ms) cubic-bezier(0.22, 1, 0.36, 1) both;
}

@keyframes pour {
  from {
    clip-path: inset(0 100% 0 0);
  }

  to {
    clip-path: inset(0 0 0 0);
  }
}

@keyframes appear {
  from {
    opacity: 0;
  }
}

@keyframes label-in {
  from {
    opacity: 0;
    transform: translate(-0.25rem, -50%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .flow-band,
  .flow-bucket,
  .flow-label {
    animation: none;
  }
}
</style>
