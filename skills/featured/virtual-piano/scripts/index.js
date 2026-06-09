import { log } from '../../_shared/log.js';
import { fail } from '../../_shared/result.js';

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const fullUrl = `ui.html?v=${Date.now()}`;

    return JSON.stringify({
      webview: { url: fullUrl },
      result: 'Success. Tell the user to tap the preview card to play the piano.'
    });

  } catch (e) {
    fail('virtual-piano.topLevel', e);
    return JSON.stringify({ error: `Failed to load piano: ${e.message}` });
  }
};
