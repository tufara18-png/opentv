# OpenTV × TiviMate-style TV shell

## Goal

Keep OpenTV/TufaraTV as the product and data engine, but move the TV experience toward the fast, remote-first interaction model users expect from TiviMate-style players.

## Important licensing constraint

The current `johnpc/telly` repository is source-available, but its README currently says it has no license file yet. That means we should not copy its source into this GPL project until the author publishes an explicit compatible license.

We can still:
- study behavior and interaction patterns;
- independently implement equivalent UX;
- keep all OpenTV catalog intelligence, parsers, Room data and Media3 playback;
- later swap in upstream code only if licensing becomes explicit and compatible.

## What already exists in OpenTV

OpenTV already has the hard parts we want to preserve:

- Xtream/M3U/Stalker sources
- Room catalog
- XMLTV + EPG matching
- Media3 player
- channel quality grouping
- country/category normalization
- national channel ordering
- canonical VOD catalog
- TMDB enrichment
- source/quality variants
- recordings, profiles, settings

The fusion is therefore mainly an interaction/UI refactor, not a rewrite.

## Phase 1 — shell

Create a reusable TV shell with:
- collapsible left navigation rail
- deterministic D-pad focus
- full-screen content slot
- sections: Live, Guide, Movies, Series, Search, Recordings, Settings
- no data migration
- no player changes

## Phase 2 — Live / Guide

Use the existing normalized OpenTV channel data:
- one logical row per quality group
- country-first group rail
- national ordering inside countries
- existing EPG matching
- instant tune / preview behavior
- long-OK context actions

## Phase 3 — Player

Keep the current Media3 stack and add:
- now/next overlay
- recent channels
- channel list side panel
- quality/source selector
- audio/subtitle track controls
- quick actions

## Phase 4 — VOD

Keep OpenTV's canonical VOD model:
- one canonical work
- TMDB metadata
- provider/quality variants behind it
- source/quality remembered per work/series
- resume state

## Architectural rule

UI consumes OpenTV's canonical domain model. Provider-specific rows never leak into the main TV UI unless the user explicitly opens a source/quality chooser.
