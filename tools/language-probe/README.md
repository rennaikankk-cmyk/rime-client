# Isolated language verification

This test-only mod verifies every registered module/setting label, description and hover text in English; legacy-config migration; subsequent Chinese persistence; stable configuration IDs; hover-cache switching; and actual English/Chinese GUI and HUD screenshots.

Use a copied `ProbeWorld` under `../language-lab/ui01`, never a user's live world. Build `languageProbeJar` with this Gradle init script, then run `runClient` with the existing `-PgzwDevelopmentBuild=true` gate. The probe is a separate source set and must never be included in the production mod. Production authorization is unchanged.
