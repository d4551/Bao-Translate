---
name: restaurant-roulette
description: Show a roulette wheel to allow user to randomly select a restaurant based on location and cuisine.
metadata:
  require-secret: true
  require-secret-description: you can get api key from https://ai.google.dev/gemini-api/docs/api-key
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/restaurant-roulette
---

# Restaurant Roulette

This skill searches for up to 10 restaurants matching a specific cuisine and location in a spin wheel.

## Examples

* "Suggest Mexican food in San Jose."
* "Find a random Italian restaurant near Sunnyvale."
* "Where should I get Sushi in San Francisco today?"
* "Show a restaurant roulette for Indian food in Palo Alto."

## Instructions

Call the `run_js` tool with the following exact parameters:
- data: A JSON string with the following fields
  - location: the target city or location (e.g., "San Jose", "Sunnyvale", "San Francisco").
  - cuisine: the style of food or cuisine desired (e.g., "Mexican", "Italian", "Indian", "Sushi").

Use `run_js` only. Do not call `run_intent`.

After the wheel is generated, do not pick a winner or invent a restaurant. Return the requested webview
and tell the user to tap the preview card to spin the wheel themselves.
