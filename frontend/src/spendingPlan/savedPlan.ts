import type {
  SpendingPlanBucket,
  SpendingPlanRequest,
  SpendingPlanResponse,
} from '../api/SpendingPlanService'
import type { BucketId, PlanDraft } from './plan'

/** A plan as the app works with it: the editor's shape plus what it was saved with. */
export interface SavedPlan {
  accountId: string | null
  takeHome: number | null
  bufferPercent: number
  plan: PlanDraft
  updatedAt: string
}

const API_BUCKETS: Record<BucketId, SpendingPlanBucket> = {
  fixedCosts: 'FIXED_COSTS',
  investments: 'INVESTMENTS',
  savings: 'SAVINGS',
}

export const BUCKET_IDS: Record<SpendingPlanBucket, BucketId> = {
  FIXED_COSTS: 'fixedCosts',
  INVESTMENTS: 'investments',
  SAVINGS: 'savings',
}

export function toSaveRequest(
  accountId: string | null,
  takeHome: number | null,
  bufferPercent: number,
  plan: PlanDraft,
): SpendingPlanRequest {
  const bucketIds = Object.keys(API_BUCKETS) as BucketId[]
  return {
    account_id: accountId,
    take_home: takeHome,
    fixed_cost_buffer_percent: bufferPercent,
    lines: bucketIds.flatMap((bucket) =>
      plan[bucket].map((line) => ({
        bucket: API_BUCKETS[bucket],
        name: line.name,
        amount: line.amount,
        from_paycheck: line.fromPaycheck,
        items: line.items.map((item) => ({
          name: item.name,
          amount: item.amount,
          stream_id: item.streamId,
        })),
      })),
    ),
  }
}

export function fromSaved(saved: SpendingPlanResponse): SavedPlan {
  const plan: PlanDraft = { fixedCosts: [], investments: [], savings: [] }
  for (const line of saved.lines) {
    plan[BUCKET_IDS[line.bucket]].push({
      name: line.name,
      amount: line.amount,
      fromPaycheck: line.from_paycheck,
      items: line.items.map((item) => ({
        name: item.name,
        amount: item.amount,
        streamId: item.stream_id,
      })),
    })
  }
  return {
    accountId: saved.account_id,
    takeHome: saved.take_home,
    bufferPercent: saved.fixed_cost_buffer_percent,
    plan,
    updatedAt: saved.updated_at,
  }
}
