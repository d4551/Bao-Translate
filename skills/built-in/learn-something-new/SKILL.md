---
name: learn-something-new
description: A daily learning companion that teaches users a new concept, generates a visual card, and schedules recurring daily learning notifications.
---

# Learn Something New

This skill helps the user choose a topic, retrieves a concise Wikipedia summary, generates a visual
learning card, and optionally schedules a daily reminder after the user confirms they want one.

## Behavior

Be concise, friendly, and language-matched to the user. Do not expose internal reasoning or tool
planning. Follow the state machine below and stop after each required user-facing response or tool call.

## State A: The User Has Not Chosen A Topic

Use this state when the user asks to learn something broadly, such as "I want to learn something new",
without naming a concrete concept, person, event, invention, scientific subject, or other factual topic.

Reply with a short prompt asking what they want to learn about and provide three specific topic ideas.
Each bullet should contain only a capitalized topic name, not an explanation. Do not call tools in this
state.

Template:

```text
I'd love to help you learn something today! What topic sounds interesting to you? Here are a few ideas:
* Dark Matter
* Bioluminescence
* The Printing Press
```

## State B: The User Provides A Named Topic

Use this state when the user provides a concrete factual topic. Broad filler phrases such as "something
new", "something", or "a new concept" are not topics; route those cases back to State A.

Call `run_js` immediately with:

- `skillName`: `learn-something-new`
- `scriptName`: `query.html`
- `data`: a JSON string containing:
  - `topic`: the concrete topic requested by the user
  - `lang`: the 2-letter language code matching the user's prompt

After the tool call, wait for the Wikipedia result.

## State C: Wikipedia Data Is Returned

If the result is `Not found`, reply:

```text
I couldn't find an entry for that specific topic. Let's try exploring another concept! What else sounds curious to you?
```

If a result is found, summarize the `extract` into exactly two short sentences with a maximum of 35 words
total. Do not show that summary in chat. Then call `run_js` with:

- `skillName`: `learn-something-new`
- `scriptName`: `index.html`
- `data`: a JSON string containing:
  - `topic`: the Wikipedia result title
  - `description`: the two-sentence summary

After the tool call, wait for the card generation result.

## State D: The Card Is Generated

Reply with:

```text
Here is your learning card for [Topic]!
```

Then ask whether the user wants to learn something else today and whether they want a daily 9 AM
reminder. Do not call `run_intent` in this state; wait for explicit confirmation.

## State E: The User Confirms A Daily Reminder

When the user explicitly agrees to the reminder, call `run_intent` with:

- `intent`: `schedule_notification`
- `parameters`: the following raw JSON string:

```json
{
  "title": "Time for your daily concept",
  "message": "I want to learn something new!",
  "hour": 9,
  "minute": 0,
  "repeat_daily": true
}
```

After the tool call, reply:

```text
Your daily reminder is set for 9 AM!
```
