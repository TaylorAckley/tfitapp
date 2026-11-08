// Setup type definitions for built-in Supabase Runtime APIs.
import 'jsr:@supabase/functions-js/edge-runtime.d.ts'

import { createMcpHandler, McpServer } from 'npm:@modelcontextprotocol/server@^2.0.0'
import { pipeline } from 'npm:@supabase/middleware@^0.5.0'
import { withOAuthProtectedResource, withSupabase } from 'npm:@supabase/server@^1.6.0'
import { z } from 'npm:zod@^4.3.6'

import type { Database } from './database.types.ts'

// design basis §2 exception: the only code that knows the profile_snapshot/
// current_profile shape from the Claude side. Auth is Supabase Auth itself as
// the OAuth 2.1 authorization server (withOAuthProtectedResource +
// withSupabase({ auth: 'user' })) — every tool call runs RLS-scoped as the
// signed-in TFitApp user, so there is no service_role key in this function.

type ProfileRow = Database['public']['Tables']['profile_snapshot']['Row']

/** Mirrors GoalPhase's defaults in domain/nutrition/Goal.kt — kept identical so a phase
 * change from Claude behaves the same as one made in the app's own Settings screen. */
const PHASE_DEFAULTS: Record<string, { budgetOffsetKcal: number; proteinPerKg: number }> = {
  CUT: { budgetOffsetKcal: -500, proteinPerKg: 2.0 },
  RECOMP: { budgetOffsetKcal: 0, proteinPerKg: 1.8 },
  LEAN_GAIN: { budgetOffsetKcal: 250, proteinPerKg: 1.8 },
}

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const

const text = (value: string) => ({ content: [{ type: 'text' as const, text: value }] })
const NO_PROFILE_YET = text(
  'No bodyspec on file yet. Open TFitApp, fill in Settings → Body and Goal, and sync once before asking me to change it.',
)

Deno.serve(
  pipeline(
    [withOAuthProtectedResource(), withSupabase<Database>({ auth: 'user' })],
    async (req, { supabase }) => {
      const handler = createMcpHandler(() => {
        const server = new McpServer({ name: 'tfit-profile', version: '1.0.0' })

        server.registerTool(
          'get_profile',
          {
            description:
              'Reads the signed-in TFitApp user’s current bodyspec (sex, age, height, weight, activity factor) and weekly goal (phase, calorie offset, protein target, training days, session time budget).',
            inputSchema: z.object({}),
            annotations: { readOnlyHint: true },
          },
          async () => {
            const { data, error } = await supabase.from('current_profile').select('*').maybeSingle()
            if (error) throw new Error(error.message)
            if (!data) return NO_PROFILE_YET
            return text(JSON.stringify(data))
          },
        )

        server.registerTool(
          'set_weekly_goal',
          {
            description:
              'Sets the calorie/protein goal for the week ahead by choosing a phase. Omit budgetOffsetKcal/proteinPerKg to use that phase’s defaults; give them to override.',
            inputSchema: z.object({
              phase: z.enum(['CUT', 'RECOMP', 'LEAN_GAIN']),
              budgetOffsetKcal: z.number().int().min(-1000).max(500).optional(),
              proteinPerKg: z.number().min(1.0).max(3.0).optional(),
              note: z.string().max(500).optional(),
            }),
          },
          async ({ phase, budgetOffsetKcal, proteinPerKg, note }) => {
            const { data: current, error: readError } = await supabase.from('current_profile').select('*').maybeSingle()
            if (readError) throw new Error(readError.message)
            if (!current) return NO_PROFILE_YET
            const row = current as ProfileRow

            const changingPhase = phase !== row.goal_phase
            const defaults = PHASE_DEFAULTS[phase]
            const nextOffset = budgetOffsetKcal ?? (changingPhase ? defaults.budgetOffsetKcal : row.budget_offset_kcal)
            const nextProtein = proteinPerKg ?? (changingPhase ? defaults.proteinPerKg : row.protein_per_kg)

            const { error } = await supabase.from('profile_snapshot').insert({
              sex: row.sex,
              age_years: row.age_years,
              height_cm: row.height_cm,
              weight_kg: row.weight_kg,
              activity_factor: row.activity_factor,
              goal_phase: phase,
              budget_offset_kcal: nextOffset,
              protein_per_kg: nextProtein,
              training_days: row.training_days,
              time_budget_minutes: row.time_budget_minutes,
              source: 'claude',
              note: note ?? null,
            })
            if (error) throw new Error(error.message)
            return text(`Goal set: ${phase}, ${nextOffset >= 0 ? '+' : ''}${nextOffset} kcal, ${nextProtein} g protein/kg.`)
          },
        )

        server.registerTool(
          'update_bodyspec',
          {
            description:
              'Updates the user’s current weight, activity factor, training days, and/or session time budget. Leave a field out to keep its current value.',
            inputSchema: z.object({
              weightKg: z.number().positive().optional(),
              activityFactor: z.number().min(1.0).max(2.5).optional(),
              trainingDays: z.array(z.enum(DAYS)).optional(),
              timeBudgetMinutes: z.number().int().positive().optional(),
            }),
          },
          async ({ weightKg, activityFactor, trainingDays, timeBudgetMinutes }) => {
            const { data: current, error: readError } = await supabase.from('current_profile').select('*').maybeSingle()
            if (readError) throw new Error(readError.message)
            if (!current) return NO_PROFILE_YET
            const row = current as ProfileRow

            const { error } = await supabase.from('profile_snapshot').insert({
              sex: row.sex,
              age_years: row.age_years,
              height_cm: row.height_cm,
              weight_kg: weightKg ?? row.weight_kg,
              activity_factor: activityFactor ?? row.activity_factor,
              goal_phase: row.goal_phase,
              budget_offset_kcal: row.budget_offset_kcal,
              protein_per_kg: row.protein_per_kg,
              training_days: trainingDays ?? row.training_days,
              time_budget_minutes: timeBudgetMinutes ?? row.time_budget_minutes,
              source: 'claude',
            })
            if (error) throw new Error(error.message)
            return text('Bodyspec updated.')
          },
        )

        return server
      })

      return handler.fetch(req)
    },
  ),
)
