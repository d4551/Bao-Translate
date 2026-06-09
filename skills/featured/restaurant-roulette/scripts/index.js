import { log } from '../../shared/log.js';
import { fail } from '../../shared/result.js';

window['ai_edge_gallery_get_result'] = async (dataStr, secret) => {
  try {
    const jsonData = JSON.parse(dataStr || '{}');
    const location = jsonData.location || 'Mountain View, CA';
    const cuisine = jsonData.cuisine || 'Sushi';

    const GEMINI_API_KEY = secret || 'YOUR_GEMINI_API_KEY';
    if (GEMINI_API_KEY === 'YOUR_GEMINI_API_KEY') {
      fail('restaurant-roulette.config', new Error('Missing Gemini API key'), 'Add your Gemini API key in settings');
      return JSON.stringify({
        webview: { url: 'assets/ui.html?error=missing_key' },
        result: 'A Gemini API key is required. Please add it in the app settings.'
      });
    }

    const prompt = `List 10 real, highly-rated ${cuisine} restaurants in ${location}. Within 15 miles location range`;

    const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${GEMINI_API_KEY}`;

    let places = [];

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{ parts: [{ text: prompt }] }],
          generationConfig: {
            responseMimeType: 'application/json',
            responseSchema: { type: 'ARRAY', items: { type: 'STRING' } }
          }
        })
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP Error ${response.status}: ${errorText}`);
      }

      const data = await response.json();

      if (data.candidates && data.candidates.length > 0) {
        const rawText = data.candidates[0].content.parts[0].text;
        places = JSON.parse(rawText);
        // Fisher-Yates shuffle with seeded PRNG (deterministic per request timestamp)
        const seed = Date.now() & 0xFFFFFFFF;
        let s = seed;
        const rand = () => { s = (s * 1664525 + 1013904223) & 0xFFFFFFFF; return (s >>> 0) / 0xFFFFFFFF; };
        for (let i = places.length - 1; i > 0; i--) {
          const j = Math.floor(rand() * (i + 1));
          [places[i], places[j]] = [places[j], places[i]];
        }
      } else {
        throw new Error('Empty response from AI');
      }
    } catch (apiError) {
      log.error('Gemini API failed:', apiError);
      fail('restaurant-roulette.fetchPlaces', apiError, 'Places unavailable — check your connection');
      places = ['Error:', apiError.message.substring(0, 15), 'Check', 'Console'];
    }

    const placeString = places.join('|');
    const compressedData = btoa(unescape(encodeURIComponent(placeString)));

    const baseUrl = 'webview.html';
    const fullUrl = `${baseUrl}?c=${encodeURIComponent(cuisine)}&l=${encodeURIComponent(location)}&data=${compressedData}&v=${Date.now()}`;

    return JSON.stringify({
      webview: { url: fullUrl },
      result: 'Here is the restaurant roulette wheel you requested! Tap the preview card to spin it and pick a winner!'
    });

  } catch (e) {
    fail('restaurant-roulette.topLevel', e);
    return JSON.stringify({ error: `Failed to load roulette: ${e.message}` });
  }
};
