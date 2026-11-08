// Generated via `mcp__supabase__generate_typescript_types`. Re-generate after any
// migration that touches `profile_snapshot` or `current_profile` — do not hand-edit.
export type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[]

export type Database = {
  __InternalSupabase: {
    PostgrestVersion: "14.5"
  }
  public: {
    Tables: {
      profile_snapshot: {
        Row: {
          activity_factor: number
          age_years: number
          budget_offset_kcal: number
          created_at: string
          effective_at: string
          goal_phase: string
          height_cm: number
          id: string
          note: string | null
          protein_per_kg: number
          sex: string
          source: string
          time_budget_minutes: number
          training_days: string[]
          user_id: string
          weight_kg: number
        }
        Insert: {
          activity_factor: number
          age_years: number
          budget_offset_kcal: number
          created_at?: string
          effective_at?: string
          goal_phase: string
          height_cm: number
          id?: string
          note?: string | null
          protein_per_kg: number
          sex: string
          source: string
          time_budget_minutes: number
          training_days?: string[]
          user_id?: string
          weight_kg: number
        }
        Update: {
          activity_factor?: number
          age_years?: number
          budget_offset_kcal?: number
          created_at?: string
          effective_at?: string
          goal_phase?: string
          height_cm?: number
          id?: string
          note?: string | null
          protein_per_kg?: number
          sex?: string
          source?: string
          time_budget_minutes?: number
          training_days?: string[]
          user_id?: string
          weight_kg?: number
        }
        Relationships: []
      }
    }
    Views: {
      current_profile: {
        Row: {
          activity_factor: number | null
          age_years: number | null
          budget_offset_kcal: number | null
          created_at: string | null
          effective_at: string | null
          goal_phase: string | null
          height_cm: number | null
          id: string | null
          note: string | null
          protein_per_kg: number | null
          sex: string | null
          source: string | null
          time_budget_minutes: number | null
          training_days: string[] | null
          user_id: string | null
          weight_kg: number | null
        }
        Relationships: []
      }
    }
    Functions: {
      [_ in never]: never
    }
    Enums: {
      [_ in never]: never
    }
    CompositeTypes: {
      [_ in never]: never
    }
  }
}
